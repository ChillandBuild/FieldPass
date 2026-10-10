from __future__ import annotations

from tests.helpers import FACULTY_PASSWORD, P, PASSWORD, new_device_key


def login(client, username: str, key: str | None, password: str = PASSWORD):
    body = {"username": username, "password": password}
    if key is not None:
        body["device_public_key"] = key
    return client.post(f"{P}/auth/login", json=body)


def code(resp) -> tuple[int, str]:
    return resp.status_code, resp.json()["error"]["code"]


def test_first_login_binds_and_same_phone_logs_in_again(client):
    _, key = new_device_key()
    assert login(client, "student1", key).status_code == 200
    assert login(client, "student1", key).status_code == 200


def test_other_phone_is_device_mismatch(client):
    _, first = new_device_key()
    _, second = new_device_key()
    login(client, "student1", first)
    assert code(login(client, "student1", second)) == (403, "device_mismatch")


def test_phone_of_another_student_is_device_taken(client):
    _, key = new_device_key()
    login(client, "student1", key)
    assert code(login(client, "student2", key)) == (403, "device_taken")


def test_student_without_key_is_device_key_required(client):
    assert code(login(client, "student1", None)) == (422, "device_key_required")


def test_malformed_key_is_validation_error(client):
    assert code(login(client, "student1", "bm90LWEta2V5")) == (422, "validation_error")


def test_faculty_needs_no_key(client):
    assert login(client, "faculty1", None, password=FACULTY_PASSWORD).status_code == 200


def test_wrong_password_is_checked_before_the_device(client):
    assert code(login(client, "student1", None, password="wrong")) == (401, "invalid_credentials")


def students(api) -> dict[str, dict]:
    return {s["username"]: s for s in api.get("faculty1", "/students").json()["items"]}


def test_students_list_shows_when_a_phone_was_bound(api, test_clock):
    assert students(api)["student1"]["device_bound_at"] is None
    api.auth("student1")
    assert students(api)["student1"]["device_bound_at"] == "2026-10-10T09:00:00Z"


def test_reset_then_new_phone_binds(api, client):
    api.auth("student1")
    _, new_phone = new_device_key()
    assert code(login(client, "student1", new_phone)) == (403, "device_mismatch")
    resp = api.post("faculty1", "/students/u_s1/device/reset")
    assert resp.status_code == 200 and resp.json()["device_bound_at"] is None
    assert login(client, "student1", new_phone).status_code == 200


def test_reset_is_faculty_only_and_needs_a_student(api):
    assert api.post("student1", "/students/u_s1/device/reset").status_code == 403
    assert code(api.post("faculty1", "/students/u_f1/device/reset")) == (404, "not_found")
    assert code(api.post("faculty1", "/students/nope/device/reset")) == (404, "not_found")


def test_reset_without_a_bound_phone_is_fine(api):
    resp = api.post("faculty1", "/students/u_s2/device/reset")
    assert resp.status_code == 200 and resp.json()["device_bound_at"] is None
