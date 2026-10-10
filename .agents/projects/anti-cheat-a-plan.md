# Anti-cheat Piece A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock each student to one phone, flag fake-GPS / rooted / emulator / repeated locations, and ask for a blink plus a server-chosen head turn that the server checks — all offline, in a sideloaded APK, record-and-flag only.

**Architecture:** The backend gains a `student_devices` table, a pure signing module (`app/device_keys.py`), a pure liveness module (`app/liveness.py`) and five new review reasons; submit verifies an ECDSA signature over a canonical message before judging anything. Android generates an EC key in the Keystore, signs every submit, reports mock/root/emulator flags, and guides the student through straight → blink → turn with bundled ML Kit. The dashboard gets the new reason labels and a Students page with **Reset device**.

**Tech Stack:** FastAPI + SQLAlchemy + `cryptography` 50.0.2 (already a dependency) + OpenCV YuNet/SFace; Kotlin + Compose + CameraX 1.6.2 + bundled `com.google.mlkit:face-detection`; React + TS + Vitest.

**Spec:** `.agents/projects/anti-cheat-a-design.md`

## Global Constraints

- Offline only. No Play Integrity, no Play Services ML Kit (use the **bundled** `com.google.mlkit:face-detection` artifact), no network calls beyond the existing backend.
- Record + flag: no new check ever blocks a check-in except the signature/device errors (`device_key_required`, `device_mismatch`, `device_taken`, `bad_signature`). Everything else is a review reason.
- Contract v1 is frozen: every API change is written in the `contracts/API.md` Addendum.
- Numeric scores never leave the backend (`face.py` rule; decisions/log.md "numeric confidence in API" rejected). Nose offsets stay server-side.
- Student coordinates are never stored; only a 16-hex-char fingerprint.
- Dashboard reason labels must not contain the words confidence, liveness or "live person" (`dashboard/tests/labels.test.ts:27-30`).
- Canonical signed message (both sides must produce identical bytes, UTF-8, `\n` separators, no trailing newline):
  ```
  fieldpass-attempt-v1
  {attempt_id}
  {nonce}
  {sha256 hex of selfie bytes}
  {sha256 hex of turn_photo bytes}
  {lat formatted %.6f, Locale.US}
  {lng formatted %.6f, Locale.US}
  {accuracy_m formatted %.1f, Locale.US}
  {location_captured_at as integer epoch seconds}
  {location_is_mock 1|0}
  {device_rooted 1|0}
  {device_emulator 1|0}
  ```
- Shared test vector (used by Task 1, Task 9 and Task 12): attempt `att_1`, nonce `n0nce`, selfie bytes `A`, turn bytes `B`, lat 12.9716, lng 77.5946, accuracy 10, captured `2026-10-10T09:31:05Z`, flags mock=0 rooted=1 emulator=0 →
  `"fieldpass-attempt-v1\natt_1\nn0nce\n559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd\ndf7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c\n12.971600\n77.594600\n10.0\n1791624665\n0\n1\n0"`
- Key format: public key = Base64 (no wrap) of X.509 SubjectPublicKeyInfo DER, EC secp256r1. Signature = Base64 (no wrap) of DER ECDSA (`SHA256withECDSA` on Android).
- Working tree note: the tree was clean when this plan was refreshed (2026-10-11). Run `git status` before Task 1; if it is dirty, stop and ask.

## Review Focus

1. **Locale with comma decimals** (e.g. a phone set to German): lat sent as `12,971600` breaks parsing and signatures — Task 12 pins `Locale.US` with a test.
2. **Student reinstalls the app / clears data**: new key → `device_mismatch` with a message telling them to ask faculty; the reset must work and the next login must bind — Task 3 test `test_reset_then_new_phone_binds`.
3. **Device reset between attempt creation and submit**: submit must fail cleanly with `device_mismatch`, not a 500 — Task 5 test `test_submit_after_device_reset_is_device_mismatch`.
4. **Turn photo with no detectable face** (head turned too far for YuNet): must become `liveness_failed`, not `unusable_capture`, so the honest student isn't stuck — Task 7 test `test_turn_photo_without_face_is_liveness_failed`.
5. **Idempotent resubmit after a lost response**: replaying the same attempt must return the saved event even though a new signature is not re-verified — Task 5 test `test_resubmit_returns_saved_event_without_new_signature`.

## File map

| File | Responsibility |
|---|---|
| `backend/app/device_keys.py` (new) | canonical message, signature verify, public-key validation, location fingerprint |
| `backend/app/liveness.py` (new) | nose offset from landmarks, head-turn verdict |
| `backend/app/models.py` | `StudentDevice`; `Attempt.nonce/challenge`; `AttendanceEvent.location_fingerprint` |
| `backend/app/routers/auth.py` | bind device on student login |
| `backend/app/routers/activities.py` | `/students` returns `device_bound_at`; reset endpoint |
| `backend/app/routers/attempts.py` | nonce/challenge on create; signature + flags + liveness on submit |
| `backend/app/errors.py`, `schemas.py`, `config.py`, `face.py`, `face_opencv.py` | errors, DTOs, settings, `nose_offset` |
| `backend/tests/helpers.py`, `fakes.py` | test client signs; fake engine knows head poses |
| `contracts/API.md`, `contracts/openapi.yaml`, `contracts/fixtures/*` | Addendum + regenerated artefacts |
| `dashboard/src/api/*`, `lib/labels.ts`, `screens/StudentsScreen.tsx` (new), `App.tsx`, `components/AppShell.tsx` | reasons, students page, reset |
| `android/.../data/device/*` (new) | Keystore signer, integrity checker, canonical message |
| `android/.../capture/*` | mock flag, liveness guide + camera, two photos |
| `android/.../core/*`, `data/repo/*`, `res/values/*` | DTOs, errors, reasons, strings |

---

### Task 1: Backend signing module

**Files:**
- Create: `backend/app/device_keys.py`
- Test: `backend/tests/test_device_keys.py`

**Interfaces:**
- Produces:
  - `canonical_message(*, attempt_id: str, nonce: str, selfie: bytes, turn_photo: bytes, lat: float, lng: float, accuracy_m: float, captured_at: datetime, is_mock: bool, rooted: bool, emulator: bool) -> bytes`
  - `is_valid_public_key(b64: str) -> bool`
  - `verify_signature(public_key_b64: str, message: bytes, signature_b64: str) -> bool` (never raises)
  - `location_fingerprint(lat: float, lng: float) -> str` (16 hex chars)

- [ ] **Step 1: Write the failing tests**

```python
# backend/tests/test_device_keys.py
from __future__ import annotations

import base64
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.device_keys import canonical_message, is_valid_public_key, location_fingerprint, verify_signature

VECTOR = (
    "fieldpass-attempt-v1\natt_1\nn0nce\n"
    "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd\n"
    "df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c\n"
    "12.971600\n77.594600\n10.0\n1791624665\n0\n1\n0"
)


def _key():
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return private, base64.b64encode(der).decode()


def _vector_message() -> bytes:
    return canonical_message(
        attempt_id="att_1", nonce="n0nce", selfie=b"A", turn_photo=b"B", lat=12.9716, lng=77.5946,
        accuracy_m=10, captured_at=datetime(2026, 10, 10, 9, 31, 5, tzinfo=timezone.utc),
        is_mock=False, rooted=True, emulator=False,
    )


def test_canonical_message_matches_shared_vector():
    assert _vector_message() == VECTOR.encode()


def test_valid_signature_verifies():
    private, public_b64 = _key()
    signature = base64.b64encode(private.sign(_vector_message(), ec.ECDSA(hashes.SHA256()))).decode()
    assert verify_signature(public_b64, _vector_message(), signature) is True


def test_signature_over_other_message_fails():
    private, public_b64 = _key()
    signature = base64.b64encode(private.sign(b"something else", ec.ECDSA(hashes.SHA256()))).decode()
    assert verify_signature(public_b64, _vector_message(), signature) is False


def test_signature_from_other_key_fails():
    other, _ = _key()
    _, public_b64 = _key()
    signature = base64.b64encode(other.sign(_vector_message(), ec.ECDSA(hashes.SHA256()))).decode()
    assert verify_signature(public_b64, _vector_message(), signature) is False


def test_garbage_signature_and_key_never_raise():
    _, public_b64 = _key()
    assert verify_signature(public_b64, b"m", "not-base64!!") is False
    assert verify_signature("not-a-key", b"m", "AAAA") is False


def test_public_key_validation():
    _, public_b64 = _key()
    rsa_like = base64.b64encode(b"\x30\x03\x02\x01\x00").decode()
    assert is_valid_public_key(public_b64) is True
    assert is_valid_public_key(rsa_like) is False
    assert is_valid_public_key("") is False
    p384 = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    assert is_valid_public_key(base64.b64encode(p384).decode()) is False


def test_location_fingerprint_is_stable_and_short():
    assert location_fingerprint(12.9716, 77.5946) == "0be44efee92b0b0b"
    assert location_fingerprint(12.9716001, 77.5946) == "0be44efee92b0b0b"  # same at 6 decimals
    assert location_fingerprint(12.971601, 77.5946) != "0be44efee92b0b0b"
```

- [ ] **Step 2: Run to verify failure**

Run (from `backend/`): `python -m pytest tests/test_device_keys.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.device_keys'`

- [ ] **Step 3: Implement**

```python
# backend/app/device_keys.py
"""Phone-bound signatures for attendance submits, and the location fingerprint.

The phone holds an EC P-256 key in the Android Keystore and signs a canonical message per submit.
Coordinates are never stored; only a short fingerprint of them at 6 decimals.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
from datetime import datetime

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

MESSAGE_VERSION = "fieldpass-attempt-v1"
FINGERPRINT_HEX_CHARS = 16


def _flag(value: bool) -> str:
    return "1" if value else "0"


def canonical_message(
    *, attempt_id: str, nonce: str, selfie: bytes, turn_photo: bytes, lat: float, lng: float,
    accuracy_m: float, captured_at: datetime, is_mock: bool, rooted: bool, emulator: bool,
) -> bytes:
    lines = [
        MESSAGE_VERSION, attempt_id, nonce,
        hashlib.sha256(selfie).hexdigest(), hashlib.sha256(turn_photo).hexdigest(),
        f"{lat:.6f}", f"{lng:.6f}", f"{accuracy_m:.1f}", str(int(captured_at.timestamp())),
        _flag(is_mock), _flag(rooted), _flag(emulator),
    ]
    return "\n".join(lines).encode("utf-8")


def _load_public_key(b64: str) -> ec.EllipticCurvePublicKey | None:
    try:
        key = serialization.load_der_public_key(base64.b64decode(b64, validate=True))
    except (ValueError, binascii.Error, TypeError):
        return None
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        return None
    return key


def is_valid_public_key(b64: str) -> bool:
    return bool(b64) and _load_public_key(b64) is not None


def verify_signature(public_key_b64: str, message: bytes, signature_b64: str) -> bool:
    key = _load_public_key(public_key_b64)
    if key is None:
        return False
    try:
        signature = base64.b64decode(signature_b64, validate=True)
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError, binascii.Error):
        return False
    return True


def location_fingerprint(lat: float, lng: float) -> str:
    digest = hashlib.sha256(f"{lat:.6f},{lng:.6f}".encode("utf-8")).hexdigest()
    return digest[:FINGERPRINT_HEX_CHARS]
```

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest tests/test_device_keys.py -q`
Expected: 7 passed

- [ ] **Step 5: Commit**

```bash
git add backend/app/device_keys.py backend/tests/test_device_keys.py
git commit -m "feat: add device signature and location fingerprint helpers"
```

---

### Task 2: Bind a phone at student login

**Files:**
- Create: `backend/app/devices.py`
- Modify: `backend/app/models.py` (add `StudentDevice` after `User`, line ~24), `backend/app/errors.py` (after `too_many_attempts`, line ~96), `backend/app/schemas.py:188-190` (`LoginIn`), `backend/app/routers/auth.py:29-44` (`login`)
- Modify tests: `backend/tests/helpers.py` (Api signs in with a key), `backend/tests/test_auth.py` lines 10, 20, 122 and `backend/tests/test_errors_and_app.py:66` (successful student logins), `backend/scripts/generate_fixtures.py:162`
- Test: `backend/tests/test_devices.py`

**Interfaces:**
- Consumes: `is_valid_public_key` (Task 1)
- Produces: `StudentDevice(student_id, public_key, bound_at)`; `devices.bind_or_check(session, student_id: str, public_key: str | None, now: datetime) -> None`; `devices.bound_key(session, student_id: str) -> str | None`; errors `device_key_required()`, `device_mismatch()`, `device_taken()`, `bad_signature()`; test helper `new_device_key() -> tuple[EllipticCurvePrivateKey, str]` and `Api.keys: dict[str, tuple]`

- [ ] **Step 1: Add the test-key helper and make `Api.auth` send a key for students**

In `backend/tests/helpers.py` add imports and the helper, and change `Api.__init__`/`Api.auth`:

```python
import base64

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


def new_device_key():  # noqa: ANN201
    """A fresh P-256 key pair, public half as Base64 SubjectPublicKeyInfo DER (what the phone sends)."""
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    return private, base64.b64encode(der).decode()
```

```python
    def __init__(self, client, clock) -> None:
        self.client = client
        self.clock = clock
        self._tokens: dict[str, dict[str, str]] = {}
        self.keys: dict[str, tuple] = {}  # username -> (private key, public b64): one phone per student

    def auth(self, username: str) -> dict[str, str]:
        if username not in self._tokens:
            password = FACULTY_PASSWORD if username.startswith("faculty") else PASSWORD
            body = {"username": username, "password": password}
            if not username.startswith("faculty"):
                body["device_public_key"] = self.keys.setdefault(username, new_device_key())[1]
            resp = self.client.post(f"{P}/auth/login", json=body).json()
            self._tokens[username] = {"Authorization": f"Bearer {resp['access_token']}"}
        return self._tokens[username]
```

- [ ] **Step 2: Write the failing tests**

```python
# backend/tests/test_devices.py
from __future__ import annotations

from tests.helpers import FACULTY_PASSWORD, P, PASSWORD, new_device_key


def login(client, username: str, key: str | None, password: str = PASSWORD):
    body = {"username": username, "password": password}
    if key is not None:
        body["device_public_key"] = key
    return client.post(f"{P}/auth/login", json=body)


def code(resp) -> tuple[int, str]:
    return resp.status_code, resp.json()["error"]["code"]


def test_first_login_binds_and_same_phone_logs_in_again(client):
    _, key = new_device_key()
    assert login(client, "student1", key).status_code == 200
    assert login(client, "student1", key).status_code == 200


def test_other_phone_is_device_mismatch(client):
    _, first = new_device_key()
    _, second = new_device_key()
    login(client, "student1", first)
    assert code(login(client, "student1", second)) == (403, "device_mismatch")


def test_phone_of_another_student_is_device_taken(client):
    _, key = new_device_key()
    login(client, "student1", key)
    assert code(login(client, "student2", key)) == (403, "device_taken")


def test_student_without_key_is_device_key_required(client):
    assert code(login(client, "student1", None)) == (422, "device_key_required")


def test_malformed_key_is_validation_error(client):
    assert code(login(client, "student1", "bm90LWEta2V5")) == (422, "validation_error")


def test_faculty_needs_no_key(client):
    assert login(client, "faculty1", None, password=FACULTY_PASSWORD).status_code == 200


def test_wrong_password_is_checked_before_the_device(client):
    assert code(login(client, "student1", None, password="wrong")) == (401, "invalid_credentials")
```

- [ ] **Step 3: Run to verify failure**

Run: `python -m pytest tests/test_devices.py -q`
Expected: FAIL — the "mismatch", "taken", "key required" and "malformed" tests fail (login ignores the key today).

- [ ] **Step 4: Implement model, errors, schema, binding, login**

`backend/app/models.py`, after class `User`:

```python
class StudentDevice(Base):
    """The one phone a student may use. A faculty reset deletes the row; the next login binds again."""

    __tablename__ = "student_devices"
    student_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    public_key: Mapped[str] = mapped_column(Text, unique=True)
    bound_at: Mapped[datetime] = mapped_column(UTCDateTime)
```

`backend/app/errors.py`, after `too_many_attempts`:

```python
def device_key_required() -> ApiError:
    return ApiError(422, "device_key_required", "Update the FieldPass app. This version cannot sign in.")


def device_mismatch() -> ApiError:
    return ApiError(403, "device_mismatch", "This account is linked to another phone. Ask faculty to reset your device.")


def device_taken() -> ApiError:
    return ApiError(403, "device_taken", "This phone is linked to another student.")


def bad_signature() -> ApiError:
    return ApiError(403, "bad_signature", "This check-in could not be verified from your phone. Start again.")
```

`backend/app/schemas.py`, `LoginIn`:

```python
class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)
    # Students only: Base64 SubjectPublicKeyInfo of the phone's Keystore key. Faculty omit it.
    device_public_key: str | None = Field(default=None, max_length=512)
```

`backend/app/devices.py`:

```python
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
```

`backend/app/routers/auth.py`: add `from app import clock, devices, errors` (replace the existing `from app import clock, errors`) and, in `login`, right after the `invalid_credentials` check:

```python
    if user.role == "student":
        devices.bind_or_check(session, user.id, body.device_public_key, clock.now())
```

- [ ] **Step 5: Run the new tests**

Run: `python -m pytest tests/test_devices.py -q`
Expected: 7 passed

- [ ] **Step 6: Fix the raw student logins elsewhere, then run the whole suite**

In `tests/test_auth.py` (lines 10, 20, 122), `tests/test_errors_and_app.py:66` and `scripts/generate_fixtures.py:162`, every login that is expected to **succeed for a student** gets a key. Import `new_device_key` from `tests.helpers` and change the JSON body, for example:

```python
resp = client.post(f"{P}/auth/login", json={"username": "student1", "password": PASSWORD,
                                            "device_public_key": new_device_key()[1]})
```

For the parametrised test at `test_auth.py:20`, add the key only when `username` does not start with `faculty`. Logins expected to fail on the password stay unchanged.

One phone per student: if the same test (or script) also uses `api` for that student afterwards, the raw login must send the key `api` will send, or the second login gets `device_mismatch`. Use `api.keys.setdefault(username, new_device_key())[1]` there. This is required in `scripts/generate_fixtures.py:162` (student1 is used through `api` later):

```python
            login = client.post(f"{P}/auth/login", json={"username": "student1", "password": SEED_PASSWORD,
                                                         "device_public_key": api.keys.setdefault("student1", new_device_key())[1]}).json()
```

and the script's import becomes `from tests.helpers import P, VENUE, Api, new_device_key, offset_lat  # noqa: E402`.

Run: `python -m pytest -q`
Expected: all pass (the openapi drift test `test_openapi.py` is expected to FAIL until Task 8 re-exports — note it and move on).

- [ ] **Step 7: Commit**

```bash
git add backend/app/devices.py backend/app/models.py backend/app/errors.py backend/app/schemas.py \
        backend/app/routers/auth.py backend/tests/ backend/scripts/generate_fixtures.py
git commit -m "feat: bind each student to one phone at login"
```

---

### Task 3: Students list shows the phone; faculty reset

**Files:**
- Modify: `backend/app/schemas.py` (add `StudentOut`, `StudentList` near `UserOut`), `backend/app/routers/activities.py:42-45` (`list_students`) and add the reset route below it
- Test: `backend/tests/test_devices.py` (append)

**Interfaces:**
- Consumes: `StudentDevice`, `devices.bound_key` (Task 2)
- Produces: `GET /students` → `{"items": [StudentOut]}` where `StudentOut = User + device_bound_at: str | None`; `POST /students/{student_id}/device/reset` (faculty) → `200 StudentOut` with `device_bound_at: null`

- [ ] **Step 1: Write the failing tests** (append to `tests/test_devices.py`)

