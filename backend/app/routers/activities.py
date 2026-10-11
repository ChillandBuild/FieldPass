from __future__ import annotations

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Response
from sqlalchemy import delete, exists, select
from sqlalchemy.orm import Session

from app import clock, errors, rules
from app.db import get_session
from app.models import (
    Activity, Attempt, AttendanceEvent, CheckpointWindow, Decision, Observation, RosterEntry, StudentDevice, User,
    VenueRegion, new_id,
)
from app.schemas import ActivityIn, ActivityList, ActivityOut, CheckpointIn, CheckpointWindowOut, StudentList, StudentOut, UserList
from app.config import Settings
from app.security import current_user, get_settings_dep, require_faculty
from app.views import build_activity, build_record, load_activity_data, user_out, window_out

router = APIRouter(tags=["activities"])


def get_activity(session: Session, activity_id: str) -> Activity:
    activity = session.get(Activity, activity_id)
    if activity is None:
        raise errors.not_found("That activity does not exist.")
    return activity


def require_on_roster(session: Session, activity_id: str, student_id: str) -> None:
    if session.get(RosterEntry, (activity_id, student_id)) is None:
        raise errors.not_assigned()


def render_activity(session: Session, activity: Activity, viewer: User, timing: rules.Timing) -> ActivityOut:
    """Faculty see no my_attendance; students see their own record with next_actions."""
    now = clock.now()
    if viewer.role == "student":
        data = load_activity_data(session, activity, student_id=viewer.id)
        mine = build_record(data, viewer, now, with_actions=True, timing=timing)
        return build_activity(data, now, mine)
    return build_activity(load_activity_data(session, activity), now, None)


def _student_out(session: Session, student: User) -> StudentOut:
    device = session.get(StudentDevice, student.id)
    return StudentOut(**user_out(student).model_dump(), device_bound_at=clock.to_iso(device.bound_at) if device else None)


@router.get("/students", response_model=StudentList)
def list_students(_: User = Depends(require_faculty), session: Session = Depends(get_session)) -> StudentList:
    rows = session.scalars(select(User).where(User.role == "student").order_by(User.username))
    return StudentList(items=[_student_out(session, u) for u in rows])


@router.post("/students/{student_id}/device/reset", response_model=StudentOut)
def reset_device(
    student_id: str, _: User = Depends(require_faculty), session: Session = Depends(get_session),
) -> StudentOut:
    student = session.get(User, student_id)
    if student is None or student.role != "student":
        raise errors.not_found("That student does not exist.")
    session.execute(delete(StudentDevice).where(StudentDevice.student_id == student_id))
    session.commit()
    return _student_out(session, student)


