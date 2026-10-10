"""Generate contracts/fixtures/*.json by driving the real app (fake face engine, fixed clock).

Usage (from backend/): python scripts/generate_fixtures.py
Because every file is a real response, the fixtures cannot drift from the code.
"""
from __future__ import annotations

import json
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

from fastapi.testclient import TestClient  # noqa: E402

from app import clock  # noqa: E402
from app.deps import get_face_engine  # noqa: E402
from app.main import create_app  # noqa: E402
from app.seed import seed_users  # noqa: E402
from scripts.fixture_env import FACULTY_PASSWORD, SEED_PASSWORD, throwaway_settings  # noqa: E402
from tests.fakes import BLANK, TWO_FACES, FakeFaceEngine, face  # noqa: E402
from tests.helpers import P, VENUE, Api, offset_lat  # noqa: E402

OUT = BACKEND.parent / "contracts" / "fixtures"
START = datetime(2026, 10, 10, 9, 0, 0, tzinfo=timezone.utc)
errors_index: dict[str, int] = {}


class FixedClock:
    def __init__(self) -> None:
        self.current = START

    def advance(self, **kw: float) -> None:
        self.current += timedelta(**kw)

    def set_time(self, hour: int, minute: int, second: int = 0) -> None:
        self.current = self.current.replace(hour=hour, minute=minute, second=second)

    def __call__(self) -> datetime:
        return self.current


def save(name: str, body: object) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def keep(name: str, resp, expected: int) -> dict:
    assert resp.status_code == expected, (name, resp.status_code, resp.text)
    body = resp.json()
    save(name, body)
    return body


def keep_error(code: str, resp, expected: int) -> None:
    assert resp.status_code == expected and resp.json()["error"]["code"] == code, (code, resp.status_code, resp.text)
    errors_index[code] = expected
    save(f"error_{code}", resp.json())


def enrollment_fixtures(api: Api) -> None:
    keep("enrollment_none", api.get("student1", "/enrollment/me"), 200)
    for user, who in (("student1", "asha"), ("student2", "rohan"), ("student3", "fatima")):
        resp = api.post(user, "/enrollment", files={"photo": ("p.jpg", face(who), "image/jpeg")})
        if user == "student1":
            first = keep("enrollment_pending", resp, 201)
        elif user == "student3":
            third = resp.json()
    keep("enrollments_pending_list", api.get("faculty1", "/enrollments", params={"status": "pending"}), 200)
    clock_ = api.clock
    clock_.advance(minutes=5)
    ids = {e["student"]["username"]: e["id"] for e in api.get("faculty1", "/enrollments").json()["items"]}
    keep("enrollment_approved", api.post("faculty1", f"/enrollments/{first['id']}/approve", json={"identity_confirmed": True}), 200)
    api.post("faculty1", f"/enrollments/{ids['student2']}/approve", json={"identity_confirmed": True})
    keep("enrollment_rejected", api.post("faculty1", f"/enrollments/{third['id']}/reject", json={"reason": "Face is partly covered. Please retake."}), 200)
    api.enrollment_ids = ids  # type: ignore[attr-defined]


def flow_fixtures(api: Api) -> dict:
    activity = api.make_activity(students=("student1", "student2", "student3"))
    keep("activity_faculty_created", api.get("faculty1", f"/activities/{activity['id']}"), 200)
    api.clock.set_time(9, 31)
    keep("activities_student", api.get("student1", "/activities"), 200)
    keep("activities_faculty", api.get("faculty1", "/activities"), 200)
    aid = activity["id"]
    keep("attempt", api.attempt("student1", aid, "check_in"), 201)
    api.clock.advance(minutes=3)
    api.attempt("student1", aid, "check_in")  # superseded attempt: still valid until it expires
    attempt = api.attempt("student1", aid, "check_in").json()
    keep("event_recorded", api.submit("student1", attempt["id"], face("asha")), 200)
    second = api.attempt("student2", aid, "check_in").json()
    keep("event_needs_review", api.submit("student2", second["id"], face("not-rohan"), lat=offset_lat(105)), 200)
    return activity


