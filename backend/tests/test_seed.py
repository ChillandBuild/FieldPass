from __future__ import annotations

from datetime import timedelta

from cryptography.fernet import Fernet
from sqlalchemy import func, select

import pytest

from app import clock, gen_secrets, seed
from app.config import ConfigError
from app.db import make_engine_for, make_session_factory
from app.models import Activity, RosterEntry, User
from app.security import verify_password
from tests.helpers import FACULTY_PASSWORD, PASSWORD


def sessions(settings):
    return make_session_factory(make_engine_for(settings))()


def test_seed_creates_exact_demo_accounts_and_is_idempotent(settings, test_clock):
    seed.run(settings, None)
    seed.run(settings, None)
    with sessions(settings) as session:
        users = {u.username: u for u in session.scalars(select(User))}
    assert {k: (v.id, v.role, v.full_name) for k, v in users.items()} == {
        "faculty1": ("u_f1", "faculty", "Dr. Meera Rao"),
        "student1": ("u_s1", "student", "Asha Verma"),
        "student2": ("u_s2", "student", "Rohan Iyer"),
        "student3": ("u_s3", "student", "Fatima Khan"),
    }
    assert all(verify_password(u.password_hash, PASSWORD) for n, u in users.items() if u.role == "student")
    assert all(PASSWORD not in u.password_hash for u in users.values())


def test_faculty_account_has_its_own_password(settings, test_clock):
    seed.run(settings, None)
    with sessions(settings) as session:
        users = {u.username: u for u in session.scalars(select(User))}
    assert verify_password(users["faculty1"].password_hash, FACULTY_PASSWORD)
    assert not verify_password(users["faculty1"].password_hash, PASSWORD)
    assert not verify_password(users["student1"].password_hash, FACULTY_PASSWORD)


def test_seed_refuses_faculty_password_equal_to_student_password(settings, test_clock):
    same = type(settings)(**{**settings.__dict__, "faculty_seed_password": settings.seed_password})
    with pytest.raises(ConfigError) as info:
        seed.run(same, None)
    assert "FACULTY_SEED_PASSWORD" in str(info.value) and "SEED_PASSWORD" in str(info.value)
    assert not settings.database_path.exists()  # refused before touching the database


def test_seed_refuses_missing_faculty_password(settings, test_clock):
    blank = type(settings)(**{**settings.__dict__, "faculty_seed_password": ""})
    with pytest.raises(ConfigError):
        seed.run(blank, None)


def test_seed_cli_exits_with_the_message_when_passwords_clash(monkeypatch, settings):
    same = type(settings)(**{**settings.__dict__, "faculty_seed_password": settings.seed_password})
    monkeypatch.setattr("sys.argv", ["seed"])
    monkeypatch.setattr(seed, "get_settings", lambda: same)
    with pytest.raises(SystemExit) as info:
        seed.main()
    assert "FACULTY_SEED_PASSWORD" in str(info.value)


def test_seed_refreshes_changed_password(settings, test_clock):
    seed.run(settings, None)
    changed = type(settings)(**{**settings.__dict__, "seed_password": "a-new-password",
                                "faculty_seed_password": "a-new-faculty-password"})
    seed.run(changed, None)
    with sessions(settings) as session:
        student = session.scalar(select(User).where(User.username == "student1"))
        faculty = session.scalar(select(User).where(User.username == "faculty1"))
    assert verify_password(student.password_hash, "a-new-password")
    assert verify_password(faculty.password_hash, "a-new-faculty-password")


def test_demo_activity_starts_now_for_four_hours_with_everyone(settings, test_clock):
    seed.run(settings, (12.9716, 77.5946))
    with sessions(settings) as session:
        activity = session.scalar(select(Activity))
        roster = session.scalar(select(func.count()).select_from(RosterEntry))
    assert activity.starts_at == clock.now() and activity.ends_at - activity.starts_at == timedelta(hours=4)
    assert activity.radius_m == 100 and (activity.venue_lat, activity.venue_lng) == (12.9716, 77.5946)
    assert roster == 3


def test_seed_cli_requires_coordinates_for_demo(monkeypatch, capsys):
    import pytest

    monkeypatch.setattr("sys.argv", ["seed", "--demo-activity"])
    with pytest.raises(SystemExit):
        seed.main()
    assert "--lat" in capsys.readouterr().err


def test_seed_cli_runs(monkeypatch, settings, test_clock, capsys):
    monkeypatch.setattr("sys.argv", ["seed", "--demo-activity", "--lat", "1", "--lng", "2"])
    monkeypatch.setattr(seed, "get_settings", lambda: settings)
    seed.main()
    assert "Demo activity created" in capsys.readouterr().out


def test_gen_secrets_prints_usable_values(capsys):
    gen_secrets.main()
    lines = dict(line.split("=", 1) for line in capsys.readouterr().out.strip().splitlines())
    assert len(lines["JWT_SECRET"]) >= 48
    assert len(lines["SEED_PASSWORD"]) >= 16 and len(lines["FACULTY_SEED_PASSWORD"]) >= 16
    assert lines["SEED_PASSWORD"] != lines["FACULTY_SEED_PASSWORD"]
    Fernet(lines["PHOTO_ENCRYPTION_KEY"].encode())  # valid key or raises
