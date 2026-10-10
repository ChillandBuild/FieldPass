"""Postgres-only behaviour. Runs when TEST_DATABASE_URL points at a throwaway Postgres; skipped otherwise."""
from __future__ import annotations

import threading
import time
from datetime import timedelta

import pytest
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock
from app.config import parse_database_url
from app.db import Base, create_all, make_engine_for
from app.errors import ApiError
from app.models import Attempt, AttendanceEvent, Enrollment, User, new_id
from app.routers import attempts as attempts_module
from app.routers import enrollment as enrollment_module
from tests.conftest import postgres_test_url
from tests.fakes import face

pytestmark = pytest.mark.skipif(not postgres_test_url(), reason="needs TEST_DATABASE_URL (a throwaway Postgres)")

LOCK_WAIT_SECONDS = 10


@pytest.fixture()
def admin(settings):
    """A plain connection to the same database (no schema translation) for catalog queries."""
    engine = create_engine(parse_database_url(settings.database_url))
    with engine.connect() as conn:
        yield conn
    engine.dispose()


def scalars(admin, sql: str, **params) -> list:
    return list(admin.execute(text(sql), params).scalars())


# ---- partial unique indexes ---------------------------------------------------

def build_event(attempt_id, activity, student="u_s1", action="check_in", checkpoint_id=None) -> AttendanceEvent:
    now = clock.now()
    return AttendanceEvent(
        id=new_id("evt"), attempt_id=attempt_id, activity_id=activity, student_id=student, action=action,
        status="recorded", received_at=now, checkpoint_id=checkpoint_id, location_verdict="inside", distance_m=1,
        accuracy_m=5, captured_at=now, face_verdict="match",
    )


def make_event(session, student="u_s1", activity="act1", action="check_in", checkpoint_id=None) -> AttendanceEvent:
    """An event with its own fresh attempt row (events need a unique attempt)."""
    now = clock.now()
    attempt = Attempt(
        id=new_id("att"), student_id=student, activity_id=activity, action=action, checkpoint_id=None,
        created_at=now, expires_at=now + timedelta(seconds=90),
    )
    session.add(attempt)
    session.flush()
    return build_event(attempt.id, activity, student, action, checkpoint_id)


@pytest.fixture()
def activity_id(api, test_clock) -> str:
    return api.make_activity()["id"]


def add_event(app, activity_id, **kw) -> None:
    with app.state.session_factory() as session:
        session.add(make_event(session, activity=activity_id, **kw))
        session.commit()


@pytest.mark.parametrize("action", ["check_in", "check_out"])
def test_second_check_in_or_check_out_for_the_same_student_is_rejected(app, activity_id, action):
    add_event(app, activity_id, action=action)
    with pytest.raises(IntegrityError):
        add_event(app, activity_id, action=action)
    add_event(app, activity_id, action=action, student="u_s2")  # another student is unaffected


def test_checkpoints_in_different_windows_are_allowed_but_not_twice_in_one(app, activity_id):
    add_event(app, activity_id, action="checkpoint", checkpoint_id="win_a")
    add_event(app, activity_id, action="checkpoint", checkpoint_id="win_b")
    with pytest.raises(IntegrityError):
        add_event(app, activity_id, action="checkpoint", checkpoint_id="win_a")


def test_the_indexes_are_partial_on_postgres(app, admin, settings):
    rows = admin.execute(text(
        "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = :s AND indexname LIKE 'uq_one_%'"
    ), {"s": settings.database_schema}).all()
    defs = {name: sql for name, sql in rows}
    assert "WHERE" in defs["uq_one_checkin_checkout"] and "check_out" in defs["uq_one_checkin_checkout"]
    assert "WHERE" in defs["uq_one_answer_per_window"] and "checkpoint" in defs["uq_one_answer_per_window"]


# ---- row level security -------------------------------------------------------

def test_every_table_in_the_schema_has_rls_enabled_and_no_policies(app, admin, settings):
    rows = admin.execute(text(
        "SELECT c.relname, c.relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
        "WHERE n.nspname = :s AND c.relkind = 'r'"
    ), {"s": settings.database_schema}).all()
    assert {name for name, _ in rows} == set(Base.metadata.tables)
    assert all(enabled for _, enabled in rows)
    assert scalars(admin, "SELECT policyname FROM pg_policies WHERE schemaname = :s", s=settings.database_schema) == []


def test_public_has_no_access_to_the_schema(app, admin, settings):
    grantees = scalars(
        admin, "SELECT (aclexplode(nspacl)).grantee FROM pg_namespace WHERE nspname = :s", s=settings.database_schema
    )
    assert 0 not in grantees  # grantee 0 is PUBLIC


def test_init_is_idempotent(app, settings):
    create_all(make_engine_for(settings))
    create_all(app.state.engine)


def test_supabase_api_roles_get_no_access(settings, admin):
    """Simulate Supabase: anon is granted new tables by default privileges; init must take that away."""
    admin.execute(text("COMMIT"))
    admin.execute(text("DROP ROLE IF EXISTS anon"))
    admin.execute(text("CREATE ROLE anon NOLOGIN"))
    admin.execute(text("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon"))
    admin.execute(text("COMMIT"))
    try:
        engine = make_engine_for(settings)
        create_all(engine)
        table = f'"{settings.database_schema}".users'
        assert admin.execute(text(f"SELECT has_table_privilege('anon', '{table}', 'SELECT')")).scalar() is False
        assert admin.execute(
            text("SELECT has_schema_privilege('anon', :s, 'USAGE')"), {"s": settings.database_schema}
        ).scalar() is False
        engine.dispose()
    finally:
        admin.execute(text("ROLLBACK"))
        admin.execute(text("ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM anon"))
        admin.execute(text("DROP OWNED BY anon"))
        admin.execute(text("DROP ROLE anon"))
        admin.execute(text("COMMIT"))


