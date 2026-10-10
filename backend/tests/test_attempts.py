from __future__ import annotations

import threading
from pathlib import Path

import pytest

from tests.fakes import BLANK, TWO_FACES, face
from tests.helpers import offset_lat


@pytest.fixture()
def ready(api, test_clock):
    """Student1 approved, activity open for check-in (clock at 09:31, activity 09:30-13:30)."""
    api.enroll("student1", "asha")
    api.enroll("student2", "rohan")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


# ---- creating attempts ----------------------------------------------------

def test_attempt_created_with_90s_expiry(api, ready):
    resp = api.attempt("student1", ready["id"], "check_in")
    body = resp.json()
    assert resp.status_code == 201
    assert body["action"] == "check_in" and body["checkpoint_id"] is None
    assert body["created_at"] == "2026-10-10T09:31:00Z" and body["expires_at"] == "2026-10-10T09:32:30Z"


@pytest.mark.parametrize("setup", ["none", "pending", "rejected"])
def test_attempt_needs_approved_enrollment(api, test_clock, setup):
    if setup == "pending":
        api.enroll("student3", "fatima", approve=False)
    if setup == "rejected":
        enrollment = api.enroll("student3", "fatima", approve=False)
        api.post("faculty1", f"/enrollments/{enrollment['id']}/reject", json={"reason": "blurry"})
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    assert err(api.attempt("student3", activity["id"], "check_in")) == (409, "enrollment_not_approved")


def test_replaced_enrollment_blocks_new_attempts(api, ready):
    api.post("student1", "/enrollment", files={"photo": ("p.jpg", face("asha-new"), "image/jpeg")})
    assert err(api.attempt("student1", ready["id"], "check_in")) == (409, "enrollment_not_approved")


def test_student_not_on_roster_is_not_assigned(api, test_clock):
    api.enroll("student3", "fatima")
    activity = api.make_activity(students=("student1",))
    test_clock.advance(minutes=31)
    assert err(api.attempt("student3", activity["id"], "check_in")) == (403, "not_assigned")


def test_unknown_activity_is_404_and_bad_action_is_validation_error(api, ready):
    assert err(api.attempt("student1", "act_nope", "check_in")) == (404, "not_found")
    assert err(api.attempt("student1", ready["id"], "teleport")) == (422, "validation_error")


