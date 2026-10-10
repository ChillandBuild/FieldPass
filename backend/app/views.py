"""Turn database rows into the API's response shapes."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import rules
from app.clock import to_iso
from app.models import (
    Activity, AttendanceEvent, CheckpointWindow, Decision, Enrollment, Observation, RosterEntry, User,
)
from app.schemas import (
    ActivityOut, AttendanceEventOut, AttendanceRecordOut, CheckpointWindowOut, DecisionOut, EnrollmentOut,
    FaceOut, LocationOut, ObservationOut, UserOut,
)


def user_out(user: User) -> UserOut:
    return UserOut(id=user.id, username=user.username, full_name=user.full_name, role=user.role)


def enrollment_out(student: User, enrollment: Enrollment | None, decider: User | None) -> EnrollmentOut:
    if enrollment is None:
        return EnrollmentOut(
            id=None, status="none", student=user_out(student), submitted_at=None,
            decided_at=None, decided_by=None, decision_reason=None,
        )
    return EnrollmentOut(
        id=enrollment.id, status=enrollment.status, student=user_out(student),
        submitted_at=to_iso(enrollment.submitted_at), decided_at=to_iso(enrollment.decided_at),
        decided_by=decider.full_name if decider else None, decision_reason=enrollment.decision_reason,
    )


def event_out(event: AttendanceEvent) -> AttendanceEventOut:
    return AttendanceEventOut(
        id=event.id, attempt_id=event.attempt_id, activity_id=event.activity_id, student_id=event.student_id,
        action=event.action, status=event.status, received_at=to_iso(event.received_at),
        checkpoint_id=event.checkpoint_id,
        review_reasons=[r for r in event.review_reasons.split(",") if r],
        location=LocationOut(
            verdict=event.location_verdict, distance_m=event.distance_m,
            accuracy_m=event.accuracy_m, captured_at=to_iso(event.captured_at),
        ),
        face=FaceOut(verdict=event.face_verdict),
    )


@dataclass
class ActivityData:
    """Everything needed to render one activity, loaded in a handful of queries."""

    activity: Activity
    users: dict[str, User]
    windows: list[CheckpointWindow]
    roster: list[str]
    events: dict[str, list[AttendanceEvent]] = field(default_factory=lambda: defaultdict(list))
    decisions: dict[str, list[Decision]] = field(default_factory=lambda: defaultdict(list))
    observations: dict[str, list[Observation]] = field(default_factory=lambda: defaultdict(list))


def load_activity_data(session: Session, activity: Activity, student_id: str | None = None) -> ActivityData:
    roster = list(session.scalars(select(RosterEntry.student_id).where(RosterEntry.activity_id == activity.id)))
    windows = list(session.scalars(
        select(CheckpointWindow).where(CheckpointWindow.activity_id == activity.id).order_by(CheckpointWindow.opens_at)
    ))
    data = ActivityData(activity=activity, users={}, windows=windows, roster=sorted(roster))
    for model, bucket in ((AttendanceEvent, data.events), (Decision, data.decisions), (Observation, data.observations)):
        query = select(model).where(model.activity_id == activity.id)
        if student_id:
            query = query.where(model.student_id == student_id)
        order = {AttendanceEvent: "received_at", Decision: "decided_at", Observation: "observed_at"}[model]
        for row in session.scalars(query.order_by(getattr(model, order), model.id)):
            bucket[row.student_id].append(row)
    wanted = {activity.created_by_id, *roster, *(w.opened_by_id for w in windows)}
    wanted |= {d.actor_id for rows in data.decisions.values() for d in rows}
    wanted |= {o.actor_id for rows in data.observations.values() for o in rows}
    data.users = {u.id: u for u in session.scalars(select(User).where(User.id.in_(wanted)))}
    return data


def window_out(window: CheckpointWindow, users: dict[str, User]) -> CheckpointWindowOut:
    opener = users.get(window.opened_by_id)
    return CheckpointWindowOut(
        id=window.id, activity_id=window.activity_id, opens_at=to_iso(window.opens_at),
        closes_at=to_iso(window.closes_at), opened_by=opener.full_name if opener else "",
    )


def _decision_out(decision: Decision, current: Decision | None, users: dict[str, User]) -> DecisionOut:
    actor = users.get(decision.actor_id)
    return DecisionOut(
        id=decision.id, decision=decision.decision, reason=decision.reason,
        actor_name=actor.full_name if actor else "", decided_at=to_iso(decision.decided_at),
        is_current=current is not None and current.id == decision.id,
    )


def _observation_out(obs: Observation, users: dict[str, User]) -> ObservationOut:
    actor = users.get(obs.actor_id)
    return ObservationOut(
        id=obs.id, activity_id=obs.activity_id, student_id=obs.student_id,
        observation_type=obs.observation_type, note=obs.note,
        actor_name=actor.full_name if actor else "", observed_at=to_iso(obs.observed_at),
    )


def build_record(
    data: ActivityData, student: User, now: datetime, with_actions: bool, timing: rules.Timing
) -> AttendanceRecordOut:
    events = data.events.get(student.id, [])
    decisions = data.decisions.get(student.id, [])
    current = rules.current_decision(decisions, events)
    check_in = next((e for e in events if e.action == "check_in"), None)
    check_out = next((e for e in events if e.action == "check_out"), None)
    ordered = sorted(decisions, key=lambda d: d.decided_at)
    return AttendanceRecordOut(
        activity_id=data.activity.id, activity_name=data.activity.name, student=user_out(student),
        status=rules.derive_status(decisions, events),
        check_in=event_out(check_in) if check_in else None,
        checkpoints=[event_out(e) for e in events if e.action == "checkpoint"],
        check_out=event_out(check_out) if check_out else None,
        missing_checkout=rules.missing_checkout(data.activity, events, now, timing),
        complete=rules.is_complete(events),
        review_reasons=rules.union_reasons(events),
        decision=_decision_out(current, current, data.users) if current else None,
        decision_history=[_decision_out(d, current, data.users) for d in reversed(ordered)],
        observations=[_observation_out(o, data.users) for o in data.observations.get(student.id, [])],
        next_actions=rules.next_actions(data.activity, events, data.windows, now, timing) if with_actions else [],
    )


def build_activity(data: ActivityData, now: datetime, my_attendance: AttendanceRecordOut | None) -> ActivityOut:
    activity = data.activity
    creator = data.users.get(activity.created_by_id)
    live = rules.open_checkpoint(data.windows, now)
    return ActivityOut(
        id=activity.id, name=activity.name, venue_name=activity.venue_name, venue_lat=activity.venue_lat,
        venue_lng=activity.venue_lng, radius_m=activity.radius_m,
        venue_regions=[dict(venue_lat=r.venue_lat, venue_lng=r.venue_lng, radius_m=r.radius_m) for r in activity.venue_regions]
        or [dict(venue_lat=activity.venue_lat, venue_lng=activity.venue_lng, radius_m=activity.radius_m)],
        starts_at=to_iso(activity.starts_at),
        ends_at=to_iso(activity.ends_at), created_by=creator.full_name if creator else "",
        roster_count=len(data.roster),
        checkpoint_windows=[window_out(w, data.users) for w in data.windows],
        open_checkpoint=window_out(live, data.users) if live else None,
        my_attendance=my_attendance,
    )
