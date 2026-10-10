# FieldPass API contract (v1, frozen)

This file is the single source of truth for the backend, the faculty dashboard, and the
Android app. `contracts/openapi.yaml` is exported from the backend and must agree with it.
If something here is wrong or missing, change this file first, then the code.

## Conventions

- Base path: `/api/v1`. Base URL is configurable in every client (example `http://192.168.43.10:8000`).
- Auth: `Authorization: Bearer <access_token>` on every call except `POST /auth/login` and `GET /health`.
- JSON bodies use `snake_case`. IDs are opaque strings.
- Times are ISO-8601 UTC with a `Z` suffix (`2026-10-10T09:30:00Z`). Clients convert to local time for display.
- The server clock decides activity windows, attempt expiry, and receipt times. Client clocks are never trusted for those.
- Photos are uploaded as `multipart/form-data` JPEG parts, max 5 MB.
- Lists are wrapped: `{"items": [...]}`.
- Student identity always comes from the token. No request body carries a student ID for the student's own actions.

### Errors

Every non-2xx response has this body:

```json
{"error": {"code": "attempt_expired", "message": "This attempt has expired. Start a new one."}}
```

`message` is safe to show to a user. Clients branch on `code`, never on `message`.

| HTTP | code | Meaning |
|---|---|---|
| 401 | `invalid_credentials` | Wrong username or password |
| 401 | `not_authenticated` | Missing, invalid, or expired token |
| 403 | `forbidden` | Wrong role, or not your resource |
| 403 | `not_assigned` | Student is not on this activity's roster |
| 404 | `not_found` | Resource does not exist |
| 409 | `enrollment_not_approved` | No approved enrollment photo yet |
| 409 | `outside_activity_window` | Activity is not open for this action right now |
| 409 | `invalid_action_order` | e.g. check-out before check-in, second check-in |
| 409 | `checkpoint_not_open` | No open checkpoint window, or already answered this one |
| 409 | `already_decided` | Enrollment already approved/rejected |
| 410 | `attempt_expired` | Attempt older than 90 s and never successfully submitted |
| 413 | `image_too_large` | Photo over 5 MB |
| 422 | `validation_error` | Malformed or missing fields |
| 422 | `unusable_capture` | Image unreadable, or no face found. Retry the same attempt |
| 422 | `multiple_faces` | More than one face found. Retry the same attempt |
| 422 | `location_stale` | Location reading older than 60 s. Retry the same attempt |

## Shared types

```
Role              = "student" | "faculty"
EnrollmentStatus  = "none" | "pending" | "approved" | "rejected"
Action            = "check_in" | "checkpoint" | "check_out"
AttendanceStatus  = "recorded" | "needs_review" | "confirmed" | "not_confirmed"
LocationVerdict   = "inside" | "uncertain" | "outside"
FaceVerdict       = "match" | "uncertain" | "no_match"
ReviewReason      = "location_uncertain" | "location_outside" | "face_mismatch" | "face_uncertain"
ObservationType   = "present" | "absent" | "left_early" | "other"
```

Meaning of `AttendanceStatus`:

- `recorded` — automated checks passed; faculty confirmation pending.
- `needs_review` — evidence is uncertain or a check failed. Not an accusation.
- `confirmed` — faculty approved attendance.
- `not_confirmed` — faculty declined, with a reason.

Automated checks only ever produce `recorded` or `needs_review`.
No numeric face score or "confidence" is ever returned. There is no liveness field.

### User
```json
{"id": "u_s1", "username": "student1", "full_name": "Asha Verma", "role": "student"}
```

### Enrollment
```json
{
  "id": "enr_1", "status": "pending",
  "student": {"id": "u_s1", "username": "student1", "full_name": "Asha Verma", "role": "student"},
  "submitted_at": "2026-10-10T09:00:00Z",
  "decided_at": null, "decided_by": null, "decision_reason": null
}
```
When the student has never uploaded: `{"id": null, "status": "none", "student": {...}, "submitted_at": null, "decided_at": null, "decided_by": null, "decision_reason": null}`.
`decided_by` is the faculty member's full name.

