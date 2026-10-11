"""Real FaceEngine: OpenCV YuNet (detection) + SFace (recognition).

Models come from opencv_zoo (see scripts/download_models.py). The default cosine threshold
0.363 comes from the official OpenCV sample:
https://github.com/opencv/opencv/blob/4.x/samples/dnn/face_detect.cpp
"""
from __future__ import annotations

import io
import logging
import threading
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from app.face import FaceAnalysis, verdict_from_score
from app.liveness import nose_offset_from_landmarks

log = logging.getLogger("fieldpass")
DETECTOR_FILE = "face_detection_yunet_2023mar.onnx"
RECOGNIZER_FILE = "face_recognition_sface_2021dec.onnx"
MAX_SIDE_PX = 1280
MAX_PIXELS = 40_000_000
MIN_FACE_PX = 48
DETECT_SCORE = 0.9


def models_available(model_dir: Path) -> bool:
    return (model_dir / DETECTOR_FILE).is_file() and (model_dir / RECOGNIZER_FILE).is_file()


def decode_upright(image_bytes: bytes) -> Image.Image | None:
    """Decode a JPEG, apply EXIF orientation, downscale. None if unreadable for any reason."""
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        img = Image.open(io.BytesIO(image_bytes))
        if img.format != "JPEG":
            return None
        img.draft("RGB", (MAX_SIDE_PX, MAX_SIDE_PX))
        img = ImageOps.exif_transpose(img).convert("RGB")
        img.thumbnail((MAX_SIDE_PX, MAX_SIDE_PX))
    except UnidentifiedImageError:
        return None
    except Exception as exc:  # noqa: BLE001 - hostile input must never become a 500; Pillow raises many types
        log.info("Upload could not be decoded (%s)", type(exc).__name__)  # type only: no content, no message
        return None
    return img


def _to_jpeg(img: Image.Image) -> bytes:
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=90)  # no exif passed: metadata is dropped
    return out.getvalue()


class OpenCVFaceEngine:
    def __init__(self, model_dir: Path, threshold: float, margin: float, detect_score: float = DETECT_SCORE) -> None:
        self._threshold = threshold
        self._margin = margin
        self._lock = threading.Lock()  # cv2.dnn nets are not thread-safe
        self._detector = cv2.FaceDetectorYN.create(
            str(model_dir / DETECTOR_FILE), "", (320, 320), detect_score, 0.3, 50
        )
        self._recognizer = cv2.FaceRecognizerSF.create(str(model_dir / RECOGNIZER_FILE), "")

    def _detect(self, bgr: np.ndarray) -> np.ndarray:
        height, width = bgr.shape[:2]
        self._detector.setInputSize((width, height))
        _, faces = self._detector.detect(bgr)
        if faces is None:
            return np.empty((0, 15), dtype=np.float32)
        usable = [f for f in faces if min(f[2], f[3]) >= MIN_FACE_PX]
        return np.array(usable, dtype=np.float32).reshape(-1, 15)

    def analyze(self, image_bytes: bytes) -> FaceAnalysis:
        img = decode_upright(image_bytes)
        if img is None:
            return FaceAnalysis(count=0)
        bgr = cv2.cvtColor(np.asarray(img), cv2.COLOR_RGB2BGR)
        with self._lock:
            faces = self._detect(bgr)
            if len(faces) != 1:
                return FaceAnalysis(count=len(faces))
            aligned = self._recognizer.alignCrop(bgr, faces[0])
            feature = self._recognizer.feature(aligned)
            offset = nose_offset_from_landmarks(faces[0])
        embedding = np.asarray(feature, dtype=np.float32).reshape(1, -1).tobytes()
        return FaceAnalysis(count=1, embedding=embedding, normalized_jpeg=_to_jpeg(img), nose_offset=offset)

    def compare(self, enrolled: bytes, probe: bytes) -> str:
        a = np.frombuffer(enrolled, dtype=np.float32).reshape(1, -1).copy()
        b = np.frombuffer(probe, dtype=np.float32).reshape(1, -1).copy()
        with self._lock:
            score = float(self._recognizer.match(a, b, cv2.FaceRecognizerSF_FR_COSINE))
        return verdict_from_score(score, self._threshold, self._margin)