# ---- the submit guard under a real row lock ----------------------------------

@pytest.fixture()
def open_session(app):
    """Hands out sessions and always rolls them back at the end, so a failed test cannot leave a lock behind."""
    opened: list[Session] = []

    def factory(cls=Session) -> Session:
        session = cls(bind=app.state.engine, expire_on_commit=False, autoflush=False)
        opened.append(session)
        return session

    yield factory
    for session in opened:
        session.rollback()
        session.close()


def backend_pid(session: Session) -> int:
    return session.scalar(select(func.pg_backend_pid()))


def wait_until_blocked(admin, pid: int, worker: threading.Thread) -> None:
    """Poll the lock table until `pid` is waiting on another backend. Fails (rather than hangs) after a timeout."""
    deadline = time.monotonic() + LOCK_WAIT_SECONDS
    while time.monotonic() < deadline:
        if scalars(admin, "SELECT pg_blocking_pids(:p)", p=pid)[0]:
            assert worker.is_alive()
            return
        if not worker.is_alive():
            break
        time.sleep(0.02)
    pytest.fail("the second transaction never waited on the first one's row lock")


def run_in_thread(fn) -> tuple[threading.Thread, list]:
    outcome: list = []

    def target() -> None:
        try:
            outcome.append(fn())
        except BaseException as exc:  # noqa: BLE001  (handed back to the test, which asserts on it)
            outcome.append(exc)

    thread = threading.Thread(target=target)
    thread.start()
    return thread, outcome


@pytest.fixture()
def ready(api, app, test_clock):
    """An approved enrollment, an open check-in attempt, and the event a matching submit would save."""
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    attempt_id = api.attempt("student1", activity["id"], "check_in").json()["id"]
    with app.state.session_factory() as session:
        enrollment_id = session.scalar(select(Enrollment.id).where(Enrollment.student_id == "u_s1"))
    return build_event(attempt_id, activity["id"]), enrollment_id


def event_count(app) -> int:
    with app.state.session_factory() as session:
        return session.scalar(select(func.count()).select_from(AttendanceEvent))


def replace_enrollment(session: Session) -> None:
    """The re-upload's database work, left uncommitted: delete the current row, add a new pending one."""
    old = session.scalar(select(Enrollment).where(Enrollment.student_id == "u_s1"))
    session.delete(old)
    session.flush()
    session.add(Enrollment(
        id=new_id("enr"), student_id="u_s1", status="pending", photo_file=old.photo_file,
        embedding_enc=old.embedding_enc, submitted_at=old.submitted_at,
    ))
    session.flush()


def test_submit_waits_for_an_in_flight_reupload_then_refuses_to_save(app, admin, ready, open_session):
    event, enrollment_id = ready
    reupload, submit = open_session(), open_session()
    submit_pid = backend_pid(submit)
    replace_enrollment(reupload)  # holds the lock on the old enrollment row, not yet committed

    worker, outcome = run_in_thread(lambda: attempts_module._persist(submit, event, enrollment_id))
    wait_until_blocked(admin, submit_pid, worker)
    reupload.commit()
    worker.join(LOCK_WAIT_SECONDS)

    assert not worker.is_alive()
    assert isinstance(outcome[0], ApiError) and outcome[0].code == "enrollment_not_approved"
    assert event_count(app) == 0


class GatedSession(Session):
    """Pauses right before the commit, so a test can hold the guard's row lock for as long as it needs."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.reached, self.release = threading.Event(), threading.Event()

    def commit(self) -> None:
        self.reached.set()
        assert self.release.wait(LOCK_WAIT_SECONDS)
        super().commit()


def test_reupload_waits_for_an_in_flight_submit_so_the_event_is_saved_against_the_enrollment_it_checked(
    app, admin, ready, open_session
):
    event, enrollment_id = ready
    gated, reupload = open_session(GatedSession), open_session()
    reupload_pid = backend_pid(reupload)

    submit_worker, submit_outcome = run_in_thread(lambda: attempts_module._persist(gated, event, enrollment_id))
    assert gated.reached.wait(LOCK_WAIT_SECONDS)  # the guard ran and the event is added; commit is paused
    swap_worker, swap_outcome = run_in_thread(
        lambda: enrollment_module._swap_in(reupload, reupload.get(User, "u_s1"), "new.enc", b"emb")
    )
    try:
        wait_until_blocked(admin, reupload_pid, swap_worker)
        assert event_count(app) == 0  # nothing committed yet: the submit is still holding the lock
    finally:
        gated.release.set()  # never leave the paused commit waiting, even when an assertion above fails
    submit_worker.join(LOCK_WAIT_SECONDS)
    swap_worker.join(LOCK_WAIT_SECONDS)

    assert not submit_worker.is_alive() and not swap_worker.is_alive()
    assert isinstance(submit_outcome[0], AttendanceEvent) and event_count(app) == 1
    new_row = swap_outcome[0][0]
    assert new_row.id != enrollment_id and new_row.status == "pending"
