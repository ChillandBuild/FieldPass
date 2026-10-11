from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import clock, devices, errors
from app.config import Settings
from app.db import get_session
from app.deps import get_rate_limiter
from app.models import User
from app.schemas import HealthOut, LoginIn, LoginOut, UserOut
from app.security import (
    LoginRateLimiter, current_user, create_token, get_settings_dep, verify_password, hash_password,
)
from app.views import user_out

router = APIRouter()

# Verified against when the username is unknown, so response time does not reveal valid usernames.
_DUMMY_HASH = hash_password("not-a-real-password")


@router.get("/health", response_model=HealthOut, tags=["health"])
def health() -> HealthOut:
    return HealthOut(server_time=clock.to_iso(clock.now()))


@router.post("/auth/login", response_model=LoginOut, tags=["auth"])
def login(
    body: LoginIn,
    request: Request,
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    limiter: LoginRateLimiter = Depends(get_rate_limiter),
) -> LoginOut:
    client = request.client.host if request.client else "unknown"
    limiter.check_login(client, body.username)
    user = session.scalar(select(User).where(User.username == body.username))
    ok = verify_password(user.password_hash if user else _DUMMY_HASH, body.password)
    if user is None or not ok:
        raise errors.invalid_credentials()
    if user.role == "student":
        devices.bind_or_check(session, user.id, body.device_public_key, clock.now())
    token, expires = create_token(settings, user)
    return LoginOut(access_token=token, expires_at=clock.to_iso(expires), user=user_out(user))


@router.get("/auth/me", response_model=UserOut, tags=["auth"])
def me(user: User = Depends(current_user)) -> UserOut:
    return user_out(user)
