"""Throwaway settings for scripts that build the app without a real .env."""
from __future__ import annotations

from pathlib import Path

from cryptography.fernet import Fernet

from app.config import Settings

SEED_PASSWORD = "fixture-password"
FACULTY_PASSWORD = "fixture-faculty-password"


def throwaway_settings(directory: Path) -> Settings:
    return Settings(
        jwt_secret="fixture-jwt-secret-fixture-jwt-secret",
        photo_encryption_key=Fernet.generate_key().decode(),
        seed_password=SEED_PASSWORD,
        faculty_seed_password=FACULTY_PASSWORD,
        database_path=directory / "fixtures.db",
        photo_dir=directory / "photos",
        model_dir=directory / "models",
        cors_origins=(),
        face_match_threshold=0.363,
        face_uncertain_margin=0.1,
        attempt_ttl_seconds=90,
        location_max_age_seconds=60,
        token_ttl_minutes=480,
    )
