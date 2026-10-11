"""Rotating presence code. Pure and stateless: nothing is stored, the code is recomputed from the JWT secret.

Format: fp1.{step}.{mac}. `step` counts whole steps since the Unix epoch; `mac` is the first 16 bytes of an
HMAC over "{activity_id}|{step}", Base64url without padding. A code is valid in its own step and the next one.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import re
from datetime import datetime, timezone

CODE_PREFIX = "fp1"
KEY_LABEL = b"fieldpass-presence-code-v1"
MAC_BYTES = 16
VALID_STEPS_BACK = 1  # the current step and the one before it
_STEP_DIGITS = re.compile(r"[0-9]{1,15}")


def current_step(now: datetime, step_seconds: int) -> int:
    return int(now.timestamp()) // step_seconds


def step_ends_at(now: datetime, step_seconds: int) -> datetime:
    """When the step containing `now` ends (and the next code begins)."""
    return datetime.fromtimestamp((current_step(now, step_seconds) + 1) * step_seconds, tz=timezone.utc)


def _mac(secret: str, activity_id: str, step: int) -> str:
    key = hmac.new(secret.encode(), KEY_LABEL, hashlib.sha256).digest()
    digest = hmac.new(key, f"{activity_id}|{step}".encode(), hashlib.sha256).digest()[:MAC_BYTES]
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def make_code(secret: str, activity_id: str, now: datetime, step_seconds: int) -> str:
    step = current_step(now, step_seconds)
    return f"{CODE_PREFIX}.{step}.{_mac(secret, activity_id, step)}"


def is_valid(secret: str, activity_id: str, code: str, now: datetime, step_seconds: int) -> bool:
    """True for a well-formed code of this activity from the current or previous step. Never raises."""
    parts = code.split(".")
    if len(parts) != 3 or parts[0] != CODE_PREFIX or not _STEP_DIGITS.fullmatch(parts[1]) or not parts[2].isascii():
        return False
    step = int(parts[1])
    now_step = current_step(now, step_seconds)
    if not now_step - VALID_STEPS_BACK <= step <= now_step:
        return False
    return hmac.compare_digest(_mac(secret, activity_id, step).encode(), parts[2].encode())
