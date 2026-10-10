"""Pure location functions."""
from __future__ import annotations

import math

EARTH_RADIUS_M = 6_371_000.0


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Great-circle distance in metres."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = p2 - p1
    dlmb = math.radians(lng2 - lng1)
    h = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def location_verdict(distance_m: float, accuracy_m: float, radius_m: float) -> str:
    """API.md rule: d + a <= r inside; d - a > r outside; else uncertain."""
    if distance_m + accuracy_m <= radius_m:
        return "inside"
    if distance_m - accuracy_m > radius_m:
        return "outside"
    return "uncertain"


def regions_verdict(lat: float, lng: float, accuracy_m: float, regions: list[tuple[float, float, int]]) -> tuple[str, float]:
    """Accept any circle; report distance to the circle with the greatest boundary clearance."""
    distances = [(haversine_m(lat, lng, rlat, rlng), radius) for rlat, rlng, radius in regions]
    distance, radius = min(distances, key=lambda item: item[0] - item[1])
    return location_verdict(distance, accuracy_m, radius), distance
