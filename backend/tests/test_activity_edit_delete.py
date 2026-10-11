"""Faculty can edit an activity up to the day before it starts, and delete one that has no attendance records."""
from __future__ import annotations

from datetime import timedelta

from app.clock import to_iso
from tests.helpers import P, STUDENT_IDS, VENUE

TOMORROW_MIN = 24 * 60  # the test clock starts at 14:30 IST; +24 h is 14:30 IST the next day


def put(api, activity_id, username="faculty1", **changes):
    activity = api.get("faculty1", f"/activities/{activity_id}").json()
    body = {
        "name": activity["name"], "venue_name": activity["venue_name"],
        "venue_lat": activity["venue_lat"], "venue_lng": activity["venue_lng"], "radius_m": activity["radius_m"],
        "starts_at": activity["starts_at"], "ends_at": activity["ends_at"],
        "student_ids": [STUDENT_IDS["student1"]],
    }
    body.update(changes)
    return api.client.put(f"{P}/activities/{activity_id}", headers=api.auth(username), json=body)


def delete(api, activity_id, username="faculty1"):
    return api.client.delete(f"{P}/activities/{activity_id}", headers=api.auth(username))


def code(resp):
    return resp.status_code, resp.json()["error"]["code"]


# ---- edit ------------------------------------------------------------------

def test_edit_changes_details_and_roster_before_the_day(api):
    activity = api.make_activity(students=("student1", "student2"), starts_in_min=TOMORROW_MIN)
    resp = put(api, activity["id"], name="Renamed", venue_name="Gate 5", radius_m=250,
               student_ids=[STUDENT_IDS["student3"]])
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert (body["name"], body["venue_name"], body["radius_m"], body["roster_count"]) == ("Renamed", "Gate 5", 250, 1)
    roster = api.get("faculty1", f"/activities/{activity['id']}/roster").json()["items"]
    assert [s["username"] for s in roster] == ["student3"]


def test_edit_replaces_venue_regions(api):
    activity = api.make_activity(starts_in_min=TOMORROW_MIN)
    two = [{"venue_lat": VENUE[0], "venue_lng": VENUE[1], "radius_m": 50},
           {"venue_lat": VENUE[0] + 0.001, "venue_lng": VENUE[1], "radius_m": 60}]
    assert len(put(api, activity["id"], venue_regions=two).json()["venue_regions"]) == 2
    one = put(api, activity["id"], venue_regions=two[:1]).json()
    assert [r["radius_m"] for r in one["venue_regions"]] == [50] and one["radius_m"] == 50


def test_edit_is_locked_on_the_day_of_the_activity(api):
    activity = api.make_activity()  # starts in 30 minutes: today
    assert code(put(api, activity["id"], name="Too late")) == (409, "activity_locked")
    assert api.get("faculty1", f"/activities/{activity['id']}").json()["name"] == "Site Visit"


def test_edit_closes_at_campus_midnight(api, test_clock):
    activity = api.make_activity(starts_in_min=TOMORROW_MIN)
    test_clock.advance(minutes=9 * 60 + 29)  # 23:59 IST the evening before
    api._tokens.clear()  # the 8-hour login has expired by now; sign in again
    assert put(api, activity["id"], name="Still fine").status_code == 200
    test_clock.advance(minutes=1)  # 00:00 IST on the day itself
    assert code(put(api, activity["id"], name="Locked")) == (409, "activity_locked")


def test_edit_cannot_move_the_start_into_today(api):
    activity = api.make_activity(starts_in_min=TOMORROW_MIN)
    soon = api.clock.current + timedelta(minutes=30)
    resp = put(api, activity["id"], starts_at=to_iso(soon), ends_at=to_iso(soon + timedelta(hours=2)))
    assert code(resp) == (422, "validation_error")


def test_edit_rejects_unknown_students_and_bad_access(api):
    activity = api.make_activity(starts_in_min=TOMORROW_MIN)
    assert code(put(api, activity["id"], student_ids=["u_nope"])) == (422, "validation_error")
    assert put(api, activity["id"], username="student1").status_code == 403
    body = {"name": "A", "venue_name": "V", "venue_lat": VENUE[0], "venue_lng": VENUE[1], "radius_m": 100,
            "starts_at": "2026-10-12T09:00:00Z", "ends_at": "2026-10-12T11:00:00Z", "student_ids": []}
    missing = api.client.put(f"{P}/activities/act_missing", headers=api.auth("faculty1"), json=body)
    assert code(missing) == (404, "not_found")


# ---- delete ----------------------------------------------------------------

def test_delete_removes_an_activity_without_attendance(api):
    activity = api.make_activity()
    assert delete(api, activity["id"]).status_code == 204
    assert api.get("faculty1", f"/activities/{activity['id']}").status_code == 404
    assert api.get("faculty1", "/activities").json()["items"] == []


def test_delete_is_blocked_once_there_is_attendance(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    api.do("student1", activity["id"], "check_in", "asha")
    assert code(delete(api, activity["id"])) == (409, "activity_has_attendance")
    assert api.get("faculty1", f"/activities/{activity['id']}").status_code == 200


def test_delete_needs_faculty_and_a_real_activity(api):
    activity = api.make_activity()
    assert delete(api, activity["id"], username="student1").status_code == 403
    assert code(delete(api, "act_missing")) == (404, "not_found")
