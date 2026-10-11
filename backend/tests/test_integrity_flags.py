from __future__ import annotations

import pytest
from sqlalchemy import select

from app.device_keys import location_fingerprint
from app.models import AttendanceEvent
from tests.fakes import face
from tests.helpers import VENUE


@pytest.fixture()
def ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def reasons(resp) -> tuple[str, list[str]]:
    body = resp.json()
    return body["status"], body["review_reasons"]


def test_clean_submit_has_no_flags(api, ready):
    assert reasons(api.do("student1", ready["id"], "check_in", "asha")) == ("recorded", [])


def test_mock_location_is_flagged(api, ready):
    assert reasons(api.do("student1", ready["id"], "check_in", "asha", mock=True)) == ("needs_review", ["location_mocked"])


def test_rooted_and_emulator_are_flagged_in_order(api, ready):
    resp = api.do("student1", ready["id"], "check_in", "asha", rooted=True, emulator=True)
    assert reasons(resp) == ("needs_review", ["device_rooted", "device_emulator"])


def test_same_point_twice_is_location_repeated(api, ready):
    api.do("student1", ready["id"], "check_in", "asha", lat=VENUE[0])
    resp = api.do("student1", ready["id"], "check_out", "asha", lat=VENUE[0])
    assert reasons(resp) == ("needs_review", ["location_repeated"])


def test_nearby_but_different_point_is_not_repeated(api, ready):
    api.do("student1", ready["id"], "check_in", "asha", lat=VENUE[0])
    resp = api.do("student1", ready["id"], "check_out", "asha", lat=VENUE[0] + 0.000002)
    assert reasons(resp) == ("recorded", [])


def test_same_point_as_another_student_is_not_repeated(api, ready):
    api.enroll("student2", "ravi")
    api.do("student1", ready["id"], "check_in", "asha", lat=VENUE[0])
    resp = api.do("student2", ready["id"], "check_in", "ravi", lat=VENUE[0])
    assert reasons(resp) == ("recorded", [])


def test_flags_add_after_location_and_face_reasons(api, ready):
    # "asha~" is an uncertain match; a clearly different face is refused with face_not_matched and never saved
    resp = api.do("student1", ready["id"], "check_in", "asha~", mock=True)
    assert reasons(resp) == ("needs_review", ["face_uncertain", "location_mocked"])


def test_sub_precision_change_after_signing_is_judged_as_signed(api, ready, app):
    # lat is signed at 6 decimals; a 4e-7 nudge must not change what is judged or stored
    created = api.attempt("student1", ready["id"], "check_in").json()
    data, files = api.signed_form("student1", created["id"], face("asha"), lat=VENUE[0])
    data["lat"] = f"{VENUE[0] + 4e-7:.7f}"
    resp = api.post("student1", f"/attempts/{created['id']}/submit", data=data, files=files)
    assert resp.status_code == 200, resp.text
    with app.state.session_factory() as s:
        event = s.scalar(select(AttendanceEvent).where(AttendanceEvent.attempt_id == created["id"]))
        assert event.location_fingerprint == location_fingerprint(VENUE[0], VENUE[1])


def test_accuracy_change_after_signing_is_judged_and_stored_as_signed(api, ready, app):
    # accuracy is signed at 1 decimal ("10.5"); the posted "10.54" must be rounded back to 10.5 before use.
    # 10.5 is stored as 10 (round half to even); judging the unrounded 10.54 would store 11.
    created = api.attempt("student1", ready["id"], "check_in").json()
    data, files = api.signed_form("student1", created["id"], face("asha"), accuracy=10.5)
    assert data["accuracy_m"] == "10.5"
    data["accuracy_m"] = "10.54"
    resp = api.post("student1", f"/attempts/{created['id']}/submit", data=data, files=files)
    assert resp.status_code == 200, resp.text
    with app.state.session_factory() as s:
        event = s.scalar(select(AttendanceEvent).where(AttendanceEvent.attempt_id == created["id"]))
        assert event.accuracy_m == 10
