"""Shared FastAPI dependencies. Tests override get_face_engine via dependency_overrides."""
from __future__ import annotations

from fastapi import Request

from app.face import FaceEngine
from app.photo_store import PhotoStore
from app.security import LoginRateLimiter

MAX_PHOTO_BYTES = 5 * 1024 * 1024


def get_face_engine(request: Request) -> FaceEngine:
    engine = request.app.state.face_engine
    if engine is None:
        raise RuntimeError("Face engine is not configured. Run scripts/download_models.py.")
    return engine


def get_photo_store(request: Request) -> PhotoStore:
    return request.app.state.photo_store


def get_rate_limiter(request: Request) -> LoginRateLimiter:
    return request.app.state.rate_limiter
