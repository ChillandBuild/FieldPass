from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, File, Query, Response, UploadFile
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock, errors
from app.db import get_session
from app.deps import MAX_PHOTO_BYTES, get_face_engine, get_photo_store
from app.face import FaceEngine
from app.models import Enrollment, User, new_id
from app.photo_store import PhotoStore
from app.schemas import ApproveIn, EnrollmentList, EnrollmentOut, RejectIn
from app.security import require_faculty, require_student
from app.views import enrollment_out

router = APIRouter(tags=["enrollment"])

MAX_UPLOAD_TRIES = 3  # a unique clash means a concurrent upload by the same student won; try again on top of it


def read_image(upload: UploadFile) -> bytes:
    """Read an uploaded part, rejecting anything over 5 MB."""
    data = upload.file.read(MAX_PHOTO_BYTES + 1)
    if len(data) > MAX_PHOTO_BYTES:
        raise errors.image_too_large()
    return data


def require_one_face(engine: FaceEngine, data: bytes):  # noqa: ANN201
    analysis = engine.analyze(data)
    if analysis.count > 1:
        raise errors.multiple_faces()
    if analysis.count == 0 or analysis.embedding is None:
        raise errors.unusable_capture()
    return analysis


def _view(session: Session, student: User, enrollment: Enrollment | None) -> EnrollmentOut:
    decider = session.get(User, enrollment.decided_by_id) if enrollment and enrollment.decided_by_id else None
    return enrollment_out(student, enrollment, decider)


def _swap_in(session: Session, student: User, new_file: str, embedding_enc: bytes) -> tuple[Enrollment, list[str]]:
    """One transaction: drop the student's current enrollment (if any) and add a fresh one with a new id.

    The delete returns the old photo names itself, so a concurrent upload cannot slip an unseen file past us.
    """
    old_files = list(session.execute(
        delete(Enrollment).where(Enrollment.student_id == student.id).returning(Enrollment.photo_file)
    ).scalars())
    row = Enrollment(
        id=new_id("enr"), student_id=student.id, status="pending", photo_file=new_file,
        embedding_enc=embedding_enc, submitted_at=clock.now(),
    )
    session.add(row)
    session.commit()
    return row, old_files


def _store_enrollment(session: Session, student: User, new_file: str, embedding_enc: bytes) -> tuple[Enrollment, list[str]]:
    for _ in range(MAX_UPLOAD_TRIES):
        try:
            return _swap_in(session, student, new_file, embedding_enc)
        except IntegrityError:
            session.rollback()
    raise errors.invalid_order("Another upload for this student is in progress. Try again.")


def _enroll(photo: UploadFile, student: User, session: Session, engine: FaceEngine, store: PhotoStore) -> EnrollmentOut:
    # Every upload is a new enrollment with a new id, so a decision on the old id can never land on a new photo.
    analysis = require_one_face(engine, read_image(photo))
    new_file = store.save(analysis.normalized_jpeg or b"")
    try:
        row, old_files = _store_enrollment(session, student, new_file, store.encrypt(analysis.embedding or b""))
    except BaseException:
        store.delete(new_file)  # never leave an unreferenced encrypted photo behind
        raise
    for name in old_files:
        store.delete(name)
    return _view(session, student, row)


@router.post("/enrollment", response_model=EnrollmentOut, status_code=201)
def upload_enrollment(
    photo: UploadFile = File(...),
    student: User = Depends(require_student),
    session: Session = Depends(get_session),
    engine: FaceEngine = Depends(get_face_engine),
    store: PhotoStore = Depends(get_photo_store),
) -> EnrollmentOut:
    return _enroll(photo, student, session, engine, store)


@router.post("/students/{student_id}/enrollment", response_model=EnrollmentOut, status_code=201)
def upload_enrollment_for_student(
    student_id: str,
    photo: UploadFile = File(...),
    _: User = Depends(require_faculty),
    session: Session = Depends(get_session),
    engine: FaceEngine = Depends(get_face_engine),
    store: PhotoStore = Depends(get_photo_store),
) -> EnrollmentOut:
    # Faculty upload is pending like a student's; approval is still a separate faculty step (identity_confirmed).
    student = session.get(User, student_id)
    if student is None or student.role != "student":
        raise errors.not_found("That student does not exist.")
    return _enroll(photo, student, session, engine, store)


@router.get("/enrollment/me", response_model=EnrollmentOut)
def my_enrollment(student: User = Depends(require_student), session: Session = Depends(get_session)) -> EnrollmentOut:
    row = session.scalar(select(Enrollment).where(Enrollment.student_id == student.id))
    return _view(session, student, row)


@router.get("/enrollments", response_model=EnrollmentList)
def list_enrollments(
    status: Literal["pending", "approved", "rejected"] | None = Query(default=None),
    _: User = Depends(require_faculty),
    session: Session = Depends(get_session),
) -> EnrollmentList:
    query = select(Enrollment).order_by(Enrollment.submitted_at)
    if status:
        query = query.where(Enrollment.status == status)
    rows = list(session.scalars(query))
    return EnrollmentList(items=[_view(session, session.get(User, r.student_id), r) for r in rows])


def _get_enrollment(session: Session, enrollment_id: str) -> Enrollment:
    row = session.get(Enrollment, enrollment_id)
    if row is None:
        raise errors.not_found("That enrollment does not exist.")
    return row


@router.get("/enrollments/{enrollment_id}/photo", response_class=Response, responses={200: {"content": {"image/jpeg": {}}}})
def enrollment_photo(
    enrollment_id: str,
    _: User = Depends(require_faculty),
    session: Session = Depends(get_session),
    store: PhotoStore = Depends(get_photo_store),
) -> Response:
    row = _get_enrollment(session, enrollment_id)
    headers = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"}
    return Response(store.load(row.photo_file), media_type="image/jpeg", headers=headers)


def _decide(session: Session, enrollment_id: str, status: str, actor: User, reason: str | None) -> Enrollment:
    """Conditional update: only a still-pending row changes, so two simultaneous decisions cannot both win."""
    result = session.execute(
        update(Enrollment).where(Enrollment.id == enrollment_id, Enrollment.status == "pending")
        .values(status=status, decided_at=clock.now(), decided_by_id=actor.id, decision_reason=reason)
        .execution_options(synchronize_session=False)
    )
    if result.rowcount == 0:
        session.rollback()
        if session.scalar(select(Enrollment.id).where(Enrollment.id == enrollment_id)) is None:
            raise errors.not_found("That enrollment does not exist.")  # replaced by a newer upload meanwhile
        raise errors.already_decided()
    session.commit()
    row = session.get(Enrollment, enrollment_id)
    session.refresh(row)
    return row


@router.post("/enrollments/{enrollment_id}/approve", response_model=EnrollmentOut)
def approve(
    enrollment_id: str,
    _body: ApproveIn,
    actor: User = Depends(require_faculty),
    session: Session = Depends(get_session),
) -> EnrollmentOut:
    row = _decide(session, _get_enrollment(session, enrollment_id).id, "approved", actor, None)
    return _view(session, session.get(User, row.student_id), row)


@router.post("/enrollments/{enrollment_id}/reject", response_model=EnrollmentOut)
def reject(
    enrollment_id: str,
    body: RejectIn,
    actor: User = Depends(require_faculty),
    session: Session = Depends(get_session),
) -> EnrollmentOut:
    row = _decide(session, _get_enrollment(session, enrollment_id).id, "rejected", actor, body.reason)
    return _view(session, session.get(User, row.student_id), row)
