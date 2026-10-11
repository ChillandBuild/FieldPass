from __future__ import annotations

import logging
import secrets
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, File, Form, UploadFile
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock, devices, errors, liveness, rules
from app.config import Settings
from app.db import get_session
from app.device_keys import canonical_message, location_fingerprint, verify_signature
from app.deps import get_face_engine, get_photo_store
from app.face import NO_MATCH, FaceAnalysis, FaceEngine
from app.geo import regions_verdict
from app.models import Activity, Attempt, AttendanceEvent, CheckpointWindow, Enrollment, User, new_id
from app.photo_store import PhotoStore
from app.routers.activities import get_activity, require_on_roster
from app.routers.enrollment import read_image, require_one_face
from app.schemas import AttemptIn, AttemptOut, AttendanceEventOut
from app.security import get_settings_dep, require_student
from app.views import event_out

log = logging.getLogger("fieldpass")

router = APIRouter(tags=["attempts"])

# A reading stamped more than this far ahead of the server clock cannot be fresh; it is rejected as stale.
LOCATION_FUTURE_TOLERANCE_SECONDS = 5

# Directions are the student's own left and right.
HEAD_TURNS = ("left", "right")


def _approved_enrollment(session: Session, student_id: str) -> Enrollment:
    row = session.scalar(select(Enrollment).where(Enrollment.student_id == student_id))
    if row is None or row.status != "approved":
        raise errors.enrollment_not_approved()
    return row


def _student_state(session: Session, activity_id: str, student_id: str):  # noqa: ANN202
    events = list(session.scalars(
        select(AttendanceEvent).where(AttendanceEvent.activity_id == activity_id, AttendanceEvent.student_id == student_id)
    ))
    windows = list(session.scalars(select(CheckpointWindow).where(CheckpointWindow.activity_id == activity_id)))
    return events, windows


