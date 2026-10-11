from __future__ import annotations

import logging

import pytest
from fastapi.testclient import TestClient

from app import main as main_module
from app.config import ConfigError
from app.main import create_app
from tests.helpers import P, new_device_key


def assert_envelope(resp, status, code):
    body = resp.json()
    assert resp.status_code == status
    assert set(body) == {"error"} and set(body["error"]) == {"code", "message"}
    assert body["error"]["code"] == code and body["error"]["message"]


def test_unknown_route_uses_envelope(client):
    assert_envelope(client.get(f"{P}/nope"), 404, "not_found")
    assert_envelope(client.get("/"), 404, "not_found")


def test_wrong_method_uses_envelope(client):
    assert_envelope(client.delete(f"{P}/health"), 405, "method_not_allowed")


def test_validation_error_hides_internals(client):
    resp = client.post(f"{P}/auth/login", content=b"{not json", headers={"content-type": "application/json"})
    assert_envelope(resp, 422, "validation_error")
    assert "loc" not in resp.text and "pydantic" not in resp.text.lower()


def test_unhandled_exception_is_generic_and_logged(app, caplog):
    def boom():
        raise RuntimeError("secret internal detail: db password=hunter2")

    app.add_api_route("/api/v1/_boom", boom)
    with TestClient(app, raise_server_exceptions=False) as client:
        with caplog.at_level(logging.ERROR, logger="fieldpass"):
            resp = client.get("/api/v1/_boom")
    assert_envelope(resp, 500, "server_error")
    assert "hunter2" not in resp.text and "RuntimeError" not in resp.text
    assert "hunter2" in caplog.text  # details stay server-side


def test_unauthenticated_requests_get_envelope_everywhere(client):
    for path in ("/students", "/activities", "/attendance/me", "/enrollment/me"):
        assert_envelope(client.get(f"{P}{path}"), 401, "not_authenticated")


def test_cors_allows_configured_origin_only(client):
    ok = client.options(f"{P}/auth/login", headers={
        "Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "authorization,content-type"})
    assert ok.headers.get("access-control-allow-origin") == "http://localhost:5173"
    bad = client.options(f"{P}/auth/login", headers={
        "Origin": "http://evil.example", "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in bad.headers


def test_logs_never_contain_passwords_or_tokens(client, caplog):
    with caplog.at_level(logging.DEBUG):
        body = client.post(f"{P}/auth/login", json={"username": "student1", "password": "test-seed-password",
                                                          "device_public_key": new_device_key()[1]}).json()
        client.get(f"{P}/auth/me", headers={"Authorization": f"Bearer {body['access_token']}"})
        client.post(f"{P}/auth/login", json={"username": "student1", "password": "wrong-password-xyz"})
    assert "test-seed-password" not in caplog.text and "wrong-password-xyz" not in caplog.text
    assert body["access_token"] not in caplog.text


def test_create_app_requires_models_unless_overridden(settings):
    with pytest.raises(ConfigError) as info:
        create_app(settings)
    assert "download_models.py" in str(info.value)


def test_engine_missing_raises_clear_runtime_error(settings):
    application = create_app(settings, require_models=False)
    from app.deps import get_face_engine
    from fastapi import Request

    request = Request({"type": "http", "app": application, "headers": []})
    with pytest.raises(RuntimeError) as info:
        get_face_engine(request)
    assert "download_models.py" in str(info.value)


def test_lazy_app_attribute_unknown_name():
    with pytest.raises(AttributeError):
        main_module.__getattr__("nonsense")


def test_lazy_app_builds_from_environment(settings, monkeypatch):
    monkeypatch.setattr(main_module, "create_app", lambda: "built")
    monkeypatch.delitem(main_module.__dict__, "app", raising=False)
    assert main_module.__getattr__("app") == "built"
    monkeypatch.delitem(main_module.__dict__, "app", raising=False)
