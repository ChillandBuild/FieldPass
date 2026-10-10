"""Idempotent seed: demo accounts, and optionally a demo activity.

    python -m app.seed
    python -m app.seed --demo-activity --lat 12.9716 --lng 77.5946
"""
from __future__ import annotations

import argparse
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import clock
from app.config import ConfigError, Settings, get_settings
from app.db import create_all, make_engine, make_session_factory
from app.models import Activity, RosterEntry, User, new_id
from app.security import hash_password, verify_password

SEED_USERS = [
    ("u_f1", "faculty1", "Dr. Meera Rao", "faculty"),
    ("u_s1", "student1", "Asha Verma", "student"),
    ("u_s2", "student2", "Rohan Iyer", "student"),
    ("u_s3", "student3", "Fatima Khan", "student"),
]
DEMO_NAME = "Demo Site Visit"


def seed_users(session: Session, student_password: str, faculty_password: str) -> int:
    """Create missing accounts; refresh a hash if its seed password changed. Returns created count.

    Students use SEED_PASSWORD, faculty use FACULTY_SEED_PASSWORD.
    """
    created = 0
    for user_id, username, full_name, role in SEED_USERS:
        password = faculty_password if role == "faculty" else student_password
        user = session.scalar(select(User).where(User.username == username))
        if user is None:
            session.add(User(id=user_id, username=username, full_name=full_name, role=role,
                             password_hash=hash_password(password)))
            created += 1
        elif not verify_password(user.password_hash, password):
            user.password_hash = hash_password(password)
    session.commit()
    return created


def create_demo_activity(session: Session, lat: float, lng: float) -> Activity:
    """Activity starting now, 4 hours long, 100 m radius, all students assigned."""
    faculty = session.scalar(select(User).where(User.username == "faculty1"))
    students = list(session.scalars(select(User).where(User.role == "student")))
    now = clock.now()
    activity = Activity(
        id=new_id("act"), name=DEMO_NAME, venue_name="Demo venue", venue_lat=lat, venue_lng=lng, radius_m=100,
        starts_at=now, ends_at=now + timedelta(hours=4), created_by_id=faculty.id, created_at=now,
    )
    session.add(activity)
    session.add_all(RosterEntry(activity_id=activity.id, student_id=s.id) for s in students)
    session.commit()
    return activity


def check_passwords(settings: Settings) -> None:
    """Faculty can approve photos and decide attendance, so they must not share the students' password."""
    if not settings.faculty_seed_password:
        raise ConfigError("FACULTY_SEED_PASSWORD is missing. Set it in backend/.env (see .env.example).")
    if settings.faculty_seed_password == settings.seed_password:
        raise ConfigError("FACULTY_SEED_PASSWORD must be different from SEED_PASSWORD. Pick a separate password.")


def run(settings: Settings, demo: tuple[float, float] | None) -> None:
    check_passwords(settings)
    engine = make_engine(settings.database_path)
    create_all(engine)
    with make_session_factory(engine)() as session:
        created = seed_users(session, settings.seed_password, settings.faculty_seed_password)
        print(f"Seed users ready ({created} created, {len(SEED_USERS) - created} already present).")
        if demo:
            activity = create_demo_activity(session, *demo)
            print(f"Demo activity created: {activity.id} at ({demo[0]}, {demo[1]}), 4 h from now.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed FieldPass demo data.")
    parser.add_argument("--demo-activity", action="store_true", help="also create a 4-hour demo activity")
    parser.add_argument("--lat", type=float)
    parser.add_argument("--lng", type=float)
    args = parser.parse_args()
    if args.demo_activity and (args.lat is None or args.lng is None):
        parser.error("--demo-activity needs --lat and --lng")
    try:
        run(get_settings(), (args.lat, args.lng) if args.demo_activity else None)
    except ConfigError as exc:
        raise SystemExit(f"Seeding stopped: {exc}") from None


if __name__ == "__main__":
    main()