### CheckpointWindow
```json
{"id": "cp_1", "activity_id": "act_1", "opens_at": "2026-10-10T10:30:00Z", "closes_at": "2026-10-10T10:40:00Z", "opened_by": "Dr. Meera Rao"}
```

### Activity
```json
{
  "id": "act_1", "name": "Site Visit: Water Treatment Plant",
  "venue_name": "Plant Gate 2", "venue_lat": 12.9716, "venue_lng": 77.5946, "radius_m": 100,
  "starts_at": "2026-10-10T09:30:00Z", "ends_at": "2026-10-10T13:30:00Z",
  "created_by": "Dr. Meera Rao",
  "roster_count": 3,
  "checkpoint_windows": [],
  "open_checkpoint": null,
  "my_attendance": null
}
```
- `open_checkpoint`: the currently open `CheckpointWindow`, or `null`.
- `my_attendance`: present only for students; an `AttendanceRecord` (below) plus `next_actions`. `null` for faculty.

### AttendanceEvent (one automated observation)
```json
{
  "id": "evt_1", "attempt_id": "att_1", "activity_id": "act_1", "student_id": "u_s1",
  "action": "check_in", "status": "recorded",
  "received_at": "2026-10-10T09:31:12Z",
  "checkpoint_id": null,
  "review_reasons": [],
  "location": {"verdict": "inside", "distance_m": 40, "accuracy_m": 15, "captured_at": "2026-10-10T09:31:05Z"},
  "face": {"verdict": "match"}
}
```
`status` here is only ever `recorded` or `needs_review`.

### Decision (faculty)
```json
{"id": "dec_1", "decision": "confirmed", "reason": "Seen on site all morning", "actor_name": "Dr. Meera Rao", "decided_at": "2026-10-10T13:40:00Z", "is_current": true}
```
`is_current` is `false` when an automated event arrived after the decision; the record then
goes back to its automated status until faculty decides again.

### Observation (faculty, in person)
```json
{"id": "obs_1", "activity_id": "act_1", "student_id": "u_s1", "observation_type": "present", "note": "Signed the paper sheet", "actor_name": "Dr. Meera Rao", "observed_at": "2026-10-10T11:00:00Z"}
```

### AttendanceRecord (one student in one activity)
```json
{
  "activity_id": "act_1", "activity_name": "Site Visit: Water Treatment Plant",
  "student": {"id": "u_s1", "username": "student1", "full_name": "Asha Verma", "role": "student"},
  "status": "recorded",
  "check_in": { "...AttendanceEvent": "" },
  "checkpoints": [],
  "check_out": null,
  "missing_checkout": false,
  "complete": false,
  "review_reasons": [],
  "decision": null,
  "decision_history": [],
  "observations": [],
  "next_actions": ["checkpoint", "check_out"]
}
```
- `status`: `null` when there are no events and no decision. Otherwise the current faculty
  decision if `is_current`, else `needs_review` if any event needs review, else `recorded`.
- `missing_checkout`: `true` when there is a check-in, no check-out, and the check-out window has closed.
- `complete`: `true` only when both check-in and check-out events exist. No hours are ever calculated or invented.
- `review_reasons`: union of the events' reasons.
- `next_actions`: which `Action`s the server would accept an attempt for right now. Always `[]` in faculty responses.

## Rules the server applies

**Activity windows (server time):** check-in from `starts_at − 15 min` to `ends_at`; checkpoint only inside an open
checkpoint window; check-out from the check-in until `ends_at + 30 min`.

**Action order:** one `check_in`; then `checkpoint` (at most one per window); one `check_out`, after which nothing more.

**Attempt:** bound to student + activity + action; expires 90 s after creation.

**Submit order (exactly this order):**
1. If this attempt already has a saved event, return it with 200 (even if 90 s have passed).
2. If the attempt is older than 90 s → `410 attempt_expired`.
3. Location reading older than 60 s → `422 location_stale` (attempt stays open).
4. Selfie unreadable / no face → `422 unusable_capture`; several faces → `422 multiple_faces` (attempt stays open).
5. Compute verdicts, save the event and consume the attempt in one transaction, delete the selfie, return 200.

