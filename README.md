# FieldPass

FieldPass is a field-attendance prototype. A student uses an Android app to check in, answer
checkpoints and check out of a field activity (for example a site visit). The app sends a selfie
and a location reading to a backend. The backend compares the selfie with the student's approved
enrollment photo, checks the location against the venue, and records what it saw. Faculty use a web
dashboard to approve enrollment photos, watch the register, and confirm or decline each student's
attendance.

It is a prototype. It has been built and tested for a demo, not for real attendance decisions.

## What FieldPass does NOT prove

Read this before you present or use it.

- **A camera selfie is not liveness detection.** The app takes the photo with the camera, but the
  backend cannot tell a live face from a photo of a photo. A printed or on-screen picture may pass.
- **Location is reported by the phone and can be manipulated.** The accuracy figure is also reported
  by the phone. Indoors it can be off by tens of metres.
- **Recorded times are points of observation, not continuous presence.** A check-in at 09:31 and a
  check-out at 13:10 do not show the student was there in between. No hours are calculated.

Every automated result is evidence for faculty to review. It is not an accusation, and the
software never decides attendance by itself: faculty make the final call.

The full list is in [integration/known-limitations.md](integration/known-limitations.md).

## How the parts connect

```
  Android phone (student)                         Laptop (faculty)
  -----------------------                         ----------------
  FieldPass app                                   Browser: dashboard
        |                                         http://localhost:5173
        |  HTTP, JSON + JPEG                              |
        |  http://<laptop-ip>:8000/api/v1                 |  HTTP, JSON
        v                                                 v
   +-------------------------------------------------------------+
   |  Backend (FastAPI + SQLite + OpenCV face comparison)       |
   |  listens on 0.0.0.0:8000 on the laptop                     |
   +-------------------------------------------------------------+
```

The phone and the laptop must be on the same network. For the demo that network is a phone hotspot.
The backend is the only judge of attendance. The app and the dashboard only collect input and show
what the backend says.

## Folder map

| Folder or file | What it is |
|---|---|
| `backend/` | FastAPI server, SQLite database, OpenCV YuNet (face finder) and SFace (face comparer) |
| `dashboard/` | React + TypeScript + Vite web app for faculty |
| `android/` | Kotlin + Jetpack Compose student app |
| `contracts/API.md` | The frozen API contract. The one source of truth for all three parts. Behaviour added after the freeze is listed in its last section |
| `contracts/openapi.yaml` | OpenAPI file exported from the backend |
| `contracts/fixtures/` | Sample API responses generated from the real backend code |
| `integration/` | Runbook, demo script, checklist and known limitations for the demo |

## The four attendance statuses

These are the exact meanings from `contracts/API.md`.

| Status | Meaning |
|---|---|
| `recorded` | Automated checks passed; faculty confirmation pending. |
| `needs_review` | Evidence is uncertain or a check failed. Not an accusation. |
| `confirmed` | Faculty approved attendance. |
| `not_confirmed` | Faculty declined, with a reason. |

Automated checks only ever produce `recorded` or `needs_review`. Only a faculty decision produces
`confirmed` or `not_confirmed`. The API never returns a numeric face score or a "confidence" figure,
and there is no liveness field.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Python | 3.11 | The backend needs 3.11 exactly, and the setup names the 3.11 binary explicitly. Check with `python3.11 --version`. On the demo laptop it is `/Users/prem/.local/bin/python3.11`. That path exists only on that laptop (a uv-managed copy). On another machine install Python 3.11 first and use its path instead |
| Node.js | 22 | With npm. For the dashboard |
| JDK | 21 | The Android README says JDK 17 or newer works; 21 is what we use |
| Android command-line tools | see below | For building and installing the app |

The Android SDK was installed through Homebrew. Its root folder is:

```
/opt/homebrew/share/android-commandlinetools
```

If you need to set it up again, follow "Toolchain setup (macOS)" in [android/README.md](android/README.md).
Either export `ANDROID_HOME` to that path, or put `sdk.dir=/opt/homebrew/share/android-commandlinetools`
in `android/local.properties`.

