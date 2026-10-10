"""Export the OpenAPI document to contracts/openapi.yaml.

Usage (from backend/): python scripts/export_openapi.py
No secrets, database or models are needed: the app is built with throwaway settings.
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.main import create_app  # noqa: E402
from scripts.fixture_env import throwaway_settings  # noqa: E402

TARGET = Path(__file__).resolve().parent.parent.parent / "contracts" / "openapi.yaml"


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(throwaway_settings(Path(tmp)), require_models=False)
        document = app.openapi()
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(yaml.safe_dump(document, sort_keys=False, allow_unicode=True), encoding="utf-8")
    print(f"wrote {TARGET} ({len(document['paths'])} paths)")


if __name__ == "__main__":
    main()