def review_fixtures(api: Api, aid: str) -> None:
    api.clock.advance(minutes=10)
    keep("checkpoint_window", api.post("faculty1", f"/activities/{aid}/checkpoints", json={"duration_minutes": 10}), 201)
    api.do("student1", aid, "checkpoint", "asha")
    keep("observation", api.post("faculty1", f"/activities/{aid}/observations", json={
        "student_id": "u_s2", "observation_type": "present", "note": "Signed the paper sheet"}), 201)
    api.clock.advance(minutes=1)
    keep("decision_record", api.post("faculty1", f"/activities/{aid}/students/u_s2/decision", json={
        "decision": "confirmed", "reason": "Seen on site all morning"}), 200)
    api.clock.set_time(12, 0)
    api.do("student1", aid, "check_out", "asha")
    api.clock.set_time(14, 10)
    keep("attendance_history", api.get("student1", "/attendance/me"), 200)
    keep("register", api.get("faculty1", f"/activities/{aid}/register"), 200)
    (OUT / "export.csv").write_text(api.get("faculty1", f"/activities/{aid}/export.csv").text, encoding="utf-8", newline="")


def error_fixtures(api: Api, client: TestClient, app, aid: str) -> None:
    clock_ = api.clock
    keep_error("invalid_credentials", client.post(f"{P}/auth/login", json={"username": "student1", "password": "wrong"}), 401)
    keep_error("not_authenticated", client.get(f"{P}/auth/me"), 401)
    keep_error("forbidden", api.get("student1", "/students"), 403)
    keep_error("not_found", api.get("faculty1", "/activities/act_missing"), 404)
    keep_error("validation_error", client.post(f"{P}/auth/login", json={"username": "student1"}), 422)
    keep_error("unusable_capture", api.post("student1", "/enrollment", files={"photo": ("p.jpg", BLANK, "image/jpeg")}), 422)
    keep_error("multiple_faces", api.post("student1", "/enrollment", files={"photo": ("p.jpg", TWO_FACES, "image/jpeg")}), 422)
    keep_error("image_too_large", api.post("student1", "/enrollment", files={"photo": ("p.jpg", b"FACE:" + b"x" * 5_300_000, "image/jpeg")}), 413)
    keep_error("already_decided", api.post("faculty1", f"/enrollments/{api.enrollment_ids['student1']}/approve", json={"identity_confirmed": True}), 409)
    keep_error("enrollment_not_approved", api.attempt("student3", aid, "check_in"), 409)
    keep_error("invalid_action_order", api.attempt("student1", aid, "check_in"), 409)
    keep_error("checkpoint_not_open", api.attempt("student2", aid, "checkpoint"), 409)
    other = api.make_activity(students=("student2",), starts_in_min=600)
    keep_error("not_assigned", api.attempt("student1", other["id"], "check_in"), 403)
    keep_error("outside_activity_window", api.attempt("student2", other["id"], "check_in"), 409)
    clock_.set_time(9, 31)  # rewind: fresh attempts for the retryable errors
    api.enroll("student3", "fatima2")
    fresh = api.attempt("student3", aid, "check_in").json()
    keep_error("location_stale", api.submit("student3", fresh["id"], face("fatima2"), captured_ago_s=120), 422)
    clock_.advance(seconds=100)
    keep_error("attempt_expired", api.submit("student3", fresh["id"], face("fatima2")), 410)
    for _ in range(11):
        resp = client.post(f"{P}/auth/login", json={"username": "student9", "password": "x"})
    keep_error("too_many_attempts", resp, 429)
    keep_error("method_not_allowed", client.delete(f"{P}/health"), 405)
    app.add_api_route(f"{P}/_boom", lambda: 1 / 0)
    keep_error("server_error", client.get(f"{P}/_boom"), 500)
    save("errors_index", errors_index)


def main() -> None:
    fake_clock = FixedClock()
    clock.set_clock(fake_clock)
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(throwaway_settings(Path(tmp)), require_models=False)
        app.dependency_overrides[get_face_engine] = lambda: FakeFaceEngine()
        with app.state.session_factory() as session:
            seed_users(session, SEED_PASSWORD, FACULTY_PASSWORD)
        with TestClient(app, raise_server_exceptions=False) as client:
            import tests.helpers as helpers

            helpers.PASSWORD = SEED_PASSWORD
            helpers.FACULTY_PASSWORD = FACULTY_PASSWORD
            api = Api(client, fake_clock)
            login = client.post(f"{P}/auth/login", json={"username": "student1", "password": SEED_PASSWORD}).json()
            login["access_token"] = "eyJhbGciOiJIUzI1NiJ9.example-access-token"
            save("login", login)
            keep("health", client.get(f"{P}/health"), 200)
            keep("me", api.get("student1", "/auth/me"), 200)
            enrollment_fixtures(api)
            aid = flow_fixtures(api)["id"]
            review_fixtures(api, aid)
            error_fixtures(api, client, app, aid)
        app.state.engine.dispose()  # release SQLite files before Windows removes the temp directory
    clock.set_clock(None)
    print(f"wrote {len(list(OUT.glob('*')))} fixtures to {OUT}")


if __name__ == "__main__":
    main()
