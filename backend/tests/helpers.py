"""Small convenience wrapper over the TestClient."""
from __future__ import annotations

import base64
from datetime import datetime, timedelta

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from app.clock import to_iso
from app.device_keys import canonical_message
from tests.fakes import face

PASSWORD = "test-seed-password"
FACULTY_PASSWORD = "test-faculty-password"
VENUE = (12.9716, 77.5946)
STUDENT_IDS = {"student1": "u_s1", "student2": "u_s2", "student3": "u_s3"}
P = "/api/v1"


def new_device_key():  # noqa: ANN201
    """A fresh P-256 key pair, public half as Base64 SubjectPublicKeyInfo DER (what the phone sends)."""
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    return private, base64.b64encode(der).decode()


def offset_lat(metres: float) -> float:
    """Latitude that is `metres` north of the venue (1 degree is about 111,195 m)."""
    return VENUE[0] + metres / 111_195.0


class Api:
    def __init__(self, client, clock) -> None:
        self.client = client
        self.clock = clock
        self._tokens: dict[str, dict[str, str]] = {}
        self.keys: dict[str, tuple] = {}  # username -> (private key, public b64): one phone per student
        self.attempts: dict[str, dict] = {}  # attempt id -> create response
        self.submits = 0

    def auth(self, username: str) -> dict[str, str]:
        if username not in self._tokens:
            password = FACULTY_PASSWORD if username.startswith("faculty") else PASSWORD
            req = {"username": username, "password": password}
            if not username.startswith("faculty"):
                req["device_public_key"] = self.keys.setdefault(username, new_device_key())[1]
            body = self.client.post(f"{P}/auth/login", json=req).json()
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
        resp = self.post(username, f"/activities/{activity_id}/attempts", json={"action": action})
        if resp.status_code == 201:
            self.attempts[resp.json()["id"]] = resp.json()
        return resp

    def signed_form(self, username: str, attempt_id: str, selfie: bytes, lat: float | None = None,
                    lng: float | None = None, accuracy: float = 10, captured_ago_s: float = 5, *,
                    turn: bytes | None = None, mock: bool = False, rooted: bool = False, emulator: bool = False,
                    signed_lat: float | None = None, key=None, signature: str | None = None):  # noqa: ANN201
        """The multipart body the phone sends. By default the turn photo shows the challenged pose."""
        self.submits += 1
        lat = VENUE[0] + self.submits * 0.000001 if lat is None else lat
        lng = VENUE[1] if lng is None else lng
        captured_at = (self.clock.current - timedelta(seconds=captured_ago_s)).replace(microsecond=0)
        created = self.attempts.get(attempt_id, {"nonce": "", "challenge": "left"})
        turn = selfie + b"@" + created["challenge"].encode() if turn is None else turn
        self.auth(username)
        private = key or self.keys.get(username, (None, None))[0]
        if signature is None and private is not None:
            message = canonical_message(
                attempt_id=attempt_id, nonce=created["nonce"], selfie=selfie, turn_photo=turn,
                lat=lat if signed_lat is None else signed_lat, lng=lng, accuracy_m=accuracy,
                captured_at=captured_at, is_mock=mock, rooted=rooted, emulator=emulator,
            )
            signature = base64.b64encode(private.sign(message, ec.ECDSA(hashes.SHA256()))).decode()
        data = {
            "lat": f"{lat:.6f}", "lng": f"{lng:.6f}", "accuracy_m": f"{accuracy:.1f}",
            "location_captured_at": to_iso(captured_at), "location_is_mock": str(mock).lower(),
            "device_rooted": str(rooted).lower(), "device_emulator": str(emulator).lower(),
            "signature": signature or "AAAA",
        }
        files = {"selfie": ("s.jpg", selfie, "image/jpeg"), "turn_photo": ("t.jpg", turn, "image/jpeg")}
        return data, files

    def submit(self, username: str, attempt_id: str, selfie: bytes, *args, **kw):
        data, files = self.signed_form(username, attempt_id, selfie, *args, **kw)
        return self.post(username, f"/attempts/{attempt_id}/submit", data=data, files=files)

    def do(self, username: str, activity_id: str, action: str, identity: str, **kw):
        """Create an attempt and submit a matching selfie; returns the submit response."""
        created = self.attempt(username, activity_id, action)
        assert created.status_code == 201, created.text
        return self.submit(username, created.json()["id"], face(identity), **kw)
