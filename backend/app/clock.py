"""Single injectable clock. All server-side "now" values come from here."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Callable

NowFn = Callable[[], datetime]


def _system_now() -> datetime:
    return datetime.now(timezone.utc)


_now_fn: NowFn = _system_now


def now() -> datetime:
    """Current server time (timezone-aware UTC)."""
    return _now_fn()


def set_clock(fn: NowFn | None) -> None:
    """Replace the clock (tests). Pass None to restore the system clock."""
    global _now_fn
    _now_fn = fn or _system_now


def to_iso(value: datetime | None) -> str | None:
    """ISO-8601 UTC with Z suffix, whole seconds."""
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(text: str) -> datetime:
    """Parse an ISO-8601 string (Z or offset) into aware UTC; naive input is taken as UTC."""
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
