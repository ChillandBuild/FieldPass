from __future__ import annotations

from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, File, Form, UploadFile
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock, errors, rules
from app.config import Settings
from app.db import get_session
from app.deps import get_face_engine, get_photo_store
from app.face import FaceEngine
from app.geo import regions_verdict
from app.models import Activity, Attempt, AttendanceEvent, CheckpointWindow, Enrollment, User, new_id
from app.photo_store import PhotoStore
from app.routers.activities import get_activity, require_on_roster
from app.routers.enrollment import read_image, require_one_face
from app.schemas import AttemptIn, AttemptOut, AttendanceEventOut
from app.security import get_settings_dep, require_student
from app.views import event_out

router = APIRouter(tags=["attempts"])

# A reading stamped more than this far ahead of the server clock cannot be fresh; it is rejected as stale.
LOCATION_FUTURE_TOLERANCE_SECONDS = 5


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
    )
    session.add(attempt)
    session.commit()
    return AttemptOut(
        id=attempt.id, activity_id=attempt.activity_id, action=attempt.action, created_at=clock.to_iso(now),
        expires_at=clock.to_iso(attempt.expires_at), checkpoint_id=checkpoint_id,
    )


def _event_for(session: Session, attempt_id: str) -> AttendanceEvent | None:
    return session.scalar(select(AttendanceEvent).where(AttendanceEvent.attempt_id == attempt_id))


def _face_verdict(engine: FaceEngine, store: PhotoStore, enrollment: Enrollment, probe: bytes) -> str:
    return engine.compare(store.decrypt(enrollment.embedding_enc), probe)


def _build_event(
    attempt: Attempt, activity: Activity, now: datetime, loc: tuple[str, int, int, datetime], face: str
) -> AttendanceEvent:
    loc_verdict, distance, accuracy, captured = loc
    reasons = []
    if loc_verdict != "inside":
        reasons.append(f"location_{loc_verdict}")
    if face != "match":
        reasons.append("face_mismatch" if face == "no_match" else "face_uncertain")
    return AttendanceEvent(
        id=new_id("evt"), attempt_id=attempt.id, activity_id=activity.id, student_id=attempt.student_id,
        action=attempt.action, status="recorded" if not reasons else "needs_review", received_at=now,
        checkpoint_id=attempt.checkpoint_id, review_reasons=",".join(reasons), location_verdict=loc_verdict,
        distance_m=distance, accuracy_m=accuracy, captured_at=captured, face_verdict=face,
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


@router.post("/attempts/{attempt_id}/submit", response_model=AttendanceEventOut)
def submit_attempt(
    attempt_id: str,
    selfie: UploadFile = File(...),
    lat: float = Form(..., ge=-90, le=90),
    lng: float = Form(..., ge=-180, le=180),
    accuracy_m: float = Form(..., ge=0, le=100000),
    location_captured_at: datetime = Form(...),
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
    age_seconds = (now - captured).total_seconds()
    if age_seconds > settings.location_max_age_seconds or age_seconds < -LOCATION_FUTURE_TOLERANCE_SECONDS:
        raise errors.location_stale()
    # Selfie bytes stay in memory only; they are never written anywhere by this code.
    analysis = require_one_face(engine, read_image(selfie))
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
    if face == "no_match":
        # A clearly different face is refused outright: nothing is saved and the attempt stays open.
        raise errors.face_not_matched()
    saved = _persist(session, _build_event(attempt, activity, now, loc, face), enrollment.id)
    return event_out(saved)