You also need a phone hotspot, an Android phone (minSdk 26) with a USB cable, and `adb`
(part of the SDK's platform-tools).

## Quick start (all three parts on one laptop)

This runs the backend and the dashboard on your laptop. For the phone and the hotspot, follow
[integration/runbook.md](integration/runbook.md) instead; it covers everything below plus the network steps.

### 1. Backend

```bash
cd backend
/Users/prem/.local/bin/python3.11 -m venv .venv     # on another machine: the path of your Python 3.11
.venv/bin/pip install -r requirements-dev.txt

cp .env.example .env
.venv/bin/python -m app.gen_secrets      # prints four lines: JWT_SECRET, PHOTO_ENCRYPTION_KEY, SEED_PASSWORD, FACULTY_SEED_PASSWORD
```

Open `backend/.env` and fill in these four values. `.env` already has an empty line for each of them
(`JWT_SECRET=`). Put each printed value after the `=` on its own line. Do not add a second line for the same
name. The two password lines are suggestions: different random values. You may choose your own instead.

- `JWT_SECRET` and `PHOTO_ENCRYPTION_KEY`: required.
- `SEED_PASSWORD`: the password for the three student accounts. Required.
- `FACULTY_SEED_PASSWORD`: the password for the faculty account. Required, separate from
  `SEED_PASSWORD`, and the two must be different.

The server will not start if any of the four is empty. The seed command stops with a message
("Seeding stopped: ...") and creates nothing if `FACULTY_SEED_PASSWORD` is missing or equal to `SEED_PASSWORD`.

Then download the face models, create the accounts, and start the server:

```bash
.venv/bin/python scripts/download_models.py      # YuNet + SFace into models/ (about 39 MB)
.venv/bin/python -m app.seed                     # demo accounts (safe to run again)
./run.sh                                         # listens on 0.0.0.0:8000. Another port: ./run.sh 8002
```

The first start in a new virtual environment was slow when checked on 2026-10-10: about 45 seconds before the
terminal showed `Application startup complete.` and `Uvicorn running on http://0.0.0.0:8000`. Until then,
`curl` gets "connection refused". Wait for those two lines. The next start took about 1 second.

The interactive API docs (`/docs`, `/redoc`, `/openapi.json`) are off unless you set `ENABLE_DOCS=true`
in `backend/.env`. The default is off.

Check it: `curl http://localhost:8000/api/v1/health` should return `{"status":"ok","server_time":"..."}`.
Then check it on the laptop's network address too: `curl http://$(ipconfig getifaddr en0):8000/api/v1/health`.
The same answer on both addresses proves the server listens on all interfaces, which the phone needs.
(If your port is not 8000, use your port in both commands.)

Demo accounts:

| Username | Role | Name | Password setting |
|---|---|---|---|
| `faculty1` | faculty | Dr. Meera Rao | `FACULTY_SEED_PASSWORD` |
| `student1` | student | Asha Verma | `SEED_PASSWORD` |
| `student2` | student | Rohan Iyer | `SEED_PASSWORD` |
| `student3` | student | Fatima Khan | `SEED_PASSWORD` |

### 2. Dashboard

In a second terminal:

```bash
cd dashboard
npm install
cp .env.example .env.local
npm run dev                  # http://localhost:5173
```

To use another port, add it after `--`: `npm run dev -- --port 5174`. Then open `http://localhost:5174`, and add
`http://localhost:5174` to `CORS_ORIGINS` in `backend/.env` (see section 8 of the runbook), or the login screen
will say it cannot reach the server.

Open http://localhost:5173, sign in as `faculty1`. In `.env.local`, `VITE_USE_FIXTURES` must be `false`
(or the line removed) to use the real backend. `.env.example` already says `false`. On the login screen, type
the backend address in "Server address" (for a one-laptop run, `http://localhost:8000`) and press "Test
connection". The field starts filled with the example address `http://192.168.43.10:8000`, which is almost
certainly wrong for you, so always replace it. A good result reads "Connected. Server time ...".

### 3. Android app

```bash
cd android
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk` (about 18 MB). If Gradle stops with
"SDK location not found", neither `ANDROID_HOME` nor `android/local.properties` is set. Either one is enough.
The build checked on 2026-10-10 took 4 to 7 seconds because that laptop's Gradle cache was already warm. A first
build on a machine with an empty cache was not tested. Expect it to need internet and to take longer.

A phone cannot reach `localhost` on your laptop, so the app needs the laptop's network address.
That is covered in the runbook.

## Run the tests

```bash
# backend (fake face engine, no models needed)
cd backend && .venv/bin/python -m pytest
cd backend && .venv/bin/python -m pytest --cov=app --cov-report=term-missing

# dashboard
cd dashboard && npm run typecheck
cd dashboard && npm run lint
cd dashboard && npm test

# dashboard end-to-end against the REAL backend (needs setup first, see dashboard/README.md)
cd dashboard && npm run e2e

# android
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew lintDebug

# android contract tests against a throw-away real backend on port 8001
android/scripts/integration-test.sh
```

`npm run e2e` needs ports 8000 and 5173 free, the face models downloaded, Playwright's Chromium
installed, three sample photos in `backend/tests/samples/`, and both `SEED_PASSWORD` and
`FACULTY_SEED_PASSWORD` set in `backend/.env`. The exact steps are in
[dashboard/README.md](dashboard/README.md). Never commit those photos.

`android/scripts/integration-test.sh` also reads both passwords from `backend/.env` (or the environment).

## Where to read next

| Document | What is in it |
|---|---|
| [contracts/API.md](contracts/API.md) | Every endpoint, error code and rule |
| [backend/README.md](backend/README.md) | Backend setup, face matching details, behaviour beyond the contract |
| [dashboard/README.md](dashboard/README.md) | Dashboard setup, fixture mode, end-to-end tests |
| [android/README.md](android/README.md) | Toolchain, build, install, server address, fixture mode |
| [integration/runbook.md](integration/runbook.md) | Step-by-step startup on a hotspot, troubleshooting, cleanup |
| [integration/demo-script.md](integration/demo-script.md) | Timed 8-minute spoken walkthrough |
| [integration/checklist.md](integration/checklist.md) | The eight demo scenarios and the pre-demo checks. Fill it in during the demo |
| [integration/known-limitations.md](integration/known-limitations.md) | What is untested, unvalidated or unsafe |

## Privacy

Enrollment photos are pictures of real faces. Only enrol people who have agreed to it, and delete
the data after the demo (see the cleanup section of the runbook).
