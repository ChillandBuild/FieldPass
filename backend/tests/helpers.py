"""Small convenience wrapper over the TestClient."""
from __future__ import annotations

from datetime import datetime, timedelta

from app.clock import to_iso
from tests.fakes import face

PASSWORD = "test-seed-password"
FACULTY_PASSWORD = "test-faculty-password"
VENUE = (12.9716, 77.5946)
STUDENT_IDS = {"student1": "u_s1", "student2": "u_s2", "student3": "u_s3"}
P = "/api/v1"


def offset_lat(metres: float) -> float:
    """Latitude that is `metres` north of the venue (1 degree is about 111,195 m)."""
    return VENUE[0] + metres / 111_195.0


class Api:
    def __init__(self, client, clock) -> None:
        self.client = client
        self.clock = clock
        self._tokens: dict[str, dict[str, str]] = {}

    def auth(self, username: str) -> dict[str, str]:
        if username not in self._tokens:
            password = FACULTY_PASSWORD if username.startswith("faculty") else PASSWORD
            body = self.client.post(f"{P}/auth/login", json={"username": username, "password": password}).json()
            self._tokens[username] = {"Authorization": f"Bearer {body['access_token']}"}
        return self._tokens[username]

    def get(self, username: str, path: str, **kw):
        return self.client.get(P + path, headers=self.auth(username), **kw)

    def post(self, username: str, path: str, **kw):
        return self.client.post(P + path, headers=self.auth(username), **kw)

    # ---- setup helpers ----
    def enroll(self, username: str, identity: str, approve: bool = True) -> dict:
        resp = self.post(username, "/enrollment", files={"photo": ("p.jpg", face(identity), "image/jpeg")})
        assert resp.status_code == 201, resp.text
        enrollment = resp.json()
        if approve:
            done = self.post("faculty1", f"/enrollments/{enrollment['id']}/approve", json={"identity_confirmed": True})
            assert done.status_code == 200, done.text
            return done.json()
        return enrollment

    def make_activity(self, students=("student1", "student2", "student3"), starts_in_min: int = 30, hours: float = 4, **extra) -> dict:
        now: datetime = self.clock.current
        body = {
            "name": "Site Visit", "venue_name": "Plant Gate 2", "venue_lat": VENUE[0], "venue_lng": VENUE[1],
            "radius_m": 100, "starts_at": to_iso(now + timedelta(minutes=starts_in_min)),
            "ends_at": to_iso(now + timedelta(minutes=starts_in_min, hours=hours)),
            "student_ids": [STUDENT_IDS[s] for s in students],
        }
        body.update(extra)
        resp = self.post("faculty1", "/activities", json=body)
        assert resp.status_code == 201, resp.text
        return resp.json()

    def attempt(self, username: str, activity_id: str, action: str):
        return self.post(username, f"/activities/{activity_id}/attempts", json={"action": action})

    def submit(self, username: str, attempt_id: str, selfie: bytes, lat: float | None = None, lng: float | None = None,
               accuracy: float = 10, captured_ago_s: float = 5):
        captured = to_iso(self.clock.current - timedelta(seconds=captured_ago_s))
        data = {
            "lat": str(VENUE[0] if lat is None else lat), "lng": str(VENUE[1] if lng is None else lng),
            "accuracy_m": str(accuracy), "location_captured_at": captured,
        }
        return self.post(username, f"/attempts/{attempt_id}/submit", data=data,
                         files={"selfie": ("s.jpg", selfie, "image/jpeg")})

    def do(self, username: str, activity_id: str, action: str, identity: str, **kw):
        """Create an attempt and submit a matching selfie; returns the submit response."""
        created = self.attempt(username, activity_id, action)
        assert created.status_code == 201, created.text
        return self.submit(username, created.json()["id"], face(identity), **kw)
