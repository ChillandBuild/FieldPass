# Subsystem Notes & Load-Bearing Gotchas

## Backend startup / network
- Needs Python **3.11 exactly**. The README's uv-managed python3.11 path is specific to the demo laptop; use your own 3.11 binary elsewhere.
- First start in a new venv takes ~45 s with the port closed (curl → connection refused). Later starts ~1 s. Wait for "Application startup complete."
- `run.sh` binds `0.0.0.0:8000`. Plain uvicorn binds 127.0.0.1 and the phone can't reach it.
- `CORS_ORIGINS` must contain the dashboard's exact origin; restart backend after editing. CORS allows only GET/POST/OPTIONS.
- `ENABLE_DOCS` off by default → `/docs`, `/redoc`, `/openapi.json` return 404. `scripts/export_openapi.py` works either way.
- Laptop mode is plain HTTP, so only the **debug** APK works against it. The release build blocks cleartext and needs the hosted https address.
- Hosted: Render free sleeps after 15 min (first request ~1 min); `render.yaml` runs `app.seed` in the build, so `DATABASE_URL` must be reachable at build time. Release signing reads `FIELDPASS_KEYSTORE*` env vars or untracked `android/keystore.properties`.
- Losing `PHOTO_ENCRYPTION_KEY` makes stored photos unreadable. Changing `JWT_SECRET` signs everyone out.

## Timing rules (rules.py)
- Defaults: ATTEMPT_TTL 90 s, LOCATION_MAX_AGE 60 s, CHECKIN_LEAD 15 min, CHECKOUT_GRACE 30 min.
- missing_checkout = has check_in, no check_out, and now > ends_at + grace.
- Location verdict: inside if d+a<=r, outside if d-a>r, else uncertain (d = distance, a = phone-reported accuracy, r = radius).
- Timing e2e tests start a backend with short TTL and zero grace (`android/scripts/integration-test.sh`, `dashboard/e2e/timing.spec.ts`). Don't change those env names without updating both.

## Face engine
- Real: `face_opencv.py` (models in `backend/models/*.onnx`, ~39 MB, gitignored, fetched by `scripts/download_models.py`). Tests use `FakeFaceEngine` via `dependency_overrides[get_face_engine]`.
- Threshold 0.363 is the OpenCV sample default; uncertain margin 0.1 is our own guess. Neither is validated (known-limitations 10–13).
- Head turn: `liveness.py` uses the nose offset from YuNet landmarks; sign depends on photo mirroring (`LIVENESS_MIRRORED`). Real-engine test harnesses open the limits wide (`LIVENESS_FRONT_MAX_OFFSET=9`, `LIVENESS_TURN_MIN_OFFSET=-9`) because no turned-head sample photo exists. Thresholds (front 0.12, turn 0.25) are uncalibrated on real faces; the two real-engine tests have never run (sample photos `backend/tests/samples/*.jpg` absent).

## Database (SQLite vs Postgres/Supabase)
- Selected purely by `DATABASE_URL` being non-empty. Accepts postgres://, postgresql://, postgresql+psycopg://; normalised to psycopg 3.
- Targets Supabase's **session pooler** (port 5432). sslmode=require for non-local hosts. Pool 5+5. Advisory lock on schema init. Tables live in `DATABASE_SCHEMA` via `schema_translate_map`.
- `TEST_DATABASE_URL` switches the whole pytest suite to Postgres; `tests/test_postgres.py` skips without it.
- Error messages must never echo the URL (contains the password).
- **Settings trap:** the settings loader reads `backend/.env` (real `DATABASE_URL`/`SUPABASE_*`) even for scripts that only set `DATABASE_PATH`, and an EMPTY environment variable overrides the `.env` value. Any script or test harness that boots the backend must blank `DATABASE_URL`, `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` (`android/scripts/integration-test.sh` and `dashboard/e2e/support/backend.ts` now do). Two accidental runs wrote test data into the production Supabase DB before this was found (see [[active-backlog]]).
- Anti-cheat A adds columns; `create_all` never adds columns to existing tables, so existing DBs need the `ALTER TABLE` statements in `contracts/API.md` or a reset.
- Only enrollment photos go to the bucket; attendance selfies never touch storage.
- Faculty can upload an enrollment photo for a student (`POST /students/{id}/enrollment`, dashboard Students page). It is `pending`; approval stays a separate `identity_confirmed` step (invariant 5).

## Contracts are generated
- `contracts/fixtures/*.json` come from `backend/scripts/generate_fixtures.py` (fake engine, fixed clock); `contracts/openapi.yaml` from `export_openapi.py`. **Regenerate both after any API change.**
- API.md Addendum quirks: each enrollment upload makes a new id (old one 404s); `next_actions` ignores enrollment state; CSV cells starting `= + - @` get a leading `'`; faculty1 password comes from FACULTY_SEED_PASSWORD (overrides the main text).

## Presence code (QR)
- Made in `backend/app/presence.py` (pure, from `JWT_SECRET` + activity id + `clock.now()`); stateless, nothing stored per code. Changing `JWT_SECRET` invalidates shown codes (they rotate anyway). Step length: `PRESENCE_CODE_STEP_SECONDS` (5-60, default 10); valid for the current and previous step.
- Only `POST /attempts/{id}/presence` writes (table `presence_scans`, new table, no ALTER). A scan clears `location_uncertain` only (`_build_event` in `routers/attempts.py`), and only when submitted `accuracy_m` <= `PRESENCE_CODE_MAX_ACCURACY_M` (default 150): accuracy is phone-reported, so uncapped it let a far phone claim a huge figure to turn `outside` into `uncertain`. Do not remove the cap; `location_outside` and every other reason are untouched, and a bad code never errors or adds a reason.
- `presence_verified` on events comes from one extra query in `views.load_activity_data` (`scanned_attempts`), not per event; only scans with `verified_at <= received_at` count (a scan that loses a race with submit is not shown). Postgres RLS is automatic (`_lock_down_schema` loops over every table in the schema).

## Dashboard
- `VITE_USE_FIXTURES=true` shows a fixture banner; must be false/absent for the real backend.
- Server address is set on the login screen and stored in localStorage; the auth token is in sessionStorage only.
- Stale-enrollment message is not covered by Playwright (known-limitations 15).

## Android
- Never run on a physical phone yet (camera orientation, permissions, GPS time, Keystore, hotspot untested; known-limitations 17).
- Needs `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` or `android/local.properties` (gitignored).
- Phone clock skew can cause `location_stale`.
- Phone key lives in the Keystore (`fieldpass_device_key`); reinstall = new key = faculty reset. The signed text must match `backend/app/device_keys.py` byte for byte (shared test vector in all three test suites; numbers formatted with `Locale.US`).
- Test helpers nudge latitude by 0.000001° per submit, otherwise every test check-in is flagged `location_repeated`.
- App version comes from git (commit count touching `android/`), not a hand-edited line; see decisions log 2026-10-11. Commit before building an APK to hand out — uncommitted edits keep the old `versionCode` and a phone refuses an equal-or-lower one.
- `./gradlew` needs JDK 17+ (Gradle 9.6). On the Windows dev box `JAVA_HOME` defaults to JDK 8 (`C:\java\jdk1.8.0_321`); point it at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` for terminal builds. Android Studio uses its own JDK.
