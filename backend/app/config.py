"""Environment-driven settings. Fails fast when a required secret is missing."""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from cryptography.fernet import Fernet

BACKEND_DIR = Path(__file__).resolve().parent.parent

# SFace cosine-similarity threshold from the official OpenCV sample
# https://github.com/opencv/opencv/blob/4.x/samples/dnn/face_detect.cpp
# (`double cosine_similar_thresh = 0.363;`), also used by opencv_zoo's sface.py.
DEFAULT_FACE_MATCH_THRESHOLD = 0.363
DEFAULT_FACE_UNCERTAIN_MARGIN = 0.1

REQUIRED_SECRETS = ("JWT_SECRET", "PHOTO_ENCRYPTION_KEY", "SEED_PASSWORD", "FACULTY_SEED_PASSWORD")
MIN_JWT_SECRET_CHARS = 32
TRUE_WORDS = ("1", "true", "yes", "on")


class ConfigError(RuntimeError):
    """Raised at startup when configuration is missing or invalid."""


@dataclass(frozen=True)
class Settings:
    jwt_secret: str
    photo_encryption_key: str
    seed_password: str
    faculty_seed_password: str
    database_path: Path
    photo_dir: Path
    model_dir: Path
    cors_origins: tuple[str, ...]
    face_match_threshold: float
    face_uncertain_margin: float
    attempt_ttl_seconds: int
    location_max_age_seconds: int
    token_ttl_minutes: int
    enable_docs: bool = False


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _resolve(path_text: str) -> Path:
    path = Path(path_text)
    return path if path.is_absolute() else BACKEND_DIR / path


def _number(env: dict[str, str], key: str, default: float, cast: type) -> float:
    raw = env.get(key, "")
    if raw == "":
        return default
    try:
        return cast(raw)
    except ValueError as exc:
        raise ConfigError(f"{key} must be a number, got {raw!r}") from exc


def _check_secrets(env: dict[str, str]) -> None:
    if len(env["JWT_SECRET"]) < MIN_JWT_SECRET_CHARS:
        raise ConfigError(
            f"JWT_SECRET must be at least {MIN_JWT_SECRET_CHARS} characters. Run `python -m app.gen_secrets`."
        )
    try:
        Fernet(env["PHOTO_ENCRYPTION_KEY"].encode())
    except ValueError as exc:
        raise ConfigError(
            "PHOTO_ENCRYPTION_KEY is not a valid Fernet key (32 url-safe base64 bytes). "
            "Run `python -m app.gen_secrets` for a new one."
        ) from exc


def load_settings(env_file: Path | None = None, environ: dict[str, str] | None = None) -> Settings:
    """Build settings from backend/.env overlaid by real environment variables."""
    env = _read_env_file(env_file or BACKEND_DIR / ".env")
    env.update(environ if environ is not None else os.environ)

    missing = [key for key in REQUIRED_SECRETS if not env.get(key)]
    if missing:
        raise ConfigError(
            "Missing required setting(s): " + ", ".join(missing)
            + ". Copy backend/.env.example to backend/.env and run `python -m app.gen_secrets`."
        )
    _check_secrets(env)
    origins = tuple(o.strip() for o in env.get("CORS_ORIGINS", "").split(",") if o.strip())
    return Settings(
        jwt_secret=env["JWT_SECRET"],
        photo_encryption_key=env["PHOTO_ENCRYPTION_KEY"],
        seed_password=env["SEED_PASSWORD"],
        faculty_seed_password=env["FACULTY_SEED_PASSWORD"],
        database_path=_resolve(env.get("DATABASE_PATH") or "data/fieldpass.db"),
        photo_dir=_resolve(env.get("PHOTO_DIR") or "data/photos"),
        model_dir=_resolve(env.get("MODEL_DIR") or "models"),
        cors_origins=origins,
        face_match_threshold=float(_number(env, "FACE_MATCH_THRESHOLD", DEFAULT_FACE_MATCH_THRESHOLD, float)),
        face_uncertain_margin=float(_number(env, "FACE_UNCERTAIN_MARGIN", DEFAULT_FACE_UNCERTAIN_MARGIN, float)),
        attempt_ttl_seconds=int(_number(env, "ATTEMPT_TTL_SECONDS", 90, int)),
        location_max_age_seconds=int(_number(env, "LOCATION_MAX_AGE_SECONDS", 60, int)),
        token_ttl_minutes=int(_number(env, "TOKEN_TTL_MINUTES", 480, int)),
        enable_docs=env.get("ENABLE_DOCS", "").strip().lower() in TRUE_WORDS,
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return load_settings()