@router.post("/activities", response_model=ActivityOut, status_code=201)
def create_activity(
    body: ActivityIn,
    faculty: User = Depends(require_faculty),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ActivityOut:
    student_ids = _checked_student_ids(session, body.student_ids)
    activity = Activity(
        id=new_id("act"), name=body.name, venue_name=body.venue_name, venue_lat=body.venue_lat,
        venue_lng=body.venue_lng, radius_m=body.radius_m, starts_at=body.starts_at, ends_at=body.ends_at,
        created_by_id=faculty.id, created_at=clock.now(),
    )
    _apply_regions(activity, body)
    session.add(activity)
    session.add_all(RosterEntry(activity_id=activity.id, student_id=sid) for sid in student_ids)
    session.commit()
    return render_activity(session, activity, faculty, rules.timing_from(settings))


def _checked_student_ids(session: Session, requested: list[str]) -> list[str]:
    student_ids = list(dict.fromkeys(requested))
    found = set(session.scalars(select(User.id).where(User.id.in_(student_ids), User.role == "student")))
    if found != set(student_ids):
        raise errors.validation_error("student_ids contains an unknown student.")
    return student_ids


def _apply_regions(activity: Activity, body: ActivityIn) -> None:
    """Venue regions replace the single circle; the first region also fills the legacy venue fields."""
    if body.venue_regions:
        first = body.venue_regions[0]
        activity.venue_lat, activity.venue_lng, activity.radius_m = first.venue_lat, first.venue_lng, first.radius_m
    activity.venue_regions = [VenueRegion(position=i, **region.model_dump()) for i, region in enumerate(body.venue_regions)]


def _campus_day_start(moment: datetime, settings: Settings) -> datetime:
    """Start (as UTC) of the campus-local calendar day that contains `moment`."""
    offset = timedelta(minutes=settings.campus_utc_offset_minutes)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    local = moment.astimezone(timezone.utc) + offset
    return local.replace(hour=0, minute=0, second=0, microsecond=0) - offset


@router.put("/activities/{activity_id}", response_model=ActivityOut)
def update_activity(
    activity_id: str,
    body: ActivityIn,
    faculty: User = Depends(require_faculty),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ActivityOut:
    """Replace an activity's details and roster. Allowed up to the day before it starts (campus time)."""
    activity = get_activity(session, activity_id)
    now = clock.now()
    if now >= _campus_day_start(activity.starts_at, settings):
        raise errors.activity_locked()
    if now >= _campus_day_start(body.starts_at, settings):
        raise errors.validation_error("The new start must be on a day after today.")
    student_ids = _checked_student_ids(session, body.student_ids)
    activity.name, activity.venue_name = body.name, body.venue_name
    activity.venue_lat, activity.venue_lng, activity.radius_m = body.venue_lat, body.venue_lng, body.radius_m
    activity.starts_at, activity.ends_at = body.starts_at, body.ends_at
    _apply_regions(activity, body)
    session.execute(delete(RosterEntry).where(RosterEntry.activity_id == activity.id))
    session.add_all(RosterEntry(activity_id=activity.id, student_id=sid) for sid in student_ids)
    session.commit()
    return render_activity(session, activity, faculty, rules.timing_from(settings))


@router.delete("/activities/{activity_id}", status_code=204)
def delete_activity(
    activity_id: str, _: User = Depends(require_faculty), session: Session = Depends(get_session)
) -> Response:
    """Delete an activity that has no attendance records at all; otherwise 409 and nothing changes."""
    activity = get_activity(session, activity_id)
    for model in (Attempt, AttendanceEvent, Decision, Observation):
        if session.scalar(select(exists().where(model.activity_id == activity.id))):
            raise errors.activity_has_attendance()
    for model in (CheckpointWindow, RosterEntry):
        session.execute(delete(model).where(model.activity_id == activity.id))
    session.delete(activity)  # its venue regions go with it (delete-orphan cascade)
    session.commit()
    return Response(status_code=204)


@router.get("/activities", response_model=ActivityList)
def list_activities(
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ActivityList:
    query = select(Activity).order_by(Activity.starts_at.desc(), Activity.id)
    if user.role == "student":
        query = query.join(RosterEntry, RosterEntry.activity_id == Activity.id).where(RosterEntry.student_id == user.id)
    timing = rules.timing_from(settings)
    return ActivityList(items=[render_activity(session, a, user, timing) for a in session.scalars(query)])


@router.get("/activities/{activity_id}", response_model=ActivityOut)
def read_activity(
    activity_id: str,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ActivityOut:
    activity = get_activity(session, activity_id)
    if user.role == "student":
        require_on_roster(session, activity.id, user.id)
    return render_activity(session, activity, user, rules.timing_from(settings))


@router.get("/activities/{activity_id}/roster", response_model=UserList)
def roster(
    activity_id: str, _: User = Depends(require_faculty), session: Session = Depends(get_session)
) -> UserList:
    activity = get_activity(session, activity_id)
    rows = session.scalars(
        select(User).join(RosterEntry, RosterEntry.student_id == User.id)
        .where(RosterEntry.activity_id == activity.id).order_by(User.username)
    )
    return UserList(items=[user_out(u) for u in rows])


@router.post("/activities/{activity_id}/checkpoints", response_model=CheckpointWindowOut, status_code=201)
def open_checkpoint(
    activity_id: str,
    body: CheckpointIn | None = None,
    faculty: User = Depends(require_faculty),
    session: Session = Depends(get_session),
) -> CheckpointWindowOut:
    activity = get_activity(session, activity_id)
    now = clock.now()
    if not (activity.starts_at <= now <= activity.ends_at):
        raise errors.outside_window()
    windows = list(session.scalars(select(CheckpointWindow).where(CheckpointWindow.activity_id == activity.id)))
    if rules.open_checkpoint(windows, now) is not None:
        raise errors.invalid_order("A checkpoint is already open for this activity.")
    minutes = (body or CheckpointIn()).duration_minutes
    window = CheckpointWindow(
        id=new_id("cp"), activity_id=activity.id, opens_at=now,
        closes_at=now + timedelta(minutes=minutes), opened_by_id=faculty.id,
    )
    session.add(window)
    session.commit()
    return window_out(window, {faculty.id: faculty})
