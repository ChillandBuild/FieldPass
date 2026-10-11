"""Presence code endpoints, the submit rule change, and presence_verified in event output."""
from __future__ import annotations

import pytest
from sqlalchemy import select

from app import clock, presence
from app.models import PresenceScan
from tests.fakes import face
from tests.helpers import offset_lat

STEP = 10
LONG_CODE = "x" * 201


@pytest.fixture()
def ready(api, test_clock):
    """Student1 and student2 approved, activity 09:30-13:30, clock at 09:31:00."""
    api.enroll("student1", "asha")
    api.enroll("student2", "rohan")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


def code_for(api, activity_id: str) -> str:
    resp = api.get("faculty1", f"/activities/{activity_id}/presence-code")
    assert resp.status_code == 200, resp.text
    return resp.json()["code"]


def new_attempt(api, activity_id: str, user: str = "student1") -> str:
    resp = api.attempt(user, activity_id, "check_in")
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def scan(api, attempt_id: str, code: str, user: str = "student1"):
    return api.post(user, f"/attempts/{attempt_id}/presence", json={"code": code})


def scan_rows(app) -> list[str]:
    with app.state.session_factory() as session:
        return list(session.scalars(select(PresenceScan.attempt_id)))


# ---- faculty endpoint ------------------------------------------------------

def test_faculty_gets_code_with_expiry_server_time_and_step(api, ready, test_clock):
    test_clock.advance(seconds=7)  # 09:31:07
    body = api.get("faculty1", f"/activities/{ready['id']}/presence-code").json()
    assert set(body) == {"code", "expires_at", "server_time", "step_seconds"}
    assert body["server_time"] == "2026-10-10T09:31:07Z"
    assert body["expires_at"] == "2026-10-10T09:31:10Z"
    assert body["step_seconds"] == STEP
    assert body["code"].startswith("fp1.")


def test_code_changes_every_step_and_matches_the_pure_module(api, ready, test_clock):
    first = code_for(api, ready["id"])
    assert code_for(api, ready["id"]) == first
    test_clock.advance(seconds=10)
    second = code_for(api, ready["id"])
    assert second != first
    assert presence.is_valid("test-jwt-secret-test-jwt-secret-1234", ready["id"], second, test_clock.current, STEP)


def test_student_cannot_read_the_code(api, ready):
    assert err(api.get("student1", f"/activities/{ready['id']}/presence-code")) == (403, "forbidden")


def test_unknown_activity_is_not_found(api, ready):
    assert err(api.get("faculty1", "/activities/act_nope/presence-code")) == (404, "not_found")


def test_code_needs_authentication(client, ready):
    assert client.get(f"/api/v1/activities/{ready['id']}/presence-code").status_code == 401


def test_code_window_opens_15_minutes_before_start(api, ready, test_clock):
    path = f"/activities/{ready['id']}/presence-code"
    test_clock.set(test_clock.current.replace(hour=9, minute=14, second=59))
    assert err(api.get("faculty1", path)) == (409, "outside_activity_window")
    test_clock.advance(seconds=1)  # 09:15:00
    assert api.get("faculty1", path).status_code == 200


def test_code_window_closes_30_minutes_after_end(api, ready, test_clock):
    path = f"/activities/{ready['id']}/presence-code"
    test_clock.set(test_clock.current.replace(hour=14, minute=0, second=0))
    assert api.get("faculty1", path).status_code == 200
    test_clock.advance(seconds=1)
    assert err(api.get("faculty1", path)) == (409, "outside_activity_window")


# ---- student endpoint: access ----------------------------------------------

