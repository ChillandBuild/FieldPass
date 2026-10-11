"""Head-turn check on the second photo. The phone checks the blink; the server checks the turn.

nose offset = (nose x - eye midpoint x) / eye distance, in picture coordinates. In an unmirrored front-camera
photo the student's own left is the picture's right, so "left" means a positive offset.
"""
from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

from app.config import Settings

# YuNet row layout: bbox 0-3, right eye 4-5, left eye 6-7, nose 8-9, mouth corners 10-13, score 14.
EYE_A, EYE_B, NOSE_X = (4, 5), (6, 7), 8
MIN_EYE_DISTANCE_PX = 1.0


@dataclass(frozen=True)
class LivenessLimits:
    front_max_offset: float
    turn_min_offset: float
    mirrored: bool


def limits_from(settings: Settings) -> LivenessLimits:
    return LivenessLimits(
        settings.liveness_front_max_offset, settings.liveness_turn_min_offset, settings.liveness_mirrored
    )


def nose_offset_from_landmarks(row: Sequence[float]) -> float | None:
    ax, ay = float(row[EYE_A[0]]), float(row[EYE_A[1]])
    bx, by = float(row[EYE_B[0]]), float(row[EYE_B[1]])
    eye_distance = math.hypot(bx - ax, by - ay)
    if eye_distance < MIN_EYE_DISTANCE_PX:
        return None
    return (float(row[NOSE_X]) - (ax + bx) / 2) / eye_distance


def head_turn_ok(front: float | None, turned: float | None, challenge: str, limits: LivenessLimits) -> bool:
    if front is None or turned is None or abs(front) > limits.front_max_offset:
        return False
    direction = 1.0 if challenge == "left" else -1.0
    if limits.mirrored:
        direction = -direction
    return direction * turned >= limits.turn_min_offset
