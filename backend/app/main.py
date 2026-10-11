"""App factory. Run with: uvicorn app.main:app --host 0.0.0.0 --port 8000"""
from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from starlette.formparsers import MultiPartParser

from app.body_limit import MAX_UPLOAD_BODY_BYTES, BodyLimitMiddleware
from app.config import ConfigError, Settings, get_settings
from app.db import create_all, make_engine_for, make_session_factory
from app.errors import install_error_handlers
from app.openapi_doc import install_openapi
from app.face import FaceEngine
from app.face_opencv import OpenCVFaceEngine, models_available
from app.photo_store import PhotoStore
from app.routers import activities, attempts, attendance, auth, enrollment, review
from app.security import LoginRateLimiter

API_PREFIX = "/api/v1"
log = logging.getLogger("fieldpass")

# Starlette spills an upload part to a temp file once it passes spool_max_size (default 1 MB). BodyLimitMiddleware
# caps every request body at MAX_UPLOAD_BODY_BYTES, so no part can be bigger than that; with the spool limit at or
# above the cap, uploads (enrollment photos, attendance selfies) stay in memory and never reach the OS temp directory.
MultiPartParser.spool_max_size = MAX_UPLOAD_BODY_BYTES


def _build_engine(settings: Settings) -> FaceEngine:
    if not models_available(settings.model_dir):
        raise ConfigError(
            f"Face models not found in {settings.model_dir}. Run: python scripts/download_models.py"
        )
    return OpenCVFaceEngine(
        settings.model_dir, settings.face_match_threshold, settings.face_uncertain_margin,
        settings.face_detect_score,
    )


def create_app(
    settings: Settings | None = None, face_engine: FaceEngine | None = None, require_models: bool = True
) -> FastAPI:
    """Build the app. Raises ConfigError right away when secrets or models are missing."""
    settings = settings or get_settings()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    engine = make_engine_for(settings)
    create_all(engine)

    # Interactive docs and the openapi route are off unless ENABLE_DOCS is set. app.openapi() still works
    # (scripts/export_openapi.py uses it), because it does not need the route.
    docs = {} if settings.enable_docs else {"docs_url": None, "redoc_url": None, "openapi_url": None}
    app = FastAPI(
        title="FieldPass API", version="1", description="Field attendance backend. See contracts/API.md.", **docs
    )
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = make_session_factory(engine)
    app.state.photo_store = PhotoStore.from_settings(settings)
    app.state.rate_limiter = LoginRateLimiter()
    app.state.face_engine = face_engine or (_build_engine(settings) if require_models else None)

    app.add_middleware(BodyLimitMiddleware)  # added before CORS so its 413 still carries CORS headers
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_origins),
        allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type"],
        expose_headers=["Content-Disposition"],
    )
    install_error_handlers(app)
    install_openapi(app)
    for module in (auth, enrollment, activities, attempts, attendance, review):
        app.include_router(module.router, prefix=API_PREFIX)
    return app


def __getattr__(name: str) -> FastAPI:
    """Lazy `app` so importing this module (tests, tooling) does not require a configured .env."""
    if name == "app":
        instance = create_app()
        globals()["app"] = instance
        return instance
    raise AttributeError(name)