**Location verdict** (`d` = distance to venue, `a` = reported accuracy, `r` = radius, all metres):
`d + a <= r` → `inside`; `d − a > r` → `outside` (+ `location_outside`); otherwise `uncertain` (+ `location_uncertain`).

**Face verdict:** `match`; `uncertain` (+ `face_uncertain`); `no_match` (+ `face_mismatch`).

**Event status:** `recorded` only if location is `inside` and face is `match`; otherwise `needs_review`.

## Endpoints

### Health
| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/health` | none | `{"status": "ok", "server_time": "..."}` |

### Authentication
| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/auth/login` | none | `{"username": "...", "password": "..."}` | `{"access_token": "...", "token_type": "bearer", "expires_at": "...", "user": User}` |
| GET | `/auth/me` | any | — | `User` |

### Enrollment
| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/enrollment` | student | multipart: `photo` | `201 Enrollment` (status `pending`). Replaces any earlier photo and cancels any earlier approval. Errors: `unusable_capture`, `multiple_faces`, `image_too_large` |
| GET | `/enrollment/me` | student | — | `Enrollment` |
| GET | `/enrollments?status=pending` | faculty | `status` optional (`pending`/`approved`/`rejected`) | `{"items": [Enrollment]}` |
| GET | `/enrollments/{id}/photo` | faculty | — | `image/jpeg` bytes (decrypted on the fly, `Cache-Control: no-store`) |
| POST | `/enrollments/{id}/approve` | faculty | `{"identity_confirmed": true}` (must be `true`) | `Enrollment` |
| POST | `/enrollments/{id}/reject` | faculty | `{"reason": "..."}` (required, non-empty) | `Enrollment` |

### Students and activities
| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/students` | faculty | — | `{"items": [User]}` |
| POST | `/activities` | faculty | `{"name", "venue_name", "venue_lat", "venue_lng", "radius_m" (default 100, 10–5000), "starts_at", "ends_at", "student_ids": [...]}` | `201 Activity` |
| GET | `/activities` | any | — | `{"items": [Activity]}`. Student: assigned activities only, with `my_attendance`. Faculty: all |
| GET | `/activities/{id}` | any | — | `Activity` (student must be on the roster) |
| GET | `/activities/{id}/roster` | faculty | — | `{"items": [User]}` |
| POST | `/activities/{id}/checkpoints` | faculty | `{"duration_minutes": 10}` (1–60, default 10) | `201 CheckpointWindow`. `409 checkpoint_not_open` is NOT used here; opening while one is open returns `409 invalid_action_order` |

### Attempts (student)
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/activities/{id}/attempts` | `{"action": Action}` | `201 {"id", "activity_id", "action", "created_at", "expires_at", "checkpoint_id"}`. Errors: `not_assigned`, `enrollment_not_approved`, `outside_activity_window`, `invalid_action_order`, `checkpoint_not_open` |
| POST | `/attempts/{id}/submit` | multipart: `selfie` (JPEG), `lat`, `lng`, `accuracy_m`, `location_captured_at` | `200 AttendanceEvent`. Safe to repeat. Errors: `attempt_expired`, `location_stale`, `unusable_capture`, `multiple_faces`, `forbidden` (not your attempt) |

### Attendance
| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/attendance/me` | student | `{"items": [AttendanceRecord]}` — one per assigned activity, newest activity first |
| GET | `/activities/{id}/register` | faculty | `{"activity": Activity, "server_time": "...", "rows": [AttendanceRecord]}` — one row per rostered student |
| GET | `/activities/{id}/export.csv` | faculty | `text/csv` with one line per rostered student |

