# Subsystem Notes & Load-Bearing Gotchas

## Backend startup / network
- Needs Python **3.11 exactly**. The README's uv-managed python3.11 path is specific to the demo laptop; use your own 3.11 binary elsewhere.
- First start in a new venv takes ~45 s with the port closed (curl → connection refused). Later starts ~1 s. Wait for "Application startup complete."
- `run.sh` binds `0.0.0.0:8000`. Plain uvicorn binds 127.0.0.1 and the phone can't reach it.
- `CORS_ORIGINS` must contain the dashboard's exact origin; restart backend after editing. CORS allows only GET/POST/OPTIONS.
- `ENABLE_DOCS` off by default → `/docs`, `/redoc`, `/openapi.json` return 404. `scripts/export_openapi.py` works either way.
- Plain HTTP only. Android release build blocks cleartext, so only the **debug** build works against the laptop.
- Losing `PHOTO_ENCRYPTION_KEY` makes stored photos unreadable. Changing `JWT_SECRET` signs everyone out.

## Timing rules (rules.py)
- Defaults: ATTEMPT_TTL 90 s, LOCATION_MAX_AGE 60 s, CHECKIN_LEAD 15 min, CHECKOUT_GRACE 30 min.
- missing_checkout = has check_in, no check_out, and now > ends_at + grace.
- Location verdict: inside if d+a<=r, outside if d-a>r, else uncertain (d = distance, a = phone-reported accuracy, r = radius).
- Timing e2e tests start a backend with short TTL and zero grace (`android/scripts/integration-test.sh`, `dashboard/e2e/timing.spec.ts`). Don't change those env names without updating both.

## Face engine
- Real: `face_opencv.py` (models in `backend/models/*.onnx`, ~39 MB, gitignored, fetched by `scripts/download_models.py`). Tests use `FakeFaceEngine` via `dependency_overrides[get_face_engine]`.
- Threshold 0.363 is the OpenCV sample default; uncertain margin 0.1 is our own guess. Neither is validated (known-limitations 10–13).

## Database (SQLite vs Postgres/Supabase)
- Selected purely by `DATABASE_URL` being non-empty. Accepts postgres://, postgresql://, postgresql+psycopg://; normalised to psycopg 3.
- Targets Supabase's **session pooler** (port 5432). sslmode=require for non-local hosts. Pool 5+5. Advisory lock on schema init. Tables live in `DATABASE_SCHEMA` via `schema_translate_map`.
- `TEST_DATABASE_URL` switches the whole pytest suite to Postgres; `tests/test_postgres.py` skips without it.
- Error messages must never echo the URL (contains the password).
- The README and known-limitations still describe SQLite only: see [[active-backlog]].

## Contracts are generated
- `contracts/fixtures/*.json` come from `backend/scripts/generate_fixtures.py` (fake engine, fixed clock); `contracts/openapi.yaml` from `export_openapi.py`. **Regenerate both after any API change.**
- API.md Addendum quirks: each enrollment upload makes a new id (old one 404s); `next_actions` ignores enrollment state; CSV cells starting `= + - @` get a leading `'`; faculty1 password comes from FACULTY_SEED_PASSWORD (overrides the main text).

## Dashboard
- `VITE_USE_FIXTURES=true` shows a fixture banner; must be false/absent for the real backend.
- Server address is set on the login screen and stored in localStorage; the auth token is in sessionStorage only.
- Stale-enrollment message is not covered by Playwright (known-limitations 15).

## Android
- Never run on a physical phone yet (camera orientation, permissions, GPS time, Keystore, hotspot untested; known-limitations 17).
- Needs `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` or `android/local.properties` (gitignored).
- Phone clock skew can cause `location_stale`.
