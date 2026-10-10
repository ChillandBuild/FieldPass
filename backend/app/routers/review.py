from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import clock, errors
from app.db import get_session
from app.models import Decision, Observation, RosterEntry, User, new_id
from app.routers.activities import get_activity
from app.schemas import AttendanceRecordOut, DecisionIn, ObservationIn, ObservationOut
from app.security import require_faculty
from app.views import build_record, load_activity_data

router = APIRouter(tags=["review"])


def _rostered_student(session: Session, activity_id: str, student_id: str) -> User:
    student = session.get(User, student_id)
    on_roster = session.get(RosterEntry, (activity_id, student_id)) is not None
    if student is None or not on_roster:
        raise errors.not_found("That student is not on this activity's roster.")
    return student


@router.post("/activities/{activity_id}/students/{student_id}/decision", response_model=AttendanceRecordOut)
def decide(
    activity_id: str,
    student_id: str,
    body: DecisionIn,
    faculty: User = Depends(require_faculty),
    session: Session = Depends(get_session),
) -> AttendanceRecordOut:
    activity = get_activity(session, activity_id)
    student = _rostered_student(session, activity.id, student_id)
    session.add(Decision(
        id=new_id("dec"), activity_id=activity.id, student_id=student.id, decision=body.decision,
        reason=body.reason, actor_id=faculty.id, decided_at=clock.now(),
    ))
    session.commit()
    data = load_activity_data(session, activity, student_id=student.id)
    return build_record(data, student, clock.now(), with_actions=False)


@router.post("/activities/{activity_id}/observations", response_model=ObservationOut, status_code=201)
def observe(
    activity_id: str,
    body: ObservationIn,
    faculty: User = Depends(require_faculty),
    session: Session = Depends(get_session),
) -> ObservationOut:
    activity = get_activity(session, activity_id)
    student = _rostered_student(session, activity.id, body.student_id)
    row = Observation(
        id=new_id("obs"), activity_id=activity.id, student_id=student.id,
        observation_type=body.observation_type, note=body.note, actor_id=faculty.id, observed_at=clock.now(),
    )
    session.add(row)
    session.commit()
    return ObservationOut(
        id=row.id, activity_id=row.activity_id, student_id=row.student_id,
        observation_type=row.observation_type, note=row.note, actor_name=faculty.full_name,
        observed_at=clock.to_iso(row.observed_at),
    )
