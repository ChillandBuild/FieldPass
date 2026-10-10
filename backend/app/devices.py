"""One phone per student: bind on first login, refuse other phones until faculty reset."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import errors
from app.device_keys import is_valid_public_key
from app.models import StudentDevice


def bound_key(session: Session, student_id: str) -> str | None:
    row = session.get(StudentDevice, student_id)
    return row.public_key if row else None


def _check_existing(session: Session, student_id: str, public_key: str) -> bool:
    """True if this student is already bound to this key; raises if bound elsewhere or the key is taken."""
    current = bound_key(session, student_id)
    if current is not None:
        if current != public_key:
            raise errors.device_mismatch()
        return True
    if session.scalar(select(StudentDevice).where(StudentDevice.public_key == public_key)) is not None:
        raise errors.device_taken()
    return False


def bind_or_check(session: Session, student_id: str, public_key: str | None, now: datetime) -> None:
    if not public_key:
        raise errors.device_key_required()
    if not is_valid_public_key(public_key):
        raise errors.validation_error("The device key is not valid.")
    if _check_existing(session, student_id, public_key):
        return
    session.add(StudentDevice(student_id=student_id, public_key=public_key, bound_at=now))
    try:
        session.commit()
    except IntegrityError:
        # A concurrent login bound first; re-check against what won.
        session.rollback()
        if not _check_existing(session, student_id, public_key):
            raise errors.device_taken() from None
