"""Rotating presence code: pure generation/validation and the PRESENCE_CODE_STEP_SECONDS setting."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from cryptography.fernet import Fernet

from app import presence
from app.config import ConfigError, load_settings

SECRET = "test-jwt-secret-test-jwt-secret-1234"
NOW = datetime(2026, 10, 10, 9, 31, 0, tzinfo=timezone.utc)
STEP = 10

GOOD = {
    "JWT_SECRET": "j" * 32,
    "PHOTO_ENCRYPTION_KEY": Fernet.generate_key().decode(),
    "SEED_PASSWORD": "student-pass",
    "FACULTY_SEED_PASSWORD": "faculty-pass",
}


def code_at(when: datetime, activity_id: str = "act_1") -> str:
    return presence.make_code(SECRET, activity_id, when, STEP)


def valid(code: str, when: datetime, activity_id: str = "act_1") -> bool:
    return presence.is_valid(SECRET, activity_id, code, when, STEP)


# ---- format ----------------------------------------------------------------

def test_code_has_the_fp1_step_mac_shape():
    prefix, step, mac = code_at(NOW).split(".")
    assert prefix == "fp1"
    assert step == str(int(NOW.timestamp()) // STEP)
    assert len(mac) == 22 and "=" not in mac  # 16 bytes, base64url, no padding


def test_code_is_stable_inside_a_step_and_changes_at_the_boundary():
    assert code_at(NOW) == code_at(NOW + timedelta(seconds=9))
    assert code_at(NOW) != code_at(NOW + timedelta(seconds=10))


def test_code_depends_on_activity_and_secret():
    assert code_at(NOW, "act_1") != code_at(NOW, "act_2")
    assert presence.make_code("another-secret-another-secret-12345", "act_1", NOW, STEP) != code_at(NOW)


def test_step_ends_at_the_next_boundary():
    assert presence.step_ends_at(NOW, STEP) == NOW + timedelta(seconds=10)
    assert presence.step_ends_at(NOW + timedelta(seconds=7), STEP) == NOW + timedelta(seconds=10)


# ---- validation ------------------------------------------------------------

def test_current_step_is_valid():
    assert valid(code_at(NOW), NOW + timedelta(seconds=3))


def test_previous_step_is_valid():
    assert valid(code_at(NOW), NOW + timedelta(seconds=12))


def test_two_steps_old_is_not_valid():
    assert not valid(code_at(NOW), NOW + timedelta(seconds=20))


def test_future_step_is_not_valid():
    assert not valid(code_at(NOW + timedelta(seconds=10)), NOW)


def test_code_for_another_activity_is_not_valid():
    assert not valid(code_at(NOW, "act_2"), NOW)


def test_tampered_mac_is_not_valid():
    prefix, step, mac = code_at(NOW).split(".")
    flipped = ("A" if mac[0] != "A" else "B") + mac[1:]
    assert not valid(f"{prefix}.{step}.{flipped}", NOW)


def test_step_edited_to_a_valid_step_still_needs_its_own_mac():
    prefix, step, mac = code_at(NOW).split(".")
    assert not valid(f"{prefix}.{int(step) + 1}.{mac}", NOW + timedelta(seconds=10))


@pytest.mark.parametrize("garbage", [
    "", "x", "fp1", "fp1.1", "fp1..", "fp1.abc.def", "fp2.1.abc", "fp1.-3.abc", "fp1.1.2.3",
    "fp1.².abc", "fp1.1.éé", "a" * 200, "fp1." + "9" * 100 + ".abc", "\x00\x00",
])
def test_garbage_is_not_valid_and_never_raises(garbage):
    assert valid(garbage, NOW) is False


# ---- setting ---------------------------------------------------------------

def load(tmp_path, **overrides):
    return load_settings(env_file=tmp_path / "none.env", environ={**GOOD, **overrides})


def test_step_defaults_to_10_seconds(tmp_path):
    assert load(tmp_path).presence_code_step_seconds == 10


@pytest.mark.parametrize("value", ["5", "10", "60"])
def test_step_accepts_5_to_60(tmp_path, value):
    assert load(tmp_path, PRESENCE_CODE_STEP_SECONDS=value).presence_code_step_seconds == int(value)


def test_empty_step_means_default(tmp_path):
    assert load(tmp_path, PRESENCE_CODE_STEP_SECONDS="").presence_code_step_seconds == 10


@pytest.mark.parametrize("bad", ["4", "61", "0", "-10", "ten", "7.5"])
def test_step_outside_range_or_not_a_number_is_a_config_error(tmp_path, bad):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, PRESENCE_CODE_STEP_SECONDS=bad)
    assert "PRESENCE_CODE_STEP_SECONDS" in str(info.value)


def test_non_ascii_mac_is_just_invalid():
    assert presence.is_valid(SECRET, "act_1", "fp1.1.\udc80", NOW, STEP) is False
    assert presence.is_valid(SECRET, "act_1", "fp1.1.é", NOW, STEP) is False


# ---- PRESENCE_CODE_MAX_ACCURACY_M ------------------------------------------

@pytest.mark.parametrize("value", ["0", "150", "75.5", "5000"])
def test_max_accuracy_accepts_0_to_5000(tmp_path, value):
    assert load(tmp_path, PRESENCE_CODE_MAX_ACCURACY_M=value).presence_code_max_accuracy_m == float(value)


def test_empty_max_accuracy_means_default(tmp_path):
    assert load(tmp_path, PRESENCE_CODE_MAX_ACCURACY_M="").presence_code_max_accuracy_m == 150


@pytest.mark.parametrize("bad", ["-1", "5001", "nan", "inf", "far"])
def test_max_accuracy_outside_range_or_not_a_number_is_a_config_error(tmp_path, bad):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, PRESENCE_CODE_MAX_ACCURACY_M=bad)
    assert "PRESENCE_CODE_MAX_ACCURACY_M" in str(info.value)
