from __future__ import annotations

import pytest

from tests.fakes import face
from tests.helpers import STUDENT_IDS, VENUE


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


def test_create_activity_shape_and_defaults(api):
    activity = api.make_activity(students=("student1", "student2"))
    assert activity["radius_m"] == 100 and activity["roster_count"] == 2
    assert activity["created_by"] == "Dr. Meera Rao"
    assert activity["checkpoint_windows"] == [] and activity["open_checkpoint"] is None
    assert activity["my_attendance"] is None
    assert activity["starts_at"].endswith("Z")


def test_create_activity_default_radius_when_omitted(api):
    body = {
        "name": "A", "venue_name": "V", "venue_lat": VENUE[0], "venue_lng": VENUE[1],
        "starts_at": "2026-10-10T10:00:00Z", "ends_at": "2026-10-10T12:00:00Z", "student_ids": ["u_s1"],
    }
    assert api.post("faculty1", "/activities", json=body).json()["radius_m"] == 100


@pytest.mark.parametrize("patch", [
    {"radius_m": -1}, {"radius_m": 5001}, {"name": "  "}, {"venue_lat": 91}, {"venue_lng": -181},
    {"ends_at": "2026-10-10T09:00:00Z"}, {"student_ids": ["u_nope"]}, {"student_ids": ["u_f1"]},
    {"starts_at": "not-a-date"},
])
def test_create_activity_rejects_bad_input(api, patch):
    body = {
        "name": "A", "venue_name": "V", "venue_lat": VENUE[0], "venue_lng": VENUE[1], "radius_m": 100,
        "starts_at": "2026-10-10T10:00:00Z", "ends_at": "2026-10-10T12:00:00Z", "student_ids": ["u_s1"],
    }
    body.update(patch)
    assert err(api.post("faculty1", "/activities", json=body)) == (422, "validation_error")


def test_activity_with_radius_above_input_cap_still_renders(api, app):
    """Rows written outside the API (test or legacy data) can exceed the 5000 m input cap; reads must not crash."""
    from app.models import Activity

    activity = api.make_activity(students=("student1",))
    with app.state.session_factory() as session:
        session.get(Activity, activity["id"]).radius_m = 20_000_000
        session.commit()
    listed = api.get("faculty1", "/activities")
    assert listed.status_code == 200
    assert listed.json()["items"][0]["venue_regions"][0]["radius_m"] == 20_000_000
    assert api.get("faculty1", f"/activities/{activity['id']}").status_code == 200
    assert api.get("student1", "/activities").status_code == 200


def test_students_list_and_roster(api):
    activity = api.make_activity(students=("student1", "student3"))
    students = api.get("faculty1", "/students").json()["items"]
    assert [s["username"] for s in students] == ["student1", "student2", "student3"]
    roster = api.get("faculty1", f"/activities/{activity['id']}/roster").json()["items"]
    assert [s["username"] for s in roster] == ["student1", "student3"]


def test_student_sees_only_assigned_activities(api):
    mine = api.make_activity(students=("student1",))
    api.make_activity(students=("student2",))
    items = api.get("student1", "/activities").json()["items"]
    assert [a["id"] for a in items] == [mine["id"]]
    assert items[0]["my_attendance"]["next_actions"] == []  # check-in not open yet (starts in 30 min)
    assert len(api.get("faculty1", "/activities").json()["items"]) == 2


def test_get_activity_access_rules(api):
    activity = api.make_activity(students=("student1",))
    assert api.get("student1", f"/activities/{activity['id']}").status_code == 200
    assert api.get("faculty1", f"/activities/{activity['id']}").json()["my_attendance"] is None
    assert err(api.get("student2", f"/activities/{activity['id']}")) == (403, "not_assigned")
    assert err(api.get("student1", "/activities/act_nope")) == (404, "not_found")


def test_open_checkpoint_window_lifecycle(api, test_clock):
    activity = api.make_activity()
    path = f"/activities/{activity['id']}/checkpoints"
    assert err(api.post("faculty1", path, json={})) == (409, "outside_activity_window")  # not started yet
    test_clock.advance(minutes=40)
    window = api.post("faculty1", path, json={"duration_minutes": 10})
    assert window.status_code == 201
    body = window.json()
    assert body["opened_by"] == "Dr. Meera Rao" and body["opens_at"] == "2026-10-10T09:40:00Z"
    assert body["closes_at"] == "2026-10-10T09:50:00Z"
    assert err(api.post("faculty1", path, json={})) == (409, "invalid_action_order")
    shown = api.get("faculty1", f"/activities/{activity['id']}").json()
    assert shown["open_checkpoint"]["id"] == body["id"] and len(shown["checkpoint_windows"]) == 1
    test_clock.advance(minutes=10)
    assert api.get("faculty1", f"/activities/{activity['id']}").json()["open_checkpoint"] is None
    assert api.post("faculty1", path, json={}).status_code == 201  # default 10 min, no body fields


