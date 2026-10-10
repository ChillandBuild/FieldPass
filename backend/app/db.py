"""Engine and session handling (SQLAlchemy 2.x, synchronous): SQLite by default, Postgres when DATABASE_URL is set."""
from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path
from typing import TYPE_CHECKING

from fastapi import Request
from datetime import datetime, timezone

from sqlalchemy import Connection, DateTime, Engine, create_engine, event, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.types import TypeDecorator

if TYPE_CHECKING:
    from app.config import Settings

LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1")
POSTGRES_CONNECT_TIMEOUT_S = 10
POSTGRES_POOL_SIZE = 5
POSTGRES_MAX_OVERFLOW = 5
INIT_LOCK_KEY = 7_203_911_042  # arbitrary; serialises schema setup when several workers start together
DB_ROLES_WITHOUT_ACCESS = ("anon", "authenticated")  # Supabase API roles; they must never read these tables


class Base(DeclarativeBase):
    pass


class UTCDateTime(TypeDecorator):
    """Stores UTC; always returns timezone-aware datetimes (SQLite drops tzinfo)."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect):  # noqa: ANN001
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("naive datetime not allowed")
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    def process_result_value(self, value: datetime | None, dialect):  # noqa: ANN001
        return None if value is None else value.replace(tzinfo=timezone.utc)


def make_engine(database_path: Path | str) -> Engine:
    """Create an engine; ':memory:' is not supported (threads need a shared file)."""
    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(
        f"sqlite:///{path}",
        connect_args={"check_same_thread": False, "timeout": 15},
    )

    @event.listens_for(engine, "connect")
    def _pragmas(dbapi_conn, _record) -> None:  # noqa: ANN001
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.execute("PRAGMA busy_timeout=15000")
        cur.close()

    return engine


def _is_local(host: str | None) -> bool:
    return not host or host in LOCAL_HOSTS or host.startswith("/")  # empty or a path means a unix socket


def make_postgres_engine(url: str, schema: str) -> Engine:
    """Pooled Postgres engine whose tables live in `schema` (the None schema is translated at execution time).

    Meant for Supabase's session pooler (port 5432), so nothing relies on server-side startup options.
    """
    parsed = make_url(url)
    connect_args: dict[str, object] = {"connect_timeout": POSTGRES_CONNECT_TIMEOUT_S}
    if "sslmode" not in parsed.query and not _is_local(parsed.host):
        connect_args["sslmode"] = "require"
    engine = create_engine(
        parsed, pool_pre_ping=True, pool_size=POSTGRES_POOL_SIZE, max_overflow=POSTGRES_MAX_OVERFLOW,
        connect_args=connect_args,
    )
    return engine.execution_options(schema_translate_map={None: schema})


def make_engine_for(settings: Settings) -> Engine:
    """SQLite at settings.database_path, or Postgres when settings.database_url is set."""
    if settings.database_url:
        return make_postgres_engine(settings.database_url, settings.database_schema)
    return make_engine(settings.database_path)


def _quote(conn: Connection, name: str) -> str:
    return conn.dialect.identifier_preparer.quote_identifier(name)


def _lock_down_schema(conn: Connection, schema: str) -> None:
    """Row level security on every table with no policies, and no schema access for anyone but the owner.

    The backend connects as the table owner, which bypasses RLS. Supabase's API roles get nothing.
    """
    quoted = _quote(conn, schema)
    tables = conn.execute(text("SELECT tablename FROM pg_tables WHERE schemaname = :s"), {"s": schema}).scalars().all()
    for table in tables:
        conn.execute(text(f"ALTER TABLE {quoted}.{_quote(conn, table)} ENABLE ROW LEVEL SECURITY"))
    conn.execute(text(f"REVOKE ALL ON SCHEMA {quoted} FROM PUBLIC"))
    roles = conn.execute(
        text("SELECT rolname FROM pg_roles WHERE rolname = ANY(:names)"), {"names": list(DB_ROLES_WITHOUT_ACCESS)}
    ).scalars().all()
    for role in roles:
        who = _quote(conn, role)
        for kind in ("SCHEMA", "ALL TABLES IN SCHEMA", "ALL SEQUENCES IN SCHEMA"):
            conn.execute(text(f"REVOKE ALL ON {kind} {quoted} FROM {who}"))


def _init_postgres(engine: Engine) -> None:
    """Idempotent: create the schema and tables, then lock them down. One transaction, one worker at a time."""
    schema = engine.get_execution_options()["schema_translate_map"][None]
    with engine.begin() as conn:
        conn.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": INIT_LOCK_KEY})
        conn.execute(text(f"CREATE SCHEMA IF NOT EXISTS {_quote(conn, schema)}"))
        Base.metadata.create_all(conn)
        _lock_down_schema(conn, schema)


def create_all(engine: Engine) -> None:
    from app import models  # noqa: F401  (register tables)

    if engine.dialect.name == "postgresql":
        _init_postgres(engine)
    else:
        Base.metadata.create_all(engine)


def make_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)


def get_session(request: Request) -> Iterator[Session]:
    """FastAPI dependency: one session per request, from app.state."""
    session = request.app.state.session_factory()
    try:
        yield session
    finally:
        session.close()
