"""Findings 10 and 11: error labels for Starlette 400/415, nosniff header, docs off by default."""
from __future__ import annotations

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.deps import get_face_engine
from app.main import create_app
from tests.fakes import face
from tests.helpers import P


def envelope(resp):
    body = resp.json()
    assert set(body) == {"error"} and set(body["error"]) == {"code", "message"}
    return body["error"]["code"]


def test_malformed_multipart_is_validation_error_not_not_found(api, client):
    resp = client.post(f"{P}/enrollment", headers={**api.auth("student1"), "Content-Type": "multipart/form-data"},
                       content=b"garbage")
    assert (resp.status_code, envelope(resp)) == (422, "validation_error")


def test_unsupported_media_type_is_validation_error(app):
    def odd():
        raise HTTPException(status_code=415)

    app.add_api_route(f"{P}/_odd", odd)
    with TestClient(app, raise_server_exceptions=False) as client:
        resp = client.get(f"{P}/_odd")
    assert (resp.status_code, envelope(resp)) == (422, "validation_error")


def test_real_404_and_405_keep_their_codes(client):
    assert envelope(client.get(f"{P}/nope")) == "not_found"
    assert envelope(client.delete(f"{P}/health")) == "method_not_allowed"


def test_photo_response_has_nosniff(api):
    created = api.post("student1", "/enrollment", files={"photo": ("p.jpg", face("asha"), "image/jpeg")}).json()
    resp = api.get("faculty1", f"/enrollments/{created['id']}/photo")
    assert resp.status_code == 200
    assert resp.headers["x-content-type-options"] == "nosniff"


def test_docs_and_openapi_routes_are_off_by_default(client):
    for path in ("/docs", "/redoc", "/openapi.json"):
        resp = client.get(path)
        assert (resp.status_code, envelope(resp)) == (404, "not_found")


def test_docs_can_be_enabled_by_setting(settings, fake_engine):
    enabled = type(settings)(**{**settings.__dict__, "enable_docs": True})
    application = create_app(enabled, require_models=False)
    application.dependency_overrides[get_face_engine] = lambda: fake_engine
    with TestClient(application) as client:
        assert client.get("/docs").status_code == 200
        assert client.get("/openapi.json").status_code == 200


def test_openapi_document_is_still_buildable_without_the_route(app):
    assert len(app.openapi()["paths"]) == 21
