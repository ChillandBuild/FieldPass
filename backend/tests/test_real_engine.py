"""Opt-in smoke tests for the real OpenCV engine.

Skipped unless the ONNX models are in backend/models/ (python scripts/download_models.py).
The identity test also needs git-ignored sample photos in backend/tests/samples/:
  person_a_1.jpg, person_a_2.jpg  two different photos of the SAME person (one face each)
  person_b.jpg                    a different person (one face)
"""
from __future__ import annotations

import io
from pathlib import Path

import pytest
from PIL import Image

from app.config import BACKEND_DIR
from app.face_opencv import OpenCVFaceEngine, models_available

MODELS = BACKEND_DIR / "models"
SAMPLES = Path(__file__).parent / "samples"
NEEDED = ("person_a_1.jpg", "person_a_2.jpg", "person_b.jpg")

needs_models = pytest.mark.skipif(not models_available(MODELS), reason="ONNX models not downloaded")
needs_samples = pytest.mark.skipif(
    not all((SAMPLES / n).is_file() for n in NEEDED), reason="sample photos not present in tests/samples/"
)


@pytest.fixture(scope="module")
def engine():
    return OpenCVFaceEngine(MODELS, threshold=0.363, margin=0.1)


def _jpeg(color=(255, 255, 255), size=(640, 480)) -> bytes:
    out = io.BytesIO()
    Image.new("RGB", size, color).save(out, "JPEG")
    return out.getvalue()


@needs_models
def test_real_engine_loads_and_blank_image_has_no_face(engine):
    assert engine.analyze(_jpeg()).count == 0
    assert engine.analyze(_jpeg(color=(0, 0, 0))).count == 0
    assert engine.analyze(b"definitely not an image").count == 0


@needs_models
@needs_samples
def test_real_engine_same_person_matches_and_other_does_not(engine):
    a1, a2, b = (engine.analyze((SAMPLES / n).read_bytes()) for n in NEEDED)
    assert a1.count == a2.count == b.count == 1
    assert engine.compare(a1.embedding, a2.embedding) == "match"
    assert engine.compare(a1.embedding, b.embedding) == "no_match"


@needs_models
@needs_samples
def test_real_photo_has_a_nose_offset(engine):
    analysis = engine.analyze((SAMPLES / "person_a_1.jpg").read_bytes())
    assert analysis.count == 1 and isinstance(analysis.nose_offset, float)
