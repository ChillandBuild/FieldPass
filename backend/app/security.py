"""Password hashing, bearer tokens, role dependencies, login rate limit."""
from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timedelta

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from fastapi import Depends, Request
from sqlalchemy.orm import Session

from app import clock, errors
from app.config import Settings
from app.db import get_session
from app.models import User

_hasher = PasswordHasher()
ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return _hasher.verify(password_hash, password)
    except (VerificationError, InvalidHashError):
        return False


def create_token(settings: Settings, user: User) -> tuple[str, datetime]:
    expires = clock.now() + timedelta(minutes=settings.token_ttl_minutes)
    payload = {"sub": user.id, "role": user.role, "exp": int(expires.timestamp())}
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM), expires


def decode_token(settings: Settings, token: str) -> str:
    """Return the user id. Expiry is checked against the injectable clock."""
    try:
        payload = jwt.decode(
            token, settings.jwt_secret, algorithms=[ALGORITHM], options={"verify_exp": False}
        )
    except jwt.PyJWTError as exc:
        raise errors.not_authenticated() from exc
    if int(payload.get("exp", 0)) <= int(clock.now().timestamp()):
        raise errors.not_authenticated()
    subject = payload.get("sub")
    if not isinstance(subject, str):
        raise errors.not_authenticated()
    return subject


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


def current_user(
    request: Request,
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> User:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise errors.not_authenticated()
    user = session.get(User, decode_token(settings, token.strip()))
    if user is None:
        raise errors.not_authenticated()
    return user


def require_student(user: User = Depends(current_user)) -> User:
    if user.role != "student":
        raise errors.forbidden()
    return user


def require_faculty(user: User = Depends(current_user)) -> User:
    if user.role != "faculty":
        raise errors.forbidden()
    return user


@dataclass
class LoginRateLimiter:
    """In-memory sliding window per key: one budget per ip+username pair, a larger one per ip.

    Idle keys are dropped each window, and the map never holds more than max_keys keys, so a flood of
    made-up usernames or addresses cannot grow memory without bound.
    """

    max_tries: int = 10
    window_seconds: float = 60.0
    ip_max_tries: int = 30
    max_keys: int = 10_000

    def __post_init__(self) -> None:
        self._hits: dict[str, deque[float]] = {}
        self._last_sweep = time.monotonic()
        self._lock = threading.Lock()

    def check_login(self, client: str, username: str) -> None:
        """Count one login try; raise too_many_attempts when the ip or the ip+username budget is used up."""
        now = time.monotonic()
        with self._lock:
            self._sweep(now)
            self._record(f"ip|{client}", self.ip_max_tries, now)
            self._record(f"pair|{client}|{username.lower()}", self.max_tries, now)

    def _record(self, key: str, limit: int, now: float) -> None:
        hits = self._hits.setdefault(key, deque())
        while hits and now - hits[0] > self.window_seconds:
            hits.popleft()
        if len(hits) >= limit:
            raise errors.too_many_attempts()
        hits.append(now)

    def _sweep(self, now: float) -> None:
        """Drop keys with no hit inside the window; if still over max_keys, drop the longest-tracked ones."""
        room = self.max_keys - 2  # one login adds at most two keys
        if now - self._last_sweep >= self.window_seconds or len(self._hits) > room:
            self._last_sweep = now
            idle = [k for k, hits in self._hits.items() if not hits or now - hits[-1] > self.window_seconds]
            for key in idle:
                del self._hits[key]
        while len(self._hits) > room:
            del self._hits[next(iter(self._hits))]

    def tracked_keys(self) -> int:
        with self._lock:
            return len(self._hits)

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()
