"""Deterministic fake FaceEngine: no models, no real faces.

Image bytes are plain markers:  b"FACE:alice" is one face with identity "alice";
b"FACES" is two faces; anything else is "no face".
b"FACE:alice@left" is alice turned to her own left (nose offset +0.35); "@right" is -0.35; no suffix is 0.0.
Compare: same identity -> match; "alice" vs "alice~" (same base) -> uncertain; else no_match.
"""
from __future__ import annotations

from app.face import MATCH, NO_MATCH, UNCERTAIN, FaceAnalysis


def face(identity: str) -> bytes:
    return b"FACE:" + identity.encode()


TWO_FACES = b"FACES"
BLANK = b"BLANK"
POSE_OFFSETS = {b"": 0.0, b"left": 0.35, b"right": -0.35}


class FakeFaceEngine:
    def __init__(self) -> None:
        self.analyzed: list[bytes] = []

    def analyze(self, image_bytes: bytes) -> FaceAnalysis:
        self.analyzed.append(image_bytes)
        if image_bytes.startswith(b"FACE:"):
            identity, _, pose = image_bytes[len(b"FACE:"):].partition(b"@")
            return FaceAnalysis(
                count=1, embedding=identity, normalized_jpeg=image_bytes, nose_offset=POSE_OFFSETS.get(pose, 0.0)
            )
        if image_bytes == TWO_FACES:
            return FaceAnalysis(count=2)
        return FaceAnalysis(count=0)

    def similarity(self, enrolled: bytes, probe: bytes) -> float:
        return {MATCH: 0.9, UNCERTAIN: 0.3}.get(self.compare(enrolled, probe), 0.0)

    def compare(self, enrolled: bytes, probe: bytes) -> str:
        if enrolled == probe:
            return MATCH
        if enrolled.rstrip(b"~") == probe.rstrip(b"~"):
            return UNCERTAIN
        return NO_MATCH
