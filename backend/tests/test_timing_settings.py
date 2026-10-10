"""CHECKIN_LEAD_MINUTES and CHECKOUT_GRACE_MINUTES: configuration, pure rules, and the running API."""
from __future__ import annotations

import dataclasses
from datetime import timedelta

import pytest
from cryptography.fernet import Fernet

from app import rules
from app.config import ConfigError, load_settings
from tests.test_units import T0, activity, event

GOOD = {
    "JWT_SECRET": "j" * 32,
    "PHOTO_ENCRYPTION_KEY": Fernet.generate_key().decode(),
    "SEED_PASSWORD": "student-pass",
    "FACULTY_SEED_PASSWORD": "faculty-pass",
}


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


def load(tmp_path, **overrides):
    return load_settings(env_file=tmp_path / "none.env", environ={**GOOD, **overrides})


# ---- configuration ---------------------------------------------------------

def test_defaults_are_15_and_30_minutes(tmp_path):
    settings = load(tmp_path)
    assert (settings.checkin_lead_minutes, settings.checkout_grace_minutes) == (15.0, 30.0)
    assert rules.timing_from(settings) == rules.DEFAULT_TIMING


def test_values_can_be_overridden_including_fractions_and_zero(tmp_path):
    settings = load(tmp_path, CHECKIN_LEAD_MINUTES="0", CHECKOUT_GRACE_MINUTES="0.5")
    assert (settings.checkin_lead_minutes, settings.checkout_grace_minutes) == (0.0, 0.5)
    timing = rules.timing_from(settings)
    assert timing.checkin_lead == timedelta(0) and timing.checkout_grace == timedelta(seconds=30)


def test_empty_value_means_default(tmp_path):
    assert load(tmp_path, CHECKIN_LEAD_MINUTES="", CHECKOUT_GRACE_MINUTES="").checkout_grace_minutes == 30.0


@pytest.mark.parametrize("name", ["CHECKIN_LEAD_MINUTES", "CHECKOUT_GRACE_MINUTES"])
@pytest.mark.parametrize("bad", ["-1", "-0.01", "nan", "inf", "soon", "99999999", "1e400"])
def test_invalid_values_are_config_errors(tmp_path, name, bad):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, **{name: bad})
    assert name in str(info.value)


# ---- pure rules ------------------------------------------------------------

def test_custom_checkin_lead_moves_the_opening_edge():
    act = activity()
    timing = rules.Timing(checkin_lead=timedelta(minutes=2), checkout_grace=timedelta(minutes=30))
    assert rules.checkin_window(act, timing)[0] == T0 - timedelta(minutes=2)
    assert rules.check_action_allowed("check_in", act, [], [], T0 - timedelta(minutes=2), timing) is None
    with pytest.raises(Exception) as info:
        rules.check_action_allowed("check_in", act, [], [], T0 - timedelta(minutes=2, seconds=1), timing)
    assert info.value.code == "outside_activity_window"


def test_zero_grace_closes_checkout_and_flags_missing_at_the_end():
    act = activity()
    timing = rules.Timing(checkout_grace=timedelta(0))
    checked_in = [event("check_in")]
    assert rules.checkout_deadline(act, timing) == act.ends_at
    assert rules.check_action_allowed("check_out", act, checked_in, [], act.ends_at, timing) is None
    assert not rules.missing_checkout(act, checked_in, act.ends_at, timing)
    assert rules.missing_checkout(act, checked_in, act.ends_at + timedelta(seconds=1), timing)
    assert rules.next_actions(act, checked_in, [], act.ends_at + timedelta(seconds=1), timing) == []


def test_default_timing_is_unchanged_without_an_argument():
    act = activity()
    assert rules.checkout_deadline(act) == act.ends_at + timedelta(minutes=30)
    assert rules.checkin_window(act)[0] == T0 - timedelta(minutes=15)


# ---- through the API -------------------------------------------------------

@pytest.fixture()
def settings(settings):
    return dataclasses.replace(settings, checkin_lead_minutes=1.0, checkout_grace_minutes=0.0)


def test_api_uses_configured_checkin_lead(api, test_clock):
    api.enroll("student1", "asha")
    act = api.make_activity()                                  # starts 09:30
    test_clock.set(test_clock.current.replace(hour=9, minute=28, second=59))
    assert err(api.attempt("student1", act["id"], "check_in")) == (409, "outside_activity_window")
    test_clock.advance(seconds=1)                              # 09:29:00 = starts_at - 1 min
    assert api.attempt("student1", act["id"], "check_in").status_code == 201


def test_api_flags_missing_checkout_right_after_the_end_with_zero_grace(api, test_clock):
    api.enroll("student1", "asha")
    act = api.make_activity()
    test_clock.advance(minutes=31)
    assert api.do("student1", act["id"], "check_in", "asha").status_code == 200
    test_clock.set(test_clock.current.replace(hour=13, minute=30, second=0))   # exactly ends_at
    record = api.get("student1", "/attendance/me").json()["items"][0]
    assert record["missing_checkout"] is False and record["next_actions"] == ["check_out"]
    test_clock.advance(seconds=1)
    record = api.get("student1", "/attendance/me").json()["items"][0]
    assert record["missing_checkout"] is True and record["complete"] is False and record["next_actions"] == []
    assert err(api.attempt("student1", act["id"], "check_out")) == (409, "outside_activity_window")
    row = api.get("faculty1", f"/activities/{act['id']}/register").json()["rows"][0]
    assert row["missing_checkout"] is True
