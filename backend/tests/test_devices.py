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
