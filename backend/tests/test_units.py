from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from cryptography.fernet import Fernet

from app import clock, errors, rules
from app.config import ConfigError, load_settings
from app.face import MATCH, NO_MATCH, UNCERTAIN, verdict_from_score
from app.geo import haversine_m, location_verdict
from app.models import Activity, AttendanceEvent, CheckpointWindow, Decision
from app.photo_store import PhotoStore

T0 = datetime(2026, 10, 10, 9, 30, tzinfo=timezone.utc)
KEY = Fernet.generate_key().decode()
SECRET = "s" * 32


@pytest.mark.parametrize("d,a,expected", [
    (0, 0, "inside"),
    (90, 10, "inside"),            # d + a == r
    (90.01, 10, "uncertain"),      # d + a just over r
    (100, 0, "inside"),            # exactly on the boundary, perfect accuracy
    (100.01, 0, "outside"),        # d - a just over r
    (110, 10, "uncertain"),        # d - a == r is NOT outside
    (110.01, 10, "outside"),       # d - a just over r
    (100, 50, "uncertain"),
    (500, 15, "outside"),
])
def test_location_verdict_boundaries(d, a, expected):
    assert location_verdict(d, a, 100) == expected


def test_haversine_known_distances():
    assert haversine_m(12.9716, 77.5946, 12.9716, 77.5946) == 0
    assert haversine_m(0, 0, 1, 0) == pytest.approx(111_195, rel=1e-3)
    assert haversine_m(12.9716, 77.5946, 12.9716 + 0.0009, 77.5946) == pytest.approx(100, rel=0.01)


@pytest.mark.parametrize("score,expected", [
    (0.9, MATCH), (0.363, MATCH), (0.3629, UNCERTAIN), (0.263, UNCERTAIN), (0.2629, NO_MATCH), (-0.2, NO_MATCH),
])
def test_face_verdict_thresholds(score, expected):
    assert verdict_from_score(score, 0.363, 0.1) == expected


def test_clock_helpers_roundtrip():
    assert clock.to_iso(T0) == "2026-10-10T09:30:00Z"
    assert clock.to_iso(None) is None
    assert clock.parse_iso("2026-10-10T15:00:00+05:30") == T0
    assert clock.parse_iso("2026-10-10T09:30:00") == T0
    assert clock.to_iso(datetime(2026, 10, 10, 9, 30)) == "2026-10-10T09:30:00Z"


def test_set_clock_controls_now():
    clock.set_clock(lambda: T0)
    try:
        assert clock.now() == T0
    finally:
        clock.set_clock(None)
    assert clock.now() != T0


def activity(starts=T0, hours=4) -> Activity:
    return Activity(id="act_1", starts_at=starts, ends_at=starts + timedelta(hours=hours))


def event(action, cp=None, status="recorded", at=T0, reasons="") -> AttendanceEvent:
    return AttendanceEvent(id=f"e_{action}_{cp}", action=action, checkpoint_id=cp, status=status,
                           received_at=at, review_reasons=reasons)


def window(wid="cp_1", opens=T0, minutes=10) -> CheckpointWindow:
    return CheckpointWindow(id=wid, opens_at=opens, closes_at=opens + timedelta(minutes=minutes))


def code_of(fn, *args):
    with pytest.raises(errors.ApiError) as info:
        fn(*args)
    return info.value.code


def test_checkin_window_edges():
    act = activity()
    ok = lambda now: rules.check_action_allowed("check_in", act, [], [], now)  # noqa: E731
    assert ok(T0 - timedelta(minutes=15)) is None
    assert code_of(rules.check_action_allowed, "check_in", act, [], [], T0 - timedelta(minutes=15, seconds=1)) == "outside_activity_window"
    assert ok(T0 + timedelta(minutes=30)) is None
    assert code_of(rules.check_action_allowed, "check_in", act, [], [], T0 + timedelta(minutes=30, seconds=1)) == "outside_activity_window"


def test_checkout_deadline_edges():
    act = activity()
    events = [event("check_in")]
    deadline = act.ends_at + timedelta(minutes=30)
    assert rules.check_action_allowed("check_out", act, events, [], deadline) is None
    assert code_of(rules.check_action_allowed, "check_out", act, events, [], deadline + timedelta(seconds=1)) == "outside_activity_window"


def test_order_rules():
    act, now = activity(), T0 + timedelta(minutes=5)
    assert code_of(rules.check_action_allowed, "check_out", act, [], [], now) == "invalid_action_order"
    assert code_of(rules.check_action_allowed, "checkpoint", act, [], [window()], now) == "invalid_action_order"
    assert code_of(rules.check_action_allowed, "check_in", act, [event("check_in")], [], now) == "invalid_action_order"
    done = [event("check_in"), event("check_out")]
    for action in ("check_in", "checkpoint", "check_out"):
        assert code_of(rules.check_action_allowed, action, act, done, [window()], now) == "invalid_action_order"


