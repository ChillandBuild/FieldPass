# FieldPass backend

FastAPI + SQLite. The backend is the only judge of attendance: it compares a fresh selfie with
the approved enrollment photo, evaluates location evidence, records events and supports faculty
review. The contract is `../contracts/API.md`; `../contracts/openapi.yaml` is exported from this code.

## Setup from scratch

Python 3.11 is required (the system `python3` may be newer; use the 3.11 binary explicitly).

```bash
cd backend
python3.11 -m venv .venv     # if python3.11 is not on your PATH, use its full path (find it with: which -a python3.11)
.venv/bin/pip install -r requirements-dev.txt        # runtime + test tools

cp .env.example .env
.venv/bin/python -m app.gen_secrets                  # prints JWT_SECRET, PHOTO_ENCRYPTION_KEY and two suggested passwords
# paste all four lines into .env: SEED_PASSWORD (student accounts) and FACULTY_SEED_PASSWORD (faculty1)
# must be different. JWT_SECRET must be at least 32 characters.

.venv/bin/python scripts/download_models.py          # YuNet + SFace into models/ (about 39 MB)
.venv/bin/python -m app.seed                         # demo accounts (idempotent)
.venv/bin/python -m app.seed --demo-activity --lat 12.9716 --lng 77.5946
```

After pulling the anti-cheat changes, recreate an older demo database (new columns are not added in place):
delete `data/fieldpass.db` (or drop the Postgres schema) and run the seed commands again. To keep the data instead, run
the `ALTER TABLE` statements in the "Upgrading an existing database" note of `contracts/API.md`.

The server refuses to start (with a clear message) if a secret is missing or invalid (short
`JWT_SECRET`, malformed `PHOTO_ENCRYPTION_KEY`) or the models are not downloaded. Seeding stops if
`FACULTY_SEED_PASSWORD` is missing or equal to `SEED_PASSWORD`. `.env`, `data/` and `models/*.onnx` are git-ignored. Losing `PHOTO_ENCRYPTION_KEY`
makes stored enrollment photos unreadable; changing `JWT_SECRET` signs everyone out.

Demo accounts: `student1`, `student2`, `student3` (password `SEED_PASSWORD`) and `faculty1` (password
`FACULTY_SEED_PASSWORD`). Faculty can approve photos and decide attendance, so they have their own password.
`--demo-activity` creates an activity that starts now, lasts 4 hours, has a 100 m radius and all
three students on the roster. Run it again for a fresh one.

## Timing settings

All optional, read from `.env` or the environment. Defaults match `../contracts/API.md`.

| Setting | Default | Meaning |
|---|---|---|
| `ATTEMPT_TTL_SECONDS` | 90 | How long an attempt can still be submitted after it is created |
| `LOCATION_MAX_AGE_SECONDS` | 60 | Oldest location reading accepted |
| `CHECKIN_LEAD_MINUTES` | 15 | Check-in opens this long before `starts_at` (and closes at `ends_at`) |
| `CHECKOUT_GRACE_MINUTES` | 30 | Check-out stays open this long after `ends_at`; after that a check-in with no check-out is `missing_checkout` |

The two window settings are numbers of minutes (fractions allowed, so `0.5` is 30 s), from 0 to 10080 (7 days).
A negative, non-numeric, `nan` or too large value stops the server at startup with a message naming the setting.
Changing them changes the contract's 15/30 minute rules for that server; use non-default values only for tests
(the end-to-end tests start a backend with a short attempt TTL and a zero checkout grace).

## Run (LAN)

```bash
./run.sh            # or: ./run.sh 9000
# same as: .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

`run.sh` prints the URL to use. To find it yourself on macOS: `ipconfig getifaddr en0`
(Wi-Fi), then the base URL is `http://<that-ip>:8000`. The phone and the laptop must be on the same
network (a phone hotspot works). Point the Android app and the dashboard at that base URL; the API
lives under `/api/v1` (health check: `curl http://<ip>:8000/api/v1/health`). The interactive docs
(`/docs`, `/redoc`) and `/openapi.json` are off by default; set `ENABLE_DOCS=true` in `.env` to serve them
(they need no login). `scripts/export_openapi.py` works either way. For a dashboard served from another origin, add it to `CORS_ORIGINS` in `.env`.
The connection is plain HTTP, so use a network you trust and do not use real student data.

## Tests

```bash
.venv/bin/python -m pytest                       # fake face engine, no models needed
.venv/bin/python -m pytest --cov=app --cov-report=term-missing
```

Tests use a deterministic fake `FaceEngine` injected through FastAPI dependency overrides and a
controllable clock (`app/clock.py`), so nothing sleeps. `tests/test_real_engine.py` is opt-in: the
blank-image check runs when the models exist; the identity check also needs three git-ignored photos
in `tests/samples/` (`person_a_1.jpg`, `person_a_2.jpg`, `person_b.jpg`).

