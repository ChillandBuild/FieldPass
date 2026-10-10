"""Download the YuNet and SFace ONNX models from opencv_zoo into backend/models/.

opencv_zoo stores the .onnx files in Git LFS, so github.com/.../raw/... redirects to
media.githubusercontent.com, which serves the real binary. A sanity check on file size
catches the tiny LFS pointer file (about 130 bytes) if a wrong URL is ever used.

Usage (from backend/): python scripts/download_models.py
"""
from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

ZOO = "https://github.com/opencv/opencv_zoo/raw/main/models"
MODELS = {
    "face_detection_yunet_2023mar.onnx": (f"{ZOO}/face_detection_yunet/face_detection_yunet_2023mar.onnx", 100_000),
    "face_recognition_sface_2021dec.onnx": (f"{ZOO}/face_recognition_sface/face_recognition_sface_2021dec.onnx", 10_000_000),
}
MODEL_DIR = Path(__file__).resolve().parent.parent / "models"


def download(name: str, url: str, min_bytes: int) -> None:
    target = MODEL_DIR / name
    if target.is_file() and target.stat().st_size >= min_bytes:
        print(f"ok (already present): {name} {target.stat().st_size} bytes")
        return
    tmp = target.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=120) as resp, tmp.open("wb") as out:  # noqa: S310
        while chunk := resp.read(1 << 20):
            out.write(chunk)
    size = tmp.stat().st_size
    if size < min_bytes:
        tmp.unlink()
        raise SystemExit(f"{name}: only {size} bytes, expected >= {min_bytes}. Got an LFS pointer?")
    tmp.replace(target)
    print(f"downloaded: {name} {size} bytes")


def main() -> int:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    for name, (url, min_bytes) in MODELS.items():
        download(name, url, min_bytes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