```python
def students(api) -> dict[str, dict]:
    return {s["username"]: s for s in api.get("faculty1", "/students").json()["items"]}


def test_students_list_shows_when_a_phone_was_bound(api, test_clock):
    assert students(api)["student1"]["device_bound_at"] is None
    api.auth("student1")
    assert students(api)["student1"]["device_bound_at"] == "2026-10-10T09:00:00Z"


def test_reset_then_new_phone_binds(api, client):
    api.auth("student1")
    _, new_phone = new_device_key()
    assert code(login(client, "student1", new_phone)) == (403, "device_mismatch")
    resp = api.post("faculty1", "/students/u_s1/device/reset")
    assert resp.status_code == 200 and resp.json()["device_bound_at"] is None
    assert login(client, "student1", new_phone).status_code == 200


def test_reset_is_faculty_only_and_needs_a_student(api):
    assert api.post("student1", "/students/u_s1/device/reset").status_code == 403
    assert code(api.post("faculty1", "/students/u_f1/device/reset")) == (404, "not_found")
    assert code(api.post("faculty1", "/students/nope/device/reset")) == (404, "not_found")


def test_reset_without_a_bound_phone_is_fine(api):
    resp = api.post("faculty1", "/students/u_s2/device/reset")
    assert resp.status_code == 200 and resp.json()["device_bound_at"] is None
```

- [ ] **Step 2: Run to verify failure**

Run: `python -m pytest tests/test_devices.py -q`
Expected: FAIL — `KeyError: 'device_bound_at'` and 404/405 on the reset path.

- [ ] **Step 3: Implement**

`backend/app/schemas.py`, below `UserOut`:

```python
class StudentOut(UserOut):
    device_bound_at: str | None


class StudentList(BaseModel):
    items: list[StudentOut]
```

`backend/app/routers/activities.py` (imports at lines 6-15): change to `from sqlalchemy import delete, select`, add `StudentDevice` to the `app.models` import (line 11, which already has `VenueRegion`), and add `StudentList, StudentOut` to the `app.schemas` import (line 12; keep `UserList`, `roster` still returns it). `clock` is already imported as a module. Replace `list_students` and add the reset route:

```python
def _student_out(session: Session, student: User) -> StudentOut:
    device = session.get(StudentDevice, student.id)
    return StudentOut(**user_out(student).model_dump(), device_bound_at=clock.to_iso(device.bound_at) if device else None)


@router.get("/students", response_model=StudentList)
def list_students(_: User = Depends(require_faculty), session: Session = Depends(get_session)) -> StudentList:
    rows = session.scalars(select(User).where(User.role == "student").order_by(User.username))
    return StudentList(items=[_student_out(session, u) for u in rows])


@router.post("/students/{student_id}/device/reset", response_model=StudentOut)
def reset_device(
    student_id: str, _: User = Depends(require_faculty), session: Session = Depends(get_session),
) -> StudentOut:
    student = session.get(User, student_id)
    if student is None or student.role != "student":
        raise errors.not_found("That student does not exist.")
    session.execute(delete(StudentDevice).where(StudentDevice.student_id == student_id))
    session.commit()
    return _student_out(session, student)
```

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest tests/test_devices.py -q`
Expected: 11 passed

- [ ] **Step 5: Commit**

```bash
git add backend/app/schemas.py backend/app/routers/activities.py backend/tests/test_devices.py
git commit -m "feat: show bound phone per student and let faculty reset it"
```

---

### Task 4: Attempts carry a nonce and a head-turn challenge

**Files:**
- Modify: `backend/app/models.py` (`Attempt`, lines 80-88), `backend/app/schemas.py:146-152` (`AttemptOut`), `backend/app/routers/attempts.py:45-69` (`create_attempt`)
- Modify tests: `backend/tests/helpers.py` (`Api.attempt` remembers the response), `backend/tests/test_postgres.py:55` (`make_event` builds an `Attempt(...)` directly: add `nonce="n0nce", challenge="left"`; these Postgres tests are opt-in)
- Test: `backend/tests/test_attempts.py` (append)

**Interfaces:**
- Produces: `Attempt.nonce: str`, `Attempt.challenge: str` (`"left"|"right"`); `AttemptOut.nonce`, `AttemptOut.challenge`; `Api.attempts: dict[str, dict]` (attempt id → create response JSON)

- [ ] **Step 1: Write the failing tests** (append to `tests/test_attempts.py`)

```python
def test_attempt_has_nonce_and_challenge(api, ready):
    first = api.attempt("student1", ready["id"], "check_in").json()
    assert len(first["nonce"]) >= 16 and first["challenge"] in ("left", "right")


def test_each_attempt_gets_a_fresh_nonce(api, ready):
    first = api.attempt("student1", ready["id"], "check_in").json()
    second = api.attempt("student1", ready["id"], "check_in").json()
    assert first["nonce"] != second["nonce"]


def test_both_challenge_directions_occur(api, ready):
    seen = {api.attempt("student1", ready["id"], "check_in").json()["challenge"] for _ in range(40)}
    assert seen == {"left", "right"}  # chance of a false failure: 2 * 0.5**40
```

- [ ] **Step 2: Run to verify failure**

Run: `python -m pytest tests/test_attempts.py -q -k "nonce or challenge"`
Expected: FAIL — `KeyError: 'nonce'`

- [ ] **Step 3: Implement**

`models.py`, add to `Attempt` after `expires_at`:

```python
    nonce: Mapped[str] = mapped_column(String)
    challenge: Mapped[str] = mapped_column(String)
```

`schemas.py`, add a literal near the others (line ~15) and two fields to `AttemptOut`:

```python
HeadTurn = Literal["left", "right"]
```

```python
    nonce: str
    challenge: HeadTurn
```

`routers/attempts.py`: `import secrets` at the top; add a constant under `LOCATION_FUTURE_TOLERANCE_SECONDS`:

```python
# Directions are the student's own left and right.
HEAD_TURNS = ("left", "right")
```

In `create_attempt`, add `nonce=secrets.token_urlsafe(16), challenge=secrets.choice(HEAD_TURNS),` to the `Attempt(...)` constructor and `nonce=attempt.nonce, challenge=attempt.challenge,` to the `AttemptOut(...)` return.

`tests/helpers.py`, in `Api.__init__` add `self.attempts: dict[str, dict] = {}` and replace `attempt`:

```python
    def attempt(self, username: str, activity_id: str, action: str):
        resp = self.post(username, f"/activities/{activity_id}/attempts", json={"action": action})
        if resp.status_code == 201:
            self.attempts[resp.json()["id"]] = resp.json()
        return resp
```

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest tests/test_attempts.py -q`
Expected: all pass. The demo DB must be reset (no migrations; `create_all` adds tables, not columns — `db.py:128`). Task 8 documents it.

- [ ] **Step 5: Commit**

```bash
git add backend/app/models.py backend/app/schemas.py backend/app/routers/attempts.py backend/tests/
git commit -m "feat: give each attempt a nonce and a head-turn challenge"
```

---

### Task 5: Submit is signed by the bound phone

**Files:**
- Modify: `backend/app/routers/attempts.py:124-176` (`submit_attempt`)
- Modify tests: `backend/tests/helpers.py` (`signed_form`, `submit`), `backend/tests/test_attempts.py:222-250` (`test_concurrent_duplicate_submits_make_one_event` uses `signed_form`)
- Test: `backend/tests/test_submit_signing.py`

**Interfaces:**
- Consumes: `canonical_message`, `verify_signature` (Task 1); `devices.bound_key` (Task 2); `Attempt.nonce/challenge` (Task 4)
- Produces: submit multipart fields `selfie`, `turn_photo` (files), `lat`, `lng`, `accuracy_m`, `location_captured_at`, `location_is_mock`, `device_rooted`, `device_emulator` (`true|false`), `signature`; `Api.signed_form(...) -> tuple[dict, dict]`; `Api.submit(..., *, turn=None, mock=False, rooted=False, emulator=False, signed_lat=None, key=None, signature=None)`

- [ ] **Step 1: Replace the submit helper with a signing one** (`tests/helpers.py`)

Add imports: `from cryptography.hazmat.primitives import hashes` and `from app.device_keys import canonical_message`. Replace `submit` with:

```python
    def signed_form(self, username: str, attempt_id: str, selfie: bytes, lat: float | None = None,
                    lng: float | None = None, accuracy: float = 10, captured_ago_s: float = 5, *,
                    turn: bytes | None = None, mock: bool = False, rooted: bool = False, emulator: bool = False,
                    signed_lat: float | None = None, key=None, signature: str | None = None):  # noqa: ANN201
        """The multipart body the phone sends. By default the turn photo shows the challenged pose."""
        lat = VENUE[0] if lat is None else lat
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
```

In `tests/test_attempts.py` `test_concurrent_duplicate_submits_make_one_event`, build the body once before the threads and use it in `worker`:

```python
    data, files = api.signed_form("student1", attempt["id"], face("asha"), captured_ago_s=2)
```

```python
            resp = client.post(f"{P}/attempts/{attempt['id']}/submit", headers=headers, data=data, files=files)
```

(remove the now-unused `VENUE` import inside `worker`).

- [ ] **Step 2: Write the failing tests**

```python
# backend/tests/test_submit_signing.py
from __future__ import annotations

import pytest

from tests.fakes import face
from tests.helpers import new_device_key, offset_lat


@pytest.fixture()
def ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def err(resp):
    return resp.status_code, resp.json()["error"]["code"]


def new_attempt(api, ready) -> str:
    return api.attempt("student1", ready["id"], "check_in").json()["id"]


def test_signed_submit_is_recorded(api, ready):
    resp = api.submit("student1", new_attempt(api, ready), face("asha"))
    assert resp.status_code == 200 and resp.json()["status"] == "recorded"


def test_location_changed_after_signing_is_bad_signature(api, ready):
    resp = api.submit("student1", new_attempt(api, ready), face("asha"), lat=offset_lat(500), signed_lat=offset_lat(0))
    assert err(resp) == (403, "bad_signature")


def test_signature_from_another_phone_is_bad_signature(api, ready):
    other, _ = new_device_key()
    assert err(api.submit("student1", new_attempt(api, ready), face("asha"), key=other)) == (403, "bad_signature")


def test_flag_flipped_after_signing_is_bad_signature(api, ready):
    attempt_id = new_attempt(api, ready)
    data, files = api.signed_form("student1", attempt_id, face("asha"))
    data["location_is_mock"] = "true"
    assert err(api.post("student1", f"/attempts/{attempt_id}/submit", data=data, files=files)) == (403, "bad_signature")


def test_submit_after_device_reset_is_device_mismatch(api, ready):
    attempt_id = new_attempt(api, ready)
    api.post("faculty1", "/students/u_s1/device/reset")
    assert err(api.submit("student1", attempt_id, face("asha"))) == (403, "device_mismatch")


def test_resubmit_returns_saved_event_without_new_signature(api, ready):
    attempt_id = new_attempt(api, ready)
    first = api.submit("student1", attempt_id, face("asha")).json()
    again = api.submit("student1", attempt_id, face("asha"), signature="AAAA")
    assert again.status_code == 200 and again.json()["id"] == first["id"]


def test_missing_turn_photo_is_validation_error(api, ready):
    attempt_id = new_attempt(api, ready)
    data, files = api.signed_form("student1", attempt_id, face("asha"))
    del files["turn_photo"]
    assert err(api.post("student1", f"/attempts/{attempt_id}/submit", data=data, files=files)) == (422, "validation_error")
```

- [ ] **Step 3: Run to verify failure**

Run: `python -m pytest tests/test_submit_signing.py -q`
Expected: FAIL — tampering tests return 200 (no verification yet).

- [ ] **Step 4: Implement signature checking in `submit_attempt`**

Imports in `routers/attempts.py`: `from app import clock, devices, errors, rules` and `from app.device_keys import canonical_message, verify_signature`.

New parameters, after `location_captured_at`:

```python
    turn_photo: UploadFile = File(...),
    location_is_mock: bool = Form(...),
    device_rooted: bool = Form(...),
    device_emulator: bool = Form(...),
    signature: str = Form(..., min_length=1, max_length=200),
```

Add a helper above the endpoint:

```python
def _require_signed(session: Session, attempt: Attempt, message: bytes, signature: str) -> None:
    """The submit must be signed by the phone bound to this student (reset since the attempt: mismatch)."""
    key = devices.bound_key(session, attempt.student_id)
    if key is None:
        raise errors.device_mismatch()
    if not verify_signature(key, message, signature):
        raise errors.bad_signature()
```

Replace the block from `captured = clock.parse_iso(...)` through `analysis = require_one_face(...)` with:

```python
    captured = clock.parse_iso(location_captured_at.isoformat())
    selfie_bytes = read_image(selfie)
    turn_bytes = read_image(turn_photo)
    message = canonical_message(
        attempt_id=attempt.id, nonce=attempt.nonce, selfie=selfie_bytes, turn_photo=turn_bytes,
        lat=lat, lng=lng, accuracy_m=accuracy_m, captured_at=captured,
        is_mock=location_is_mock, rooted=device_rooted, emulator=device_emulator,
    )
    _require_signed(session, attempt, message, signature)
    age_seconds = (now - captured).total_seconds()
    if age_seconds > settings.location_max_age_seconds or age_seconds < -LOCATION_FUTURE_TOLERANCE_SECONDS:
        raise errors.location_stale()
    # Photo bytes stay in memory only; they are never written anywhere by this code.
    analysis = require_one_face(engine, selfie_bytes)
```

- [ ] **Step 5: Run to verify pass, then the whole suite**

Run: `python -m pytest tests/test_submit_signing.py -q` → Expected: 7 passed
Run: `python -m pytest -q` → Expected: all pass except `test_openapi.py` (re-exported in Task 8).

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/attempts.py backend/tests/
git commit -m "feat: verify the bound phone's signature on every submit"
```

---

### Task 6: Fake-GPS, rooted, emulator and repeated-location flags

**Files:**
- Modify: `backend/app/models.py:91-123` (`AttendanceEvent`), `backend/app/routers/attempts.py:80-94` (`_build_event`) and the tail of `submit_attempt` (lines 168-176, after the `face_not_matched` refusal)
- Modify tests: `backend/tests/helpers.py` (`signed_form` default location gets a tiny per-submit nudge)
- Test: `backend/tests/test_integrity_flags.py`

**Interfaces:**
- Consumes: `location_fingerprint(lat, lng) -> str` (Task 1); submit form booleans (Task 5)
- Produces: `AttendanceEvent.location_fingerprint: str | None`; `_integrity_reasons(is_mock, rooted, emulator, repeated) -> list[str]`; `_build_event(attempt, activity, now, loc, face, extra_reasons: list[str], fingerprint: str | None)`. **Reason order** on an event: `location_{verdict}`, `face_*`, `liveness_failed` (Task 7), `location_mocked`, `location_repeated`, `device_rooted`, `device_emulator`.

Why the helper changes: with every test submitting the exact venue point, a normal check-in then check-out would now be flagged `location_repeated`. Real GPS never repeats to 6 decimals (about 11 cm), so the helper nudges each default fix by 0.000001° per submit (0.1 m — `distance_m` still rounds to 0).

- [ ] **Step 1: Nudge the helper's default location** (`tests/helpers.py`)

In `Api.__init__` add `self.submits = 0`. In `signed_form` replace the first line with:

```python
        self.submits += 1
        lat = VENUE[0] + self.submits * 0.000001 if lat is None else lat
```

Run: `python -m pytest -q` → Expected: same result as end of Task 5 (no behaviour change yet).

- [ ] **Step 2: Write the failing tests**

```python
# backend/tests/test_integrity_flags.py
from __future__ import annotations

import pytest

from tests.helpers import VENUE


@pytest.fixture()
def ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def reasons(resp) -> tuple[str, list[str]]:
    body = resp.json()
    return body["status"], body["review_reasons"]


def test_clean_submit_has_no_flags(api, ready):
    assert reasons(api.do("student1", ready["id"], "check_in", "asha")) == ("recorded", [])


def test_mock_location_is_flagged(api, ready):
    assert reasons(api.do("student1", ready["id"], "check_in", "asha", mock=True)) == ("needs_review", ["location_mocked"])


def test_rooted_and_emulator_are_flagged_in_order(api, ready):
    resp = api.do("student1", ready["id"], "check_in", "asha", rooted=True, emulator=True)
    assert reasons(resp) == ("needs_review", ["device_rooted", "device_emulator"])


def test_same_point_twice_is_location_repeated(api, ready):
    api.do("student1", ready["id"], "check_in", "asha", lat=VENUE[0])
    resp = api.do("student1", ready["id"], "check_out", "asha", lat=VENUE[0])
    assert reasons(resp) == ("needs_review", ["location_repeated"])


def test_nearby_but_different_point_is_not_repeated(api, ready):
    api.do("student1", ready["id"], "check_in", "asha", lat=VENUE[0])
    resp = api.do("student1", ready["id"], "check_out", "asha", lat=VENUE[0] + 0.000002)
    assert reasons(resp) == ("recorded", [])


def test_same_point_as_another_student_is_not_repeated(api, ready):
    api.enroll("student2", "ravi")
    api.do("student1", ready["id"], "check_in", "asha", lat=VENUE[0])
    resp = api.do("student2", ready["id"], "check_in", "ravi", lat=VENUE[0])
    assert reasons(resp) == ("recorded", [])


def test_flags_add_after_location_and_face_reasons(api, ready):
    # "asha~" is an uncertain match; a clearly different face is refused with face_not_matched and never saved
    resp = api.do("student1", ready["id"], "check_in", "asha~", mock=True)
    assert reasons(resp) == ("needs_review", ["face_uncertain", "location_mocked"])
```

- [ ] **Step 3: Run to verify failure**

Run: `python -m pytest tests/test_integrity_flags.py -q`
Expected: FAIL — flagged cases return `("recorded", [])`.

- [ ] **Step 4: Implement**

`models.py`, add to `AttendanceEvent` after `face_verdict` (nullable so events from before this change still load):

```python
    # Truncated SHA-256 of the coordinates; the coordinates themselves are never stored.
    location_fingerprint: Mapped[str | None] = mapped_column(String, nullable=True)
```

`routers/attempts.py`: extend the import to `from app.device_keys import canonical_message, location_fingerprint, verify_signature`. Add above `_build_event`:

```python
def _integrity_reasons(is_mock: bool, rooted: bool, emulator: bool, repeated: bool) -> list[str]:
    """Phone-reported red flags, in the fixed order the dashboard shows them."""
    flags = (("location_mocked", is_mock), ("location_repeated", repeated),
             ("device_rooted", rooted), ("device_emulator", emulator))
    return [reason for reason, raised in flags if raised]
```

Change `_build_event`'s signature and body:

```python
def _build_event(
    attempt: Attempt, activity: Activity, now: datetime, loc: tuple[str, int, int, datetime], face: str,
    extra_reasons: list[str], fingerprint: str | None,
) -> AttendanceEvent:
    loc_verdict, distance, accuracy, captured = loc
    reasons = []
    if loc_verdict != "inside":
        reasons.append(f"location_{loc_verdict}")
    if face != "match":
        reasons.append("face_mismatch" if face == "no_match" else "face_uncertain")
    reasons.extend(extra_reasons)
    return AttendanceEvent(
        id=new_id("evt"), attempt_id=attempt.id, activity_id=activity.id, student_id=attempt.student_id,
        action=attempt.action, status="recorded" if not reasons else "needs_review", received_at=now,
        checkpoint_id=attempt.checkpoint_id, review_reasons=",".join(reasons), location_verdict=loc_verdict,
        distance_m=distance, accuracy_m=accuracy, captured_at=captured, face_verdict=face,
        location_fingerprint=fingerprint,
    )
```

At the end of `submit_attempt`, replace the last two lines (`saved = _persist(...)`, `return ...`) with:

```python
    fingerprint = location_fingerprint(lat, lng)
    repeated = any(e.location_fingerprint == fingerprint for e in events)
    extra = _integrity_reasons(location_is_mock, device_rooted, device_emulator, repeated)
    saved = _persist(session, _build_event(attempt, activity, now, loc, face, extra, fingerprint), enrollment.id)
    return event_out(saved)
