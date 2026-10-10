from __future__ import annotations

import pytest

from tests.fakes import face
from tests.helpers import new_device_key, offset_lat


@pytest.fixture()
def ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


def new_attempt(api, ready) -> str:
    return api.attempt("student1", ready["id"], "check_in").json()["id"]


def test_signed_submit_is_recorded(api, ready):
    resp = api.submit("student1", new_attempt(api, ready), face("asha"))
    assert resp.status_code == 200 and resp.json()["status"] == "recorded"


def test_location_changed_after_signing_is_bad_signature(api, ready):
    resp = api.submit("student1", new_attempt(api, ready), face("asha"), lat=offset_lat(500), signed_lat=offset_lat(0))
    assert err(resp) == (403, "bad_signature")


def test_signature_from_another_phone_is_bad_signature(api, ready):
    other, _ = new_device_key()
    assert err(api.submit("student1", new_attempt(api, ready), face("asha"), key=other)) == (403, "bad_signature")


def test_flag_flipped_after_signing_is_bad_signature(api, ready):
    attempt_id = new_attempt(api, ready)
    data, files = api.signed_form("student1", attempt_id, face("asha"))
    data["location_is_mock"] = "true"
    assert err(api.post("student1", f"/attempts/{attempt_id}/submit", data=data, files=files)) == (403, "bad_signature")


def test_submit_after_device_reset_is_device_mismatch(api, ready):
    attempt_id = new_attempt(api, ready)
    api.post("faculty1", "/students/u_s1/device/reset")
    assert err(api.submit("student1", attempt_id, face("asha"))) == (403, "device_mismatch")


def test_resubmit_returns_saved_event_without_new_signature(api, ready):
    attempt_id = new_attempt(api, ready)
    first = api.submit("student1", attempt_id, face("asha")).json()
    again = api.submit("student1", attempt_id, face("asha"), signature="AAAA")
    assert again.status_code == 200 and again.json()["id"] == first["id"]


def test_missing_turn_photo_is_validation_error(api, ready):
    attempt_id = new_attempt(api, ready)
    data, files = api.signed_form("student1", attempt_id, face("asha"))
    del files["turn_photo"]
    assert err(api.post("student1", f"/attempts/{attempt_id}/submit", data=data, files=files)) == (422, "validation_error")
