from __future__ import annotations

from tests.helpers import P


def test_openapi_documents_error_envelope_and_all_paths(app):
    doc = app.openapi()
    schemas = doc["components"]["schemas"]
    assert "ErrorOut" in schemas and "HTTPValidationError" not in schemas
    assert len(doc["paths"]) == 25
    login = doc["paths"][f"{P}/auth/login"]["post"]["responses"]
    assert set(login) >= {"200", "401", "422", "429"}
    assert "401" not in doc["paths"][f"{P}/health"]["get"]["responses"]
    assert app.openapi() == doc  # cached, stable


def test_exported_yaml_matches_live_app(app):
    from pathlib import Path

    import yaml

    exported = yaml.safe_load((Path(__file__).parents[2] / "contracts" / "openapi.yaml").read_text())
    assert exported == app.openapi()