def test_checkin_window_opens_15_minutes_early(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()           # starts 09:30
    test_clock.set(test_clock.current.replace(hour=9, minute=14, second=59))
    assert err(api.attempt("student1", activity["id"], "check_in")) == (409, "outside_activity_window")
    test_clock.advance(seconds=1)            # 09:15:00
    assert api.attempt("student1", activity["id"], "check_in").status_code == 201


def test_checkin_window_closes_at_end(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()           # ends 13:30
    test_clock.set(test_clock.current.replace(hour=13, minute=30, second=0))
    assert api.attempt("student1", activity["id"], "check_in").status_code == 201
    test_clock.advance(seconds=1)
    assert err(api.attempt("student1", activity["id"], "check_in")) == (409, "outside_activity_window")


# ---- submitting -----------------------------------------------------------

def test_happy_path_is_recorded(api, ready):
    resp = api.do("student1", ready["id"], "check_in", "asha")
    body = resp.json()
    assert resp.status_code == 200
    assert body["status"] == "recorded" and body["review_reasons"] == []
    assert body["face"] == {"verdict": "match"}
    assert body["location"]["verdict"] == "inside" and body["location"]["distance_m"] == 0
    assert body["location"]["accuracy_m"] == 10
    assert body["received_at"] == "2026-10-10T09:31:00Z" and body["checkpoint_id"] is None
    assert set(body["face"]) == {"verdict"}  # no scores, no liveness field


def test_no_numeric_scores_or_liveness_anywhere_in_event(api, ready):
    text = api.do("student1", ready["id"], "check_in", "asha").text.lower()
    assert "score" not in text and "confidence" not in text and "liveness" not in text


def test_wrong_face_is_refused_and_nothing_is_recorded(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert err(api.submit("student1", attempt["id"], face("someone-else"))) == (422, "face_not_matched")
    # attempt stays open: the right person can still use it
    assert api.submit("student1", attempt["id"], face("asha")).json()["status"] == "recorded"


def test_uncertain_face_needs_review_face_uncertain(api, ready):
    body = api.do("student1", ready["id"], "check_in", "asha~").json()
    assert body["status"] == "needs_review" and body["review_reasons"] == ["face_uncertain"]


@pytest.mark.parametrize("metres,accuracy,verdict,reasons,status", [
    (0, 10, "inside", [], "recorded"),
    (85, 10, "inside", [], "recorded"),
    (95, 10, "uncertain", ["location_uncertain"], "needs_review"),
    (105, 10, "uncertain", ["location_uncertain"], "needs_review"),
    (200, 10, "outside", ["location_outside"], "needs_review"),
])
def test_location_verdicts_through_api(api, ready, metres, accuracy, verdict, reasons, status):
    body = api.do("student1", ready["id"], "check_in", "asha", lat=offset_lat(metres), accuracy=accuracy).json()
    assert body["location"]["verdict"] == verdict
    assert body["review_reasons"] == reasons and body["status"] == status


def test_location_and_face_reasons_combine(api, ready):
    body = api.do("student1", ready["id"], "check_in", "asha~", lat=offset_lat(500)).json()
    assert body["review_reasons"] == ["location_outside", "face_uncertain"]


def test_unusable_capture_keeps_attempt_open(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert err(api.submit("student1", attempt["id"], BLANK)) == (422, "unusable_capture")
    assert err(api.submit("student1", attempt["id"], TWO_FACES)) == (422, "multiple_faces")
    assert api.submit("student1", attempt["id"], face("asha")).status_code == 200


def test_stale_location_keeps_attempt_open(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert err(api.submit("student1", attempt["id"], face("asha"), captured_ago_s=61)) == (422, "location_stale")
    assert api.submit("student1", attempt["id"], face("asha"), captured_ago_s=60).status_code == 200


def test_stale_location_is_checked_before_selfie(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert err(api.submit("student1", attempt["id"], BLANK, captured_ago_s=120)) == (422, "location_stale")


def test_expired_attempt_is_410(api, ready, test_clock):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    test_clock.advance(seconds=91)
    assert err(api.submit("student1", attempt["id"], face("asha"))) == (410, "attempt_expired")


def test_attempt_valid_at_exactly_90_seconds(api, ready, test_clock):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    test_clock.advance(seconds=90)
    assert api.submit("student1", attempt["id"], face("asha")).status_code == 200


def test_expired_check_comes_before_location_check(api, ready, test_clock):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    test_clock.advance(seconds=100)
    assert err(api.submit("student1", attempt["id"], face("asha"), captured_ago_s=500)) == (410, "attempt_expired")


def test_replay_returns_identical_event_even_after_expiry(api, ready, test_clock):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    first = api.submit("student1", attempt["id"], face("asha"))
    test_clock.advance(seconds=300)
    replay = api.submit("student1", attempt["id"], BLANK, captured_ago_s=900)  # even a bad body is ignored
    assert replay.status_code == 200 and replay.json() == first.json()


def test_replay_does_not_create_second_event(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    api.submit("student1", attempt["id"], face("asha"))
    api.submit("student1", attempt["id"], face("asha"))
    record = api.get("student1", "/attendance/me").json()["items"][0]
    assert record["check_in"] is not None and record["checkpoints"] == []


def test_other_students_attempt_is_forbidden(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert err(api.submit("student2", attempt["id"], face("rohan"))) == (403, "forbidden")
    done = api.submit("student1", attempt["id"], face("asha"))
    assert err(api.submit("student2", attempt["id"], face("rohan"))) == (403, "forbidden")  # not even replays
    assert done.status_code == 200


def test_unknown_attempt_is_404(api, ready):
    assert err(api.submit("student1", "att_nope", face("asha"))) == (404, "not_found")


def test_submit_validates_form_fields(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    path = f"/attempts/{attempt['id']}/submit"
    files = {"selfie": ("s.jpg", face("asha"), "image/jpeg")}
    bad_forms = [
        {"lat": "999", "lng": "77", "accuracy_m": "5", "location_captured_at": "2026-10-10T09:31:00Z"},
        {"lat": "12", "lng": "77", "accuracy_m": "-1", "location_captured_at": "2026-10-10T09:31:00Z"},
        {"lat": "12", "lng": "77", "accuracy_m": "5", "location_captured_at": "yesterday"},
        {"lat": "12", "lng": "77"},
    ]
    for form in bad_forms:
        assert err(api.post("student1", path, data=form, files=files)) == (422, "validation_error")
    assert err(api.post("student1", path, data=bad_forms[0])) == (422, "validation_error")


def test_oversize_selfie_is_413(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert err(api.submit("student1", attempt["id"], b"FACE:" + b"x" * (5 * 1024 * 1024))) == (413, "image_too_large")


def test_selfie_never_written_to_photo_dir(api, ready, settings):
    before = sorted(p.name for p in Path(settings.photo_dir).iterdir())
    api.do("student1", ready["id"], "check_in", "asha")
    assert sorted(p.name for p in Path(settings.photo_dir).iterdir()) == before


def test_concurrent_duplicate_submits_make_one_event(api, ready, app):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    headers = api.auth("student1")
    data, files = api.signed_form("student1", attempt["id"], face("asha"), captured_ago_s=2)
    results, barrier = [], threading.Barrier(6)

    def worker():
        from fastapi.testclient import TestClient
        from tests.helpers import P

        with TestClient(app) as client:
            barrier.wait()
            resp = client.post(f"{P}/attempts/{attempt['id']}/submit", headers=headers, data=data, files=files)
            results.append((resp.status_code, resp.json()))

    threads = [threading.Thread(target=worker) for _ in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert [status for status, _ in results] == [200] * 6
    assert len({body["id"] for _, body in results}) == 1
    from sqlalchemy import func, select

    from app.models import AttendanceEvent
    with app.state.session_factory() as session:
        assert session.scalar(select(func.count()).select_from(AttendanceEvent)) == 1


# ---- finding 5: future-dated location readings --------------------------------

def test_future_dated_location_is_stale(api, ready):
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    resp = api.submit("student1", attempt["id"], face("asha"), captured_ago_s=-3600)
    assert err(resp) == (422, "location_stale")
    assert err(api.submit("student1", attempt["id"], face("asha"), captured_ago_s=-6)) == (422, "location_stale")


def test_small_clock_skew_into_the_future_is_tolerated(api, ready):
    from app.routers.attempts import LOCATION_FUTURE_TOLERANCE_SECONDS

    assert LOCATION_FUTURE_TOLERANCE_SECONDS == 5
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    assert api.submit("student1", attempt["id"], face("asha"), captured_ago_s=-5).status_code == 200


def test_attempt_has_nonce_and_challenge(api, ready):
    first = api.attempt("student1", ready["id"], "check_in").json()
    assert len(first["nonce"]) >= 16 and first["challenge"] in ("left", "right")


def test_each_attempt_gets_a_fresh_nonce(api, ready):
    first = api.attempt("student1", ready["id"], "check_in").json()
    second = api.attempt("student1", ready["id"], "check_in").json()
    assert first["nonce"] != second["nonce"]


def test_both_challenge_directions_occur(api, ready):
    seen = {api.attempt("student1", ready["id"], "check_in").json()["challenge"] for _ in range(40)}
    assert seen == {"left", "right"}  # chance of a false failure: 2 * 0.5**40
