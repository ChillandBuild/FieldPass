"""DATABASE_URL / DATABASE_SCHEMA settings and the Postgres engine options (no database needed)."""
from __future__ import annotations

import pytest

from app import db
from app.config import ConfigError, load_settings
from tests.test_config_hardening import GOOD

PASSWORD = "s3cr3t-pw-do-not-leak"


def load(tmp_path, **overrides):
    environ = {**GOOD, **overrides}
    return load_settings(env_file=tmp_path / "none.env", environ={k: v for k, v in environ.items() if v is not None})


def test_no_database_url_means_sqlite_with_the_default_schema(tmp_path):
    settings = load(tmp_path)
    assert settings.database_url == "" and settings.database_schema == "fieldpass"
    assert settings.database_path.name == "fieldpass.db"


@pytest.mark.parametrize("scheme", ["postgres", "postgresql", "postgresql+psycopg"])
def test_postgres_schemes_are_normalised_to_the_psycopg_driver(tmp_path, scheme):
    settings = load(tmp_path, DATABASE_URL=f"{scheme}://postgres.abc:{PASSWORD}@aws-0.pooler.supabase.com:5432/postgres")
    assert settings.database_url == (
        f"postgresql+psycopg://postgres.abc:{PASSWORD}@aws-0.pooler.supabase.com:5432/postgres"
    )


@pytest.mark.parametrize("raw", [
    f"mysql://u:{PASSWORD}@h/d",
    f"postgresql+psycopg2://u:{PASSWORD}@h/d",
    f"sqlite:///{PASSWORD}.db",
    f"u:{PASSWORD}@h/d",
    f"postgresql://u:{PASSWORD}@/d",
    f"postgresql://u:{PASSWORD}@h:notaport/d",
    f"postgresql://u:{PASSWORD}@h",
])
def test_bad_database_urls_are_rejected_without_echoing_the_password(tmp_path, raw):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, DATABASE_URL=raw)
    assert "DATABASE_URL" in str(info.value) and PASSWORD not in str(info.value)


def test_settings_repr_does_not_show_the_database_url(tmp_path):
    settings = load(tmp_path, DATABASE_URL=f"postgresql://u:{PASSWORD}@h:5432/d")
    assert PASSWORD not in repr(settings)


@pytest.mark.parametrize("schema", ["Field", "1abc", "a-b", "a b", 'a"; drop', "x" * 64, "public.x"])
def test_unsafe_schema_names_are_rejected(tmp_path, schema):
    with pytest.raises(ConfigError) as info:
        load(tmp_path, DATABASE_SCHEMA=schema)
    assert "DATABASE_SCHEMA" in str(info.value)


def test_custom_schema_is_accepted(tmp_path):
    assert load(tmp_path, DATABASE_SCHEMA="field_pass_2").database_schema == "field_pass_2"


@pytest.fixture()
def captured(monkeypatch):
    """Record the kwargs make_postgres_engine hands to create_engine (building an engine does not connect)."""
    seen: dict = {}
    real = db.create_engine

    def spy(url, **kwargs):
        seen.update(kwargs)
        return real(url, **kwargs)

    monkeypatch.setattr(db, "create_engine", spy)
    return seen


def test_remote_host_requires_ssl_and_the_pool_is_small(captured):
    engine = db.make_postgres_engine(f"postgresql+psycopg://u:{PASSWORD}@aws-0.pooler.supabase.com:5432/postgres", "fieldpass")
    assert captured["connect_args"] == {"connect_timeout": 10, "sslmode": "require"}
    assert captured["pool_pre_ping"] is True and captured["pool_size"] == 5 and captured["max_overflow"] == 5
    assert engine.get_execution_options()["schema_translate_map"] == {None: "fieldpass"}
    assert PASSWORD not in repr(engine)


@pytest.mark.parametrize("host", ["localhost", "127.0.0.1", "[::1]"])
def test_local_hosts_do_not_force_ssl(captured, host):
    db.make_postgres_engine(f"postgresql+psycopg://u:p@{host}:5432/postgres", "fieldpass")
    assert "sslmode" not in captured["connect_args"]


def test_an_explicit_sslmode_in_the_url_is_kept(captured):
    db.make_postgres_engine("postgresql+psycopg://u:p@db.example.com/postgres?sslmode=verify-full", "fieldpass")
    assert "sslmode" not in captured["connect_args"]  # the URL's own value wins