@router.post("/activities/{activity_id}/attempts", response_model=AttemptOut, status_code=201)
def create_attempt(
    activity_id: str,
    body: AttemptIn,
    student: User = Depends(require_student),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> AttemptOut:
    activity = get_activity(session, activity_id)
    require_on_roster(session, activity.id, student.id)
    _approved_enrollment(session, student.id)
    now = clock.now()
    events, windows = _student_state(session, activity.id, student.id)
    checkpoint_id = rules.check_action_allowed(body.action, activity, events, windows, now, rules.timing_from(settings))
    attempt = Attempt(
        id=new_id("att"), student_id=student.id, activity_id=activity.id, action=body.action,
        checkpoint_id=checkpoint_id, created_at=now,
        expires_at=now + timedelta(seconds=settings.attempt_ttl_seconds),
        nonce=secrets.token_urlsafe(16), challenge=secrets.choice(HEAD_TURNS),
    )
    session.add(attempt)
    session.commit()
    return AttemptOut(
        id=attempt.id, activity_id=attempt.activity_id, action=attempt.action, created_at=clock.to_iso(now),
        expires_at=clock.to_iso(attempt.expires_at), checkpoint_id=checkpoint_id,
        nonce=attempt.nonce, challenge=attempt.challenge,
    )


def _event_for(session: Session, attempt_id: str) -> AttendanceEvent | None:
    return session.scalar(select(AttendanceEvent).where(AttendanceEvent.attempt_id == attempt_id))


def _face_verdict(engine: FaceEngine, store: PhotoStore, enrollment: Enrollment, probe: bytes) -> str:
    return engine.compare(store.decrypt(enrollment.embedding_enc), probe)


def _liveness_reasons(
    engine: FaceEngine, straight: FaceAnalysis, turn_bytes: bytes, challenge: str, limits: liveness.LivenessLimits
) -> list[str]:
    """Photo B must be one face, the same person as photo A, turned the way the server asked."""
    turned = engine.analyze(turn_bytes)
    if turned.count != 1 or turned.embedding is None or straight.embedding is None:
        return ["liveness_failed"]
    if engine.compare(straight.embedding, turned.embedding) == NO_MATCH:
        return ["liveness_failed"]
    ok = liveness.head_turn_ok(straight.nose_offset, turned.nose_offset, challenge, limits)
    return [] if ok else ["liveness_failed"]


def _integrity_reasons(is_mock: bool, rooted: bool, emulator: bool, repeated: bool) -> list[str]:
    """Phone-reported red flags, in the fixed order the dashboard shows them."""
    flags = (("location_mocked", is_mock), ("location_repeated", repeated),
             ("device_rooted", rooted), ("device_emulator", emulator))
    return [reason for reason, raised in flags if raised]


def _build_event(
    attempt: Attempt, activity: Activity, now: datetime, loc: tuple[str, int, int, datetime], face: str,
    extra_reasons: list[str], fingerprint: str | None, face_score: float | None = None,
) -> AttendanceEvent:
    loc_verdict, distance, accuracy, captured = loc
    reasons = []
    if loc_verdict != "inside":
        reasons.append(f"location_{loc_verdict}")
    if face != "match":
        reasons.append("face_mismatch" if face == "no_match" else "face_uncertain")
    reasons.extend(extra_reasons)
    return AttendanceEvent(
        id=new_id("evt"), attempt_id=attempt.id, activity_id=activity.id, student_id=attempt.student_id,
        action=attempt.action, status="recorded" if not reasons else "needs_review", received_at=now,
        checkpoint_id=attempt.checkpoint_id, review_reasons=",".join(reasons), location_verdict=loc_verdict,
        distance_m=distance, accuracy_m=accuracy, captured_at=captured, face_verdict=face,
        location_fingerprint=fingerprint, face_score=face_score,
    )


def _persist(session: Session, event: AttendanceEvent, enrollment_id: str) -> AttendanceEvent:
    """Insert the event; a UNIQUE clash means another submit won, so return its event.

    First, inside the same transaction, confirm the enrollment the face was compared against is still the
    approved one. The no-op UPDATE takes SQLite's write lock, so a re-upload cannot commit between the check
    and the insert; zero rows matched means it was replaced or is no longer approved.
    """
    attempt_id = event.attempt_id
    still_approved = session.execute(
        update(Enrollment).where(Enrollment.id == enrollment_id, Enrollment.status == "approved")
        .values(status="approved").execution_options(synchronize_session=False)
    )
    if still_approved.rowcount == 0:
        session.rollback()
        raise errors.enrollment_not_approved()
    session.add(event)
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        existing = _event_for(session, attempt_id)
        if existing is None:
            raise errors.invalid_order("That action was already recorded.") from None
        return existing
    return event


def _require_signed(session: Session, attempt: Attempt, message: bytes, signature: str) -> None:
    """The submit must be signed by the phone bound to this student (reset since the attempt: mismatch)."""
    key = devices.bound_key(session, attempt.student_id)
    if key is None:
        raise errors.device_not_bound()
    if not verify_signature(key, message, signature):
        raise errors.bad_signature()


@router.post("/attempts/{attempt_id}/submit", response_model=AttendanceEventOut)
def submit_attempt(
    attempt_id: str,
    selfie: UploadFile = File(...),
    lat: float = Form(..., ge=-90, le=90),
    lng: float = Form(..., ge=-180, le=180),
    accuracy_m: float = Form(..., ge=0, le=100000),
    location_captured_at: datetime = Form(...),
    turn_photo: UploadFile = File(...),
    location_is_mock: bool = Form(...),
    device_rooted: bool = Form(...),
    device_emulator: bool = Form(...),
    signature: str = Form(..., min_length=1, max_length=200),
    student: User = Depends(require_student),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    engine: FaceEngine = Depends(get_face_engine),
    store: PhotoStore = Depends(get_photo_store),
) -> AttendanceEventOut:
    attempt = session.get(Attempt, attempt_id)
    if attempt is None:
        raise errors.not_found("That attempt does not exist.")
    if attempt.student_id != student.id:
        raise errors.forbidden("This attempt belongs to someone else.")
    existing = _event_for(session, attempt.id)
    if existing is not None:
        return event_out(existing)
    now = clock.now()
    if now > attempt.expires_at:
        raise errors.attempt_expired()
    captured = clock.parse_iso(location_captured_at.isoformat())
    selfie_bytes = read_image(selfie)
    turn_bytes = read_image(turn_photo)
    message = canonical_message(
        attempt_id=attempt.id, nonce=attempt.nonce, selfie=selfie_bytes, turn_photo=turn_bytes,
        lat=lat, lng=lng, accuracy_m=accuracy_m, captured_at=captured,
        is_mock=location_is_mock, rooted=device_rooted, emulator=device_emulator,
    )
    _require_signed(session, attempt, message, signature)
    # Judge and store exactly what was signed: the signature covers these rounded values, not the raw form.
    lat, lng = round(lat, 6), round(lng, 6)
    accuracy_m = round(accuracy_m, 1)
    captured = captured.replace(microsecond=0)
    age_seconds = (now - captured).total_seconds()
    if age_seconds > settings.location_max_age_seconds or age_seconds < -LOCATION_FUTURE_TOLERANCE_SECONDS:
        raise errors.location_stale()
    # Photo bytes stay in memory only; they are never written anywhere by this code.
    analysis = require_one_face(engine, selfie_bytes)
    activity = get_activity(session, attempt.activity_id)
    enrollment = _approved_enrollment(session, student.id)
    events, windows = _student_state(session, activity.id, student.id)
    try:
        rules.check_action_allowed(
            attempt.action, activity, events, windows, attempt.created_at, rules.timing_from(settings)
        )
    except errors.ApiError:
        # A concurrent duplicate submit may have just saved this very attempt: that is a replay, not a conflict.
        saved = _event_for(session, attempt.id)
        if saved is None:
            raise
        return event_out(saved)
    regions = [(r.venue_lat, r.venue_lng, r.radius_m) for r in activity.venue_regions]
    verdict, distance = regions_verdict(lat, lng, accuracy_m, regions or [(activity.venue_lat, activity.venue_lng, activity.radius_m)])
    loc = (verdict, round(distance), round(accuracy_m), captured)
    face = _face_verdict(engine, store, enrollment, analysis.embedding or b"")
    face_score = engine.similarity(store.decrypt(enrollment.embedding_enc), analysis.embedding or b"")
    log.info("face score=%.3f verdict=%s student=%s", face_score, face, student.id)
    if face == "no_match":
        # A clearly different face is refused outright: nothing is saved and the attempt stays open.
        raise errors.face_not_matched()
    fingerprint = location_fingerprint(lat, lng)
    repeated = any(e.location_fingerprint == fingerprint for e in events)
    extra = (
        _liveness_reasons(engine, analysis, turn_bytes, attempt.challenge, liveness.limits_from(settings))
        if settings.liveness_required else []
    )
    extra += _integrity_reasons(location_is_mock, device_rooted, device_emulator, repeated)
    saved = _persist(session, _build_event(attempt, activity, now, loc, face, extra, fingerprint, face_score), enrollment.id)
    return event_out(saved)
