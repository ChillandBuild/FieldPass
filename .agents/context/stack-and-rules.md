# Invariants, Tech Stack & File Map

## Tech Stack
- **Backend** (`backend/`): Python 3.11 exactly, FastAPI, SQLAlchemy 2 (sync), uvicorn, pydantic 2, argon2-cffi, PyJWT (HS256), cryptography (Fernet), opencv-python-headless (YuNet detect + SFace compare), Pillow. API prefix `/api/v1`.
- **DB**: SQLite by default (`DATABASE_PATH`, WAL). Postgres/Supabase when `DATABASE_URL` is non-empty (`DATABASE_SCHEMA`, default `fieldpass`). See [[subsystem-notes]].
- **Dashboard** (`dashboard/`): React 19, TypeScript, Vite, react-router 7. Node 22. Vitest + Playwright.
- **Android** (`android/`): Kotlin 2.4, Compose M3, CameraX, Fused Location, Retrofit/OkHttp, minSdk 26, targetSdk 36. JDK 21.
- **Hosting**: two modes. Laptop on a phone hotspot (plain HTTP, 8000/5173, debug APK only), or hosted: Render (`render.yaml`) + Supabase + Vercel + a signed release APK over https. Steps and settings: `backend/README.md` "Hosting", `android/README.md` "Release build". The hosted path has not been run on real services or a phone.

## Hard Invariants
1. Automated checks only ever produce `recorded` or `needs_review`. Only a faculty decision produces `confirmed` / `not_confirmed` (reason required). (`routers/attempts.py` `_build_event`, `schemas.py`, API.md)
2. No numeric face score / confidence in the API or logs, and no liveness field. Scores never leave `face.py`.
3. Server clock decides windows and expiry; clients are never trusted. Submit order is fixed.
4. Decisions and observations are append-only audit rows. Newest decision is current; it goes stale (`decision` null) if a later automated event arrives.
5. Enrollment approve requires `identity_confirmed: true` and a faculty token (`require_faculty`).
6. Faculty seed password must differ from student `SEED_PASSWORD` (seed refuses).
7. Server refuses to start without JWT_SECRET (>=32 chars), PHOTO_ENCRYPTION_KEY (valid Fernet), SEED_PASSWORD, FACULTY_SEED_PASSWORD, or face models.
8. Attendance selfies never touch disk. Enrollment photos are Fernet-encrypted at rest; embeddings encrypted in DB.
9. Never commit `.env`, face photos (`backend/tests/samples/`), or emulator screenshots (all gitignored).
10. Postgres: RLS on every table with no policies; `anon`/`authenticated` roles revoked. Don't weaken.

## File Map
| Path | What |
|---|---|
| `backend/app/main.py` | `create_app()` factory, middleware, 6 routers |
| `backend/app/rules.py` | pure rules: windows, action order, missing_checkout, derive_status |
| `backend/app/routers/` | auth, enrollment, activities, attempts, attendance (+CSV), review |
| `backend/app/{db,models,config,security,photo_store,face*,clock,geo,seed}.py` | infra and domain |
| `backend/scripts/` | download_models, generate_fixtures, export_openapi |
| `backend/tests/` | 19 files; fake engine in `fakes.py`, injected in `conftest.py` |
| `contracts/API.md` | the frozen contract; `openapi.yaml` + `fixtures/` are GENERATED from the backend |
| `dashboard/src/{screens,components,hooks,lib,api}` | UI; `api/realClient` vs `api/fixtureClient` |
| `android/app/src/main/java/com/fieldpass/student/` | ui, auth, enrollment, activities, capture, history, core, data |
| `integration/` | runbook, demo-script, checklist, known-limitations |

## Test Commands
- Backend: `cd backend && .venv/bin/python -m pytest` (add `--cov=app --cov-report=term-missing`)
- Dashboard: `cd dashboard && npm run typecheck && npm run lint && npm test`; e2e: `npm run e2e` (needs setup, see dashboard/README.md)
- Android: `cd android && ./gradlew testDebugUnitTest lintDebug`; contract: `android/scripts/integration-test.sh`
