"""SQLAlchemy tables. Decisions and observations are append-only audit rows."""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Float, Index, ForeignKey, Integer, LargeBinary, String, Text, UniqueConstraint, text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base, UTCDateTime


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    username: Mapped[str] = mapped_column(String, unique=True, index=True)
    full_name: Mapped[str] = mapped_column(String)
    role: Mapped[str] = mapped_column(String)
    password_hash: Mapped[str] = mapped_column(String)


class StudentDevice(Base):
    """The one phone a student may use. A faculty reset deletes the row; the next login binds again."""

    __tablename__ = "student_devices"
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    public_key: Mapped[str] = mapped_column(Text, unique=True)
    bound_at: Mapped[datetime] = mapped_column(UTCDateTime)


class Enrollment(Base):
    """One current row per student. A re-upload replaces it with a new row (new id), status pending."""

    __tablename__ = "enrollments"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), unique=True)
    status: Mapped[str] = mapped_column(String, default="pending")
    photo_file: Mapped[str] = mapped_column(String)
    embedding_enc: Mapped[bytes] = mapped_column(LargeBinary)
    submitted_at: Mapped[datetime] = mapped_column(UTCDateTime)
    decided_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    decided_by_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    decision_reason: Mapped[str | None] = mapped_column(Text, nullable=True)


class Activity(Base):
    __tablename__ = "activities"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)
    venue_name: Mapped[str] = mapped_column(String)
    venue_lat: Mapped[float] = mapped_column(Float)
    venue_lng: Mapped[float] = mapped_column(Float)
    radius_m: Mapped[int] = mapped_column(Integer)
    starts_at: Mapped[datetime] = mapped_column(UTCDateTime)
    ends_at: Mapped[datetime] = mapped_column(UTCDateTime)
    created_by_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    venue_regions: Mapped[list[VenueRegion]] = relationship(cascade="all, delete-orphan", order_by="VenueRegion.position")


class VenueRegion(Base):
    __tablename__ = "venue_regions"
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"), primary_key=True)
    position: Mapped[int] = mapped_column(Integer, primary_key=True)
    venue_lat: Mapped[float] = mapped_column(Float)
    venue_lng: Mapped[float] = mapped_column(Float)
    radius_m: Mapped[int] = mapped_column(Integer)


class RosterEntry(Base):
    __tablename__ = "roster"
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"), primary_key=True)
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)


class CheckpointWindow(Base):
    __tablename__ = "checkpoint_windows"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"), index=True)
    opens_at: Mapped[datetime] = mapped_column(UTCDateTime)
    closes_at: Mapped[datetime] = mapped_column(UTCDateTime)
    opened_by_id: Mapped[str] = mapped_column(ForeignKey("users.id"))


class Attempt(Base):
    __tablename__ = "attempts"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"))
    action: Mapped[str] = mapped_column(String)
    checkpoint_id: Mapped[str | None] = mapped_column(ForeignKey("checkpoint_windows.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    nonce: Mapped[str] = mapped_column(String)
    challenge: Mapped[str] = mapped_column(String)


class PresenceScan(Base):
    """A fresh presence (QR) code was scanned for this attempt. One row at most; it only ever helps the attempt."""

    __tablename__ = "presence_scans"
    attempt_id: Mapped[str] = mapped_column(ForeignKey("attempts.id"), primary_key=True)
    verified_at: Mapped[datetime] = mapped_column(UTCDateTime)


class AttendanceEvent(Base):
    """One automated observation. UNIQUE(attempt_id) makes submit idempotent."""

    __tablename__ = "attendance_events"
    __table_args__ = (
        UniqueConstraint("attempt_id", name="uq_event_attempt"),
        # Action-order invariants enforced by the database, so concurrent attempts cannot double-record.
        Index(
            "uq_one_checkin_checkout", "activity_id", "student_id", "action", unique=True,
            sqlite_where=text("action IN ('check_in', 'check_out')"),
            postgresql_where=text("action IN ('check_in', 'check_out')"),
        ),
        Index(
            "uq_one_answer_per_window", "activity_id", "student_id", "checkpoint_id", unique=True,
            sqlite_where=text("action = 'checkpoint'"),
            postgresql_where=text("action = 'checkpoint'"),
        ),
    )
    id: Mapped[str] = mapped_column(String, primary_key=True)
    attempt_id: Mapped[str] = mapped_column(ForeignKey("attempts.id"))
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"), index=True)
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    action: Mapped[str] = mapped_column(String)
    status: Mapped[str] = mapped_column(String)
    received_at: Mapped[datetime] = mapped_column(UTCDateTime)
    checkpoint_id: Mapped[str | None] = mapped_column(String, nullable=True)
    review_reasons: Mapped[str] = mapped_column(Text, default="")
    location_verdict: Mapped[str] = mapped_column(String)
    distance_m: Mapped[int] = mapped_column(Integer)
    accuracy_m: Mapped[int] = mapped_column(Integer)
    captured_at: Mapped[datetime] = mapped_column(UTCDateTime)
    face_verdict: Mapped[str] = mapped_column(String)
    # Raw cosine similarity behind face_verdict, kept for calibration. Never exposed through the API.
    face_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    # Truncated SHA-256 of the coordinates; the coordinates themselves are never stored.
    location_fingerprint: Mapped[str | None] = mapped_column(String, nullable=True)


class Decision(Base):
    __tablename__ = "decisions"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"), index=True)
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    decision: Mapped[str] = mapped_column(String)
    reason: Mapped[str] = mapped_column(Text)
    actor_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    decided_at: Mapped[datetime] = mapped_column(UTCDateTime)


class Observation(Base):
    __tablename__ = "observations"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    activity_id: Mapped[str] = mapped_column(ForeignKey("activities.id"), index=True)
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    observation_type: Mapped[str] = mapped_column(String)
    note: Mapped[str] = mapped_column(Text)
    actor_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    observed_at: Mapped[datetime] = mapped_column(UTCDateTime)
