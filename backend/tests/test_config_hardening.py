"""Findings 2, 6 and 11 at the configuration layer."""
from __future__ import annotations

import pytest
from cryptography.fernet import Fernet

from app.config import ConfigError, load_settings

GOOD = {
    "JWT_SECRET": "j" * 32,
    "PHOTO_ENCRYPTION_KEY": Fernet.generate_key().decode(),
    "SEED_PASSWORD": "student-pass",
    "FACULTY_SEED_PASSWORD": "faculty-pass",
}


def load(tmp_path, **overrides):
    environ = {**GOOD, **overrides}
    return load_settings(env_file=tmp_path / "none.env", environ={k: v for k, v in environ.items() if v is not None})


def test_faculty_seed_password_is_required(tmp_path):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, FACULTY_SEED_PASSWORD=None)
    assert "FACULTY_SEED_PASSWORD" in str(info.value)


def test_faculty_password_is_loaded_separately(tmp_path):
    settings = load(tmp_path)
    assert settings.faculty_seed_password == "faculty-pass" and settings.seed_password == "student-pass"


def test_short_jwt_secret_is_rejected(tmp_path):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, JWT_SECRET="j" * 31)
    assert "JWT_SECRET" in str(info.value) and "32" in str(info.value)
    assert load(tmp_path, JWT_SECRET="j" * 32).jwt_secret == "j" * 32


def test_invalid_photo_key_is_a_config_error_not_a_raw_exception(tmp_path):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, PHOTO_ENCRYPTION_KEY="not-a-fernet-key")
    assert "PHOTO_ENCRYPTION_KEY" in str(info.value)
    assert "not-a-fernet-key" not in str(info.value)


def test_docs_are_off_unless_enabled(tmp_path):
    assert load(tmp_path).enable_docs is False
    assert load(tmp_path, ENABLE_DOCS="true").enable_docs is True
    assert load(tmp_path, ENABLE_DOCS="0").enable_docs is False