def test_checkpoint_duration_validation(api, test_clock):
    activity = api.make_activity()
    test_clock.advance(minutes=40)
    path = f"/activities/{activity['id']}/checkpoints"
    for minutes in (0, 61):
        assert err(api.post("faculty1", path, json={"duration_minutes": minutes})) == (422, "validation_error")
    assert api.post("faculty1", path).status_code == 201  # no body at all uses the default


@pytest.fixture()
def running(api, test_clock):
    for username, identity in (("student1", "asha"), ("student2", "rohan")):
        api.enroll(username, identity)
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def test_action_order_via_api(api, running, test_clock):
    aid = running["id"]
    assert err(api.attempt("student1", aid, "check_out")) == (409, "invalid_action_order")
    assert err(api.attempt("student1", aid, "checkpoint")) == (409, "invalid_action_order")
    assert api.do("student1", aid, "check_in", "asha").status_code == 200
    assert err(api.attempt("student1", aid, "check_in")) == (409, "invalid_action_order")
    assert err(api.attempt("student1", aid, "checkpoint")) == (409, "checkpoint_not_open")
    cp = api.post("faculty1", f"/activities/{aid}/checkpoints", json={}).json()
    answered = api.do("student1", aid, "checkpoint", "asha")
    assert answered.status_code == 200 and answered.json()["checkpoint_id"] == cp["id"]
    assert err(api.attempt("student1", aid, "checkpoint")) == (409, "checkpoint_not_open")  # same window again
    assert api.do("student1", aid, "check_out", "asha").status_code == 200
    for action in ("check_in", "checkpoint", "check_out"):
        assert err(api.attempt("student1", aid, action)) == (409, "invalid_action_order")


def test_second_window_can_be_answered(api, running, test_clock):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha")
    api.post("faculty1", f"/activities/{aid}/checkpoints", json={"duration_minutes": 1})
    assert api.do("student1", aid, "checkpoint", "asha").status_code == 200
    test_clock.advance(minutes=5)
    api.post("faculty1", f"/activities/{aid}/checkpoints", json={})
    assert api.do("student1", aid, "checkpoint", "asha").status_code == 200
    record = api.get("student1", "/attendance/me").json()["items"][0]
    assert len(record["checkpoints"]) == 2


def test_checkout_deadline_is_30_minutes_after_end(api, running, test_clock):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha")
    test_clock.set(test_clock.current.replace(hour=14, minute=0, second=0))  # ends_at + 30 min
    assert api.attempt("student1", aid, "check_out").status_code == 201
    test_clock.advance(seconds=1)
    assert err(api.attempt("student1", aid, "check_out")) == (409, "outside_activity_window")


def test_two_attempts_cannot_both_record_check_in(api, running):
    aid = running["id"]
    first = api.attempt("student1", aid, "check_in").json()
    second = api.attempt("student1", aid, "check_in").json()
    assert api.submit("student1", first["id"], face("asha")).status_code == 200
    assert err(api.submit("student1", second["id"], face("asha"))) == (409, "invalid_action_order")


def test_next_actions_follow_progress(api, running):
    aid = running["id"]
    assert api.get("student1", f"/activities/{aid}").json()["my_attendance"]["next_actions"] == ["check_in"]
    api.do("student1", aid, "check_in", "asha")
    assert api.get("student1", "/attendance/me").json()["items"][0]["next_actions"] == ["check_out"]
    api.post("faculty1", f"/activities/{aid}/checkpoints", json={})
    assert api.get("student1", "/attendance/me").json()["items"][0]["next_actions"] == ["checkpoint", "check_out"]
    assert api.get("student2", "/attendance/me").json()["items"][0]["next_actions"] == ["check_in"]
    row = api.get("faculty1", f"/activities/{aid}/register").json()["rows"][0]
    assert row["next_actions"] == []


