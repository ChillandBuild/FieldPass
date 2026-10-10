"""OpenAPI document tweaks: every error uses the FieldPass envelope, not FastAPI's default 422 body."""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi

ERROR_DESCRIPTIONS = {
    "401": "not_authenticated or invalid_credentials",
    "403": "forbidden, not_assigned, device_mismatch, device_taken or bad_signature",
    "404": "not_found",
    "409": "enrollment_not_approved, outside_activity_window, invalid_action_order, checkpoint_not_open, already_decided",
    "410": "attempt_expired",
    "413": "image_too_large",
    "422": "validation_error, unusable_capture, multiple_faces, location_stale, device_key_required, face_not_matched",
}


def build_openapi(app: FastAPI) -> dict:
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(title=app.title, version=app.version, description=app.description, routes=app.routes)
    schemas = schema.setdefault("components", {}).setdefault("schemas", {})
    for name in ("HTTPValidationError", "ValidationError"):
        schemas.pop(name, None)
    from app.schemas import ErrorOut

    schemas["ErrorOut"] = ErrorOut.model_json_schema(ref_template="#/components/schemas/{model}")
    schemas.update(schemas["ErrorOut"].pop("$defs", {}))
    for path, methods in schema["paths"].items():
        for operation in methods.values():
            _tag_errors(operation, _error_codes(path))
    app.openapi_schema = schema
    return schema


def _error_codes(path: str) -> list[str]:
    if path.endswith("/health"):
        return []
    if path.endswith("/auth/login"):
        return ["401", "403", "422", "429"]
    return ["401", "403", "404", "409", "410", "413", "422"]


def _tag_errors(operation: dict, codes: list[str]) -> None:
    responses = operation.setdefault("responses", {})
    for code in codes:
        responses[code] = {
            "description": ERROR_DESCRIPTIONS.get(code, "too_many_attempts"),
            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ErrorOut"}}},
        }


def install_openapi(app: FastAPI) -> None:
    app.openapi = lambda: build_openapi(app)  # type: ignore[method-assign]
