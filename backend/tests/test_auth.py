from __future__ import annotations

import jwt
import pytest

from tests.helpers import P, PASSWORD


def test_login_success_returns_token_and_user(client):
    resp = client.post(f"{P}/auth/login", json={"username": "student1", "password": PASSWORD})
    body = resp.json()
    assert resp.status_code == 200
    assert body["token_type"] == "bearer"
    assert body["user"] == {"id": "u_s1", "username": "student1", "full_name": "Asha Verma", "role": "student"}
    assert body["expires_at"].endswith("Z")


@pytest.mark.parametrize("username,password", [("student1", "wrong"), ("nobody", PASSWORD)])
def test_login_failure_is_invalid_credentials(client, username, password):
    resp = client.post(f"{P}/auth/login", json={"username": username, "password": password})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "invalid_credentials"


def test_login_missing_fields_is_validation_error(client):
    resp = client.post(f"{P}/auth/login", json={"username": "student1"})
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation_error"


def test_login_is_rate_limited(client):
    codes = [
        client.post(f"{P}/auth/login", json={"username": "student1", "password": "bad"}).status_code
        for _ in range(12)
    ]
    assert codes[:10] == [401] * 10
    assert codes[10:] == [429, 429]


def test_health_needs_no_auth(client):
    body = client.get(f"{P}/health").json()
    assert body["status"] == "ok" and body["server_time"].endswith("Z")


def test_me_returns_user(api):
    assert api.get("faculty1", "/auth/me").json()["role"] == "faculty"


def test_missing_and_garbage_tokens_are_not_authenticated(client):
    for headers in ({}, {"Authorization": "Bearer garbage"}, {"Authorization": "Basic abc"}):
        resp = client.get(f"{P}/auth/me", headers=headers)
        assert resp.status_code == 401
        assert resp.json()["error"]["code"] == "not_authenticated"


def test_expired_token_is_rejected(api, test_clock):
    api.auth("student1")
    test_clock.advance(minutes=481)
    assert api.get("student1", "/auth/me").status_code == 401


def test_token_for_deleted_user_or_wrong_secret_is_rejected(client, settings):
    forged = jwt.encode({"sub": "u_s1", "role": "faculty", "exp": 4102444800}, "other-secret", algorithm="HS256")
    resp = client.get(f"{P}/auth/me", headers={"Authorization": f"Bearer {forged}"})
    assert resp.status_code == 401
    ghost = jwt.encode({"sub": "u_ghost", "exp": 4102444800}, settings.jwt_secret, algorithm="HS256")
    assert client.get(f"{P}/auth/me", headers={"Authorization": f"Bearer {ghost}"}).status_code == 401


def test_token_role_claim_is_not_trusted(api, client, settings):
    """A student token claiming role=faculty still gets the role stored in the database."""
    forged = jwt.encode({"sub": "u_s1", "role": "faculty", "exp": 4102444800}, settings.jwt_secret, algorithm="HS256")
    assert client.get(f"{P}/students", headers={"Authorization": f"Bearer {forged}"}).status_code == 403


FACULTY_ONLY = [
    ("get", "/students"), ("get", "/enrollments"), ("post", "/activities"),
    ("get", "/activities/x/roster"), ("post", "/activities/x/checkpoints"), ("get", "/activities/x/register"),
    ("get", "/activities/x/export.csv"), ("post", "/enrollments/x/approve"), ("post", "/enrollments/x/reject"),
    ("get", "/enrollments/x/photo"), ("post", "/activities/x/students/u_s1/decision"),
    ("post", "/activities/x/observations"),
]
STUDENT_ONLY = [
    ("post", "/enrollment"), ("get", "/enrollment/me"), ("post", "/activities/x/attempts"),
    ("post", "/attempts/x/submit"), ("get", "/attendance/me"),
]


@pytest.mark.parametrize("method,path", FACULTY_ONLY)
def test_student_calling_faculty_endpoint_is_forbidden(api, method, path):
    resp = getattr(api.client, method)(f"{P}{path}", headers=api.auth("student1"))
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "forbidden"


@pytest.mark.parametrize("method,path", STUDENT_ONLY)
def test_faculty_calling_student_endpoint_is_forbidden(api, method, path):
    resp = getattr(api.client, method)(f"{P}{path}", headers=api.auth("faculty1"))
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "forbidden"


# ---- finding 4: per-IP limit and bounded memory ------------------------------

def test_one_ip_cannot_spray_many_usernames(client):
    from app.security import LoginRateLimiter

    cap = LoginRateLimiter().ip_max_tries
    codes = [
        client.post(f"{P}/auth/login", json={"username": f"user{i}", "password": "bad"}).status_code
        for i in range(cap + 2)
    ]
    assert codes[:cap] == [401] * cap
    assert codes[cap:] == [429, 429]


def test_ip_limit_also_blocks_the_real_account_from_that_ip(client):
    from app.security import LoginRateLimiter

    for i in range(LoginRateLimiter().ip_max_tries):
        client.post(f"{P}/auth/login", json={"username": f"user{i}", "password": "bad"})
    resp = client.post(f"{P}/auth/login", json={"username": "student1", "password": PASSWORD})
    assert resp.status_code == 429 and resp.json()["error"]["code"] == "too_many_attempts"


def test_idle_keys_are_evicted(monkeypatch):
    from app import security

    now = [1000.0]
    monkeypatch.setattr(security.time, "monotonic", lambda: now[0])
    limiter = security.LoginRateLimiter()
    for i in range(500):
        limiter.check_login(f"10.0.{i // 250}.{i % 250}", f"user{i}")
    assert limiter.tracked_keys() > 500
    now[0] += limiter.window_seconds + 1
    limiter.check_login("10.9.9.9", "someone")
    assert limiter.tracked_keys() == 2  # only the fresh ip key and pair key remain


def test_tracked_keys_have_a_hard_cap(monkeypatch):
    from app import security

    now = [1000.0]
    monkeypatch.setattr(security.time, "monotonic", lambda: now[0])
    limiter = security.LoginRateLimiter(max_keys=100)
    for i in range(1000):
        now[0] += 0.001
        limiter.check_login(f"10.0.{i // 250}.{i % 250}", f"user{i}")
    assert limiter.tracked_keys() <= 100
