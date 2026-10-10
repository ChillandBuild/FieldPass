"""One error envelope for everything: {"error": {"code", "message"}}."""
from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("fieldpass")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def invalid_credentials() -> ApiError:
    return ApiError(401, "invalid_credentials", "Wrong username or password.")


def not_authenticated() -> ApiError:
    return ApiError(401, "not_authenticated", "Please sign in again.")


def forbidden(message: str = "You do not have access to this.") -> ApiError:
    return ApiError(403, "forbidden", message)


def not_assigned() -> ApiError:
    return ApiError(403, "not_assigned", "You are not on the roster for this activity.")


def not_found(message: str = "That item does not exist.") -> ApiError:
    return ApiError(404, "not_found", message)


def enrollment_not_approved() -> ApiError:
    return ApiError(409, "enrollment_not_approved", "Your enrollment photo has not been approved yet.")


def outside_window() -> ApiError:
    return ApiError(409, "outside_activity_window", "This activity is not open for that action right now.")


def invalid_order(message: str = "That action is not available at this point.") -> ApiError:
    return ApiError(409, "invalid_action_order", message)


def checkpoint_not_open() -> ApiError:
    return ApiError(409, "checkpoint_not_open", "There is no open checkpoint for you to answer.")


def already_decided() -> ApiError:
    return ApiError(409, "already_decided", "This enrollment has already been decided.")


def attempt_expired() -> ApiError:
    return ApiError(410, "attempt_expired", "This attempt has expired. Start a new one.")


def image_too_large() -> ApiError:
    return ApiError(413, "image_too_large", "The photo is larger than 5 MB.")


def request_too_large() -> ApiError:
    """Same contract code as image_too_large (the only 413), for an oversized non-upload body."""
    return ApiError(413, "image_too_large", "The request body is too large.")


def validation_error(message: str = "Some fields are missing or invalid.") -> ApiError:
    return ApiError(422, "validation_error", message)


def unusable_capture() -> ApiError:
    return ApiError(422, "unusable_capture", "We could not find a face in that photo. Try again.")


def multiple_faces() -> ApiError:
    return ApiError(422, "multiple_faces", "More than one face was found. Only you should be in the photo.")


def location_stale() -> ApiError:
    return ApiError(422, "location_stale", "Your location reading is too old. Try again.")


def too_many_attempts() -> ApiError:
    return ApiError(429, "too_many_attempts", "Too many sign-in tries. Wait a minute and retry.")


def _envelope(status: int, code: str, message: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status, headers=headers)


# Malformed bodies (Starlette answers 400 or 415) are the contract's validation_error, which is a 422.
_AS_VALIDATION = (400, 415)
_STATUS_CODES = {401: "not_authenticated", 403: "forbidden", 404: "not_found", 405: "method_not_allowed", 413: "image_too_large"}


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api(_: Request, exc: ApiError) -> JSONResponse:
        return _envelope(exc.status, exc.code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _envelope(422, "validation_error", "Some fields are missing or invalid.")

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code in _AS_VALIDATION:
            return _envelope(422, "validation_error", "Some fields are missing or invalid.")
        code = _STATUS_CODES.get(exc.status_code, "not_found" if exc.status_code < 500 else "server_error")
        message = "That item does not exist." if exc.status_code == 404 else "Request could not be completed."
        return _envelope(exc.status_code, code, message)

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        log.exception("Unhandled error on %s %s", request.method, request.url.path)
        return _envelope(500, "server_error", "Something went wrong on the server.")