```

(`events` is already this student's events in this activity, loaded by `_student_state` above.)

- [ ] **Step 5: Run to verify pass, then the whole suite**

Run: `python -m pytest tests/test_integrity_flags.py -q` → Expected: 7 passed
Run: `python -m pytest -q` → Expected: all pass except `test_openapi.py`.

- [ ] **Step 6: Commit**

```bash
git add backend/app/models.py backend/app/routers/attempts.py backend/tests/
git commit -m "feat: flag fake GPS, rooted phones, emulators and repeated locations"
```

---

### Task 7: Server checks the head-turn photo

**Files:**
- Create: `backend/app/liveness.py`, `backend/scripts/nose_offset.py` (calibration helper)
- Modify: `backend/app/face.py:17-28` (`FaceAnalysis`), `backend/app/face_opencv.py:76-88` (`analyze`), `backend/app/config.py` (Settings + loader), `backend/.env.example`, `backend/app/routers/attempts.py` (`submit_attempt` tail)
- Modify tests: `backend/tests/fakes.py` (pose suffix), `backend/tests/test_real_engine.py` (opt-in landmark test)
- Test: `backend/tests/test_liveness.py`

**Interfaces:**
- Consumes: `Attempt.challenge` (Task 4); `_build_event(..., extra_reasons, fingerprint)` and reason order (Task 6)
- Produces: `FaceAnalysis.nose_offset: float | None = None`; `liveness.LivenessLimits(front_max_offset: float, turn_min_offset: float, mirrored: bool)`; `liveness.limits_from(settings) -> LivenessLimits`; `liveness.nose_offset_from_landmarks(row) -> float | None`; `liveness.head_turn_ok(front: float | None, turned: float | None, challenge: str, limits: LivenessLimits) -> bool`; settings `liveness_front_max_offset` (0.12), `liveness_turn_min_offset` (0.25), `liveness_mirrored` (False); fake marker `face("asha@left")` = Asha's face turned to her left (offset +0.35), `@right` = −0.35, no suffix = 0.0

How the number works (worked example): YuNet finds the eyes at x=40 and x=60 and the nose at x=57. Eye midpoint 50, eye distance 20, nose offset = (57 − 50) / 20 = **+0.35**. The camera faces the student, so in an unmirrored photo the student's own left is the picture's right: a positive offset means "turned to their left". Challenge `left` passes when the straight photo is within ±0.12 and the turn photo is ≥ +0.25. If the phone's photos turn out mirrored, `LIVENESS_MIRRORED=true` flips the sign. Distances use the true eye distance (not x only) and the eye midpoint, so the result does not depend on which eye YuNet labels "right".

- [ ] **Step 1: Write the failing tests**

```python
# backend/tests/test_liveness.py
from __future__ import annotations

import pytest

from app.liveness import LivenessLimits, head_turn_ok, nose_offset_from_landmarks
from tests.fakes import BLANK, TWO_FACES, face
from tests.test_timing_settings import load

LIMITS = LivenessLimits(front_max_offset=0.12, turn_min_offset=0.25, mirrored=False)


def row(nose_x: float, right_eye=(40.0, 50.0), left_eye=(60.0, 50.0)) -> list[float]:
    """A YuNet row: bbox(4), right eye(2), left eye(2), nose(2), mouth corners(4), score(1)."""
    return [0, 0, 100, 100, *right_eye, *left_eye, nose_x, 60.0, 45, 70, 55, 70, 0.9]


# ---- landmark maths --------------------------------------------------------

def test_centred_nose_is_zero():
    assert nose_offset_from_landmarks(row(50)) == pytest.approx(0.0)


def test_nose_toward_picture_right_is_positive():
    assert nose_offset_from_landmarks(row(57)) == pytest.approx(0.35)


def test_eye_labels_swapped_gives_same_offset():
    assert nose_offset_from_landmarks(row(57, right_eye=(60, 50), left_eye=(40, 50))) == pytest.approx(0.35)


def test_eyes_on_top_of_each_other_is_none():
    assert nose_offset_from_landmarks(row(50, right_eye=(50, 50), left_eye=(50.5, 50))) is None


@pytest.mark.parametrize(("front", "turned", "challenge", "ok"), [
    (0.0, 0.35, "left", True),
    (0.0, 0.25, "left", True),      # exactly on the threshold passes
    (0.0, 0.24, "left", False),
    (0.0, 0.35, "right", False),    # turned the wrong way
    (0.0, -0.35, "right", True),
    (0.2, 0.35, "left", False),     # the "straight" photo was already turned
    (None, 0.35, "left", False),
    (0.0, None, "left", False),
])
def test_head_turn_ok(front, turned, challenge, ok):
    assert head_turn_ok(front, turned, challenge, LIMITS) is ok


def test_mirrored_flips_the_direction():
    mirrored = LivenessLimits(front_max_offset=0.12, turn_min_offset=0.25, mirrored=True)
    assert head_turn_ok(0.0, -0.35, "left", mirrored) and not head_turn_ok(0.0, 0.35, "left", mirrored)


# ---- settings --------------------------------------------------------------

def test_liveness_settings_defaults(tmp_path):
    s = load(tmp_path)
    assert (s.liveness_front_max_offset, s.liveness_turn_min_offset, s.liveness_mirrored) == (0.12, 0.25, False)


def test_liveness_settings_from_env(tmp_path):
    s = load(tmp_path, LIVENESS_FRONT_MAX_OFFSET="0.1", LIVENESS_TURN_MIN_OFFSET="0.3", LIVENESS_MIRRORED="true")
    assert (s.liveness_front_max_offset, s.liveness_turn_min_offset, s.liveness_mirrored) == (0.1, 0.3, True)


# ---- through the API -------------------------------------------------------

@pytest.fixture()
def ready(api, test_clock):
    api.enroll("student1", "asha")
    activity = api.make_activity()
    test_clock.advance(minutes=31)
    return activity


def submit_with_turn(api, ready, turn_for, selfie=face("asha"), **kw):
    """turn_for(challenge) -> turn photo bytes."""
    attempt = api.attempt("student1", ready["id"], "check_in").json()
    return api.submit("student1", attempt["id"], selfie, turn=turn_for(attempt["challenge"]), **kw).json()


def opposite(challenge: str) -> str:
    return "right" if challenge == "left" else "left"


def test_correct_turn_is_recorded(api, ready):
    body = submit_with_turn(api, ready, lambda c: face(f"asha@{c}"))
    assert (body["status"], body["review_reasons"]) == ("recorded", [])


def test_wrong_direction_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: face(f"asha@{opposite(c)}"))["review_reasons"] == ["liveness_failed"]


def test_no_turn_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: face("asha"))["review_reasons"] == ["liveness_failed"]


def test_turn_photo_without_face_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: BLANK)["review_reasons"] == ["liveness_failed"]


def test_turn_photo_with_two_faces_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: TWO_FACES)["review_reasons"] == ["liveness_failed"]


def test_turn_photo_of_someone_else_is_liveness_failed(api, ready):
    assert submit_with_turn(api, ready, lambda c: face(f"ravi@{c}"))["review_reasons"] == ["liveness_failed"]


def test_liveness_reason_sits_between_face_and_flags(api, ready):
    # selfie "asha~" is an uncertain match for the enrolled "asha" (a clear mismatch is refused: face_not_matched)
    body = submit_with_turn(api, ready, lambda c: face("ravi"), selfie=face("asha~"), mock=True)
    assert body["review_reasons"] == ["face_uncertain", "liveness_failed", "location_mocked"]
```

Append to `tests/test_real_engine.py` (opt-in like the rest of that file):

```python
@needs_models
@needs_samples
def test_real_photo_has_a_nose_offset(engine):
    analysis = engine.analyze((SAMPLES / "person_a_1.jpg").read_bytes())
    assert analysis.count == 1 and isinstance(analysis.nose_offset, float)
```

- [ ] **Step 2: Run to verify failure**

Run: `python -m pytest tests/test_liveness.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.liveness'`

- [ ] **Step 3: Implement the maths** (`backend/app/liveness.py`)

```python
"""Head-turn check on the second photo. The phone checks the blink; the server checks the turn.

nose offset = (nose x - eye midpoint x) / eye distance, in picture coordinates. In an unmirrored front-camera
photo the student's own left is the picture's right, so "left" means a positive offset.
"""
from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

from app.config import Settings

# YuNet row layout: bbox 0-3, right eye 4-5, left eye 6-7, nose 8-9, mouth corners 10-13, score 14.
EYE_A, EYE_B, NOSE_X = (4, 5), (6, 7), 8
MIN_EYE_DISTANCE_PX = 1.0


@dataclass(frozen=True)
class LivenessLimits:
    front_max_offset: float
    turn_min_offset: float
    mirrored: bool


def limits_from(settings: Settings) -> LivenessLimits:
    return LivenessLimits(
        settings.liveness_front_max_offset, settings.liveness_turn_min_offset, settings.liveness_mirrored
    )


def nose_offset_from_landmarks(row: Sequence[float]) -> float | None:
    ax, ay = float(row[EYE_A[0]]), float(row[EYE_A[1]])
    bx, by = float(row[EYE_B[0]]), float(row[EYE_B[1]])
    eye_distance = math.hypot(bx - ax, by - ay)
    if eye_distance < MIN_EYE_DISTANCE_PX:
        return None
    return (float(row[NOSE_X]) - (ax + bx) / 2) / eye_distance


def head_turn_ok(front: float | None, turned: float | None, challenge: str, limits: LivenessLimits) -> bool:
    if front is None or turned is None or abs(front) > limits.front_max_offset:
        return False
    direction = 1.0 if challenge == "left" else -1.0
    if limits.mirrored:
        direction = -direction
    return direction * turned >= limits.turn_min_offset
```

`app/face.py`: add the field to `FaceAnalysis` (and one docstring line, `nose_offset: head-turn measure, see app/liveness.py; set when count == 1 and landmarks are usable.`):

```python
    nose_offset: float | None = None
```

`app/face_opencv.py`: `from app.liveness import nose_offset_from_landmarks`; in `analyze`, inside the lock after `feature = ...`, add `offset = nose_offset_from_landmarks(faces[0])`, and return `FaceAnalysis(count=1, embedding=embedding, normalized_jpeg=_to_jpeg(img), nose_offset=offset)`.

`app/config.py`: constants next to the other defaults:

```python
DEFAULT_LIVENESS_FRONT_MAX_OFFSET = 0.12
DEFAULT_LIVENESS_TURN_MIN_OFFSET = 0.25
```

Settings fields, after `face_detect_score` (defaults keep `tests/conftest.py` unchanged):

```python
    liveness_front_max_offset: float = DEFAULT_LIVENESS_FRONT_MAX_OFFSET
    liveness_turn_min_offset: float = DEFAULT_LIVENESS_TURN_MIN_OFFSET
    liveness_mirrored: bool = False
```

In `load_settings`, after `face_detect_score=...`:

```python
        liveness_front_max_offset=float(
            _number(env, "LIVENESS_FRONT_MAX_OFFSET", DEFAULT_LIVENESS_FRONT_MAX_OFFSET, float)
        ),
        liveness_turn_min_offset=float(
            _number(env, "LIVENESS_TURN_MIN_OFFSET", DEFAULT_LIVENESS_TURN_MIN_OFFSET, float)
        ),
        liveness_mirrored=env.get("LIVENESS_MIRRORED", "").strip().lower() in TRUE_WORDS,
```

`backend/.env.example`, after the `FACE_DETECT_SCORE=0.9` line:

```
# Head-turn check: the straight photo's nose offset must be within +/-FRONT_MAX, the turned one past TURN_MIN.
# Set LIVENESS_MIRRORED=true if the phone's front-camera photos come out mirrored (calibrate: scripts/nose_offset.py).
LIVENESS_FRONT_MAX_OFFSET=0.12
LIVENESS_TURN_MIN_OFFSET=0.25
LIVENESS_MIRRORED=false
```

- [ ] **Step 4: Teach the fake engine poses** (`tests/fakes.py`)

Docstring: add the line `b"FACE:alice@left" is alice turned to her own left (nose offset +0.35); "@right" is -0.35; no suffix is 0.0.`
Add the table and change the `FACE:` branch of `analyze`:

```python
POSE_OFFSETS = {b"": 0.0, b"left": 0.35, b"right": -0.35}
```

```python
        if image_bytes.startswith(b"FACE:"):
            identity, _, pose = image_bytes[len(b"FACE:"):].partition(b"@")
            return FaceAnalysis(
                count=1, embedding=identity, normalized_jpeg=image_bytes, nose_offset=POSE_OFFSETS.get(pose, 0.0)
            )
```

- [ ] **Step 5: Check the turn photo in `submit_attempt`**

Imports in `routers/attempts.py`: `from app import clock, devices, errors, liveness, rules` and replace `from app.face import FaceEngine` with `from app.face import NO_MATCH, FaceAnalysis, FaceEngine`. Add above `_integrity_reasons`:

```python
def _liveness_reasons(
    engine: FaceEngine, straight: FaceAnalysis, turn_bytes: bytes, challenge: str, limits: liveness.LivenessLimits
) -> list[str]:
    """Photo B must be one face, the same person as photo A, turned the way the server asked."""
    turned = engine.analyze(turn_bytes)
    if turned.count != 1 or turned.embedding is None or straight.embedding is None:
        return ["liveness_failed"]
    if engine.compare(straight.embedding, turned.embedding) == NO_MATCH:
        return ["liveness_failed"]
    ok = liveness.head_turn_ok(straight.nose_offset, turned.nose_offset, challenge, limits)
    return [] if ok else ["liveness_failed"]
```

In the tail of `submit_attempt` (from Task 6) change the `extra = ...` line to:

```python
    extra = _liveness_reasons(engine, analysis, turn_bytes, attempt.challenge, liveness.limits_from(settings))
    extra += _integrity_reasons(location_is_mock, device_rooted, device_emulator, repeated)
```

The turn photo is analysed in memory and never stored (spec section 3).

- [ ] **Step 6: Add the calibration helper** (`backend/scripts/nose_offset.py`)

```python
"""Print the nose offset of each photo, to calibrate the head-turn thresholds and the mirroring direction.

Usage (from backend/): python scripts/nose_offset.py straight.jpg turned_left.jpg turned_right.jpg
A photo of someone turned to THEIR left should print a positive number; if it prints negative, the phone's
photos are mirrored: set LIVENESS_MIRRORED=true.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import BACKEND_DIR  # noqa: E402
from app.face_opencv import OpenCVFaceEngine  # noqa: E402


def main(paths: list[str]) -> None:
    engine = OpenCVFaceEngine(BACKEND_DIR / "models", threshold=0.363, margin=0.1)
    for path in paths:
        analysis = engine.analyze(Path(path).read_bytes())
        shown = "no single face" if analysis.count != 1 else f"{analysis.nose_offset:+.2f}"
        print(f"{path}: {shown}")


if __name__ == "__main__":
    main(sys.argv[1:])
```

- [ ] **Step 7: Run to verify pass, then the whole suite**

Run: `python -m pytest tests/test_liveness.py -q` → Expected: 22 passed
Run: `python -m pytest -q` → Expected: all pass except `test_openapi.py`; `test_real_engine.py` skips unless models and samples are present.
Run (if models are downloaded): `python scripts/nose_offset.py tests/samples/person_a_1.jpg` → Expected: one line with a signed number near 0.

- [ ] **Step 8: Commit**

```bash
git add backend/app/ backend/scripts/nose_offset.py backend/.env.example backend/tests/
git commit -m "feat: check the server-chosen head turn on a second photo"
```

---

### Task 8: Contract addendum, openapi and fixtures

**Files:**
- Modify: `contracts/API.md` (append to the Addendum, at the end of the file, after the "Multiple venue regions" subsection), `backend/scripts/generate_fixtures.py` (`flow_fixtures`, `error_fixtures`), `integration/known-limitations.md` (append 22–25), `backend/README.md` (reset note under the seed commands)
- Regenerate: `contracts/openapi.yaml`, `contracts/fixtures/*`

**Interfaces:**
- Consumes: everything from Tasks 1–7
- Produces: fixtures `students.json`, `student_device_reset.json`, `error_device_key_required.json`, `error_device_mismatch.json`, `error_device_taken.json`, `error_bad_signature.json`; updated `attempt.json` (has `nonce`, `challenge`). Nothing in the repo reads the fixtures automatically (checked: no references from `android/`, `dashboard/`); they are reference copies for the clients.

- [ ] **Step 1: Append the contract text** (`contracts/API.md`, end of file)

````markdown
### Phone lock, head turn and phone flags

These rules stop a student checking in from someone else's phone, with a photo of a photo, or with fake GPS.
They do not change the `v1` paths.

**Phone lock.** `POST /auth/login` takes an extra field `device_public_key` (string, at most 512 characters):
Base64 of the DER `SubjectPublicKeyInfo` of an EC P-256 key held in the phone's Android Keystore.

- Student login without it: `422 device_key_required`. Faculty logins ignore it.
- First student login binds the key to the student. Later logins must send the same key, otherwise
  `403 device_mismatch`. A key already bound to another student: `403 device_taken`.
- `GET /students` items gain `device_bound_at` (ISO time, or `null` when no phone is bound).
- New: `POST /students/{student_id}/device/reset` (faculty, no body) removes the binding and returns the student
  with `device_bound_at: null`. The student's next login binds whichever phone they use. `404 not_found` if the id
  is not a student.

