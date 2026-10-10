"""Print the nose offset of each photo, to calibrate the head-turn thresholds and the mirroring direction.

Usage (from backend/): python scripts/nose_offset.py straight.jpg turned_left.jpg turned_right.jpg
A photo of someone turned to THEIR left should print a positive number; if it prints negative, the phone's
photos are mirrored: set LIVENESS_MIRRORED=true.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import BACKEND_DIR  # noqa: E402
from app.face_opencv import OpenCVFaceEngine  # noqa: E402


def main(paths: list[str]) -> None:
    engine = OpenCVFaceEngine(BACKEND_DIR / "models", threshold=0.363, margin=0.1)
    for path in paths:
        analysis = engine.analyze(Path(path).read_bytes())
        shown = "no single face" if analysis.count != 1 else f"{analysis.nose_offset:+.2f}"
        print(f"{path}: {shown}")


if __name__ == "__main__":
    main(sys.argv[1:])