def test_checkpoint_window_rules():
    act, now = activity(), T0 + timedelta(minutes=5)
    checked_in = [event("check_in")]
    assert code_of(rules.check_action_allowed, "checkpoint", act, checked_in, [], now) == "checkpoint_not_open"
    late = T0 + timedelta(minutes=10)  # closes_at is exclusive
    assert code_of(rules.check_action_allowed, "checkpoint", act, checked_in, [window()], late) == "checkpoint_not_open"
    assert rules.check_action_allowed("checkpoint", act, checked_in, [window()], now) == "cp_1"
    answered = checked_in + [event("checkpoint", cp="cp_1")]
    assert code_of(rules.check_action_allowed, "checkpoint", act, answered, [window()], now) == "checkpoint_not_open"
    second = window("cp_2", opens=T0 + timedelta(minutes=20))
    assert rules.check_action_allowed("checkpoint", act, answered, [window(), second], T0 + timedelta(minutes=21)) == "cp_2"


def test_next_actions_by_stage():
    act, now = activity(), T0 + timedelta(minutes=5)
    assert rules.next_actions(act, [], [], now) == ["check_in"]
    assert rules.next_actions(act, [event("check_in")], [], now) == ["check_out"]
    assert rules.next_actions(act, [event("check_in")], [window()], now) == ["checkpoint", "check_out"]
    assert rules.next_actions(act, [event("check_in"), event("check_out")], [], now) == []


def test_missing_checkout_and_complete():
    act = activity()
    after = act.ends_at + timedelta(minutes=30, seconds=1)
    assert rules.missing_checkout(act, [event("check_in")], after)
    assert not rules.missing_checkout(act, [event("check_in")], after - timedelta(seconds=2))
    assert not rules.missing_checkout(act, [], after)
    assert not rules.missing_checkout(act, [event("check_in"), event("check_out")], after)
    assert rules.is_complete([event("check_in"), event("check_out")])
    assert not rules.is_complete([event("check_in")])


def decision(kind, at, did="d1") -> Decision:
    return Decision(id=did, decision=kind, decided_at=at)


def test_status_derivation():
    ev_ok, ev_review = event("check_in"), event("checkpoint", "cp", status="needs_review")
    assert rules.derive_status([], []) is None
    assert rules.derive_status([], [ev_ok]) == "recorded"
    assert rules.derive_status([], [ev_ok, ev_review]) == "needs_review"
    dec = decision("confirmed", T0 + timedelta(minutes=1))
    assert rules.derive_status([dec], [ev_review]) == "confirmed"
    assert rules.derive_status([dec], []) == "confirmed"  # decision with no events still counts
    later = event("check_out", at=T0 + timedelta(minutes=2))
    assert rules.derive_status([dec], [ev_review, later]) == "needs_review"
    assert rules.current_decision([dec], [ev_review, later]) is None


def test_newest_decision_wins_and_union_reasons():
    first = decision("confirmed", T0, "d1")
    second = decision("not_confirmed", T0 + timedelta(minutes=1), "d2")
    assert rules.current_decision([first, second], []).id == "d2"
    events = [event("check_in", reasons="location_outside,face_mismatch"), event("check_out", reasons="face_mismatch")]
    assert rules.union_reasons(events) == ["location_outside", "face_mismatch"]


def test_photo_store_roundtrip_and_path_safety(tmp_path):
    store = PhotoStore(tmp_path / "p", Fernet.generate_key().decode())
    name = store.save(b"jpeg-bytes")
    assert store.load(name) == b"jpeg-bytes"
    with pytest.raises(ValueError):
        store.load("../escape")
    store.delete("../escape")  # ignored silently
    store.delete(name)
    assert not (tmp_path / "p" / name).exists()
    other = PhotoStore(tmp_path / "p", Fernet.generate_key().decode())
    with pytest.raises(ValueError):
        other.decrypt(store.encrypt(b"x"))


def test_config_fails_fast_when_secrets_missing(tmp_path):
    with pytest.raises(ConfigError) as info:
        load_settings(env_file=tmp_path / "none.env", environ={"JWT_SECRET": "x" * 32})
    assert "PHOTO_ENCRYPTION_KEY" in str(info.value) and "SEED_PASSWORD" in str(info.value)


def test_config_reads_env_file_and_defaults(tmp_path):
    env = tmp_path / ".env"
    env.write_text(
        f"# c\nJWT_SECRET={SECRET}\nPHOTO_ENCRYPTION_KEY={KEY}\nSEED_PASSWORD='pw'\n"
        "FACULTY_SEED_PASSWORD=fpw\nCORS_ORIGINS=http://a, http://b\n"
    )
    settings = load_settings(env_file=env, environ={})
    assert settings.seed_password == "pw" and settings.faculty_seed_password == "fpw"
    assert settings.cors_origins == ("http://a", "http://b")
    assert settings.face_match_threshold == 0.363
    assert settings.attempt_ttl_seconds == 90 and settings.location_max_age_seconds == 60


def test_config_rejects_non_numeric_threshold(tmp_path):
    environ = {
        "JWT_SECRET": SECRET, "PHOTO_ENCRYPTION_KEY": KEY, "SEED_PASSWORD": "c",
        "FACULTY_SEED_PASSWORD": "d", "FACE_MATCH_THRESHOLD": "high",
    }
    with pytest.raises(ConfigError):
        load_settings(env_file=tmp_path / "none.env", environ=environ)