**Attempts.** `Attempt` gains `nonce` (random string, single use) and `challenge` (`"left"` or `"right"`: the way
the student must turn their head, from the student's own point of view).

**Submit.** `POST /attempts/{id}/submit` multipart gains five required parts:

| part | type | meaning |
|---|---|---|
| `turn_photo` | JPEG file | second photo, head turned the way `challenge` says; checked, never stored |
| `location_is_mock` | `true`/`false` | Android reported the location as coming from a mock provider |
| `device_rooted` | `true`/`false` | the phone shows signs of being rooted |
| `device_emulator` | `true`/`false` | the app is running on an emulator |
| `signature` | string | Base64 DER ECDSA-SHA256 signature by the bound key over the message below |

The signed message is these 12 lines joined by `\n` (no trailing newline), UTF-8:

```
fieldpass-attempt-v1
<attempt id>
<nonce>
<lowercase hex SHA-256 of the selfie bytes>
<lowercase hex SHA-256 of the turn photo bytes>
<lat, 6 decimals, "." separator>
<lng, 6 decimals>
<accuracy_m, 1 decimal>
<location_captured_at as whole Unix seconds>
<1 or 0: location_is_mock>
<1 or 0: device_rooted>
<1 or 0: device_emulator>
```

Send `lat`, `lng` and `accuracy_m` in the form exactly as they appear in the message. Example: attempt `att_1`,
nonce `n0nce`, selfie bytes `A`, turn bytes `B`, 12.9716, 77.5946, accuracy 10, captured
`2026-10-10T09:31:05Z`, not mock, rooted, not emulator →

```
fieldpass-attempt-v1
att_1
n0nce
559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd
df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c
12.971600
77.594600
10.0
1791624665
0
1
0
```

- No phone bound for the student (reset after the attempt was created): `403 device_mismatch`.
  Signature does not verify: `403 bad_signature`. Both are checked before `location_stale`.
- Submitting an attempt that already has an event returns that event without checking the new signature
  (a phone that lost the response can safely retry).

**Review reasons.** `ReviewReason` gains five values. A red flag never blocks the event; it makes it `needs_review`.

| reason | when |
|---|---|
| `liveness_failed` | the turn photo has no single face, is not the same person as the selfie, or is not turned the way `challenge` asked (or the selfie itself was not straight) |
| `location_mocked` | `location_is_mock` was `true` |
| `location_repeated` | the exact same lat/lng (6 decimals) as an earlier event of this student in this activity. Only a hash of the coordinates is kept |
| `device_rooted` | `device_rooted` was `true` |
| `device_emulator` | `device_emulator` was `true` |

Order inside `review_reasons`: location verdict, face, `liveness_failed`, `location_mocked`, `location_repeated`,
`device_rooted`, `device_emulator`.

**Upgrading a demo database.** These changes add columns. The server creates missing tables but not missing
columns, so an older demo database must be recreated: delete the SQLite file (`DATABASE_PATH`) or drop the
Postgres schema (`DATABASE_SCHEMA`), then run the seed command again.
````

Add these rows to the "Extra error codes" table (after `server_error`):

```markdown
| 422 | `device_key_required` | Student login without `device_public_key` |
| 403 | `device_mismatch` | Student login or submit from a phone other than the bound one |
| 403 | `device_taken` | The phone's key is already bound to another student |
| 403 | `bad_signature` | Submit signature does not verify against the bound phone's key |
```

- [ ] **Step 2: Add the new fixtures** (`backend/scripts/generate_fixtures.py`)

`new_device_key` is already imported (Task 2). The seed has only student1-3 and all three are already bound by the earlier fixtures, so the device fixtures use student2 (its token is cached, so it is unaffected by the rebinding below). In `error_fixtures`, after the `already_decided` line:

```python
    keep_error("device_key_required", client.post(f"{P}/auth/login", json={
        "username": "student2", "password": SEED_PASSWORD}), 422)
    keep_error("device_mismatch", client.post(f"{P}/auth/login", json={
        "username": "student2", "password": SEED_PASSWORD, "device_public_key": new_device_key()[1]}), 403)
    keep("students", api.get("faculty1", "/students"), 200)
    keep("student_device_reset", api.post("faculty1", "/students/u_s2/device/reset"), 200)
    keep_error("device_taken", client.post(f"{P}/auth/login", json={
        "username": "student2", "password": SEED_PASSWORD, "device_public_key": api.keys["student1"][1]}), 403)
    client.post(f"{P}/auth/login", json={  # bind student2's own phone again
        "username": "student2", "password": SEED_PASSWORD, "device_public_key": api.keys["student2"][1]})
```

Also in `flow_fixtures` change `face("not-rohan")` to `face("rohan~")` (the `event_needs_review` line): a clearly different face is now refused with `422 face_not_matched` and never saved, so only an uncertain face (`rohan~`) still produces a `needs_review` event. Optionally add `keep_error("face_not_matched", ...)` the same way for a wrong face on a fresh attempt.

Then, before the `location_stale` line (the failed signature does not use up the attempt):

```python
    keep_error("bad_signature", api.submit("student3", fresh["id"], face("fatima2"), signature="AAAA"), 403)
```

`SEED_PASSWORD` is the module-level import from `scripts.fixture_env` (line 23).

- [ ] **Step 3: Regenerate and check**

Run (from `backend/`):

```bash
.venv/bin/python scripts/export_openapi.py
.venv/bin/python scripts/generate_fixtures.py
.venv/bin/python -m pytest -q
grep -c '"nonce"' ../contracts/fixtures/attempt.json
grep -o '"code": "[a-z_]*"' ../contracts/fixtures/error_device_*.json ../contracts/fixtures/error_bad_signature.json
```

Expected: the fixture script prints `wrote N fixtures` (N is 6 more than before); pytest all pass including
`test_openapi.py`; the grep counts `1`; the codes printed are `device_key_required`, `device_mismatch`,
`device_taken`, `bad_signature`.

- [ ] **Step 4: Known limits and README**

Append to `integration/known-limitations.md` under `## Scope`:

```markdown
22. **A modified app on a rooted phone can lie about the phone flags.** The fake-GPS, rooted and emulator flags
    come from the app itself. The rooted flag and `location_repeated` are the backstop. Full cover needs Google
    Play Integrity, which needs internet and Play Store distribution; this demo runs offline from a sideloaded APK.
23. **A replayed video with the right head turn can pass.** The blink is checked on the phone only; the server
    checks the turn direction on a second photo.
24. **Head-turn thresholds are starting values.** `LIVENESS_FRONT_MAX_OFFSET` (0.12), `LIVENESS_TURN_MIN_OFFSET`
    (0.25) and `LIVENESS_MIRRORED` are calibrated with team photos before the demo (`scripts/nose_offset.py`).
25. **Reinstalling the app or clearing its data needs a faculty reset.** The phone key lives in the Android
    Keystore and is lost with the app's data; the dashboard Students page has **Reset phone**.
```

In `backend/README.md`, after the two seed lines (line ~23), add:

```markdown
After pulling the anti-cheat changes, recreate an older demo database (new columns are not added in place):
delete `data/fieldpass.db` (or drop the Postgres schema) and run the seed commands again.
```

- [ ] **Step 5: Commit**

```bash
git add contracts/ backend/scripts/generate_fixtures.py integration/known-limitations.md backend/README.md
git commit -m "docs: contract addendum for phone lock, head turn and phone flags"
```

---

### Task 9: Dashboard end-to-end harness signs like the phone

The Playwright suite (`dashboard/e2e/`) drives a **real** backend and logs students in / submits check-ins from a
script (`e2e/support/studentApi.ts`). Since Task 2 that script's student logins get `422 device_key_required`;
since Task 5 its submits are refused. This task makes the script behave like the phone.

**Files:**
- Create: `dashboard/e2e/support/canonical.ts`, `dashboard/tests/canonical.test.ts`
- Modify: `dashboard/e2e/support/studentApi.ts` (login, createAttempt, submitAttempt, `Attempt`), `dashboard/e2e/support/backend.ts:78-86` (liveness env in `launchBackend`'s `env`, shared by both e2e backends)

**Interfaces:**
- Consumes: the canonical message and test vector (Global Constraints); `Attempt.nonce`
- Produces: `canonicalMessage(input: CanonicalInput): Buffer` in `e2e/support/canonical.ts`; `studentApi` callers unchanged (`login`, `createAttempt`, `submitAttempt(token, attemptId, opts)`, `checkAction`)

Worked example: `fieldpass.spec.ts` logs in `student1` → the script makes (or reloads) an EC key for student1, sends its public half, the backend binds it. `checkAction(..., 'check_in', { photo: 'person_a_1' })` → attempt `att_…` with nonce `Xy…` → the script hashes `person_a_1.jpg` twice (selfie and turn photo are the same file), signs the 12-line message with student1's key, submits. The e2e backend runs with wide head-turn limits because there are no turned-head sample photos; the turn rule itself is proven by `backend/tests/test_liveness.py`.

- [ ] **Step 1: Write the failing test** (`dashboard/tests/canonical.test.ts`)

```ts
// @vitest-environment node
import { canonicalMessage } from '../e2e/support/canonical'

describe('canonical submit message (same vector as backend and Android)', () => {
  it('matches the contract example byte for byte', () => {
    const message = canonicalMessage({
      attemptId: 'att_1', nonce: 'n0nce', selfie: Buffer.from('A'), turn: Buffer.from('B'),
      lat: 12.9716, lng: 77.5946, accuracyM: 10, capturedEpoch: Date.parse('2026-10-10T09:31:05Z') / 1000,
      isMock: false, rooted: true, emulator: false,
    })
    expect(message.toString('utf-8')).toBe([
      'fieldpass-attempt-v1', 'att_1', 'n0nce',
      '559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd',
      'df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c',
      '12.971600', '77.594600', '10.0', '1791624665', '0', '1', '0',
    ].join('\n'))
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run (from `dashboard/`): `npx vitest run tests/canonical.test.ts`
Expected: FAIL — cannot resolve `../e2e/support/canonical`.

- [ ] **Step 3: Implement** (`dashboard/e2e/support/canonical.ts`)

```ts
import { createHash } from 'node:crypto'

export interface CanonicalInput {
  attemptId: string
  nonce: string
  selfie: Buffer
  turn: Buffer
  lat: number
  lng: number
  accuracyM: number
  /** Whole Unix seconds. */
  capturedEpoch: number
  isMock: boolean
  rooted: boolean
  emulator: boolean
}

const sha256Hex = (bytes: Buffer): string => createHash('sha256').update(bytes).digest('hex')
const bit = (flag: boolean): string => (flag ? '1' : '0')

/** The 12 lines the phone signs for a submit (contracts/API.md, "Phone lock, head turn and phone flags"). */
export function canonicalMessage(p: CanonicalInput): Buffer {
  return Buffer.from([
    'fieldpass-attempt-v1', p.attemptId, p.nonce, sha256Hex(p.selfie), sha256Hex(p.turn),
    p.lat.toFixed(6), p.lng.toFixed(6), p.accuracyM.toFixed(1), String(p.capturedEpoch),
    bit(p.isMock), bit(p.rooted), bit(p.emulator),
  ].join('\n'), 'utf-8')
}
```

Run: `npx vitest run tests/canonical.test.ts` → Expected: 1 passed.

- [ ] **Step 4: Make the script log in and submit like the phone** (`e2e/support/studentApi.ts`)

Imports at the top:

```ts
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, type KeyObject } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { BACKEND_URL, passwordFor } from './backend'
import { canonicalMessage } from './canonical'
```

Add `nonce: string` and `challenge: 'left' | 'right'` to `interface Attempt`. Replace `sample` with:

```ts
function sampleBytes(name: Sample): Buffer {
  return readFileSync(path.join(SAMPLES, `${name}.jpg`))
}

const jpeg = (bytes: Buffer): Blob => new Blob([bytes], { type: 'image/jpeg' })

/**
 * One phone key per student per backend. Kept in a temp file so a restarted Playwright worker sends the same
 * key (a new key would be refused with device_mismatch). Each run's backend is freshly seeded, so a key left
 * over from an earlier run simply binds on first login.
 */
function deviceKeys(backendUrl: string): (username: string) => KeyObject {
  const file = path.join(tmpdir(), `fieldpass-e2e-keys-${new URL(backendUrl).port}.json`)
  let pems: Record<string, string> = existsSync(file) ? JSON.parse(readFileSync(file, 'utf-8')) : {}
  return (username) => {
    if (!pems[username]) {
      const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
      pems = { ...pems, [username]: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString() }
      writeFileSync(file, JSON.stringify(pems))
    }
    return createPrivateKey(pems[username] as string)
  }
}

const publicKeyB64 = (key: KeyObject): string =>
  createPublicKey(key).export({ type: 'spki', format: 'der' }).toString('base64')
```

In `enroll`, change `sample(photo)` to `jpeg(sampleBytes(photo))`. Inside `createStudentApi`, after `const root = ...`:

```ts
  const keyFor = deviceKeys(backendUrl)
  const owners = new Map<string, string>() // token -> username
  const nonces = new Map<string, string>() // attempt id -> nonce
  let submits = 0
```

Replace `login`, `createAttempt` and `submitAttempt`:

```ts
  async function login(username: string): Promise<string> {
    const res = await api.post<{ access_token: string }>('/auth/login', null, {
      username, password: passwordFor(username), device_public_key: publicKeyB64(keyFor(username)),
    })
    if (res.status !== 200) throw new Error(`login ${username} failed: ${res.status}`)
    owners.set(res.body.access_token, username)
    return res.body.access_token
  }
```

```ts
  async function createAttempt(token: string, activityId: string, action: string): Promise<ApiResult<Attempt>> {
    const res = await api.post<Attempt>(`/activities/${activityId}/attempts`, token, { action })
    if (res.status === 201) nonces.set(res.body.id, res.body.nonce)
    return res
  }

  /**
   * Submits a selfie and location for an attempt, signed like the phone. Safe to call again with the same id.
   * The selfie doubles as the head-turn photo (the e2e backend runs with wide head-turn limits).
   * Each default location is nudged by 0.000001° (~0.1 m) so check-in and check-out are not flagged as repeated.
   */
  async function submitAttempt(token: string, attemptId: string, opts: SubmitOptions): Promise<ApiResult<AttendanceEventBody>> {
    submits += 1
    const selfie = sampleBytes(opts.photo)
    const lat = opts.lat ?? VENUE.lat + submits * 0.000001
    const lng = opts.lng ?? VENUE.lng
    const accuracyM = opts.accuracyM ?? 15
    const capturedEpoch = Math.floor(Date.now() / 1000)
    const owner = owners.get(token)
    const message = canonicalMessage({
      attemptId, nonce: nonces.get(attemptId) ?? '', selfie, turn: selfie, lat, lng, accuracyM, capturedEpoch,
      isMock: false, rooted: false, emulator: false,
    })
    const form = new FormData()
    form.append('selfie', jpeg(selfie), 'selfie.jpg')
    form.append('turn_photo', jpeg(selfie), 'turn.jpg')
    form.append('lat', lat.toFixed(6))
    form.append('lng', lng.toFixed(6))
    form.append('accuracy_m', accuracyM.toFixed(1))
    form.append('location_captured_at', new Date(capturedEpoch * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z'))
    form.append('location_is_mock', 'false')
    form.append('device_rooted', 'false')
    form.append('device_emulator', 'false')
    form.append('signature', owner ? sign('sha256', message, keyFor(owner)).toString('base64') : 'AAAA')
    return call('POST', `/attempts/${attemptId}/submit`, token, form)
  }
```

(`crypto.sign` with an EC key returns DER, which is what the backend verifies.)

- [ ] **Step 5: Wide head-turn limits for the e2e backends** (`e2e/support/backend.ts`, inside `const env = {...}` before `...settings`)

```ts
    // There are no turned-head sample photos, so the e2e sends the selfie twice and the turn rule is opened
    // wide. backend/tests/test_liveness.py proves the rule itself.
    LIVENESS_FRONT_MAX_OFFSET: '9',
    LIVENESS_TURN_MIN_OFFSET: '-9',
```

- [ ] **Step 6: Run everything the dashboard has**

Run (from `dashboard/`): `npm run typecheck && npm run lint && npm test && npm run e2e`
Expected: all pass. The e2e needs `backend/tests/samples/*.jpg` and the ONNX models, as before; Chromium only.

- [ ] **Step 7: Commit**

```bash
git add dashboard/e2e/support/ dashboard/tests/canonical.test.ts
git commit -m "test: e2e harness binds a phone key and signs submits"
```

---

### Task 10: Dashboard knows the new reasons and the phone reset

**Files:**
- Modify: `dashboard/src/api/types.ts:9-13` (`ReviewReason`), `:17-22` (add `Student` after `User`), `:178` (`Api.listStudents`, add `resetDevice`); `dashboard/src/lib/labels.ts:18-23`; `dashboard/src/api/realClient.ts:97`; `dashboard/src/api/fixtureClient.ts` (`Store`, `createStore`, `listStudents`, new `resetDevice`)
- Test: `dashboard/tests/labels.test.ts:17-31` (extend), `dashboard/tests/studentsApi.test.ts` (new)

**Interfaces:**
- Consumes: contract text from Task 8
- Produces: `type ReviewReason` (+5 values); `interface Student extends User { device_bound_at: string | null }`; `Api.listStudents(): Promise<Student[]>`; `Api.resetDevice(studentId: string): Promise<Student>`. Fixture store: u_s1–u_s3 have a phone bound, u_s4 (Kabir Menon) has none.

Label wording rule (Global Constraints): no "confidence", "liveness" or "live person" — the existing test enforces it.

| reason | label |
|---|---|
| `liveness_failed` | Head-turn check failed |
| `location_mocked` | Fake GPS was on |
| `location_repeated` | Same exact spot as an earlier check-in |
| `device_rooted` | Rooted phone |
| `device_emulator` | Running on an emulator |

- [ ] **Step 1: Write the failing tests**

In `tests/labels.test.ts`, add to the `review reason labels` `cases` array (after `face_uncertain`):

```ts
    ['liveness_failed', 'Head-turn check failed'],
    ['location_mocked', 'Fake GPS was on'],
    ['location_repeated', 'Same exact spot as an earlier check-in'],
    ['device_rooted', 'Rooted phone'],
    ['device_emulator', 'Running on an emulator'],
```

New `tests/studentsApi.test.ts`:

```ts
import { ApiError } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
})

describe('fixture students and phone reset', () => {
  it('lists who has a phone bound', async () => {
    const students = await fixtureApi.listStudents()
    const bound = Object.fromEntries(students.map((s) => [s.username, s.device_bound_at !== null]))
    expect(bound).toEqual({ student1: true, student2: true, student3: true, student4: false })
  })

  it('reset clears the phone and the list shows it', async () => {
    const reset = await fixtureApi.resetDevice('u_s1')
    expect(reset.device_bound_at).toBeNull()
    const again = await fixtureApi.listStudents()
    expect(again.find((s) => s.id === 'u_s1')?.device_bound_at).toBeNull()
  })

  it('reset of an unknown id is not_found', async () => {
    await expect(fixtureApi.resetDevice('u_nobody')).rejects.toMatchObject({ code: 'not_found', status: 404 })
  })

  it('a reset is undone by resetFixtures', async () => {
    await fixtureApi.resetDevice('u_s2')
    resetFixtures()
    await fixtureApi.login('faculty1', 'x')
    expect((await fixtureApi.listStudents()).find((s) => s.id === 'u_s2')?.device_bound_at).not.toBeNull()
  })

  it('students cannot reset phones', async () => {
    await fixtureApi.login('student1', 'x')
    await expect(fixtureApi.resetDevice('u_s1')).rejects.toBeInstanceOf(ApiError)
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run (from `dashboard/`): `npx vitest run tests/labels.test.ts tests/studentsApi.test.ts`
Expected: FAIL — `reasonLabel('liveness_failed')` is `undefined`; `fixtureApi.resetDevice is not a function`.

- [ ] **Step 3: Types** (`src/api/types.ts`)

```ts
export type ReviewReason =
  | 'location_uncertain'
  | 'location_outside'
  | 'face_mismatch'
  | 'face_uncertain'
  | 'liveness_failed'
  | 'location_mocked'
  | 'location_repeated'
  | 'device_rooted'
  | 'device_emulator'
```

After `interface User`:

```ts
/** A student as faculty see them: when their phone was bound (null: none, the next login binds one). */
export interface Student extends User {
  device_bound_at: string | null
}
```

In `interface Api` replace `listStudents(): Promise<User[]>` with:

```ts
  listStudents(): Promise<Student[]>
  resetDevice(studentId: string): Promise<Student>
```

- [ ] **Step 4: Labels** (`src/lib/labels.ts`, `REASON_LABELS`)

```ts
export const REASON_LABELS: Record<ReviewReason, string> = {
  location_uncertain: 'Location uncertain',
  location_outside: 'Location outside the boundary',
  face_mismatch: 'Face comparison did not match',
  face_uncertain: 'Face comparison uncertain',
  liveness_failed: 'Head-turn check failed',
  location_mocked: 'Fake GPS was on',
  location_repeated: 'Same exact spot as an earlier check-in',
  device_rooted: 'Rooted phone',
  device_emulator: 'Running on an emulator',
}
```

- [ ] **Step 5: Real client** (`src/api/realClient.ts`; add `Student` to the type import)

```ts
  listStudents: async () => (await json<{ items: Student[] }>('/students')).items,
  resetDevice: (id) => json<Student>(`/students/${encodeURIComponent(id)}/device/reset`, { method: 'POST' }),
```

- [ ] **Step 6: Fixture client** (`src/api/fixtureClient.ts`; add `Student` to the type import)

`Store` gains `devices: Record<string, string | null>` (student id → bound time). In `createStore`:

```ts
  const devices: Record<string, string | null> = {
    u_s1: iso(now - 3 * DAY), u_s2: iso(now - 3 * DAY), u_s3: iso(now - 1 * DAY), u_s4: null,
  }
  return { activities, rows, enrollments: seedEnrollments(now), counter: 100, devices }
```

with `const DAY = 24 * 60 * MIN` next to `const MIN`. Add a helper and the two methods:

```ts
function withDevice(student: User): Student {
  return { ...student, device_bound_at: store.devices[student.id] ?? null }
}
```

```ts
  listStudents: async () => { await wait(); requireFaculty(); return copy(STUDENTS.map(withDevice)) },
  resetDevice: async (id) => {
    await wait()
    requireFaculty()
    const student = STUDENTS.find((s) => s.id === id)
    if (!student) throw new ApiError('not_found', 'No such student', 404)
    store = { ...store, devices: { ...store.devices, [id]: null } }
    return copy(withDevice(student))
  },
```

- [ ] **Step 7: Run to verify pass, then all dashboard checks**

Run: `npx vitest run tests/labels.test.ts tests/studentsApi.test.ts` → Expected: all pass (9 reason cases, the wording test, 5 student tests).
Run: `npm run typecheck && npm run lint && npm test` → Expected: all pass. `ActivityCreateScreen` still compiles (`Student` is a `User`).

- [ ] **Step 8: Commit**

```bash
git add dashboard/src/api/ dashboard/src/lib/labels.ts dashboard/tests/
git commit -m "feat: dashboard labels the new review reasons and can reset a phone"
```

---

### Task 11: Dashboard Students page with "Reset phone"

**Files:**
- Create: `dashboard/src/screens/StudentsScreen.tsx`, `dashboard/tests/studentsScreen.test.tsx`
- Modify: `dashboard/src/App.tsx:21-22` (route, after `/activities/:activityId`), `dashboard/src/components/AppShell.tsx:17-19` (nav link)

**Interfaces:**
- Consumes: `api.listStudents(): Promise<Student[]>`, `api.resetDevice(id)` (Task 10); `useLoad`, `Notice`, `Loading`, `Empty`, `messageForError`, `formatDateTime`
- Produces: route `/students`; nav link "Students"

Worked example (fixture mode): faculty open **Students**. Asha Verma shows "Phone linked 8 Oct, 10:15" and a **Reset phone** button; Kabir Menon shows "No phone linked yet. Their next sign-in links the phone they use." and no button. Faculty press **Reset phone** on Asha → the row asks "Unlink Asha Verma's phone?" with **Yes, unlink** and **Cancel** (a two-step press, so one stray tap does nothing) → **Yes, unlink** → green notice "Unlinked Asha Verma's phone. Their next sign-in links the phone they use." and Asha's row now reads "No phone linked yet…". Layout reuses the existing `card activity-row` styles; no new CSS.

- [ ] **Step 1: Write the failing test** (`dashboard/tests/studentsScreen.test.tsx`)

```tsx
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '../src/api'
import { fixtureApi, resetFixtures } from '../src/api/fixtureClient'
import { StudentsScreen } from '../src/screens/StudentsScreen'

vi.mock('../src/api', async (orig) => {
  const real = await orig<typeof import('../src/api')>()
  const fx = await import('../src/api/fixtureClient')
  return { ...real, api: fx.fixtureApi }
})

beforeEach(async () => {
  resetFixtures()
  await fixtureApi.login('faculty1', 'x')
})

afterEach(() => { vi.restoreAllMocks() })

const row = async (name: string) => (await screen.findByRole('heading', { name })).closest('li') as HTMLElement

describe('Students page', () => {
  it('shows who has a phone linked', async () => {
    render(<StudentsScreen />)
    expect(within(await row('Asha Verma')).getByText(/^Phone linked/)).toBeInTheDocument()
    const kabir = await row('Kabir Menon')
    expect(within(kabir).getByText(/No phone linked yet/)).toBeInTheDocument()
    expect(within(kabir).queryByRole('button', { name: /Reset phone/ })).toBeNull()
  })

  it('reset asks once more, then unlinks and says so', async () => {
    render(<StudentsScreen />)
    await userEvent.click(within(await row('Asha Verma')).getByRole('button', { name: 'Reset phone for Asha Verma' }))
    expect(screen.getByText("Unlink Asha Verma's phone?")).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Yes, unlink' }))
    expect(await screen.findByText("Unlinked Asha Verma's phone. Their next sign-in links the phone they use.")).toBeInTheDocument()
    await vi.waitFor(async () => expect(within(await row('Asha Verma')).getByText(/No phone linked yet/)).toBeInTheDocument())
  })

  it('cancel leaves the phone linked', async () => {
    const spy = vi.spyOn(fixtureApi, 'resetDevice')
    render(<StudentsScreen />)
    await userEvent.click(within(await row('Asha Verma')).getByRole('button', { name: 'Reset phone for Asha Verma' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(spy).not.toHaveBeenCalled()
    expect(within(await row('Asha Verma')).getByText(/^Phone linked/)).toBeInTheDocument()
  })

  it('a failed reset shows the friendly error, not server text', async () => {
    vi.spyOn(fixtureApi, 'resetDevice').mockRejectedValue(new ApiError('not_found', 'raw server text', 404))
    render(<StudentsScreen />)
    await userEvent.click(within(await row('Asha Verma')).getByRole('button', { name: 'Reset phone for Asha Verma' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, unlink' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('That item no longer exists.')
    expect(screen.queryByText('raw server text')).toBeNull()
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run (from `dashboard/`): `npx vitest run tests/studentsScreen.test.tsx`
Expected: FAIL — cannot resolve `../src/screens/StudentsScreen`.

- [ ] **Step 3: Implement** (`dashboard/src/screens/StudentsScreen.tsx`)

```tsx
import { useState } from 'react'
import { api, messageForError } from '../api'
import type { Student } from '../api'
import { Empty, Loading, Notice } from '../components/Notice'
import { useLoad } from '../hooks/useLoad'
import { formatDateTime } from '../lib/time'

const NEXT_SIGN_IN = 'Their next sign-in links the phone they use.'

interface RowProps {
  student: Student
  isAsking: boolean
  isBusy: boolean
  onAsk: () => void
  onCancel: () => void
  onConfirm: () => void
}

function StudentRow({ student, isAsking, isBusy, onAsk, onCancel, onConfirm }: RowProps) {
  const bound = student.device_bound_at
  return (
    <li className="card activity-row">
      <div>
        <h2>{student.full_name}</h2>
        <p className="muted">{student.username}</p>
        <p>{bound ? `Phone linked ${formatDateTime(bound)}` : `No phone linked yet. ${NEXT_SIGN_IN}`}</p>
      </div>
      {bound && !isAsking ? (
        <button type="button" className="btn" onClick={onAsk} aria-label={`Reset phone for ${student.full_name}`}>
          Reset phone
        </button>
      ) : null}
      {bound && isAsking ? (
        <div className="btn-row">
          <span>Unlink {student.full_name}&apos;s phone?</span>
          <button type="button" className="btn btn-primary" onClick={onConfirm} disabled={isBusy}>Yes, unlink</button>
          <button type="button" className="btn" onClick={onCancel} disabled={isBusy}>Cancel</button>
        </div>
      ) : null}
    </li>
  )
}

/** Faculty see which phone each student is locked to, and unlink it when a student changes phone. */
export function StudentsScreen() {
  const { data, isLoading, error, reload } = useLoad(() => api.listStudents())
  const [askingId, setAskingId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  async function confirmReset(student: Student): Promise<void> {
    setBusy(true)
    setFailure(null)
    try {
      await api.resetDevice(student.id)
      setDone(`Unlinked ${student.full_name}'s phone. ${NEXT_SIGN_IN}`)
      setAskingId(null)
      reload()
    } catch (err) {
      setDone(null)
      setFailure(messageForError(err))
    } finally {
      setBusy(false)
    }
  }

  const students = data ?? []
  return (
    <>
      <div className="page-head">
        <h1>Students and their phones</h1>
        <button type="button" className="btn btn-small" onClick={reload} disabled={isLoading}>Refresh</button>
      </div>
      <p className="muted">Each student can check in from one phone only. Reset it when a student gets a new phone or reinstalls the app.</p>
      {done ? <Notice kind="ok">{done}</Notice> : null}
      {failure ? <Notice kind="error">{failure}</Notice> : null}
      {error ? <Notice kind="error">{error}</Notice> : null}
      {isLoading && !data ? <Loading what="students" /> : null}
      {data && students.length === 0 ? <Empty>No students yet.</Empty> : null}
      <ul className="list-plain activity-list">
        {students.map((s) => (
          <StudentRow
            key={s.id}
            student={s}
            isAsking={askingId === s.id}
            isBusy={busy}
            onAsk={() => { setAskingId(s.id); setDone(null); setFailure(null) }}
            onCancel={() => setAskingId(null)}
            onConfirm={() => { void confirmReset(s) }}
          />
        ))}
      </ul>
    </>
  )
}
```

(`src/api/index.ts` already exports `messageForError` and re-exports every type with `export type * from './types'`.)

`src/App.tsx`: `import { StudentsScreen } from './screens/StudentsScreen'` and, after the `/activities/:activityId` route:

```tsx
        <Route path="/students" element={<StudentsScreen />} />
```

`src/components/AppShell.tsx`, after the Activities link:

```tsx
          <NavLink to="/students">Students</NavLink>
```

- [ ] **Step 4: Run to verify pass, then all checks**

Run: `npx vitest run tests/studentsScreen.test.tsx` → Expected: 4 passed.
Run: `npm run typecheck && npm run lint && npm test` → Expected: all pass.

- [ ] **Step 5: Render it and look** (user rule: describe ≠ proof)

Start fixture mode: `VITE_USE_FIXTURES=true npm run dev` (background). Save this script as `<scratchpad>/students-shots.mjs` and run it from `dashboard/` with `node <scratchpad>/students-shots.mjs`:

```js
import { chromium } from '@playwright/test'

const browser = await chromium.launch()
for (const width of [320, 768, 1440]) {
  const page = await browser.newPage({ viewport: { width, height: 900 } })
  await page.goto('http://localhost:5173/')
  await page.getByLabel('Username').fill('faculty1')
  await page.getByLabel('Password').fill('x')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('link', { name: 'Students' }).click()
  await page.getByRole('heading', { name: 'Students and their phones' }).waitFor()
  await page.getByRole('button', { name: 'Reset phone for Asha Verma' }).click()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  console.log(`width ${width}: horizontal overflow ${overflow}px`)
  await page.screenshot({ path: `students-${width}.png`, fullPage: true })
  await page.close()
}
await browser.close()
```

Expected: each line prints `horizontal overflow 0px`. Open the three PNGs and check: nav shows Students; Asha's row shows the confirm question with both buttons; at 320 px the buttons wrap below the text, nothing is cut off. Delete the PNGs afterwards (generated screenshots are not committed — see commit 93a491a).

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/screens/StudentsScreen.tsx dashboard/src/App.tsx dashboard/src/components/AppShell.tsx dashboard/tests/studentsScreen.test.tsx
git commit -m "feat: Students page shows each student's phone and can reset it"
```

---

### Task 12: Android — phone key, canonical message, login sends the key

All Android commands run from `android/`. Unit tests are plain JVM tests (no Robolectric), so anything under test uses `java.util.Base64`/`java.security`, never `android.util.*`.

**Files:**
- Create: `android/app/src/main/java/com/fieldpass/student/data/device/DeviceSigner.kt`, `.../data/device/KeystoreDeviceSigner.kt`, `.../data/device/CanonicalMessage.kt`
- Create test: `android/app/src/test/java/com/fieldpass/student/data/CanonicalMessageTest.kt`
- Modify: `.../core/model/LocationFix.kt` (`isMock`), `.../core/model/Models.kt:15` (`LoginRequest`), `.../data/repo/RealFieldPassRepository.kt:24-35` (constructor + `login`), `.../AppContainer.kt`, `.../core/error/ErrorCode.kt`, `app/src/main/res/values/strings_errors.xml`
- Modify tests: `.../TestSupport.kt` (`SoftwareDeviceSigner`), `.../data/RealRepositoryTest.kt:36,44-57`, `.../integration/IntegrationSupport.kt:52-56`, `.../integration/BackendContractTest.kt:70,238,285,293`, `.../integration/TimingContractTest.kt:62`

**Interfaces:**
- Consumes: canonical message + test vector (Global Constraints)
- Produces: `interface DeviceSigner { fun publicKeyBase64(): String; fun sign(message: ByteArray): String }`; `KeystoreDeviceSigner()`; `SubmitFormat.coordinate(Double): String`, `SubmitFormat.accuracy(Double): String`; `canonicalMessage(attemptId: String, nonce: String, selfie: ByteArray, turnPhoto: ByteArray, fix: LocationFix, rooted: Boolean, emulator: Boolean): ByteArray`; `LocationFix.isMock: Boolean = false`; `LoginRequest.devicePublicKey: String?`; `RealFieldPassRepository(api, session, signer)`; `ErrorCode.DEVICE_KEY_REQUIRED / DEVICE_MISMATCH / DEVICE_TAKEN / BAD_SIGNATURE`; test class `SoftwareDeviceSigner`

Worked example: Arjun installs the APK and signs in. The app asks the Android Keystore for the key named `fieldpass_device_key`; none exists, so the Keystore makes one (the private half can never leave the phone). The app sends the public half with the password. Tuesday he signs in on Meena's phone: her Keystore has her key, the server answers `device_mismatch`, the login screen shows "Your account is linked to a different phone…".

Why the lat/lng strings matter: the phone formats `12.9716` as `12.971600` **once**, puts that text in the form *and* in the signed message. The server parses the text and re-formats it to the same text. A phone set to German would write `12,971600` with the default formatter — hence `Locale.US` and a test.

- [ ] **Step 1: Write the failing tests** (`.../test/.../data/CanonicalMessageTest.kt`)

```kotlin
package com.fieldpass.student.data

import com.fieldpass.student.SoftwareDeviceSigner
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.data.device.SubmitFormat
import com.fieldpass.student.data.device.canonicalMessage
import java.security.Signature
import java.util.Base64
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMessageTest {
    private val fix = LocationFix(12.9716, 77.5946, 10.0, "2026-10-10T09:31:05Z", isMock = false)

    private fun vector() = canonicalMessage(
        attemptId = "att_1", nonce = "n0nce", selfie = "A".toByteArray(), turnPhoto = "B".toByteArray(),
        fix = fix, rooted = true, emulator = false,
    )

    private val expected = listOf(
        "fieldpass-attempt-v1", "att_1", "n0nce",
        "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd",
        "df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c",
        "12.971600", "77.594600", "10.0", "1791624665", "0", "1", "0",
    ).joinToString("\n")

    @Test fun `matches the shared vector byte for byte`() {
        assertEquals(expected, String(vector(), Charsets.UTF_8))
    }

    @Test fun `a comma-decimal phone locale does not change the message`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals(expected, String(vector(), Charsets.UTF_8))
            assertEquals("12.971600", SubmitFormat.coordinate(12.9716))
            assertEquals("10.0", SubmitFormat.accuracy(10.0))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `mock flag is the tenth line`() {
        val mocked = canonicalMessage("att_1", "n0nce", "A".toByteArray(), "B".toByteArray(), fix.copy(isMock = true), true, false)
        assertEquals("1", String(mocked, Charsets.UTF_8).split("\n")[9])
    }

    @Test fun `software signer makes a DER signature its own public key verifies`() {
        val signer = SoftwareDeviceSigner()
        val signature = Base64.getDecoder().decode(signer.sign(vector()))
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(signer.publicKey); update(vector()) }
        assertTrue(verifier.verify(signature))
        assertEquals(signer.publicKeyBase64(), Base64.getEncoder().encodeToString(signer.publicKey.encoded))
    }
}
```

In `RealRepositoryTest.studentLoginStoresTokenAndFacultyLoginDoesNot`, after `assertNull(login.getHeader("Authorization"))` add:

```kotlin
        assertTrue(login.body.readUtf8().contains("\"device_public_key\":\"${signer.publicKeyBase64()}\""))
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "*CanonicalMessageTest*"`
Expected: FAIL — compilation error, `Unresolved reference: SoftwareDeviceSigner` / `canonicalMessage`.

- [ ] **Step 3: Implement the signer and the message**

`.../data/device/DeviceSigner.kt`:

```kotlin
package com.fieldpass.student.data.device

/** This phone's identity: an EC P-256 key pair whose private half never leaves the device. */
interface DeviceSigner {
    /** Base64 (no line breaks) of the DER SubjectPublicKeyInfo. Sent at login. */
    fun publicKeyBase64(): String

    /** Base64 of a DER ECDSA-SHA256 signature over [message]. */
    fun sign(message: ByteArray): String
}
```

`.../data/device/KeystoreDeviceSigner.kt`:

```kotlin
package com.fieldpass.student.data.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Key pair held in the Android Keystore (non-exportable; works offline and in a sideloaded APK).
 * It is created on first use and lost when the app's data is cleared or the app is reinstalled:
 * the server then answers device_mismatch and faculty reset the phone on the dashboard.
 */
class KeystoreDeviceSigner : DeviceSigner {
    override fun publicKeyBase64(): String =
        Base64.getEncoder().encodeToString(entry().certificate.publicKey.encoded)

    override fun sign(message: ByteArray): String {
        val signature = Signature.getInstance(ALGORITHM).apply {
            initSign(entry().privateKey)
            update(message)
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    @Synchronized
    private fun entry(): KeyStore.PrivateKeyEntry = load() ?: run {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        checkNotNull(load()) { "device key missing right after it was created" }
    }

    private fun load(): KeyStore.PrivateKeyEntry? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fieldpass_device_key" // not the token key's alias (KeystoreTokenStore)
        const val CURVE = "secp256r1"
        const val ALGORITHM = "SHA256withECDSA"
    }
}
```

`.../data/device/CanonicalMessage.kt`:

```kotlin
package com.fieldpass.student.data.device

import com.fieldpass.student.core.model.LocationFix
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** Number text used BOTH in the submit form and in the signed message, so the two can never differ. */
object SubmitFormat {
    fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
    fun accuracy(value: Double): String = String.format(Locale.US, "%.1f", value)
    fun flag(value: Boolean): String = if (value) "1" else "0"
}

private const val MESSAGE_VERSION = "fieldpass-attempt-v1"

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

/** The 12 lines the phone signs for a submit (contracts/API.md, "Phone lock, head turn and phone flags"). */
fun canonicalMessage(
    attemptId: String,
    nonce: String,
    selfie: ByteArray,
    turnPhoto: ByteArray,
    fix: LocationFix,
    rooted: Boolean,
    emulator: Boolean,
): ByteArray = listOf(
    MESSAGE_VERSION, attemptId, nonce, sha256Hex(selfie), sha256Hex(turnPhoto),
    SubmitFormat.coordinate(fix.lat), SubmitFormat.coordinate(fix.lng), SubmitFormat.accuracy(fix.accuracyM),
    Instant.parse(fix.capturedAt).epochSecond.toString(),
    SubmitFormat.flag(fix.isMock), SubmitFormat.flag(rooted), SubmitFormat.flag(emulator),
).joinToString("\n").toByteArray(Charsets.UTF_8)
```

`LocationFix.kt`: add a fifth constructor property and update the KDoc:

```kotlin
    /** True when Android says this reading came from a mock ("fake GPS") provider. */
    val isMock: Boolean = false,
```

`TestSupport.kt`: add

```kotlin
/** JVM stand-in for the Keystore signer: same key type, same signature format. */
class SoftwareDeviceSigner : DeviceSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicKey: PublicKey get() = pair.public
    override fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(pair.public.encoded)
    override fun sign(message: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }
        return Base64.getEncoder().encodeToString(signature.sign())
    }
}
```

(imports: `com.fieldpass.student.data.device.DeviceSigner`, `java.security.KeyPairGenerator`, `java.security.PublicKey`, `java.security.Signature`, `java.security.spec.ECGenParameterSpec`, `java.util.Base64`).

- [ ] **Step 4: Send the key at login**

`Models.kt:15`:

```kotlin
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_public_key") val devicePublicKey: String? = null,
)
```

`RealFieldPassRepository.kt`: add the constructor parameter `private val signer: DeviceSigner,` after `session`, and change the first line of `login`:

```kotlin
        val response = call { api.login(LoginRequest(username.trim(), password, signer.publicKeyBase64())) }
```

`AppContainer.kt`: `real = RealFieldPassRepository(ApiClient.create(settings, session), session, KeystoreDeviceSigner()),` (import `com.fieldpass.student.data.device.KeystoreDeviceSigner`).

`RealRepositoryTest.kt`: add a field `private val signer = SoftwareDeviceSigner()` and pass it at line 36: `RealFieldPassRepository(ApiClient.create(settings, session), session, signer)`.

`IntegrationSupport.kt:52-56` (add `import com.fieldpass.student.SoftwareDeviceSigner`): one phone per student, shared by every stack that signs in as that student:

```kotlin
/** One software "phone" per username, so a second stack for the same student is the same phone. */
object TestPhones {
    private val phones = mutableMapOf<String, SoftwareDeviceSigner>()
    fun of(username: String): SoftwareDeviceSigner = synchronized(phones) { phones.getOrPut(username) { SoftwareDeviceSigner() } }
}

class StudentStack(baseUrl: String, username: String = "student1") {
    val session = SessionManager(MemoryTokenStore())
    val api: ApiService = ApiClient.create(FakeSettings(baseUrl), session)
    val repo = RealFieldPassRepository(api, session, TestPhones.of(username))
}
```

In `BackendContractTest.kt` the stacks that sign in as someone else name them: line 238 `StudentStack(base, "student3")`, line 285 `StudentStack(base, "student2")`, line 293 (faculty login) `StudentStack(base, "faculty1")`. The others stay `StudentStack(base)`.

- [ ] **Step 5: New error codes**

`ErrorCode.kt`, after `LOCATION_STALE(...)`:

```kotlin
    DEVICE_KEY_REQUIRED("device_key_required", R.string.error_device_key_required),
    DEVICE_MISMATCH("device_mismatch", R.string.error_device_mismatch),
    DEVICE_TAKEN("device_taken", R.string.error_device_taken),
    BAD_SIGNATURE("bad_signature", R.string.error_bad_signature),
```

`strings_errors.xml`, after `error_location_stale`:

```xml
    <string name="error_device_key_required">This version of the app is too old to sign in. Install the latest FieldPass app.</string>
    <string name="error_device_mismatch">Your account is linked to a different phone. Ask your faculty to reset your phone on the dashboard, then sign in again.</string>
    <string name="error_device_taken">This phone is already linked to another student. Each student must use their own phone.</string>
    <string name="error_bad_signature">This phone could not prove the check-in came from it. Sign out, sign in again, and try once more.</string>
```

Add to `.../test/.../core/ErrorParserTest.kt`:

```kotlin
    @Test fun `phone lock codes map to their own errors`() {
        assertEquals(ErrorCode.DEVICE_MISMATCH, ErrorCode.fromWire("device_mismatch"))
        assertEquals(ErrorCode.DEVICE_TAKEN, ErrorCode.fromWire("device_taken"))
        assertEquals(ErrorCode.DEVICE_KEY_REQUIRED, ErrorCode.fromWire("device_key_required"))
        assertEquals(ErrorCode.BAD_SIGNATURE, ErrorCode.fromWire("bad_signature"))
    }
```

- [ ] **Step 6: Run to verify pass**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL; `CanonicalMessageTest` 4 passed. (Integration tests skip without `FIELDPASS_API_URL`.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src
git commit -m "feat(android): phone key in the Keystore, sent at login"
```

---

### Task 13: Android — phone flags, new reasons, attempt fields

**Files:**
- Create: `.../core/model/SubmitEvidence.kt`, `.../data/device/DeviceIntegrity.kt`; test `.../test/.../data/DeviceIntegrityTest.kt`
- Modify: `.../core/model/Enums.kt:49-55` (+`HeadTurn`), `.../core/model/Models.kt:139-147` (`Attempt`), `.../ui/StatusPresentation.kt:36-41`, `app/src/main/res/values/strings.xml:27-30`, `.../capture/FusedLocationProvider.kt:61-66`, `.../AppContainer.kt`
- Modify tests: `.../data/DtoSerializationTest.kt` (append)

**Interfaces:**
- Consumes: `LocationFix.isMock` (Task 12)
- Produces: `enum HeadTurn { LEFT, RIGHT }`; `Attempt.nonce: String = ""`, `Attempt.challenge: HeadTurn = HeadTurn.LEFT`; `ReviewReason` +`LIVENESS_FAILED, LOCATION_MOCKED, LOCATION_REPEATED, DEVICE_ROOTED, DEVICE_EMULATOR`; `data class DeviceFlags(rooted: Boolean, emulator: Boolean)`; `class SubmitEvidence(selfie: ByteArray, turnPhoto: ByteArray, fix: LocationFix, flags: DeviceFlags)`; `data class DeviceTraits(...)`; `DeviceIntegrity.check(traits: DeviceTraits): DeviceFlags`; `fun interface DeviceIntegritySource { fun flags(): DeviceFlags }`; `BuildDeviceIntegrity()`; `AppContainer.integrity: DeviceIntegritySource`

Why this must ship with the backend: `ReviewReason` is a strict enum. An old app that receives `"location_mocked"` cannot read the answer at all.

Worked example: Ravi's Redmi is rooted with Magisk. `BuildDeviceIntegrity` finds `/data/adb/magisk` → `DeviceFlags(rooted = true, emulator = false)`. He turns on a fake-GPS app → Android marks the reading as mock → `LocationFix.isMock = true`. Both go into the signed submit; the server records the check-in and shows faculty "Fake GPS was on" and "Rooted phone".

- [ ] **Step 1: Write the failing tests**

`.../test/.../data/DeviceIntegrityTest.kt`:

```kotlin
package com.fieldpass.student.data

import com.fieldpass.student.data.device.DeviceIntegrity
import com.fieldpass.student.data.device.DeviceTraits
import com.fieldpass.student.core.model.DeviceFlags
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIntegrityTest {
    private val phone = DeviceTraits(
        buildTags = "release-keys", fingerprint = "Xiaomi/sweet/sweet:13/TKQ1/V14:user/release-keys",
        model = "Redmi Note 10 Pro", hardware = "qcom", product = "sweet", existingPaths = emptySet(),
    )

    @Test fun `a normal phone raises nothing`() =
        assertEquals(DeviceFlags(rooted = false, emulator = false), DeviceIntegrity.check(phone))

    @Test fun `an su or magisk file means rooted`() =
        assertEquals(true, DeviceIntegrity.check(phone.copy(existingPaths = setOf("/data/adb/magisk"))).rooted)

    @Test fun `test-keys means rooted`() =
        assertEquals(true, DeviceIntegrity.check(phone.copy(buildTags = "test-keys")).rooted)

    @Test fun `missing build tags is not rooted`() =
        assertEquals(false, DeviceIntegrity.check(phone.copy(buildTags = null)).rooted)

    @Test fun `the Android emulator is spotted by hardware or model`() {
        assertEquals(true, DeviceIntegrity.check(phone.copy(hardware = "ranchu")).emulator)
        assertEquals(true, DeviceIntegrity.check(phone.copy(model = "sdk_gphone64_arm64")).emulator)
        assertEquals(true, DeviceIntegrity.check(phone.copy(fingerprint = "generic/sdk/generic:14/x:userdebug/test-keys")).emulator)
    }
}
```

Append to `DtoSerializationTest.kt` (reuse that file's `json` instance and imports; add imports for `HeadTurn`, `ReviewReason`):

```kotlin
    @Test fun `attempt carries nonce and head-turn challenge`() {
        val a = json.decodeFromString<Attempt>(
            """{"id": "att_1", "activity_id": "act_1", "action": "check_in", "created_at": "2026-10-10T09:31:00Z",
               "expires_at": "2026-10-10T09:32:30Z", "checkpoint_id": null, "nonce": "n0nce", "challenge": "right"}""",
        )
        assertEquals("n0nce", a.nonce)
        assertEquals(HeadTurn.RIGHT, a.challenge)
    }

    @Test fun `new review reasons decode`() {
        val reasons = json.decodeFromString<List<ReviewReason>>(
            """["liveness_failed","location_mocked","location_repeated","device_rooted","device_emulator"]""",
        )
        assertEquals(
            listOf(ReviewReason.LIVENESS_FAILED, ReviewReason.LOCATION_MOCKED, ReviewReason.LOCATION_REPEATED,
                ReviewReason.DEVICE_ROOTED, ReviewReason.DEVICE_EMULATOR),
            reasons,
        )
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "*DeviceIntegrityTest*" --tests "*DtoSerializationTest*"`
Expected: FAIL — compilation error, `Unresolved reference: DeviceIntegrity`.

- [ ] **Step 3: Implement the models**

`Enums.kt`: extend `ReviewReason` and add `HeadTurn` after it:

```kotlin
    @SerialName("liveness_failed") LIVENESS_FAILED,
    @SerialName("location_mocked") LOCATION_MOCKED,
    @SerialName("location_repeated") LOCATION_REPEATED,
    @SerialName("device_rooted") DEVICE_ROOTED,
    @SerialName("device_emulator") DEVICE_EMULATOR,
```

```kotlin
/** Which way the server asks the student to turn their head (their own left or right). */
@Serializable
enum class HeadTurn {
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}
```

`Models.kt`, `Attempt`: add after `checkpointId`:

```kotlin
    val nonce: String = "",
    val challenge: HeadTurn = HeadTurn.LEFT,
```

`.../core/model/SubmitEvidence.kt`:

```kotlin
package com.fieldpass.student.core.model

/** What the phone reports about itself. The server only flags these for faculty; it never blocks on them. */
data class DeviceFlags(val rooted: Boolean, val emulator: Boolean)

/** Everything one submit carries. A plain class: JPEG bytes keep identity equality, like [Selfie]. */
class SubmitEvidence(
    val selfie: ByteArray,
    val turnPhoto: ByteArray,
    val fix: LocationFix,
    val flags: DeviceFlags,
)
```

- [ ] **Step 4: Implement the integrity check** (`.../data/device/DeviceIntegrity.kt`)

```kotlin
package com.fieldpass.student.data.device

import android.os.Build
import com.fieldpass.student.core.model.DeviceFlags
import java.io.File

/** The facts the check looks at, separated from Android so the rule is a plain function. */
data class DeviceTraits(
    val buildTags: String?,
    val fingerprint: String,
    val model: String,
    val hardware: String,
    val product: String,
    val existingPaths: Set<String>,
)

/**
 * Best-effort signs of a rooted phone or an emulator. A modified app can lie about these
 * (integration/known-limitations.md); they are hints for faculty, not proof.
 */
object DeviceIntegrity {
    val ROOT_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/data/adb/magisk", "/system/bin/magisk",
    )
    private val EMULATOR_HARDWARE = setOf("goldfish", "ranchu")
    private val EMULATOR_MODEL_MARKS = listOf("sdk_gphone", "Emulator", "Android SDK built for")

    fun check(traits: DeviceTraits): DeviceFlags = DeviceFlags(
        rooted = traits.buildTags?.contains("test-keys") == true || traits.existingPaths.isNotEmpty(),
        emulator = traits.hardware in EMULATOR_HARDWARE ||
            EMULATOR_MODEL_MARKS.any { traits.model.contains(it) } ||
            traits.fingerprint.startsWith("generic") ||
            traits.product.contains("sdk_gphone"),
    )
}

fun interface DeviceIntegritySource {
    fun flags(): DeviceFlags
}

/** Reads the real phone. */
class BuildDeviceIntegrity : DeviceIntegritySource {
    override fun flags(): DeviceFlags = DeviceIntegrity.check(
        DeviceTraits(
            buildTags = Build.TAGS, fingerprint = Build.FINGERPRINT, model = Build.MODEL,
            hardware = Build.HARDWARE, product = Build.PRODUCT,
            existingPaths = DeviceIntegrity.ROOT_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }.toSet(),
        ),
    )
}
```

`AppContainer.kt`: add `val integrity: DeviceIntegritySource = BuildDeviceIntegrity()` (import both from `data.device`).

- [ ] **Step 5: Mock-location flag** (`FusedLocationProvider.kt`)

Add to the `LocationFix(...)` call at line 61: `isMock = location.isMockReading(),` and at the bottom of the file:

```kotlin
/** Android's own "this came from a fake-GPS app" marker. */
private fun Location.isMockReading(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
```

(imports `android.location.Location`, `android.os.Build` if not already there).

- [ ] **Step 6: Reason wording**

`strings.xml`, after `reason_face_uncertain`:

```xml
    <string name="reason_liveness_failed">The head-turn step could not be checked. Next time, turn your head the way the app asks, in good light.</string>
    <string name="reason_location_mocked">Your phone reported a fake-GPS (mock location) app. Turn it off before checking in.</string>
    <string name="reason_location_repeated">Your location was exactly the same as at an earlier check-in, which is unusual for real GPS.</string>
    <string name="reason_device_rooted">Your phone looks rooted, so its readings need a person to check them.</string>
    <string name="reason_device_emulator">The app was running on an emulator, not a real phone.</string>
```

`StatusPresentation.reason`, add the five branches:

```kotlin
        ReviewReason.LIVENESS_FAILED -> R.string.reason_liveness_failed
        ReviewReason.LOCATION_MOCKED -> R.string.reason_location_mocked
        ReviewReason.LOCATION_REPEATED -> R.string.reason_location_repeated
        ReviewReason.DEVICE_ROOTED -> R.string.reason_device_rooted
        ReviewReason.DEVICE_EMULATOR -> R.string.reason_device_emulator
```

- [ ] **Step 7: Run to verify pass**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL; `DeviceIntegrityTest` 5 passed, the two new DTO tests pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/src
git commit -m "feat(android): phone flags, head-turn field and new review reasons"
```

---

### Task 14: Android — signed submit with two photos

**Files:**
- Modify: `.../data/api/ApiService.kt:54-64`, `.../data/repo/FieldPassRepository.kt` (last method), `.../data/repo/RealFieldPassRepository.kt:67-77`, `.../data/repo/SwitchableRepository.kt:25-26`, `.../data/repo/FakeFieldPassRepository.kt:97-135`, `.../capture/CaptureState.kt`, `.../capture/AttendanceCaptureViewModel.kt`, `.../capture/CapturePhaseContent.kt:88`, `.../ui/Navigation.kt:88`
- Modify tests: `.../TestSupport.kt:28,50-53`, `.../capture/AttendanceCaptureViewModelTest.kt:41,58-61,225` and its 5 `onSelfieCaptured` calls, `.../data/RealRepositoryTest.kt:67-80,95`, `.../integration/BackendContractTest.kt` (lines 77, 177, 186, 192, 209, 211, 273), `.../integration/TimingContractTest.kt` (lines 81, 109, 128, 141)

**Interfaces:**
- Consumes: `DeviceSigner`, `canonicalMessage`, `SubmitFormat` (Task 12); `SubmitEvidence`, `DeviceFlags`, `DeviceIntegritySource`, `Attempt.nonce` (Task 13)
- Produces: `FieldPassRepository.submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent`; `AttendanceCaptureViewModel(activityId, action, repository, location, integrity: DeviceIntegritySource, elapsedRealtime, tickerEnabled = true)`; `vm.onPhotosCaptured(selfie: ByteArray, turnPhoto: ByteArray)` (replaces `onSelfieCaptured`); `CaptureUiState.turnPhoto: Selfie?`

Until Task 15 adds the guided camera, the capture screen sends the one photo as both parts (the server then adds `liveness_failed`). That interim line is replaced in Task 15.

- [ ] **Step 1: Write the failing tests**

`RealRepositoryTest.kt`: replace the body of `submitSendsContractPartsToTheSameAttemptPath` from the `repo.submitAttempt(...)` line to the `filename="selfie.jpg"` assertion with:

```kotlin
        val fix = LocationFix(12.5, 77.5, 18.0, "2026-10-10T09:31:05Z", isMock = true)
        val selfie = byteArrayOf(1, 2, 3)
        val turn = byteArrayOf(4, 5)
        val sent = attempt("att_1").copy(nonce = "n0nce")
        val event = repo.submitAttempt(sent, SubmitEvidence(selfie, turn, fix, DeviceFlags(rooted = false, emulator = true)))
        assertEquals(AttendanceStatus.NEEDS_REVIEW, event.status)
        val req = server.takeRequest()
        assertEquals("/api/v1/attempts/att_1/submit", req.path)
        val body = req.body.readUtf8()
        fun part(name: String) = requireNotNull(Regex("name=\"$name\"[\\s\\S]*?\\r\\n\\r\\n([^\\r]*)\\r\\n").find(body)) { "missing part $name" }.groupValues[1]
        assertEquals("12.500000", part("lat"))
        assertEquals("77.500000", part("lng"))
        assertEquals("18.0", part("accuracy_m"))
        assertEquals("2026-10-10T09:31:05Z", part("location_captured_at"))
        assertEquals("true", part("location_is_mock"))
        assertEquals("false", part("device_rooted"))
        assertEquals("true", part("device_emulator"))
        assertTrue(body.contains("filename=\"selfie.jpg\"") && body.contains("filename=\"turn_photo.jpg\""))
        val message = canonicalMessage("att_1", "n0nce", selfie, turn, fix, rooted = false, emulator = true)
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(signer.publicKey); update(message) }
        assertTrue("signature must cover exactly what was sent", verifier.verify(Base64.getDecoder().decode(part("signature"))))
```

(imports: `com.fieldpass.student.attempt`, `SubmitEvidence`, `DeviceFlags`, `canonicalMessage`, `java.security.Signature`, `java.util.Base64`). Line 95 becomes `repo.submitAttempt(attempt("att_1"), SubmitEvidence(byteArrayOf(1), byteArrayOf(1), LocationFix(1.0, 2.0, 3.0, "2026-10-10T09:31:05Z"), DeviceFlags(false, false)))`.

`AttendanceCaptureViewModelTest.kt`: the fake repo's override becomes

```kotlin
        val submitted = mutableListOf<SubmitEvidence>()

        override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent {
            // Copies: the ViewModel wipes the real arrays right after a successful submit.
            submitted += SubmitEvidence(evidence.selfie.copyOf(), evidence.turnPhoto.copyOf(), evidence.fix, evidence.flags)
            submittedAttemptIds += attempt.id
```

(rest of the body unchanged). Both VM constructions gain `integrity = { DeviceFlags(rooted = true, emulator = false) }` (line 225 positionally, after `ScriptedLocation()`). Every `vm.onSelfieCaptured(x)` becomes `vm.onPhotosCaptured(x, x)`. Add:

```kotlin
    @Test fun `submit carries both photos, the fix and the phone flags`() {
        repo.submitScript = ArrayDeque(listOf(event()))
        val vm = vm(ScriptedLocation(fix()))
        vm.start()
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        vm.submit()
        val evidence = repo.submitted.single()
        assertArrayEquals(byteArrayOf(1), evidence.selfie)
        assertArrayEquals(byteArrayOf(2), evidence.turnPhoto)
        assertEquals(DeviceFlags(rooted = true, emulator = false), evidence.flags)
        assertNull(vm.state.value.turnPhoto)
    }

    @Test fun `retake drops both photos`() {
        val vm = vm(ScriptedLocation(fix()))
        vm.start()
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        vm.retakeSelfie()
        assertNull(vm.state.value.selfie)
        assertNull(vm.state.value.turnPhoto)
    }
```

(The tests in this file run on `UnconfinedTestDispatcher`, so no `runTest` is needed. The `toReady()` helper at lines 63-65 becomes `start(); onPhotosCaptured(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3))`. Add imports `org.junit.Assert.assertArrayEquals`, `com.fieldpass.student.core.model.SubmitEvidence`, `com.fieldpass.student.core.model.DeviceFlags`. The `vm()` helper at lines 58-61 uses named arguments, so add `integrity = { DeviceFlags(rooted = true, emulator = false) }` there.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest`
Expected: FAIL — compilation errors (`SubmitEvidence` overload does not exist, `onPhotosCaptured` unresolved).

- [ ] **Step 3: API and repositories**

`ApiService.submitAttempt`:

```kotlin
    suspend fun submitAttempt(
        @Path("id") attemptId: String,
        @Part selfie: MultipartBody.Part,
        @Part turnPhoto: MultipartBody.Part,
        @Part("lat") lat: RequestBody,
        @Part("lng") lng: RequestBody,
        @Part("accuracy_m") accuracyM: RequestBody,
        @Part("location_captured_at") capturedAt: RequestBody,
        @Part("location_is_mock") isMock: RequestBody,
        @Part("device_rooted") rooted: RequestBody,
        @Part("device_emulator") emulator: RequestBody,
        @Part("signature") signature: RequestBody,
    ): AttendanceEvent
```

`FieldPassRepository`: `suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent`.

`RealFieldPassRepository`:

```kotlin
    override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent =
        call {
            val fix = evidence.fix
            val flags = evidence.flags
            val message = canonicalMessage(
                attempt.id, attempt.nonce, evidence.selfie, evidence.turnPhoto, fix, flags.rooted, flags.emulator,
            )
            api.submitAttempt(
                attemptId = attempt.id,
                selfie = jpegPart("selfie", evidence.selfie),
                turnPhoto = jpegPart("turn_photo", evidence.turnPhoto),
                lat = text(SubmitFormat.coordinate(fix.lat)),
                lng = text(SubmitFormat.coordinate(fix.lng)),
                accuracyM = text(SubmitFormat.accuracy(fix.accuracyM)),
                capturedAt = text(fix.capturedAt),
                isMock = text(fix.isMock.toString()),
                rooted = text(flags.rooted.toString()),
                emulator = text(flags.emulator.toString()),
                signature = text(signer.sign(message)),
            )
        }
```

`SwitchableRepository`: `override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence) = active.submitAttempt(attempt, evidence)`.

`FakeFieldPassRepository`: in `createAttempt` add `nonce = "demo-nonce", challenge = if (counter % 2 == 0) HeadTurn.LEFT else HeadTurn.RIGHT,` to the `Attempt(...)`; change the submit signature to `(attempt: Attempt, evidence: SubmitEvidence)` and start the body with `val attemptId = attempt.id; val selfie = evidence.selfie; val fix = evidence.fix`, then rename the local `val attempt = attempts[attemptId] ...` to `val stored = attempts[attemptId] ...` (and its uses), so the rest of the body is unchanged.

`TestSupport.kt`: `StubRepository.submitAttempt(attempt: Attempt, evidence: SubmitEvidence)`; `attempt()` adds `nonce = "n0nce", challenge = HeadTurn.LEFT` (imports `SubmitEvidence`, `HeadTurn`; `LocationFix` stays, `fix()` uses it).

- [ ] **Step 4: ViewModel holds two photos**

`CaptureState.kt`: add `val turnPhoto: Selfie? = null,` after `selfie`.

`AttendanceCaptureViewModel`: add the constructor parameter `private val integrity: DeviceIntegritySource,` after `location`. Replace `onSelfieCaptured`:

```kotlin
    /** Photo A (looking straight) and photo B (head turned the way the server asked), taken together. */
    fun onPhotosCaptured(selfie: ByteArray, turnPhoto: ByteArray) {
        val current = _state.value
        if (current.phase != CapturePhase.AWAITING_SELFIE && current.phase != CapturePhase.READY_TO_SUBMIT) return
        if (current.fix == null) return
        _state.update {
            it.copy(selfie = Selfie(selfie), turnPhoto = Selfie(turnPhoto), phase = CapturePhase.READY_TO_SUBMIT, error = null)
        }
    }
```

In `submit()`: add `val turnPhoto = current.turnPhoto ?: return` and call

```kotlin
                val event = repository.submitAttempt(attempt, SubmitEvidence(selfie.bytes, turnPhoto.bytes, fix, integrity.flags()))
```

Everywhere the file copies state with `selfie = null` (lines 107, 124, 151, 190, 206, 213; line 190 is the `unusable_capture` / `multiple_faces` / `face_not_matched` retake branch of `onSubmitError`) also set `turnPhoto = null`. `clearSelfie()` wipes both:

```kotlin
    private fun clearSelfie() {
        _state.value.selfie?.bytes?.fill(0)
        _state.value.turnPhoto?.bytes?.fill(0)
    }
```

`Navigation.kt:88`: `AttendanceCaptureViewModel(id, action, container.repository, container.location, container.integrity, SystemClock::elapsedRealtime)`.

`CapturePhaseContent.kt:88` (interim, replaced in Task 15):

```kotlin
        CameraCapture(onCaptured = { vm.onPhotosCaptured(it, it) }, onCaptureFailed = vm::onCameraError)
```

- [ ] **Step 5: Integration tests follow the new call**

In both integration files, `fix()` nudges the latitude so real check-ins are not flagged as repeated, and every `repo.submitAttempt(x.id, photo, f)` becomes `repo.submitAttempt(x, evidence(photo, f))` (pass the `Attempt` object those tests already hold; where only an id is held, keep the `Attempt` returned by `createAttempt`). Add to each companion:

```kotlin
        private var fixes = 0
        private fun nudge(lat: Double): Double = lat + (++fixes) * 0.000001 // ~0.1 m: real GPS never repeats exactly
        private fun evidence(photo: ByteArray, fix: LocationFix) =
            SubmitEvidence(photo, photo, fix, DeviceFlags(rooted = false, emulator = false))
```

and use `nudge(venueLat)` / `nudge(lat)` inside the two `fix()` helpers. `BackendContractTest` s18 (line 273) passes the new parts: `student1.api.submitAttempt("att_does_not_matter", selfie, selfie, text, text, text, text, text, text, text, text)`.

These tests need a real backend; start it with the head-turn rule opened wide (no turned-head sample photo exists): `LIVENESS_FRONT_MAX_OFFSET=9 LIVENESS_TURN_MIN_OFFSET=-9` in its environment. Add that sentence to the comment block at the top of `IntegrationSupport.kt` where the other `FIELDPASS_*` variables are described.

`android/scripts/integration-test.sh` starts that backend itself, so give both of its runs the wide limits (and say why in the header comment: "head-turn limits opened wide: the sample face has no turned-head photo"):

```bash
start_backend default LIVENESS_FRONT_MAX_OFFSET=9 LIVENESS_TURN_MIN_OFFSET=-9
```

```bash
start_backend short ATTEMPT_TTL_SECONDS=4 CHECKOUT_GRACE_MINUTES=0 LIVENESS_FRONT_MAX_OFFSET=9 LIVENESS_TURN_MIN_OFFSET=-9
```

- [ ] **Step 6: Run to verify pass**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`
Expected: BUILD SUCCESSFUL.
Then run (from the repo root): `android/scripts/integration-test.sh`
Expected: last line `[integration] all runs passed`. It needs the backend virtualenv and face models; if they are missing on this machine, say so in the task report — do not report the integration tests as passing.

- [ ] **Step 7: Commit**

```bash
git add android/app/src android/scripts/integration-test.sh
git commit -m "feat(android): sign every submit and send the head-turn photo and phone flags"
```

---

### Task 15: Android — guided camera (look straight, blink, turn)

Worked example: Arjun's attempt says `challenge = left`. The screen says "Look straight" → the app takes photo A by itself. "Blink once" → he blinks. "Turn your head to your left" → the app takes photo B by itself and moves to the "ready to send" screen. If the phone cannot follow his face for 20 seconds, a button "Take one photo instead" appears; that sends one photo twice, the server adds `liveness_failed`, and faculty decide (record + flag — nobody is locked out).

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/fieldpass/student/capture/LivenessGuide.kt`
- Create: `android/app/src/main/java/com/fieldpass/student/capture/LivenessCamera.kt`
- Modify: `android/app/src/main/java/com/fieldpass/student/capture/CapturePhaseContent.kt` (`SelfieStep`)
- Modify: `android/app/src/main/res/values/strings_capture.xml`, `strings_attempt.xml`
- Test: `android/app/src/test/java/com/fieldpass/student/capture/LivenessGuideTest.kt`

**Interfaces:**
- Consumes: `HeadTurn` (Task 13), `state.attempt?.challenge` (Task 13), `vm.onPhotosCaptured(selfie: ByteArray, turnPhoto: ByteArray)` (Task 14), `JpegProcessor.process(raw, rotation)`, `CameraCapture` (unchanged; enrollment keeps using it).
- Produces: `LivenessGuide.next(state: LivenessState, reading: FaceReading, challenge: HeadTurn): LivenessUpdate`; `LivenessCamera(challenge, onCaptured, onCaptureFailed, onUseOnePhoto, modifier)`.

The split: `LivenessGuide` is plain Kotlin with no Android in it — every rule is unit tested on the laptop. `LivenessCamera` is the thin part that feeds it camera frames and takes the two photos; it can only be checked on a phone (Step 8).

- [ ] **Step 1: Add the bundled face detector**

`libs.versions.toml` — under `[versions]` add `mlkitFaceDetection = "16.1.7"`; under `[libraries]` add:

```toml
mlkit-face-detection = { module = "com.google.mlkit:face-detection", version.ref = "mlkitFaceDetection" }
```

`app/build.gradle.kts`, after `implementation(libs.camerax.view)`:

```kotlin
    implementation(libs.mlkit.face.detection)
```

This is the **bundled** artifact: the model ships inside the APK and works with no internet. Do NOT use `com.google.android.gms:play-services-mlkit-face-detection` (that one downloads its model through Google Play).

Run (from `android/`): `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Write the failing tests**

`LivenessGuideTest.kt`:

```kotlin
package com.fieldpass.student.capture

import com.fieldpass.student.core.model.HeadTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivenessGuideTest {
    private val straight = FaceReading(faces = 1, yawDeg = 2f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
    private val eyesShut = straight.copy(leftEyeOpen = 0.1f, rightEyeOpen = 0.1f)
    private val turnedLeft = straight.copy(yawDeg = 35f * LivenessGuide.YAW_SIGN_FOR_LEFT)
    private val turnedRight = straight.copy(yawDeg = -35f * LivenessGuide.YAW_SIGN_FOR_LEFT)
    private val noFace = FaceReading(faces = 0)

    /** Feeds the readings one after another and returns the last update. */
    private fun feed(start: LivenessState, challenge: HeadTurn, vararg readings: FaceReading): LivenessUpdate =
        readings.fold(LivenessUpdate(start)) { update, reading -> LivenessGuide.next(update.state, reading, challenge) }

    private val afterFront = LivenessState(step = LivenessStep.BLINK)
    private val atTurn = LivenessState(step = LivenessStep.TURN)

    @Test
    fun `three steady straight frames take the front photo and ask for a blink`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight, straight)
        assertEquals(LivenessShot.FRONT, update.shot)
        assertEquals(LivenessStep.BLINK, update.state.step)
    }

    @Test
    fun `two straight frames are not enough`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight)
        assertNull(update.shot)
        assertEquals(LivenessStep.LOOK_STRAIGHT, update.state.step)
    }

    @Test
    fun `a turned head never counts as looking straight`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, turnedLeft, turnedLeft, turnedLeft, turnedLeft)
        assertNull(update.shot)
        assertEquals(LivenessStep.LOOK_STRAIGHT, update.state.step)
    }

    @Test
    fun `closed eyes restart the steady count`() {
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight, eyesShut, straight, straight)
        assertNull(update.shot)
        assertEquals(2, update.state.steadyFrames)
    }

    @Test
    fun `unknown eye values do not count as open`() {
        val unknown = straight.copy(leftEyeOpen = null, rightEyeOpen = null)
        val update = feed(LivenessState(), HeadTurn.LEFT, unknown, unknown, unknown)
        assertNull(update.shot)
    }

    @Test
    fun `eyes shut then open again is a blink`() {
        val update = feed(afterFront, HeadTurn.LEFT, straight, eyesShut, straight)
        assertEquals(LivenessStep.TURN, update.state.step)
        assertNull(update.shot)
    }

    @Test
    fun `open eyes alone are not a blink`() {
        val update = feed(afterFront, HeadTurn.LEFT, straight, straight, straight)
        assertEquals(LivenessStep.BLINK, update.state.step)
    }

    @Test
    fun `turning the asked way for two frames takes the turn photo`() {
        val update = feed(atTurn, HeadTurn.LEFT, turnedLeft, turnedLeft)
        assertEquals(LivenessShot.TURN, update.shot)
        assertEquals(LivenessStep.DONE, update.state.step)
    }

    @Test
    fun `turning the wrong way takes no photo`() {
        val update = feed(atTurn, HeadTurn.LEFT, turnedRight, turnedRight, turnedRight)
        assertNull(update.shot)
        assertEquals(LivenessStep.TURN, update.state.step)
    }

    @Test
    fun `a right challenge wants the opposite turn`() {
        assertEquals(LivenessShot.TURN, feed(atTurn, HeadTurn.RIGHT, turnedRight, turnedRight).shot)
        assertNull(feed(atTurn, HeadTurn.RIGHT, turnedLeft, turnedLeft).shot)
    }

    @Test
    fun `a small turn is not enough`() {
        val slight = straight.copy(yawDeg = 15f * LivenessGuide.YAW_SIGN_FOR_LEFT)
        assertNull(feed(atTurn, HeadTurn.LEFT, slight, slight, slight).shot)
    }

    @Test
    fun `losing the face for ten frames starts again from the front photo`() {
        val update = feed(atTurn, HeadTurn.LEFT, *Array(LivenessGuide.MISSED_FRAMES_RESTART) { noFace })
        assertEquals(LivenessState(), update.state)
    }

    @Test
    fun `a short loss of the face does not start again`() {
        val update = feed(atTurn, HeadTurn.LEFT, noFace, noFace, noFace, turnedLeft, turnedLeft)
        assertEquals(LivenessShot.TURN, update.shot)
    }

    @Test
    fun `two faces count as no usable face`() {
        val crowd = FaceReading(faces = 2)
        val update = feed(LivenessState(), HeadTurn.LEFT, straight, straight, crowd, straight, straight)
        assertNull(update.shot)
    }

    @Test
    fun `done stays done`() {
        val done = LivenessState(step = LivenessStep.DONE)
        assertEquals(LivenessUpdate(done), LivenessGuide.next(done, turnedLeft, HeadTurn.LEFT))
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "*LivenessGuideTest*"`
Expected: compilation FAILS — `LivenessGuide`, `FaceReading`, `LivenessState` do not exist.

- [ ] **Step 4: Implement the guide**

`LivenessGuide.kt`:

```kotlin
package com.fieldpass.student.capture

import com.fieldpass.student.core.model.HeadTurn
import kotlin.math.abs

enum class LivenessStep { LOOK_STRAIGHT, BLINK, TURN, DONE }

/** Which photo the camera must take right now. */
enum class LivenessShot { FRONT, TURN }

/** One camera frame as the face detector saw it. Yaw in degrees; eye values 0..1, null when the detector could not tell. */
data class FaceReading(
    val faces: Int,
    val yawDeg: Float = 0f,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
)

data class LivenessState(
    val step: LivenessStep = LivenessStep.LOOK_STRAIGHT,
    val steadyFrames: Int = 0,
    val eyesClosedSeen: Boolean = false,
    val missedFrames: Int = 0,
)

data class LivenessUpdate(val state: LivenessState, val shot: LivenessShot? = null)

/**
 * The on-phone part of the head-turn check: look straight (photo A), blink, turn as the server asked (photo B).
 * Pure rules, one frame in, one state out. The server re-checks both photos; this only guides the student.
 */
object LivenessGuide {
    const val FRONT_MAX_YAW_DEG = 8f
    /** Stricter than the server's LIVENESS_TURN_MIN_OFFSET so a photo taken here also passes there. */
    const val TURN_MIN_YAW_DEG = 30f
    const val EYE_OPEN_MIN = 0.6f
    const val EYE_CLOSED_MAX = 0.3f
    const val STEADY_FRAMES_FRONT = 3
    const val STEADY_FRAMES_TURN = 2
    const val MISSED_FRAMES_RESTART = 10

    /** +1 when the detector reports a positive yaw for a student turning to their own left. Checked on a real phone. */
    const val YAW_SIGN_FOR_LEFT = 1f

    fun next(state: LivenessState, reading: FaceReading, challenge: HeadTurn): LivenessUpdate {
        if (state.step == LivenessStep.DONE) return LivenessUpdate(state)
        if (reading.faces != 1) return LivenessUpdate(onMissed(state))
        val seen = state.copy(missedFrames = 0)
        return when (seen.step) {
            LivenessStep.LOOK_STRAIGHT -> lookStraight(seen, reading)
            LivenessStep.BLINK -> LivenessUpdate(blink(seen, reading))
            LivenessStep.TURN -> turn(seen, reading, challenge)
            LivenessStep.DONE -> LivenessUpdate(seen)
        }
    }

    private fun onMissed(state: LivenessState): LivenessState {
        if (state.step == LivenessStep.LOOK_STRAIGHT) return LivenessState()
        val missed = state.missedFrames + 1
        // The face was gone long enough for someone else to step in: photo A no longer counts.
        if (missed >= MISSED_FRAMES_RESTART) return LivenessState()
        return state.copy(steadyFrames = 0, missedFrames = missed)
    }

    private fun lookStraight(state: LivenessState, reading: FaceReading): LivenessUpdate {
        val ok = abs(reading.yawDeg) <= FRONT_MAX_YAW_DEG && eyesOpen(reading)
        val steady = if (ok) state.steadyFrames + 1 else 0
        if (steady >= STEADY_FRAMES_FRONT) return LivenessUpdate(LivenessState(step = LivenessStep.BLINK), LivenessShot.FRONT)
        return LivenessUpdate(state.copy(steadyFrames = steady))
    }

    private fun blink(state: LivenessState, reading: FaceReading): LivenessState = when {
        eyesClosed(reading) -> state.copy(eyesClosedSeen = true)
        state.eyesClosedSeen && eyesOpen(reading) -> LivenessState(step = LivenessStep.TURN)
        else -> state
    }

    private fun turn(state: LivenessState, reading: FaceReading, challenge: HeadTurn): LivenessUpdate {
        val wanted = if (challenge == HeadTurn.LEFT) YAW_SIGN_FOR_LEFT else -YAW_SIGN_FOR_LEFT
        val steady = if (reading.yawDeg * wanted >= TURN_MIN_YAW_DEG) state.steadyFrames + 1 else 0
        if (steady >= STEADY_FRAMES_TURN) return LivenessUpdate(LivenessState(step = LivenessStep.DONE), LivenessShot.TURN)
        return LivenessUpdate(state.copy(steadyFrames = steady))
    }

    private fun eyesOpen(reading: FaceReading): Boolean =
        (reading.leftEyeOpen ?: 0f) >= EYE_OPEN_MIN && (reading.rightEyeOpen ?: 0f) >= EYE_OPEN_MIN

    private fun eyesClosed(reading: FaceReading): Boolean =
        (reading.leftEyeOpen ?: 1f) <= EYE_CLOSED_MAX && (reading.rightEyeOpen ?: 1f) <= EYE_CLOSED_MAX
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew testDebugUnitTest --tests "*LivenessGuideTest*"`
Expected: 15 tests PASS.

- [ ] **Step 6: Add the strings**

`strings_capture.xml`, before `</resources>`:

```xml
    <string name="liveness_step">Step %1$d of %2$d</string>
    <string name="liveness_look_straight">Look straight at the camera and hold still.</string>
    <string name="liveness_blink">Good. Now blink once.</string>
    <string name="liveness_turn_left">Now slowly turn your head to your left.</string>
    <string name="liveness_turn_right">Now slowly turn your head to your right.</string>
    <string name="liveness_taking_photo">Hold still. Taking the photo.</string>
    <string name="liveness_no_face">We can\'t see one face. Keep only your face in the frame.</string>
    <string name="liveness_take_one_photo">Not working? Take one photo instead</string>
    <string name="liveness_one_photo_note">You are sending one photo without the head turn. Your faculty will review this check-in.</string>
```

`strings_attempt.xml` line 16 becomes:

```xml
    <string name="attempt_selfie_body">Location ready. Follow the three steps on screen. The app takes the photos for you.</string>
```

- [ ] **Step 7: Add the guided camera and use it**

`LivenessCamera.kt`:

```kotlin
package com.fieldpass.student.capture

import android.content.Context
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldpass.student.R
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.HeadTurn
import com.fieldpass.student.ui.SecondaryButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STUCK_AFTER_MILLIS = 20_000L
private const val STEP_COUNT = 3

/** What the screen shows, plus the front photo waiting for its partner. Memory only; wiped when dropped. */
private class LivenessSession {
    var guide by mutableStateOf(LivenessState())
    var faceVisible by mutableStateOf(true)
    var capturing by mutableStateOf(false)
    var front: ByteArray? = null

    fun dropFront() {
        front?.fill(0)
        front = null
    }

    fun startAgain() {
        dropFront()
        guide = LivenessState()
        capturing = false
    }
}

/**
 * Guided front-camera capture: look straight (photo A), blink, turn as [challenge] asks (photo B).
 * The app presses the shutter itself. Face detection runs on the phone, offline; frames are never stored or sent.
 */
@Composable
fun LivenessCamera(
    challenge: HeadTurn,
    onCaptured: (selfie: ByteArray, turnPhoto: ByteArray) -> Unit,
    onCaptureFailed: (ErrorCode) -> Unit,
    onUseOnePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val analysis = remember { ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() }
    val detector = remember { FaceDetection.getClient(detectorOptions()) }
    val session = remember { LivenessSession() }
    var stuck by remember { mutableStateOf(false) }
    val currentChallenge by rememberUpdatedState(challenge)
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnFailed by rememberUpdatedState(onCaptureFailed)
    val previewDescription = stringResource(R.string.camera_preview_description)

    fun onShot(shot: LivenessShot, jpeg: ByteArray) {
        session.capturing = false
        if (shot == LivenessShot.FRONT) {
            session.front = jpeg
            return
        }
        val front = session.front
        session.front = null // handed to the caller, who now owns and wipes it
        if (front == null) currentOnFailed(ErrorCode.CAPTURE_FAILED) else currentOnCaptured(front, jpeg)
    }

    fun onReading(reading: FaceReading) {
        if (session.capturing) return
        session.faceVisible = reading.faces == 1
        val before = session.guide.step
        val update = LivenessGuide.next(session.guide, reading, currentChallenge)
        session.guide = update.state
        if (before != LivenessStep.LOOK_STRAIGHT && update.state.step == LivenessStep.LOOK_STRAIGHT) session.dropFront()
        val shot = update.shot ?: return
        session.capturing = true
        imageCapture.takeJpeg(
            context,
            scope,
            onFailed = {
                session.startAgain()
                currentOnFailed(ErrorCode.CAPTURE_FAILED)
            },
            onJpeg = { onShot(shot, it) },
        )
    }

    LaunchedEffect(Unit) {
        delay(STUCK_AFTER_MILLIS)
        stuck = true
    }

    DisposableEffect(lifecycleOwner) {
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy -> detector.read(proxy) { onReading(it) } }
        onDispose {
            analysis.clearAnalyzer()
            detector.close()
            session.dropFront()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.liveness_step, stepNumber(session.guide.step), STEP_COUNT), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(promptFor(session, challenge)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).semantics { contentDescription = previewDescription },
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = this.surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, analysis)
                        } catch (_: Exception) {
                            // Some old phones cannot run preview + photo + analysis together: the one-photo path still works.
                            stuck = true
                            onCaptureFailed(ErrorCode.CAPTURE_FAILED)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
        if (stuck) SecondaryButton(stringResource(R.string.liveness_take_one_photo), onUseOnePhoto)
    }
}

private fun detectorOptions(): FaceDetectorOptions = FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eye-open values, for the blink
    .build()

private fun stepNumber(step: LivenessStep): Int = when (step) {
    LivenessStep.LOOK_STRAIGHT -> 1
    LivenessStep.BLINK -> 2
    LivenessStep.TURN, LivenessStep.DONE -> 3
}

@StringRes
private fun promptFor(session: LivenessSession, challenge: HeadTurn): Int = when {
    session.capturing -> R.string.liveness_taking_photo
    !session.faceVisible -> R.string.liveness_no_face
    session.guide.step == LivenessStep.LOOK_STRAIGHT -> R.string.liveness_look_straight
    session.guide.step == LivenessStep.BLINK -> R.string.liveness_blink
    challenge == HeadTurn.LEFT -> R.string.liveness_turn_left
    else -> R.string.liveness_turn_right
}

/** Runs the detector on one frame and always closes the frame. A frame the detector cannot read counts as "no face". */
@OptIn(ExperimentalGetImage::class)
private fun FaceDetector.read(proxy: ImageProxy, onReading: (FaceReading) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { faces -> onReading(faces.toReading()) }
        .addOnFailureListener { onReading(FaceReading(faces = 0)) }
        .addOnCompleteListener { proxy.close() }
}

private fun List<Face>.toReading(): FaceReading {
    val face = singleOrNull() ?: return FaceReading(faces = size)
    return FaceReading(1, face.headEulerAngleY, face.leftEyeOpenProbability, face.rightEyeOpenProbability)
}

private fun ImageCapture.takeJpeg(context: Context, scope: CoroutineScope, onFailed: () -> Unit, onJpeg: (ByteArray) -> Unit) {
    takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val raw = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                scope.launch {
                    runCatching { withContext(Dispatchers.Default) { JpegProcessor.process(raw, rotation) } }
                        .onSuccess(onJpeg)
                        .onFailure { onFailed() }
                }
            }

            override fun onError(exception: ImageCaptureException) = onFailed()
        },
    )
}
```

`CameraCapture.kt` is left exactly as it is (enrollment uses it); the photo-taking lines are repeated here on purpose so the enrollment camera is not touched in this piece.

`CapturePhaseContent.kt` — replace the whole `SelfieStep` (this removes the interim line from Task 14) and add the imports `androidx.compose.runtime.getValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.saveable.rememberSaveable`, `androidx.compose.runtime.setValue`:

```kotlin
@Composable
private fun SelfieStep(state: CaptureUiState, vm: AttendanceCaptureViewModel) {
    val attempt = state.attempt ?: return
    var onePhoto by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.attempt_selfie_heading), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.attempt_selfie_body))
    state.error?.let { ErrorText(it) }
    Text(stringResource(R.string.camera_tips), style = MaterialTheme.typography.bodySmall)
    PermissionGate(GatedPermission.CAMERA) {
        if (onePhoto) {
            Text(stringResource(R.string.liveness_one_photo_note), style = MaterialTheme.typography.bodySmall)
            CameraCapture(onCaptured = { vm.onPhotosCaptured(it, it.copyOf()) }, onCaptureFailed = vm::onCameraError)
        } else {
            LivenessCamera(
                challenge = attempt.challenge,
                onCaptured = vm::onPhotosCaptured,
                onCaptureFailed = vm::onCameraError,
                onUseOnePhoto = { onePhoto = true },
            )
        }
    }
}
```

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease -PfieldpassApiBaseUrl=https://fieldpass.example` (release builds refuse to run without an HTTPS `fieldpassApiBaseUrl`; the value is only baked into `BuildConfig`, nothing is contacted)
Expected: BUILD SUCCESSFUL (`assembleRelease` proves the shrinker keeps the face detector; if the release build has no signing set up on this machine and fails only for that reason, say so in the report).

- [ ] **Step 8: Look at it on a real phone**

Nothing above proves the camera part works — only a phone can. Install the debug APK built for the laptop's hotspot address (`./gradlew installDebug -PfieldpassApiBaseUrl=http://<laptop-ip>:8000`; the login screen has no server field, the address is baked into `BuildConfig`, and plain `http://` works in debug builds only), log in as student1 against a backend with a fresh database, start a check-in and check each line. Take a screenshot of each prompt and look at it before reporting.

1. "Look straight" → the photo is taken with no button press, the prompt changes to "blink once".
2. Blink → the prompt changes to the turn prompt and names the side the server asked for (compare with `challenge` in the backend's `POST /attempts` response or its log).
3. Turn your head to **that** side → photo B is taken and the "ready to send" screen appears. **If the photo is only taken when you turn the other way, the detector's sign is the opposite of what we assumed: change `YAW_SIGN_FOR_LEFT` to `-1f`, rerun the unit tests (they read the constant, so they still pass) and repeat.**
4. Cover the camera for a few seconds after photo A → the screen goes back to step 1.
5. Wait 20 seconds without finishing → "Not working? Take one photo instead" appears; using it reaches the "ready to send" screen and, after sending, the dashboard shows the reason "Head-turn check failed".
6. TalkBack on: each new prompt is read out.

Whether the server agrees with photo B (thresholds, mirroring) is settled in Task 16. If no phone is available, report this step as NOT DONE — do not report the task as verified.

- [ ] **Step 9: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src
git commit -m "feat(android): guided camera takes the straight and head-turn photos"
```

---

### Task 16: Real-phone calibration, demo walkthrough and docs

Everything before this task is proven with fake faces and software keys. This task is the first time a real face, a real camera and a real Keystore meet the server. It needs a person holding a phone; an agent without one writes the docs (Steps 4–5), reports Steps 1–3 as NOT DONE and stops.

**Files:**
- Modify: `backend/.env` (local only, not committed) and `backend/.env.example` if a default changes
- Modify: `android/app/src/main/java/com/fieldpass/student/capture/LivenessGuide.kt` (only if a constant changes)
- Modify: `README.md`, `android/README.md`, `dashboard/README.md`
- Modify: `.agents/decisions/log.md`, `.agents/projects/active-backlog.md`, `.agents/context/subsystem-notes.md`

**Interfaces:**
- Consumes: everything. Produces: nothing new in code.

- [ ] **Step 1: Fresh demo backend**

The new columns only exist in a new database (there is no migration tool). From `backend/`, with the server stopped:

```bash
mv data/fieldpass.db data/fieldpass.before-anti-cheat.db   # keep the old one; path is DATABASE_PATH in .env
.venv/bin/python -m app.seed
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Check the path first (`grep DATABASE_PATH .env`); if `.env` points at Postgres (`DATABASE_URL`), stop and ask the user — do not drop anything in Supabase.
Expected: server starts; `curl -s http://127.0.0.1:8000/api/v1/health` answers.

- [ ] **Step 2: Calibrate the head turn with two real people**

Worked example of what "calibrated" means: Arjun is asked to turn left, turns left, the app takes photo B, he sends it, and his row on the dashboard does **not** show "Head-turn check failed". Then Meena does the same with a `right` challenge.

Do 6 guided check-ins (3 per person; a new attempt gives a new random side) and write down for each: side asked, did the phone take photo B, did the event carry `liveness_failed`. Then fix at most one thing at a time, in this order:

| What you see | What it means | Change |
|---|---|---|
| Phone takes photo B only when turning the **other** way | detector sign is opposite | `YAW_SIGN_FOR_LEFT = -1f` (Task 15 Step 8.3) |
| Phone is right, **every** event has `liveness_failed` | the phone's photos are mirrored for the server | `LIVENESS_MIRRORED=true` in `backend/.env`, restart |
| Still every event after flipping it back and forth | the turn is too small for the server | take a straight and a turned selfie of the same person with the phone, copy them to the laptop, run `.venv/bin/python scripts/nose_offset.py straight.jpg turned.jpg` and read the two numbers; then either raise `TURN_MIN_YAW_DEG` (phone asks for a bigger turn) or lower `LIVENESS_TURN_MIN_OFFSET` (never below 0.18, or a straight face starts to pass) |
| Only **some** events have it | borderline | raise `TURN_MIN_YAW_DEG` by 5 and repeat |
| Straight photo fails (`liveness_failed` with a correct turn, offset of photo A above 0.12 in the script) | people do not hold the phone dead centre | raise `LIVENESS_FRONT_MAX_OFFSET` to at most 0.18 |

Done when 6 of 6 honest check-ins have no `liveness_failed`, and these 2 cheats **do** get it: sending one photo with "Take one photo instead", and holding up a printed or on-screen photo of the student (it cannot turn, so the guide never finishes and only the one-photo path is left). Record the final numbers; they go in the decisions log (Step 5). If the numbers changed, update the defaults in `backend/app/config.py` + `.env.example` and the constants in `LivenessGuide.kt`, and rerun `cd backend && .venv/bin/python -m pytest` and `cd android && ./gradlew testDebugUnitTest`.

- [ ] **Step 3: Demo walkthrough on the hotspot**

Laptop hotspot on, phone joined, dashboard open as faculty1. Tick each line only after seeing it; take a dashboard screenshot at lines 3, 5 and 7 and look at them.

0. Build and install the debug APK on both phones with `-PfieldpassApiBaseUrl=http://<laptop-ip>:8000` (the hotspot address; the app has no server field). Seeded names: student1 = Asha Verma, student2 = Rohan Iyer; "Arjun" and "Meena" below are the same two people in the examples.
1. Arjun (student1) logs in on phone 1 → works. Dashboard → Students → student1 shows a linked date.
2. Meena (student2) tries to log in on phone 1 → "This phone is already linked to another student. Each student must use their own phone."
3. Arjun does a guided check-in at the venue → register row has no new reasons.
4. Arjun logs out; on phone 2 he logs in → "Your account is linked to a different phone. Ask your faculty to reset your phone…".
5. Faculty: Students → student1 → "Reset phone" → "Yes, unlink". Arjun logs in on phone 2 → works; phone 1 now gets the mismatch message.
6. With a fake-GPS app enabled as the mock location app (Developer options), Arjun checks in → row shows "Fake GPS was on" and is in "needs review".
7. With the fake-GPS app still feeding the same point, a second check-in in the same activity → also "Same exact spot as an earlier check-in".
8. On an emulator: check-in shows "Running on an emulator".
9. Phone in aeroplane mode + hotspot Wi-Fi only (no internet): repeat line 3 → still works (nothing needs the internet).

Anything that does not behave as written is a bug: report it with the screenshot, do not edit this list to match.

- [ ] **Step 4: Docs**

`README.md`, "What FieldPass does NOT prove" — replace the first bullet with:

```markdown
- **The head-turn check is a speed bump, not liveness detection.** The app asks for a blink and a head
  turn to a side the server picks, and the server checks the second photo. A printed photo cannot turn,
  but a replayed video with the right turn can pass. A failed check is only a reason for faculty to look.
- **The phone lock and the fake-GPS, rooted and emulator flags come from the phone.** One student is tied
  to one phone and every check-in is signed by it, but a modified app on a rooted phone can lie about the flags.
```

`README.md`, quick start "1. Backend", after the seed command block add:

```markdown
After pulling a version that adds database columns (the phone lock did), start from a new database file:
move the old `DATABASE_PATH` file away and run the seed command again. There is no migration tool.
```

`android/README.md`, "Behaviour summary" — add:

```markdown
- First login creates a signing key inside the phone's Keystore and links this phone to the student.
  Every check-in is signed with it. Reinstalling the app or clearing its data makes a new key: faculty
  must press "Reset phone" on the dashboard's Students page before the student can log in again.
- Check-in is guided: look straight (photo), blink, turn your head to the side shown (second photo).
  Face detection for the guide runs on the phone with the bundled ML Kit model — no internet, no Play
  Store download. The second photo is checked by the server and never stored.
- The app reports three flags with each check-in: fake-GPS location, rooted phone, emulator.
```

`android/README.md`, "Known limitations" — replace the first bullet ("Camera capture is not liveness detection…") with:

```markdown
- The blink and head turn are not liveness detection. A replayed video with the right turn may pass.
- The guided camera uses preview, photo and frame analysis together; a few very old phones cannot.
  They fall back to "Take one photo instead", which faculty see as "Head-turn check failed".
```

and update the "Not yet run on a physical device" bullet with what Step 2–3 actually showed (phone model, Android version, date, final thresholds) — or leave it and say so if no phone was used.

`dashboard/README.md`, "What the register shows" — add:

```markdown
- A "Students" page lists every student and whether a phone is linked, with a two-step "Reset phone"
  button for a lost, replaced or reinstalled phone.
- New review reasons in plain words: "Head-turn check failed", "Fake GPS was on", "Same exact spot as
  an earlier check-in", "Rooted phone", "Running on an emulator".
```

- [ ] **Step 5: Agent memory**

`.agents/decisions/log.md`, Decisions table — add rows (fill the bracketed numbers from Step 2; if Step 2 was not done write "not calibrated on a phone yet"):

```markdown
| 2026-10-11 | Anti-cheat piece A: phone lock (Keystore signature), fake-GPS/root/emulator flags, blink + server-chosen head turn | Stops phone sharing, replayed uploads and printed photos in an offline APK demo | Play Integrity (needs Play Store + internet), cloud liveness, numeric risk score (piece C) |
| 2026-10-11 | Cheat signals are **record + flag**: they add a review reason, never block or auto-decline | Faculty make the final call (existing invariant) | Hard blocks on mock location or root |
| 2026-10-11 | Head-turn limits: front [0.12], turn [0.25], mirrored [false], phone yaw [30°], sign [+1] | Calibrated on [phone model] with [n] people | — |
```

Migration Index table — add:

```markdown
| 2026-10-11 | Anti-cheat A adds `student_devices`, `attempts.nonce/challenge`, `attendance_events.location_fingerprint`. No migration: recreate the SQLite file. Postgres needs the columns added by hand. |
```

`.agents/projects/active-backlog.md`, Backlog table — add:

```markdown
| Anti-cheat A (plan: anti-cheat-a-plan.md) | high | done [date] / calibration open |
| Anti-cheat B: open events with rotating screen QR and group label | high | open (needs its own design) |
| Anti-cheat C: rule-based risk score with a slot for Jev RLCD | medium | open (after duty zones) |
```

`.agents/context/subsystem-notes.md` — under "Face engine" add:

```markdown
- Head turn: `liveness.py` uses the nose offset from YuNet landmarks; sign depends on photo mirroring (`LIVENESS_MIRRORED`). Real-engine test harnesses open the limits wide (`LIVENESS_FRONT_MAX_OFFSET=9`, `LIVENESS_TURN_MIN_OFFSET=-9`) because no turned-head sample photo exists.
```

and under "Android" add:

```markdown
- Phone key lives in the Keystore (`fieldpass_device_key`); reinstall = new key = faculty reset. The signed text must match `backend/app/device_keys.py` byte for byte (shared test vector in all three test suites; numbers formatted with `Locale.US`).
- Test helpers nudge latitude by 0.000001° per submit, otherwise every test check-in is flagged `location_repeated`.
```

- [ ] **Step 6: Everything green, then commit**

```bash
cd backend && .venv/bin/python -m pytest --cov=app --cov-report=term-missing
cd ../dashboard && npm run typecheck && npm run lint && npm test && npm run e2e
cd ../android && ./gradlew testDebugUnitTest lintDebug assembleDebug && scripts/integration-test.sh
cd .. && make second-brain-close
```

Expected: every command exits 0; backend coverage stays at or above 80%. Paste the last line of each into the report. Then:

```bash
git add README.md android/README.md dashboard/README.md .agents/ backend/.env.example backend/app/config.py android/app/src
git commit -m "docs: phone lock, head-turn check and phone flags; calibration results"
```

---

## Plan self-check (done while writing)

- **Spec §1 device lock** → Tasks 1, 2, 3, 5 (server), 9 (e2e harness), 10–11 (dashboard), 12, 14 (phone).
- **Spec §2 flags** → Task 6 (server), 10 (labels), 13–14 (phone).
- **Spec §3 blink + head turn** → Task 4 (challenge), 7 (server check), 15 (phone guide), 16 (calibration).
- **Known limits** → Task 8 (known-limitations 22–25), Task 16 (READMEs).
- **One thing added beyond the spec:** the "Take one photo instead" fallback in Task 15. It follows record + flag (the event gets `liveness_failed`), and without it a student whose face the phone cannot follow has no way to check in at all.
