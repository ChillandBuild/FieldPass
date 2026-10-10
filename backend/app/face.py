"""Face engine interface and the threshold-to-verdict mapping.

The engine detects faces and produces opaque embedding bytes, and compares two
embeddings. Numeric scores never leave this layer: callers only see a verdict.
No liveness detection exists here, and none is claimed.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

MATCH = "match"
UNCERTAIN = "uncertain"
NO_MATCH = "no_match"


@dataclass(frozen=True)
class FaceAnalysis:
    """Result of looking at one uploaded image.

    count: number of usable faces (0 also covers unreadable images).
    embedding: opaque bytes, set only when count == 1.
    normalized_jpeg: upright (EXIF applied), downscaled, metadata-free JPEG; set when count == 1.
    """

    count: int
    embedding: bytes | None = None
    normalized_jpeg: bytes | None = None


class FaceEngine(Protocol):
    def analyze(self, image_bytes: bytes) -> FaceAnalysis:
        """Detect faces in an encoded image. Never raises on bad input; returns count 0."""
        ...

    def compare(self, enrolled: bytes, probe: bytes) -> str:
        """Return "match", "uncertain" or "no_match" for two embeddings."""
        ...


def verdict_from_score(score: float, threshold: float, margin: float) -> str:
    """score >= threshold: match; within `margin` below it: uncertain; lower: no_match."""
    if score >= threshold:
        return MATCH
    if score >= threshold - margin:
        return UNCERTAIN
    return NO_MATCH
