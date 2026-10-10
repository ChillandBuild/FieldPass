from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from app import clock
from app.config import Settings, parse_database_url
from app.deps import get_face_engine
from app.main import create_app
from app.seed import seed_users
from tests.fakes import FakeFaceEngine
from tests.helpers import Api

START = datetime(2026, 10, 10, 9, 0, 0, tzinfo=timezone.utc)
SEED_PASSWORD = "test-seed-password"
FACULTY_PASSWORD = "test-faculty-password"


class Clock:
    """Mutable test clock."""

    def __init__(self) -> None:
        self.current = START

    def advance(self, **kwargs: float) -> None:
        self.current += timedelta(**kwargs)

    def set(self, value: datetime) -> None:
        self.current = value

    def __call__(self) -> datetime:
        return self.current


@pytest.fixture()
def test_clock():
    ticking = Clock()
    clock.set_clock(ticking)
    yield ticking
    clock.set_clock(None)


def postgres_test_url() -> str:
    """TEST_DATABASE_URL (a throwaway Postgres) switches the whole suite from SQLite to Postgres."""
    return os.environ.get("TEST_DATABASE_URL", "").strip()


def drop_schema(url: str, schema: str) -> None:
    engine = create_engine(parse_database_url(url))
    try:
        with engine.begin() as conn:
            conn.execute(text(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE'))
    finally:
        engine.dispose()


@pytest.fixture()
def settings(tmp_path: Path):
    """Settings on SQLite in tmp_path, or on a fresh unique Postgres schema (dropped afterwards)."""
    url = postgres_test_url()
    schema = f"t_{uuid.uuid4().hex[:12]}"
    url_for_settings = parse_database_url(url).render_as_string(hide_password=False) if url else ""
    yield build_settings(tmp_path, database_url=url_for_settings, database_schema=schema)
    if url:
        drop_schema(url, schema)


def build_settings(tmp_path: Path, **overrides) -> Settings:
    return Settings(
        jwt_secret="test-jwt-secret-test-jwt-secret-1234",
        photo_encryption_key=Fernet.generate_key().decode(),
        seed_password=SEED_PASSWORD,
        faculty_seed_password=FACULTY_PASSWORD,
        database_path=tmp_path / "test.db",
        photo_dir=tmp_path / "photos",
        model_dir=tmp_path / "models",
        cors_origins=("http://localhost:5173",),
        face_match_threshold=0.363,
        face_uncertain_margin=0.1,
        attempt_ttl_seconds=90,
        location_max_age_seconds=60,
        token_ttl_minutes=480,
        **overrides,
    )


@pytest.fixture()
def fake_engine() -> FakeFaceEngine:
    return FakeFaceEngine()


@pytest.fixture()
def app(settings, fake_engine, test_clock):
    application = create_app(settings, require_models=False)
    application.dependency_overrides[get_face_engine] = lambda: fake_engine
    with application.state.session_factory() as session:
        seed_users(session, SEED_PASSWORD, FACULTY_PASSWORD)
    yield application
    application.state.engine.dispose()  # hand pooled Postgres connections back before the schema is dropped


@pytest.fixture()
def client(app):
    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


@pytest.fixture()
def api(client, test_clock) -> Api:
    return Api(client, test_clock)
