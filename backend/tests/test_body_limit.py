"""Finding 1: request bodies are capped before auth and before parsing."""
from __future__ import annotations

import asyncio
import json

from fastapi import File, UploadFile
from fastapi.testclient import TestClient
from starlette.formparsers import MultiPartParser

from app.body_limit import MAX_JSON_BODY_BYTES, MAX_UPLOAD_BODY_BYTES, BodyLimitMiddleware
from app.deps import MAX_PHOTO_BYTES
from tests.helpers import P

MB = 1024 * 1024


def run_asgi(headers, chunks, path="/api/v1/enrollment"):
    """Drive the middleware directly. Returns (messages sent, number of receive() calls)."""
    sent, reads = [], []

    async def inner(scope, receive, send):
        while True:
            message = await receive()
            if not message.get("more_body"):
                break
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"inner ran"})

    feed = iter(chunks)

    async def receive():
        reads.append(1)
        body = next(feed)
        return {"type": "http.request", "body": body, "more_body": body is not chunks[-1]}

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "method": "POST", "path": path, "headers": headers}
    asyncio.run(BodyLimitMiddleware(inner)(scope, receive, send))
    return sent, len(reads)


def status_and_error(sent):
    start = next(m for m in sent if m["type"] == "http.response.start")
    body = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    return start["status"], json.loads(body)["error"]["code"] if start["status"] != 200 else None


def multipart_headers(length=None):
    headers = [(b"content-type", b"multipart/form-data; boundary=xx")]
    if length is not None:
        headers.append((b"content-length", str(length).encode()))
    return headers


def probe(photo: UploadFile = File(...)):
    return {"rolled": photo.file._rolled, "size": photo.size}


def test_cap_constants_are_consistent():
    assert MAX_UPLOAD_BODY_BYTES == MAX_PHOTO_BYTES + MB
    assert MAX_JSON_BODY_BYTES == 64 * 1024
    assert MultiPartParser.spool_max_size >= MAX_UPLOAD_BODY_BYTES  # no upload part can spill to the OS temp dir


def test_declared_oversize_is_rejected_before_any_byte_is_read():
    sent, reads = run_asgi(multipart_headers(50 * MB), [b"x"])
    assert status_and_error(sent) == (413, "image_too_large")
    assert reads == 0


def test_declared_oversize_json_is_rejected_before_any_byte_is_read():
    headers = [(b"content-type", b"application/json"), (b"content-length", str(MAX_JSON_BODY_BYTES + 1).encode())]
    sent, reads = run_asgi(headers, [b"{}"], path="/api/v1/auth/login")
    assert status_and_error(sent) == (413, "image_too_large") and reads == 0


def test_chunked_body_without_content_length_is_cut_off_while_streaming():
    chunks = [b"x" * MB for _ in range(100)]
    sent, reads = run_asgi(multipart_headers(), chunks)
    assert status_and_error(sent) == (413, "image_too_large")
    assert reads <= MAX_UPLOAD_BODY_BYTES // MB + 2  # stopped near the cap, did not drain 100 MB


def test_body_within_the_cap_passes_through_untouched():
    sent, _ = run_asgi(multipart_headers(2 * MB), [b"x" * MB, b"x" * MB])
    assert status_and_error(sent)[0] == 200
    assert b"inner ran" in b"".join(m.get("body", b"") for m in sent)


def test_http_oversize_upload_is_413_even_without_a_token(client):
    resp = client.post(f"{P}/enrollment", files={"photo": ("p.jpg", b"x" * (7 * MB), "image/jpeg")})
    assert resp.status_code == 413 and resp.json()["error"]["code"] == "image_too_large"


def test_http_oversize_json_is_413(client):
    resp = client.post(f"{P}/auth/login", json={"username": "a", "password": "p" * (100 * 1024)})
    assert resp.status_code == 413 and resp.json()["error"]["code"] == "image_too_large"


def test_normal_requests_still_work(client):
    assert client.post(f"{P}/auth/login", json={"username": "student1", "password": "wrong"}).status_code == 401


def test_part_just_under_the_cap_stays_in_memory(app, api):
    app.add_api_route(f"{P}/_probe", probe, methods=["POST"])
    with TestClient(app) as client:
        body = b"x" * (MAX_UPLOAD_BODY_BYTES - 4096)
        resp = client.post(f"{P}/_probe", files={"photo": ("p.jpg", body, "image/jpeg")})
    assert resp.status_code == 200 and resp.json() == {"rolled": False, "size": len(body)}