def test_missing_checkout_flag_and_complete(api, running, test_clock):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha")
    api.do("student2", aid, "check_in", "rohan")
    api.do("student2", aid, "check_out", "rohan")
    test_clock.set(test_clock.current.replace(hour=14, minute=0, second=1))  # 1 s past checkout deadline
    rows = {r["student"]["username"]: r for r in api.get("faculty1", f"/activities/{aid}/register").json()["rows"]}
    assert rows["student1"]["missing_checkout"] is True and rows["student1"]["complete"] is False
    assert rows["student2"]["missing_checkout"] is False and rows["student2"]["complete"] is True
    assert rows["student3"]["missing_checkout"] is False and rows["student3"]["status"] is None


def test_missing_checkout_false_while_window_open(api, running, test_clock):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha")
    test_clock.set(test_clock.current.replace(hour=14, minute=0, second=0))
    row = api.get("student1", "/attendance/me").json()["items"][0]
    assert row["missing_checkout"] is False


def test_register_shape(api, running):
    aid = running["id"]
    body = api.get("faculty1", f"/activities/{aid}/register").json()
    assert set(body) == {"activity", "server_time", "rows"}
    assert [r["student"]["username"] for r in body["rows"]] == ["student1", "student2", "student3"]
    assert body["activity"]["id"] == aid and body["server_time"].endswith("Z")


def test_attendance_me_lists_newest_activity_first(api, test_clock):
    api.enroll("student1", "asha")
    older = api.make_activity(starts_in_min=30)
    newer = api.make_activity(starts_in_min=300)
    ids = [r["activity_id"] for r in api.get("student1", "/attendance/me").json()["items"]]
    assert ids == [newer["id"], older["id"]]


# ---- review: decisions and observations -------------------------------------

def decide(api, aid, student, decision, reason="Seen on site"):
    return api.post("faculty1", f"/activities/{aid}/students/{STUDENT_IDS[student]}/decision",
                    json={"decision": decision, "reason": reason})


def test_decision_requires_reason(api, running):
    aid = running["id"]
    for reason in ("", "   "):
        assert err(decide(api, aid, "student1", "confirmed", reason)) == (422, "validation_error")
    resp = api.post("faculty1", f"/activities/{aid}/students/u_s1/decision", json={"decision": "confirmed"})
    assert resp.status_code == 422
    resp = api.post("faculty1", f"/activities/{aid}/students/u_s1/decision", json={"decision": "maybe", "reason": "x"})
    assert resp.status_code == 422


def test_decision_sets_status_and_actor(api, running):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha", lat=offset_far())
    record = decide(api, aid, "student1", "confirmed", "Verified in person").json()
    assert record["status"] == "confirmed"
    assert record["decision"]["reason"] == "Verified in person"
    assert record["decision"]["actor_name"] == "Dr. Meera Rao" and record["decision"]["is_current"] is True
    assert record["check_in"]["status"] == "needs_review"  # the automated event is untouched


def offset_far() -> float:
    from tests.helpers import offset_lat
    return offset_lat(500)


def test_decision_history_is_preserved_newest_current(api, running, test_clock):
    aid = running["id"]
    decide(api, aid, "student1", "not_confirmed", "Not seen")
    test_clock.advance(minutes=3)
    record = decide(api, aid, "student1", "confirmed", "Found them").json()
    assert record["status"] == "confirmed"
    history = record["decision_history"]
    assert [d["decision"] for d in history] == ["confirmed", "not_confirmed"]  # newest first
    assert [d["is_current"] for d in history] == [True, False]
    assert record["decision"]["reason"] == "Found them"


def test_decision_becomes_non_current_when_later_event_arrives(api, running, test_clock):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha")
    test_clock.advance(minutes=5)
    assert decide(api, aid, "student1", "confirmed").json()["status"] == "confirmed"
    test_clock.advance(minutes=5)
    api.do("student1", aid, "check_out", "someone-else")  # later automated event, needs_review
    row = api.get("faculty1", f"/activities/{aid}/register").json()["rows"][0]
    assert row["status"] == "needs_review"
    assert row["decision"] is None
    assert len(row["decision_history"]) == 1 and row["decision_history"][0]["is_current"] is False
    test_clock.advance(minutes=1)
    assert decide(api, aid, "student1", "confirmed", "Rechecked").json()["status"] == "confirmed"


def test_decision_with_no_events_still_sets_status(api, running):
    assert decide(api, running["id"], "student3", "not_confirmed", "Absent").json()["status"] == "not_confirmed"


def test_decision_for_unrostered_student_or_unknown_activity(api, running, test_clock):
    other = api.make_activity(students=("student1",))
    assert err(decide(api, other["id"], "student2", "confirmed")) == (404, "not_found")
    assert err(decide(api, "act_nope", "student1", "confirmed")) == (404, "not_found")