### Faculty review
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/activities/{id}/students/{student_id}/decision` | `{"decision": "confirmed" \| "not_confirmed", "reason": "..."}` (reason required, non-empty) | `AttendanceRecord`. Decisions are appended, never overwritten; the newest is current |
| POST | `/activities/{id}/observations` | `{"student_id", "observation_type": ObservationType, "note": "..."}` (note required) | `201 Observation` |

## Seeded demo accounts

Created by the backend seed command. Password comes from `SEED_PASSWORD` in `backend/.env`.

| Username | Role | Name |
|---|---|---|
| `faculty1` | faculty | Dr. Meera Rao |
| `student1` | student | Asha Verma |
| `student2` | student | Rohan Iyer |
| `student3` | student | Fatima Khan |

## Known limitations (must stay visible in UIs and docs)

- Direct camera capture is not robust liveness detection. A photo of a photo may pass.
- Client-reported location can be manipulated.
- Recorded times are points of observation, not proof of continuous presence between them.

## Addendum: behaviour added after the freeze

Everything above is unchanged. This section lists behaviour of the backend that the frozen text does not
mention. Where it differs from the text above, this addendum describes what the backend does now.

### Extra error codes

| HTTP | code | When |
|---|---|---|
| 429 | `too_many_attempts` | `POST /auth/login` is rate limited: 10 tries per minute per username and client address, 30 per minute per client address (sliding 60 s window, held in memory) |
| 405 | `method_not_allowed` | A known path called with the wrong HTTP method |
| 500 | `server_error` | Any unhandled error. The message is generic |

- `413 image_too_large` is returned for any oversized request body, not only photos. A multipart upload body over
  6 MB is refused before it is read. Any other body (JSON) over 64 KB is refused the same way. A photo over 5 MB
  in a body under 6 MB is refused with the same code.
- A malformed request body that the server cannot parse (HTTP 400 or 415 from the web framework) is returned as
  `422 validation_error`.
- Two uploads for the same student at the same moment: the later one retries up to 3 times. If it still loses,
  it gets `409 invalid_action_order` ("Another upload for this student is in progress. Try again.").
- Two simultaneous approve/reject calls on one enrollment: one gets `200`, the other `409 already_decided`.
  If a newer upload replaced the enrollment in between, the other gets `404 not_found`.
- `POST /attempts/{id}/submit` returns `409 enrollment_not_approved` if the student's enrollment was replaced or
  is no longer approved by the time the event is saved.
- `POST /activities/{id}/checkpoints` returns `409 outside_activity_window` when the activity is not running
  (before `starts_at` or after `ends_at`).
- A decision or an observation for a student who is not on the activity's roster returns `404 not_found`.

### Enrollment

- Every `POST /enrollment` creates a new enrollment with a new `id`. The earlier enrollment is deleted, so its old
  `id` answers `404 not_found` on every endpoint that takes an enrollment id (photo, approve, reject).
- The uploaded file must be a JPEG. Any other format (for example PNG) is treated as unreadable and returns
  `422 unusable_capture`. This applies to enrollment photos and attendance selfies.

### Submit

- A `location_captured_at` more than 5 seconds in the future of the server clock returns `422 location_stale`,
  the same as a reading older than 60 s.

### Activity times

- `starts_at` and `ends_at` must be in the years 2000 to 2100, `ends_at` must be after `starts_at`, and the
  activity can last at most 7 days. Otherwise `422 validation_error`.
- The server does not compare the times with the current time. A `starts_at` (or `ends_at`) in the past is
  accepted. An activity whose `ends_at` has passed can be created, but no one can check in to it.

### Attendance records

- `next_actions` is computed from the activity rules only (windows and action order). It ignores the student's
  enrollment state. A student without an approved enrollment can still see `check_in` there, and gets
  `409 enrollment_not_approved` when an attempt is requested.
- `decision` is `null` when the newest decision is not current (an automated event arrived after it).
  `decision_history` always lists every decision, newest first, including that one, with `is_current: false`.

### Operations

- The interactive API docs (`/docs`, `/redoc`) and `/openapi.json` are not served unless the server is started
  with `ENABLE_DOCS=true` (default off). They need no login when on.
- Faculty and students have separate seed passwords. The demo `faculty1` account uses `FACULTY_SEED_PASSWORD`; the
  student accounts use `SEED_PASSWORD`. The two must be different, and the seed command refuses to run otherwise.
  This replaces "Password comes from `SEED_PASSWORD`" in the section above for `faculty1`.
