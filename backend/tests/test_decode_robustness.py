"""Finding 9: any failure while decoding an upload is 'no usable face', never a 500."""
from __future__ import annotations

import io
import logging

import pytest
from PIL import Image, ImageOps

from app import face_opencv
from app.face_opencv import decode_upright


def jpeg() -> bytes:
    out = io.BytesIO()
    Image.new("RGB", (64, 48), (200, 10, 10)).save(out, "JPEG")
    return out.getvalue()


def test_valid_jpeg_still_decodes():
    assert decode_upright(jpeg()).size == (64, 48)


@pytest.mark.parametrize("boom", [KeyError("k"), TypeError("t"), SyntaxError("s"), ZeroDivisionError(), RuntimeError("r")])
def test_unexpected_exif_failure_returns_none(monkeypatch, boom):
    def explode(_img):
        raise boom

    monkeypatch.setattr(ImageOps, "exif_transpose", explode)
    assert decode_upright(jpeg()) is None


def test_unexpected_open_failure_returns_none(monkeypatch):
    def explode(*_a, **_k):
        raise RuntimeError("decoder bug")

    monkeypatch.setattr(Image, "open", explode)
    assert decode_upright(jpeg()) is None


def test_failure_log_names_only_the_exception_type(monkeypatch, caplog):
    def explode(_img):
        raise KeyError("secret-looking-detail")

    monkeypatch.setattr(ImageOps, "exif_transpose", explode)
    with caplog.at_level(logging.INFO, logger="fieldpass"):
        decode_upright(jpeg())
    assert "KeyError" in caplog.text and "secret-looking-detail" not in caplog.text


def test_truncated_jpeg_returns_none():
    assert decode_upright(jpeg()[:60]) is None
    assert face_opencv.decode_upright(b"") is None