def test_observations(api, running, test_clock):
    aid = running["id"]
    test_clock.advance(minutes=2)
    resp = api.post("faculty1", f"/activities/{aid}/observations",
                    json={"student_id": "u_s1", "observation_type": "present", "note": "Signed the paper sheet"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["actor_name"] == "Dr. Meera Rao" and body["observed_at"] == "2026-10-10T09:33:00Z"
    assert body["observation_type"] == "present" and body["student_id"] == "u_s1"
    record = api.get("faculty1", f"/activities/{aid}/register").json()["rows"][0]
    assert [o["note"] for o in record["observations"]] == ["Signed the paper sheet"]
    # observations are separate from automated status
    assert record["status"] is None


def test_observation_validation(api, running):
    aid = running["id"]
    base = {"student_id": "u_s1", "observation_type": "present", "note": "ok"}
    for patch in ({"note": ""}, {"note": "  "}, {"observation_type": "dancing"}):
        resp = api.post("faculty1", f"/activities/{aid}/observations", json={**base, **patch})
        assert err(resp) == (422, "validation_error")
    unknown = api.post("faculty1", f"/activities/{aid}/observations", json={**base, "student_id": "u_nope"})
    assert err(unknown) == (404, "not_found")


def test_csv_export(api, running):
    aid = running["id"]
    api.do("student1", aid, "check_in", "asha")
    api.do("student2", aid, "check_in", "rohan", lat=offset_far())
    api.post("faculty1", f"/activities/{aid}/observations",
             json={"student_id": "u_s3", "observation_type": "other", "note": "=HYPERLINK(\"x\")"})
    resp = api.get("faculty1", f"/activities/{aid}/export.csv")
    assert resp.status_code == 200 and resp.headers["content-type"].startswith("text/csv")
    lines = resp.text.strip().split("\r\n")
    assert lines[0].startswith("student_id,username,full_name,status")
    assert len(lines) == 4  # header + one line per rostered student
    by_user = {line.split(",")[1]: line for line in lines[1:]}
    assert "recorded" in by_user["student1"] and "2026-10-10T09:31:00Z" in by_user["student1"]
    assert "needs_review" in by_user["student2"] and "location_outside" in by_user["student2"]
    assert 'other: =HYPERLINK' in by_user["student3"]  # prefixed with the type, so it never starts with "="


def test_csv_cells_that_start_like_formulas_are_neutralised():
    from app.routers.attendance import _csv_cell

    assert _csv_cell("=1+1") == "'=1+1" and _csv_cell("@x") == "'@x" and _csv_cell("-2") == "'-2"
    assert _csv_cell("Seen on site") == "Seen on site" and _csv_cell("") == ""


def test_csv_unknown_activity_is_404(api):
    assert err(api.get("faculty1", "/activities/act_nope/export.csv")) == (404, "not_found")


# ---- finding 7: bounded activity times ---------------------------------------

def _times_body(starts, ends):
    return {
        "name": "A", "venue_name": "V", "venue_lat": VENUE[0], "venue_lng": VENUE[1], "radius_m": 100,
        "starts_at": starts, "ends_at": ends, "student_ids": ["u_s1"],
    }


@pytest.mark.parametrize("starts,ends", [
    ("0001-01-01T00:00:00+05:00", "0001-01-02T00:00:00+05:00"),   # overflows when converted to UTC
    ("9999-12-31T00:00:00Z", "9999-12-31T23:59:59-05:00"),
    ("1999-12-31T00:00:00Z", "1999-12-31T10:00:00Z"),            # before year 2000
    ("2101-01-01T00:00:00Z", "2101-01-01T10:00:00Z"),            # after year 2100
    ("2026-10-10T10:00:00Z", "2026-10-10T10:00:00Z"),            # zero length
    ("2026-10-10T10:00:00Z", "2026-10-17T10:00:01Z"),            # just over 7 days
    ("2026-10-10T10:00:00Z", "2100-12-31T10:00:00Z"),            # decades long
])
def test_activity_times_out_of_range_are_validation_errors(api, starts, ends):
    assert err(api.post("faculty1", "/activities", json=_times_body(starts, ends))) == (422, "validation_error")
    assert api.get("faculty1", "/activities").status_code == 200  # listing is not poisoned


def test_activity_of_exactly_seven_days_and_year_bounds_are_accepted(api):
    resp = api.post("faculty1", "/activities", json=_times_body("2026-10-10T10:00:00Z", "2026-10-17T10:00:00Z"))
    assert resp.status_code == 201
    assert api.get("faculty1", "/activities").status_code == 200