def test_scan_by_another_student_is_forbidden(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    assert err(scan(api, attempt_id, code_for(api, ready["id"]), user="student2")) == (403, "forbidden")


def test_scan_of_unknown_attempt_is_not_found(api, ready):
    assert err(scan(api, "att_nope", "whatever")) == (404, "not_found")


def test_faculty_cannot_scan(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    assert err(scan(api, attempt_id, "x", user="faculty1")) == (403, "forbidden")


@pytest.mark.parametrize("code", ["", LONG_CODE])
def test_code_length_is_validated(api, ready, code):
    attempt_id = new_attempt(api, ready["id"])
    assert err(scan(api, attempt_id, code)) == (422, "validation_error")


def test_expired_attempt_is_refused(api, ready, test_clock):
    attempt_id = new_attempt(api, ready["id"])
    code = code_for(api, ready["id"])
    test_clock.advance(seconds=91)
    assert err(scan(api, attempt_id, code)) == (410, "attempt_expired")


# ---- student endpoint: verification ----------------------------------------

def test_valid_code_is_verified_and_stored(app, api, ready):
    attempt_id = new_attempt(api, ready["id"])
    resp = scan(api, attempt_id, code_for(api, ready["id"]))
    assert resp.status_code == 200 and resp.json() == {"verified": True}
    assert scan_rows(app) == [attempt_id]


def test_code_from_the_previous_step_is_verified(api, ready, test_clock):
    attempt_id = new_attempt(api, ready["id"])
    code = code_for(api, ready["id"])
    test_clock.advance(seconds=10)
    assert scan(api, attempt_id, code).json() == {"verified": True}


def test_stale_code_is_false_not_an_error(app, api, ready, test_clock):
    attempt_id = new_attempt(api, ready["id"])
    code = code_for(api, ready["id"])
    test_clock.advance(seconds=20)
    resp = scan(api, attempt_id, code)
    assert resp.status_code == 200 and resp.json() == {"verified": False}
    assert scan_rows(app) == []


def test_garbage_and_other_activity_codes_are_false(app, api, ready):
    other = api.make_activity(students=("student1",), starts_in_min=5)
    attempt_id = new_attempt(api, ready["id"])
    for code in ("garbage", "fp1.1.2", code_for(api, other["id"])):
        assert scan(api, attempt_id, code).json() == {"verified": False}
    assert scan_rows(app) == []


def test_scan_is_idempotent_whatever_code_is_sent_later(app, api, ready):
    attempt_id = new_attempt(api, ready["id"])
    code = code_for(api, ready["id"])
    assert scan(api, attempt_id, code).json() == {"verified": True}
    assert scan(api, attempt_id, code).json() == {"verified": True}
    assert scan(api, attempt_id, "garbage").json() == {"verified": True}
    assert scan_rows(app) == [attempt_id]


def test_concurrent_insert_clash_still_answers_true(app, api, ready, monkeypatch):
    """A second request that passes the 'row exists?' check just before the first commits."""
    attempt_id = new_attempt(api, ready["id"])
    code = code_for(api, ready["id"])
    assert scan(api, attempt_id, code).json() == {"verified": True}
    from app.routers import attempts

    monkeypatch.setattr(attempts, "has_presence_scan", lambda session, attempt: False)
    assert scan(api, attempt_id, code).json() == {"verified": True}
    assert scan_rows(app) == [attempt_id]


def test_scan_after_event_exists_changes_nothing(app, api, ready):
    attempt_id = new_attempt(api, ready["id"])
    assert api.submit("student1", attempt_id, face("asha")).status_code == 200
    resp = scan(api, attempt_id, code_for(api, ready["id"]))
    assert resp.status_code == 200 and resp.json() == {"verified": False}
    assert scan_rows(app) == []


def test_scan_after_event_exists_reports_existing_scan(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    assert scan(api, attempt_id, code_for(api, ready["id"])).json() == {"verified": True}
    assert api.submit("student1", attempt_id, face("asha")).status_code == 200
    assert scan(api, attempt_id, "garbage").json() == {"verified": True}


# ---- submit rule -----------------------------------------------------------

def submit_at(api, attempt_id: str, metres: float, accuracy: float = 10):
    return api.submit("student1", attempt_id, face("asha"), lat=offset_lat(metres), accuracy=accuracy)


def test_scan_clears_location_uncertain(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = submit_at(api, attempt_id, 95).json()
    assert body["status"] == "recorded" and body["review_reasons"] == []
    assert body["location"]["verdict"] == "uncertain"  # the stored verdict stays truthful
    assert body["presence_verified"] is True


def test_scan_does_not_clear_location_outside(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = submit_at(api, attempt_id, 200).json()
    assert body["status"] == "needs_review" and body["review_reasons"] == ["location_outside"]
    assert body["presence_verified"] is True


def test_scan_clears_uncertain_up_to_the_accuracy_cap(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = submit_at(api, attempt_id, 0, accuracy=150).json()
    assert body["location"]["verdict"] == "uncertain" and body["review_reasons"] == []


def test_scan_does_not_clear_uncertain_when_reported_accuracy_is_above_the_cap(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = submit_at(api, attempt_id, 0, accuracy=151).json()
    assert body["status"] == "needs_review" and body["review_reasons"] == ["location_uncertain"]
    assert body["presence_verified"] is True  # the scan happened; it just cannot vouch for a reading this vague


def test_far_away_phone_cannot_buy_a_clean_record_with_a_huge_accuracy(api, ready):
    """5 km off, claiming 6 km accuracy, turns "outside" into "uncertain". A relayed code must not clear that."""
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = submit_at(api, attempt_id, 5000, accuracy=6000).json()
    assert body["location"]["verdict"] == "uncertain"
    assert body["status"] == "needs_review" and body["review_reasons"] == ["location_uncertain"]


def test_scan_stored_after_the_event_is_not_reported(app, api, ready, test_clock):
    """A scan that loses a race with submit lands after the event: it changed nothing, so it is not shown."""
    attempt_id = new_attempt(api, ready["id"])
    assert submit_at(api, attempt_id, 95).json()["review_reasons"] == ["location_uncertain"]
    test_clock.advance(seconds=1)
    with app.state.session_factory() as session:
        session.add(PresenceScan(attempt_id=attempt_id, verified_at=clock.now()))
        session.commit()
    assert submit_at(api, attempt_id, 95).json()["presence_verified"] is False  # replay of the saved event
    rows = api.get("faculty1", f"/activities/{ready['id']}/register").json()["rows"]
    assert {r["student"]["username"]: r for r in rows}["student1"]["check_in"]["presence_verified"] is False


def test_no_scan_keeps_location_uncertain(api, ready):
    body = submit_at(api, new_attempt(api, ready["id"]), 95).json()
    assert body["status"] == "needs_review" and body["review_reasons"] == ["location_uncertain"]
    assert body["presence_verified"] is False


def test_failed_scan_does_not_clear_or_add_reasons(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, "garbage")
    body = submit_at(api, attempt_id, 95).json()
    assert body["review_reasons"] == ["location_uncertain"] and body["presence_verified"] is False


def test_scan_does_not_hide_other_reasons(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = api.submit("student1", attempt_id, face("asha~"), lat=offset_lat(95)).json()
    assert body["review_reasons"] == ["face_uncertain"] and body["status"] == "needs_review"


def test_scan_inside_adds_nothing(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    body = api.submit("student1", attempt_id, face("asha")).json()
    assert body["status"] == "recorded" and body["review_reasons"] == [] and body["presence_verified"] is True


def test_resubmit_of_a_saved_attempt_keeps_presence_flag(api, ready):
    attempt_id = new_attempt(api, ready["id"])
    scan(api, attempt_id, code_for(api, ready["id"]))
    assert submit_at(api, attempt_id, 95).json()["presence_verified"] is True
    assert submit_at(api, attempt_id, 95).json()["presence_verified"] is True


# ---- presence_verified in views --------------------------------------------

def test_register_and_history_carry_presence_verified(api, ready):
    scanned = new_attempt(api, ready["id"], "student1")
    plain = new_attempt(api, ready["id"], "student2")
    scan(api, scanned, code_for(api, ready["id"]))
    api.submit("student1", scanned, face("asha"))
    api.submit("student2", plain, face("rohan"))
    rows = api.get("faculty1", f"/activities/{ready['id']}/register").json()["rows"]
    by_user = {r["student"]["username"]: r for r in rows}
    assert by_user["student1"]["check_in"]["presence_verified"] is True
    assert by_user["student2"]["check_in"]["presence_verified"] is False
    assert by_user["student3"]["check_in"] is None
    mine = api.get("student1", "/attendance/me").json()["items"][0]
    assert mine["check_in"]["presence_verified"] is True
    activity = api.get("student1", f"/activities/{ready['id']}").json()
    assert activity["my_attendance"]["check_in"]["presence_verified"] is True


def test_checkpoints_and_check_out_carry_presence_verified(api, ready, test_clock):
    api.do("student1", ready["id"], "check_in", "asha")
    test_clock.advance(minutes=5)
    api.post("faculty1", f"/activities/{ready['id']}/checkpoints", json={"duration_minutes": 10})
    cp = new_attempt_for(api, ready["id"], "checkpoint")
    scan(api, cp, code_for(api, ready["id"]))
    assert api.submit("student1", cp, face("asha")).json()["presence_verified"] is True
    row = api.get("faculty1", f"/activities/{ready['id']}/register").json()["rows"][0]
    assert row["checkpoints"][0]["presence_verified"] is True
    assert row["check_in"]["presence_verified"] is False


def new_attempt_for(api, activity_id: str, action: str) -> str:
    resp = api.attempt("student1", activity_id, action)
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def test_register_does_not_query_per_event(app, api, ready):
    """One extra query for scans, not one per event."""
    from sqlalchemy import event as sa_event

    for user, who in (("student1", "asha"), ("student2", "rohan")):
        api.submit(user, new_attempt(api, ready["id"], user), face(who))
    statements: list[str] = []
    engine = app.state.engine
    sa_event.listen(engine, "before_cursor_execute", lambda c, cur, stmt, *a: statements.append(stmt))
    api.get("faculty1", f"/activities/{ready['id']}/register")
    assert sum("presence_scans" in s for s in statements) == 1
