from __future__ import annotations

import csv
import io
from datetime import datetime

from fastapi import APIRouter, Depends, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import clock, rules
from app.config import Settings
from app.db import get_session
from app.models import Activity, RosterEntry, User
from app.routers.activities import get_activity
from app.schemas import AttendanceList, AttendanceRecordOut, RegisterOut
from app.security import get_settings_dep, require_faculty, require_student
from app.views import ActivityData, build_activity, build_record, load_activity_data

router = APIRouter(tags=["attendance"])

CSV_COLUMNS = [
    "student_id", "username", "full_name", "status", "check_in_at", "check_in_status", "checkpoints_answered",
    "check_out_at", "missing_checkout", "complete", "review_reasons", "decision_reason", "observations",
]


@router.get("/attendance/me", response_model=AttendanceList)
def my_attendance(
    student: User = Depends(require_student),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> AttendanceList:
    now = clock.now()
    timing = rules.timing_from(settings)
    activities = session.scalars(
        select(Activity).join(RosterEntry, RosterEntry.activity_id == Activity.id)
        .where(RosterEntry.student_id == student.id).order_by(Activity.starts_at.desc(), Activity.id)
    )
    items = [
        build_record(load_activity_data(session, a, student_id=student.id), student, now, with_actions=True, timing=timing)
        for a in activities
    ]
    return AttendanceList(items=items)


def _rows(session: Session, activity: Activity, timing: rules.Timing) -> tuple[ActivityData, datetime, list[AttendanceRecordOut]]:
    now = clock.now()
    data = load_activity_data(session, activity)
    students = sorted((data.users[sid] for sid in data.roster if sid in data.users), key=lambda u: u.username)
    return data, now, [build_record(data, s, now, with_actions=False, timing=timing) for s in students]


@router.get("/activities/{activity_id}/register", response_model=RegisterOut)
def register(
    activity_id: str,
    _: User = Depends(require_faculty),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> RegisterOut:
    activity = get_activity(session, activity_id)
    data, now, rows = _rows(session, activity, rules.timing_from(settings))
    return RegisterOut(activity=build_activity(data, now, None), server_time=clock.to_iso(now), rows=rows)


def _csv_cell(value: str) -> str:
    """Neutralise spreadsheet formula injection in free-text fields."""
    return "'" + value if value[:1] in ("=", "+", "-", "@", "\t", "\r") else value


def _csv_line(row: AttendanceRecordOut) -> list[str]:
    check_in, check_out = row.check_in, row.check_out
    return [
        row.student.id, row.student.username, _csv_cell(row.student.full_name), row.status or "",
        check_in.received_at if check_in else "", check_in.status if check_in else "",
        str(len(row.checkpoints)), check_out.received_at if check_out else "",
        str(row.missing_checkout).lower(), str(row.complete).lower(), ";".join(row.review_reasons),
        _csv_cell(row.decision.reason) if row.decision else "",
        _csv_cell(" | ".join(f"{o.observation_type}: {o.note}" for o in row.observations)),
    ]


@router.get("/activities/{activity_id}/export.csv", response_class=Response, responses={200: {"content": {"text/csv": {}}}})
def export_csv(
    activity_id: str,
    _: User = Depends(require_faculty),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> Response:
    activity = get_activity(session, activity_id)
    _data, _now, rows = _rows(session, activity, rules.timing_from(settings))
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\r\n")
    writer.writerow(CSV_COLUMNS)
    writer.writerows(_csv_line(r) for r in rows)
    headers = {"Content-Disposition": f'attachment; filename="attendance_{activity.id}.csv"'}
    return Response(out.getvalue(), media_type="text/csv", headers=headers)
