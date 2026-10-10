"""Cap request bodies before anything parses them (and before auth runs).

Uploads (multipart) may be a photo plus a little form overhead; every other body is small JSON.
Content-Length is checked first, so an honest oversized request is refused without reading a byte.
Bodies with no Content-Length (chunked) are counted as they stream and cut off at the cap.
"""
from __future__ import annotations

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app import errors
from app.deps import MAX_PHOTO_BYTES

MULTIPART_OVERHEAD_BYTES = 1024 * 1024
MAX_UPLOAD_BODY_BYTES = MAX_PHOTO_BYTES + MULTIPART_OVERHEAD_BYTES
MAX_JSON_BODY_BYTES = 64 * 1024


class _TooLarge(Exception):
    """Raised inside receive() to stop the app from reading more."""


class BodyLimitMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = Headers(scope=scope)
        is_upload = headers.get("content-type", "").lower().startswith("multipart/")
        limit = MAX_UPLOAD_BODY_BYTES if is_upload else MAX_JSON_BODY_BYTES
        declared = headers.get("content-length", "")
        if declared.isdigit() and int(declared) > limit:
            await self._refuse(scope, send, is_upload)
            return

        seen = 0
        exceeded = replied = False

        async def counting_receive() -> Message:
            nonlocal seen, exceeded
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > limit:
                    exceeded = True
                    raise _TooLarge
            return message

        async def guarded_send(message: Message) -> None:
            nonlocal replied
            if not exceeded:
                await send(message)
            elif not replied:  # whatever the app tried to answer (often a 400), the real reason is the size
                replied = True
                await self._refuse(scope, send, is_upload)

        try:
            await self.app(scope, counting_receive, guarded_send)
        except _TooLarge:
            pass
        if exceeded and not replied:
            await self._refuse(scope, send, is_upload)

    @staticmethod
    async def _refuse(scope: Scope, send: Send, is_upload: bool) -> None:
        error = errors.image_too_large() if is_upload else errors.request_too_large()
        body = {"error": {"code": error.code, "message": error.message}}

        async def no_body() -> Message:
            return {"type": "http.disconnect"}

        await JSONResponse(body, status_code=error.status, headers={"Connection": "close"})(scope, no_body, send)