## Contracts and fixtures

```bash
.venv/bin/python scripts/export_openapi.py       # writes ../contracts/openapi.yaml
.venv/bin/python scripts/generate_fixtures.py    # writes ../contracts/fixtures/*.json
```

Fixtures are real responses from the app (fake face engine, fixed clock), so they cannot drift.
`fixtures/errors_index.json` maps every error code to its HTTP status.

## Face matching

- Detection: OpenCV `FaceDetectorYN` (YuNet 2023mar). Recognition: `FaceRecognizerSF` (SFace 2021dec).
  Both ONNX files come from https://github.com/opencv/opencv_zoo (Git LFS; the download script uses
  the `raw` URL that redirects to `media.githubusercontent.com` and checks the file sizes).
- Threshold: cosine similarity **0.363**, taken from the official OpenCV sample
  https://github.com/opencv/opencv/blob/4.x/samples/dnn/face_detect.cpp
  (`double cosine_similar_thresh = 0.363;`, same value as `models/face_recognition_sface/sface.py` in
  opencv_zoo). It is the default of `FACE_MATCH_THRESHOLD`.
- `score >= threshold` is `match`; within `FACE_UNCERTAIN_MARGIN` (default 0.1) below it is `uncertain`;
  lower is `no_match`. The 0.1 margin is our own choice, not from OpenCV.
- Uploads: JPEG only, max 5 MB, EXIF orientation applied, downscaled to at most 1280 px, faces smaller
  than 48 px are ignored. The stored enrollment photo is the upright re-encoded copy (metadata such as
  GPS is dropped), encrypted with Fernet in `data/photos/`; its embedding is stored encrypted in the DB.
- Numeric scores are never returned or logged. Attendance selfies are processed in memory only.
  `BodyLimitMiddleware` (`app/body_limit.py`) refuses any request body over 6 MB (photo limit plus form
  overhead) with `413 image_too_large`, using `Content-Length` before reading anything and a streaming
  byte count when there is none; non-upload (JSON) bodies are capped at 64 KB. Because no body can exceed
  that cap, `main.py` sets Starlette's multipart spool limit to the same value, so the parser never
  spills an upload part to a temp file. The 5 MB photo check itself still happens when the part is read.

## Behaviour decisions beyond API.md

These are small choices where the contract is silent; clients should not rely on more than the contract.

- Extra error codes: `429 too_many_attempts` (login rate limit: 10 tries a minute per IP and username, 30 per IP),
  `405 method_not_allowed`, `500 server_error`. A malformed request body (Starlette 400/415) is `422 validation_error`.
- Every enrollment upload creates a new enrollment id; the old id answers `404 not_found`. Approve and reject
  are conditional updates, so two simultaneous decisions give one `200` and one `409 already_decided`.
- A location reading dated more than 5 s in the future is `422 location_stale`. Activity times must be in the
  years 2000 to 2100 and last at most 7 days (`422 validation_error` otherwise).
- Photo responses carry `X-Content-Type-Options: nosniff`.
- `next_actions` reflects activity rules only (windows and order); it does not include the enrollment gate,
  which is reported by `enrollment_not_approved` when an attempt is requested.
- `decision_history` is newest first. `decision` is the newest decision only while it is current.
- Submit re-checks action order at save time (using the attempt's creation time for windows), and the
  database enforces one check-in, one check-out and one answer per checkpoint window, so two attempts for
  the same action cannot both be recorded; the loser gets `409 invalid_action_order`.
- A student's submit is checked in this order: attempt exists (404), is theirs (403), already saved
  (200 replay), expired (410), location stale (422), selfie unreadable/multiple faces (422/413).
- Opening a checkpoint requires the activity to be running (`starts_at` to `ends_at`), else
  `409 outside_activity_window`. A decision or observation for a student not on the roster is `404`.
- CSV cells that start with `= + - @` are prefixed with `'` to defuse spreadsheet formulas.

## Known limitations

- No liveness detection. A photo of a photo may pass. Nothing here claims otherwise.
- Client-reported location can be spoofed; accuracy is also client-reported.
- The face threshold has not been validated on a volunteer set. 0.363 is OpenCV's general default and the
  uncertain margin is a guess; measure real match and non-match scores before trusting it.
- Real-face matching is only smoke-tested by hand; the automated suite uses a fake engine.
- Plain HTTP, in-memory login rate limit (resets on restart; idle keys are dropped, at most 10,000 are kept),
  SQLite, single process. A submit whose enrollment was replaced after its comparison is refused with
  `409 enrollment_not_approved`.
- Recorded times are points of observation, not proof of continuous presence.
