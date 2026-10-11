"""Pure business rules: windows, action order, record status derivation."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Iterable, Sequence

from app import errors
from app.config import Settings
from app.models import Activity, AttendanceEvent, CheckpointWindow, Decision

ACTIONS = ("check_in", "checkpoint", "check_out")


@dataclass(frozen=True)
class Timing:
    """How early check-in opens, how long after the start it closes, and how long check-out stays open after the end."""
    checkin_lead: timedelta = timedelta(minutes=15)
    checkin_cutoff: timedelta = timedelta(minutes=30)
    checkout_grace: timedelta = timedelta(minutes=30)


DEFAULT_TIMING = Timing()


def timing_from(settings: Settings) -> Timing:
    return Timing(
        checkin_lead=timedelta(minutes=settings.checkin_lead_minutes),
        checkin_cutoff=timedelta(minutes=settings.checkin_cutoff_minutes),
        checkout_grace=timedelta(minutes=settings.checkout_grace_minutes),
    )


def checkin_window(activity: Activity, timing: Timing = DEFAULT_TIMING) -> tuple[datetime, datetime]:
    return activity.starts_at - timing.checkin_lead, min(activity.starts_at + timing.checkin_cutoff, activity.ends_at)


def checkout_deadline(activity: Activity, timing: Timing = DEFAULT_TIMING) -> datetime:
    return activity.ends_at + timing.checkout_grace


def presence_code_window(activity: Activity, timing: Timing = DEFAULT_TIMING) -> tuple[datetime, datetime]:
    """When the faculty screen may show a presence code: from check-in opening to the check-out deadline."""
    return checkin_window(activity, timing)[0], checkout_deadline(activity, timing)


def open_checkpoint(windows: Iterable[CheckpointWindow], now: datetime) -> CheckpointWindow | None:
    """The currently open window (opens_at <= now < closes_at), latest first."""
    live = [w for w in windows if w.opens_at <= now < w.closes_at]
    return max(live, key=lambda w: w.opens_at) if live else None


def _find(events: Sequence[AttendanceEvent], action: str) -> list[AttendanceEvent]:
    return [e for e in events if e.action == action]


def check_action_allowed(
    action: str,
    activity: Activity,
    events: Sequence[AttendanceEvent],
    windows: Sequence[CheckpointWindow],
    now: datetime,
    timing: Timing = DEFAULT_TIMING,
) -> str | None:
    """Raise the contract error if `action` is not allowed; else return the checkpoint id (or None)."""
    if _find(events, "check_out"):
        raise errors.invalid_order("Attendance for this activity is already finished.")
    has_checkin = bool(_find(events, "check_in"))
    if action == "check_in":
        return _check_in(activity, has_checkin, now, timing)
    if not has_checkin:
        raise errors.invalid_order("Check in first.")
    if action == "checkpoint":
        return _checkpoint(events, windows, now)
    return _check_out(activity, now, timing)


def _check_in(activity: Activity, has_checkin: bool, now: datetime, timing: Timing) -> None:
    if has_checkin:
        raise errors.invalid_order("You have already checked in.")
    opens, closes = checkin_window(activity, timing)
    if not (opens <= now <= closes):
        raise errors.outside_window()
    return None


def _checkpoint(events: Sequence[AttendanceEvent], windows: Sequence[CheckpointWindow], now: datetime) -> str:
    window = open_checkpoint(windows, now)
    if window is None:
        raise errors.checkpoint_not_open()
    if any(e.checkpoint_id == window.id for e in _find(events, "checkpoint")):
        raise errors.checkpoint_not_open()
    return window.id


def _check_out(activity: Activity, now: datetime, timing: Timing) -> None:
    if now > checkout_deadline(activity, timing):
        raise errors.outside_window()
    return None


def next_actions(
    activity: Activity,
    events: Sequence[AttendanceEvent],
    windows: Sequence[CheckpointWindow],
    now: datetime,
    timing: Timing = DEFAULT_TIMING,
) -> list[str]:
    allowed = []
    for action in ACTIONS:
        try:
            check_action_allowed(action, activity, events, windows, now, timing)
        except errors.ApiError:
            continue
        allowed.append(action)
    return allowed


def missing_checkout(
    activity: Activity, events: Sequence[AttendanceEvent], now: datetime, timing: Timing = DEFAULT_TIMING
) -> bool:
    """Check-in exists, no check-out, and the check-out window has closed."""
    return bool(_find(events, "check_in")) and not _find(events, "check_out") and now > checkout_deadline(activity, timing)


def is_complete(events: Sequence[AttendanceEvent]) -> bool:
    return bool(_find(events, "check_in")) and bool(_find(events, "check_out"))


def current_decision(decisions: Sequence[Decision], events: Sequence[AttendanceEvent]) -> Decision | None:
    """The newest decision, if no automated event arrived after it."""
    if not decisions:
        return None
    newest = max(decisions, key=lambda d: d.decided_at)
    if any(e.received_at > newest.decided_at for e in events):
        return None
    return newest


def derive_status(decisions: Sequence[Decision], events: Sequence[AttendanceEvent]) -> str | None:
    """Current decision > needs_review if any event needs review > recorded; None if nothing at all."""
    decision = current_decision(decisions, events)
    if decision is not None:
        return decision.decision
    if not events:
        return None
    return "needs_review" if any(e.status == "needs_review" for e in events) else "recorded"


def union_reasons(events: Sequence[AttendanceEvent]) -> list[str]:
    seen: list[str] = []
    for event in events:
        for reason in filter(None, event.review_reasons.split(",")):
            if reason not in seen:
                seen.append(reason)
    return seen
