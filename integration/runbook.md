# FieldPass demo runbook (laptop + Android phone + phone hotspot)

> **Verified on** macOS 26.7 (build 25G229), 2026-10-10, by following the docs literally in a fresh `git clone`
> (backend on port 8002 and dashboard on port 5174, because 8000 and 5173 were in use).
>
> **Executed from the fresh clone and worked** (with the fixes now in this runbook):
> - Section 3: venv with Python 3.11, install, `.env`, secrets, models, seed. Seeding with equal passwords was
>   refused with the documented message and created no database.
> - Section 4: `--demo-activity --lat 12.9716 --lng 77.5946`.
> - Section 5: `./run.sh 8002`. `/api/v1/health` answered 200 on `127.0.0.1` and on the laptop's `en0` address
>   (so it listens on all interfaces). `faculty1` and `student1` signed in with their own passwords; `faculty1`
>   with the student password got 401 `invalid_credentials`; `/docs`, `/redoc` and `/openapi.json` gave 404; a 7 MB
>   enrollment upload got 413 `image_too_large`.
> - Section 8 and the CORS row of the troubleshooting table: the symptom and the fix were both reproduced.
> - Section 9: dashboard in real mode in a headless browser. The login page showed no FIXTURE MODE banner, "Test
>   connection" read "Connected", and `faculty1` signed in to the Enrollments screen.
> - Section 10, build only: `./gradlew assembleDebug` produced `android/app/build/outputs/apk/debug/app-debug.apk`.
> - Section 6: the firewall commands were run read-only and the wording was checked against macOS 26.7. The
>   firewall was off on that laptop.
> - "After the demo": steps 1 and 2 (stop servers, `rm -r backend/data`). Step 3 (CSV files) and step 4
>   (`backend/tests/samples/`) had nothing to delete in a fresh clone.
> - Test commands: backend `pytest` (223 passed, 1 skipped), `pytest --cov=app` (98 percent total),
>   dashboard `npm run typecheck`, `npm run lint`, `npm test` (80 passed).
>
> **NOT executed**: section 1 (hotspot) and section 2 on a hotspot (`ipconfig getifaddr en0` itself worked and printed
> an address, but the laptop was not on a hotspot), section 7 (phone
> browser check), the `adb` steps of section 10 (install, `adb devices`, `adb uninstall`), the app's server address,
> login and camera/location behaviour, section 11 on the phone, hotspot client isolation, the firewall in its
> enabled state (adding Python in Options, the allow pop-up), any scenario in `checklist.md` or `demo-script.md`,
> `npm run e2e` (needs face photos), `npm run screenshots`, `android/scripts/integration-test.sh`,
> `./gradlew testDebugUnitTest` and `lintDebug`, and a first Gradle build with an empty cache.

This runbook gets the whole demo running with no campus Wi-Fi. Follow the steps in order.
Allow about 45 minutes the first time. The "missing checkout" scenario needs an extra short
activity prepared at least 40 minutes before the demo; see [demo-script.md](demo-script.md).

Terms used here:

- **Backend**: the server program in `backend/`. It runs on the laptop.
- **Dashboard**: the faculty web page in `dashboard/`. It runs in a browser on the laptop.
- **Hotspot**: the Wi-Fi network your own phone shares. Both the laptop and the Android phone join it.
- **Base URL**: the backend's address, for example `http://192.168.43.10:8000`. Both clients need it.
- **CORS**: a browser safety rule. A web page may only call a server that lists the page's address
  as allowed. The backend's allowed list is the `CORS_ORIGINS` setting.
- **adb**: Android Debug Bridge. A command-line tool that installs apps on a phone over USB.

Before the demo, also read [checklist.md](checklist.md). It lists the eight scenarios and the
pre-demo checks, and you fill it in during the demo. The spoken script is in [demo-script.md](demo-script.md).

## 1. Connect everything to the hotspot

1. On the hotspot phone, turn the hotspot on.
2. Join the laptop to the hotspot Wi-Fi.
3. Join the Android phone that will run the app to the same hotspot. If the hotspot phone is the same
   phone that runs the app, that is a problem: use a different phone for the app, or see the
   troubleshooting table.
4. Do not use campus Wi-Fi. It may block devices from reaching each other.

## 2. Find the laptop's address

```bash
ipconfig getifaddr en0
```

This prints something like `192.168.43.10`. Your base URL is then `http://192.168.43.10:8000`.
Write it down. It changes every time the hotspot changes, and sometimes when it reconnects, so run the
command again if anything stops working. If the command prints nothing, try `ipconfig getifaddr en1`.

## 3. First-time backend setup

Skip to step 4 if `backend/.venv`, `backend/.env` and the models already exist and you have seeded.

```bash
cd backend
/Users/prem/.local/bin/python3.11 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
cp .env.example .env
.venv/bin/python -m app.gen_secrets
```

The backend needs Python 3.11. `/Users/prem/.local/bin/python3.11` exists on the demo laptop only (a uv-managed
copy; `python3.11 --version` printed 3.11.15 there). On another machine, install Python 3.11 first and put its own
path in the first command. The system's plain `python3` is a different version (3.14 on the demo laptop) and is
not the one to use. `run.sh` prints the same hard-coded path in its "No virtualenv" message.

The last command prints four lines: `JWT_SECRET=...`, `PHOTO_ENCRYPTION_KEY=...`, `SEED_PASSWORD=...` and
`FACULTY_SEED_PASSWORD=...`. The two password lines are different random suggestions.

Open `backend/.env` in an editor and set these values. `.env` already contains an empty line for each setting
(for example `JWT_SECRET=`). Put the printed value after the `=` on that line. Do not add a second line for the
same name. You can use all four printed values, or choose your own passwords. Do not put the file in chat, email or git.

| Setting | What to put | Notes |
|---|---|---|
| `JWT_SECRET` | the printed line | At least 32 characters. Changing it later signs everyone out |
| `PHOTO_ENCRYPTION_KEY` | the printed line | If you lose it, stored enrollment photos cannot be read |
| `SEED_PASSWORD` | the printed line, or a password of your choice | The password of `student1`, `student2`, `student3` |
| `FACULTY_SEED_PASSWORD` | a different password | The password of `faculty1`. Required. It is its own setting and must differ from `SEED_PASSWORD` |

Keep the two passwords somewhere private. Students must never know the faculty password.

Download the face models. This is about 39 MB. It needs internet, so do it before you rely on the hotspot's data plan, or on home Wi-Fi.

```bash
.venv/bin/python scripts/download_models.py
```

Create the demo accounts. It is safe to run again. If you change `SEED_PASSWORD` or `FACULTY_SEED_PASSWORD` later, run it again so the accounts pick up the new password.

```bash
.venv/bin/python -m app.seed
```

You should see a line like `Seed users ready (4 created, 0 already present).` The model download printed
`downloaded: face_detection_yunet_2023mar.onnx 232589 bytes` and `downloaded: face_recognition_sface_2021dec.onnx 38696353 bytes`.

If either password (or `JWT_SECRET` or `PHOTO_ENCRYPTION_KEY`) is missing or empty, the seed command stops and
prints `Seeding stopped: Missing required setting(s): ...` naming it. If the two passwords are equal, it stops and
prints `Seeding stopped: FACULTY_SEED_PASSWORD must be different from SEED_PASSWORD. Pick a separate password.`
In both cases it creates no accounts and does not touch the database.

The server refuses to start with a clear message if a required setting is missing or the models are not downloaded.

## 4. Create the demo activity at the real demo room

An **activity** is one field event with a place, a time window, and a list of students. The check-in
location rule is a circle around the venue's coordinates (default radius 100 m). If the coordinates are
wrong, every check-in will be flagged. Use the real room's coordinates.

### Get the coordinates from a phone

1. Stand inside the demo room, near where the student will stand.
2. On the Android phone, turn location on. Open a maps app (for example Google Maps).
3. Wait until the blue dot stops jumping. Touch and hold on the dot, or on the map at your spot.
   The coordinates appear as two decimal numbers, such as `12.9716, 77.5946`. The first is latitude
   (`--lat`), the second is longitude (`--lng`).
4. Write both down with at least 4 decimal places. Check them on a second phone if you can.

Maps apps are not part of FieldPass. Any method that gives latitude and longitude works.

### Create the activity

```bash
cd backend
.venv/bin/python -m app.seed --demo-activity --lat 12.9716 --lng 77.5946
```

Replace the two numbers with your own. It prints a line like
`Demo activity created: act_08d5821e6ee0 at (12.9716, 77.5946), 4 h from now.`, after a `Seed users ready (0 created,
4 already present).` line (it runs the account seed first, which is harmless). This creates "Demo Site Visit": starts at
the moment you run the command, lasts 4 hours from then, 100 m radius, all three students on the roster. Run it again for a fresh one.
Do not run it the evening before: check-in closes 4 hours after you ran it.

You can also create activities in the dashboard (Activities, then "New activity"). There you can set the
radius (10 to 5000 m), the start and end time, and which students are on the roster (at least one is required
by the form). You need this for the extra activities in [demo-script.md](demo-script.md). The form fills in a
start 30 minutes from now and an end 2 hours after that. Check-in opens only 15 minutes before the start, so
change the start if you want the activity open now.

Timing rules: check-in opens 15 minutes before the start and closes at the end;
check-out is accepted until 30 minutes after the end. The server's clock decides, not the phone's.
The server accepts a start time in the past. It does not compare the times with "now" at all. The only limits
on times are: both must be in the years 2000 to 2100, the end must be after the start, and the activity
can last at most 7 days. (An activity whose end has already passed can be created, but nobody can check in to it.)

## 5. Start the backend on all interfaces

```bash
cd backend
./run.sh
```

`run.sh` prints `FieldPass backend: point the phone and dashboard at http://<ip>:8000`.
It is the same as running uvicorn with `--host 0.0.0.0 --port 8000`. To use another port: `./run.sh 9000`.
Leave this terminal open. Watching it is the quickest way to see errors.

Wait for the line `Uvicorn running on http://0.0.0.0:8000 (Press CTRL+C to quit)`. The first start in a new virtual
environment took about 45 seconds on 2026-10-10 (the `run.sh` line appears at once, then nothing for a while), and
the next start took about 1 second. During the wait the port is closed and `curl` fails. This is not an error.

If the server were bound to `127.0.0.1` (localhost) only, the phone could not reach it. `run.sh` already uses `0.0.0.0`.
You can prove it from the laptop, before involving the phone. Both commands must print the same answer:

```bash
curl http://127.0.0.1:8000/api/v1/health
curl http://$(ipconfig getifaddr en0):8000/api/v1/health
```

Each prints `{"status":"ok","server_time":"..."}`. If the first works and the second does not answer, the server is
bound to localhost only or the firewall (step 6) blocks it. (If you started on another port, use that port.)

The interactive API docs (`/docs`, `/redoc`, `/openapi.json`) are off by default. They need no login, so leave
them off for the demo. `ENABLE_DOCS=true` in `backend/.env` turns them on.

## 6. Allow the backend through the macOS firewall

The macOS firewall can silently block other devices from reaching the backend.

Check the state (read-only):

```bash
/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate
```

- If it says "Firewall is disabled. (State = 0)", nothing blocks you. Go on. This is the text printed on macOS
  26.7 when the firewall is off. When it is on, the text starts "Firewall is enabled."
- If it says "enabled": open System Settings, go to Network, then Firewall. Either turn the Firewall switch off
  for the demo, or choose "Options..." and add the Python program that runs the backend with
  "Allow incoming connections". In the same Options window, "Block all incoming connections" must be off,
  because it blocks everything, including Python. The first time the backend starts, macOS may also show a pop-up
  asking to allow incoming connections for Python. Choose Allow.

Two more read-only commands help:

```bash
/usr/libexec/ApplicationFirewall/socketfilterfw --getblockall     # "block all" must be disabled
/usr/libexec/ApplicationFirewall/socketfilterfw --listapps        # programs with a rule, and their setting
```

Which Python to allow: `backend/.venv/bin/python` is only a link. macOS names the real file the link points to.
Print it with `python3 -c "import os; print(os.path.realpath('.venv/bin/python'))"` from `backend/`. On the demo
laptop it printed `/Users/prem/.local/share/uv/python/cpython-3.11.15-macos-aarch64-none/bin/python3.11`.
Add that path if you add the program by hand. `--listapps` showed 7 built-in programs, each marked
"(Allow incoming connections)", and no FieldPass entry.

Checked on macOS 26.7 (2026-10-10): System Settings, Network has a Firewall section. Its text, read from the
system's Network settings file, offers the Firewall switch, an "Options..." button, "Block all incoming
connections", and per-program "Allow incoming connections" or "Block incoming connections". The firewall was
disabled on that laptop, and the settings were not changed, so adding the program through the Options window and
the pop-up were not tried. The repo's own README files do not describe the firewall.

## 7. Check the backend from the phone's browser

On the Android phone, open the browser and go to:

```
http://<laptop-ip>:8000/api/v1/health
```

You should see `{"status":"ok","server_time":"..."}`. If the page does not load, stop and use the
troubleshooting table below. Do not continue until this works. The app will not work either.

Also check the laptop's clock and the phone's clock agree to within a few seconds (compare `server_time`
with the phone's clock). A wrong phone clock causes `location_stale` errors.

## 8. Set `CORS_ORIGINS` for where the dashboard is opened

The dashboard is a web page. The backend only accepts its calls if the page's address is in
`CORS_ORIGINS` in `backend/.env`. An origin is the part of the address before the path: scheme, host, port.

The default in `.env.example` is:

```
CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
```

| Where you open the dashboard | `CORS_ORIGINS` must contain |
|---|---|
| On the backend laptop, at `http://localhost:5173` | `http://localhost:5173` (already there) |
| On the backend laptop, at `http://127.0.0.1:5173` | `http://127.0.0.1:5173` (already there) |
| On the backend laptop, at `http://<laptop-ip>:5173` | `http://<laptop-ip>:5173` |
| On another laptop, at `http://<dashboard-laptop-ip>:5173` | `http://<dashboard-laptop-ip>:5173` |

The origin must match exactly, including `http`, the address and the port. Separate entries with commas and no spaces.
Example for the second laptop case:

```
CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,http://192.168.43.20:5173
```

After editing `.env`, stop the backend (Ctrl+C) and start it again with `./run.sh`. Settings are read at startup.
The simplest setup is to open the dashboard on the same laptop that runs the backend, at `http://localhost:5173`.

If port 5173 is taken and you start the dashboard on another port (step 9 shows how), the new origin is not in the
default list. Add it, for example `http://localhost:5174`, or the login screen cannot reach the backend.

## 9. Start the dashboard in real mode

```bash
cd dashboard
npm install
cp .env.example .env.local
```

Open `dashboard/.env.local` and make sure it says:

```
VITE_USE_FIXTURES=false
```

You can leave `VITE_API_BASE_URL` as is; you will set the address on the login screen. Then start it:

```bash
npm run dev
```

To use another port, put the option after `--`: `npm run dev -- --port 5174`. Then open that port instead, and add
its origin to `CORS_ORIGINS` (step 8). The dashboard's own README does not describe this option.

Open `http://localhost:5173` in the browser. The "Server address" field starts filled with the example address
`http://192.168.43.10:8000`. Replace it with your base URL
(`http://<laptop-ip>:8000`) and press "Test connection". It should show "Connected. Server time ...". The address is
saved in the browser. Sign in as `faculty1` with `FACULTY_SEED_PASSWORD`.

If you edit `.env.local`, restart `npm run dev`.

Fixture mode (sample data, no server) shows a yellow banner "FIXTURE MODE: sample data, not the real
server" on every screen. If you see that banner, fixture mode is on: set `VITE_USE_FIXTURES=false` in
`.env.local` and restart `npm run dev`.

## 10. Build and install the Android app

### Prepare the phone (once)

1. Settings, About phone, tap Build number 7 times.
2. Settings, System, Developer options, turn on USB debugging.
3. Connect the phone to the laptop with a USB cable and accept the "Allow USB debugging" prompt.

### Build and install

```bash
cd android
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleDebug` ends with `BUILD SUCCESSFUL` and writes `app/build/outputs/apk/debug/app-debug.apk`
(about 18 MB). `ANDROID_HOME` only lasts for that terminal window. Instead of exporting it each time, create
`android/local.properties` with the line `sdk.dir=/opt/homebrew/share/android-commandlinetools`. Both ways were
tried and both built the APK. With neither, Gradle stops with "SDK location not found".

`adb devices` must list your phone as `device`. If it says `unauthorized`, accept the prompt on the phone.
If `adb` is not found, use `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`.

You must install the **debug** build. The release build blocks plain `http://` traffic, so it cannot talk to the backend.

### Set the server address in the app

1. Open FieldPass on the phone.
2. On the login screen, edit "Server address" to your base URL, `http://<laptop-ip>:8000`.
3. Tap "Test connection". It must show "Connected. The server is reachable."
4. Sign in as `student1` with `SEED_PASSWORD`.

The first time, the app asks for camera permission when it first needs the camera and for location permission
when you open a check-in. Allow both. Accept them during your rehearsal, not in front of the audience.

You can set a default address at build time instead:
`./gradlew assembleDebug -PfieldpassApiBaseUrl=http://192.168.43.10:8000`. You do not have to; the app
saves the address you type.

## 11. Confirm fixture mode is off on both clients

- Dashboard: no yellow "FIXTURE MODE" banner on any screen, including the login screen.
- Phone: the "Fixture mode (sample data, no server)" switch on the login screen is off, and no red
  "FIXTURE MODE: sample data" banner is visible on any screen.

If either shows sample data, the demo would be fake. Fix it before anyone watches.

Now do the pre-demo checks in [checklist.md](checklist.md) and enrol the volunteers (see [demo-script.md](demo-script.md), "Before you start").

## Troubleshooting

Find the symptom, check the likely cause, apply the fix. Error codes in `code` style are from `contracts/API.md`.

| Symptom | Likely cause | Fix |
|---|---|---|
| Phone browser cannot open `http://<laptop-ip>:8000/api/v1/health` (page never loads) | **Wrong address.** The hotspot gave the laptop a new address | Run `ipconfig getifaddr en0` again. Use the new address in the phone browser, the app and the dashboard |
| Same as above, but the health URL works in the laptop's own browser | **macOS firewall** blocks incoming connections | Step 6: allow Python, or turn the firewall off for the demo. Restart the backend |
| Same as above, and the laptop browser works only with `localhost`, not with the laptop's hotspot address | **Server bound to localhost.** It was started with plain uvicorn, which listens on `127.0.0.1` only | Stop it. Start with `./run.sh`, which uses `--host 0.0.0.0` |
| Same as above, and the address and firewall are right | **Hotspot client isolation.** Some hotspots stop connected devices from talking to each other | Try a different phone as the hotspot, or another hotspot band or setting if your phone has one. Test with the phone browser first. The setting's name differs per phone and is not described in the repo |
| Health works but the app says it cannot connect, or "Test connection" fails at once | **Cleartext HTTP blocked.** A release build (`assembleRelease`) was installed. Release builds forbid plain `http://` | Install the debug build: `./gradlew assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Dashboard login screen: "Test connection" fails, or the browser console says a request was blocked by CORS, though the phone browser reaches the health URL | The dashboard's address is not in `CORS_ORIGINS`. The login screen shows "Could not reach the server. Check the server address and your network." This is the same text as for a wrong address. The browser console (developer tools) tells them apart: for CORS it says `has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header is present on the requested resource`. Checked with the dashboard on `http://localhost:5174` while `CORS_ORIGINS` listed only port 5173 | Step 8. Add the exact origin of the page (for example `http://localhost:5173`, or `http://localhost:5174` if you started the dashboard on another port), save `.env`, restart the backend. After that fix, "Test connection" read "Connected. Server time ..." and `faculty1` signed in. Also check "Server address" has the right IP and port |
| `enrollment_not_approved` when a student taps Check in | The student has no approved enrollment photo. `student3` was never enrolled, or the student replaced their photo (approval does not carry over) | Student: My photo, take a photo, "Send for approval". Faculty: dashboard, Enrollments, tick "I have confirmed this person's identity", then "Approve photo" |
| Every check-in is `needs_review` with a location reason, even indoors | The location rule (see worked example below). Indoors, the phone's reported accuracy is large | See "The location rule" below. Fix the venue coordinates, raise the radius on a new activity, or improve the GPS fix |
| `location_stale` | The location reading is older than 60 s (`LOCATION_MAX_AGE_SECONDS`). Usually the phone's clock is wrong: the app derives the reading time from the phone's clock minus the age of the fix | On the phone, turn on automatic date and time. Compare the phone's time with the `server_time` from the health URL. Then retry the same attempt |
| `unusable_capture` | The image was unreadable or no face was found. Faces smaller than 48 px are ignored. Common causes: dim light, a face turned sideways or rotated, a face too far from the camera | Face the light. Hold the phone upright. Fill the frame with the face, from arm's length or closer, and take the selfie again. The attempt stays open, so you can retry until it expires |
| `multiple_faces` | More than one face is in the picture | Make sure only the student is in frame. Retry the same attempt |
| `attempt_expired` | More than 90 s passed since the attempt was created. The app creates the attempt as soon as the check-in screen opens (after location permission is granted), before the location reading and the selfie. Nothing was recorded. The app also runs its own countdown and shows "This attempt timed out" by itself when it reaches zero | Tap "Start again" in the app. The wait is deliberate in scenario 3 of the checklist |
| Login fails with 429 `too_many_attempts` | The login rate limit was hit, usually by repeated wrong passwords. A common mistake is signing in as `faculty1` with `SEED_PASSWORD`. The limit is 10 tries per minute for one username from one address, and 30 tries per minute for one address in total, counted over a sliding 60 second window | Wait about a minute, then use the right password. The limit is held in the backend's memory, so restarting the backend (Ctrl+C, then `./run.sh`) clears it at once |
| Login fails with `invalid_credentials` | Wrong password, or the two seed passwords were mixed up | Faculty uses `FACULTY_SEED_PASSWORD`; students use `SEED_PASSWORD`. If you changed a password in `.env`, run `.venv/bin/python -m app.seed` again and restart the backend |
| A student can sign in on the dashboard but sees a "this dashboard is for faculty" message | Working as designed (checklist scenario 7) | Nothing to fix. Student accounts use the app |
| Dashboard or app shows a yellow or red "FIXTURE MODE" banner | Fixture mode is on | Section 11 |
| Backend will not start and prints "Missing required setting(s)" | A required value is empty in `backend/.env` | Fill in the named settings. Required: `JWT_SECRET`, `PHOTO_ENCRYPTION_KEY`, `SEED_PASSWORD`, `FACULTY_SEED_PASSWORD` |
| Backend will not start and mentions the models | Models not downloaded | `.venv/bin/python scripts/download_models.py` |

### The location rule

The backend measures three numbers, all in metres:

- `d`: distance from the phone's reported position to the venue's coordinates.
- `a`: the accuracy the phone reports. The true position is somewhere within `a` metres of the report.
- `r`: the activity's radius. The default is 100.

Rules, from `contracts/API.md`:

- `d + a <= r` gives `inside`.
- `d - a > r` gives `outside` (reason `location_outside`).
- Anything else gives `uncertain` (reason `location_uncertain`).

An event is `recorded` only if the location is `inside` **and** the face is `match`. Otherwise it is `needs_review`.

Worked example, indoors. The venue is stored 30 m from where the student stands, so `d = 30`. The phone
reports `a = 80` because it is inside a building. The radius is `r = 100`.

- Inside test: `30 + 80 = 110`, which is more than 100. Not inside.
- Outside test: `30 - 80 = -50`, which is not more than 100. Not outside.
- Result: `uncertain`, so the event is `needs_review` with `location_uncertain`.

The student is in the right room, but the check cannot be sure, so a person looks at it. That is the
designed behaviour. To get `recorded` in the room you need `d + a <= r`.

What to change, in this order:

1. **Fix the venue coordinates.** Stand in the room and read them again (step 4). If `d` falls from 30 to 5,
   then `5 + 80 = 85 <= 100`, which is `inside`.
2. **Improve the fix.** Stand near a window or doorway, wait for the phone's location to settle, and use the
   app's "Try again for a better fix" if it warns about precision. A smaller `a` helps directly.
3. **Raise the radius on a new activity.** In the dashboard use "New activity" and set radius, for example, 200.
   With `d = 30` and `a = 80`: `110 <= 200`, so `inside`. A larger radius is a weaker check, so say so when
   you present it. The API contract has no way to edit an activity's radius, so create a new activity.
4. Do not change the rule or the face settings to make the demo look better.

## After the demo: delete the biometric data

Enrollment photos and face data are biometric data of real people. Delete them when the demo is over.

1. Stop the backend (Ctrl+C in its terminal) and the dashboard.
2. Delete the database and the encrypted photos. With the default settings both live in `backend/data/`.
   Run this from the repository root (the folder that holds `backend/` and `dashboard/`), not from inside `backend/`:

```bash
rm -r backend/data
ls backend/data     # must print: No such file or directory
```

The exact paths, from `backend/app/config.py`:

- Database: `backend/data/fieldpass.db` (setting `DATABASE_PATH`, default `data/fieldpass.db`, relative to `backend/`).
  SQLite also keeps `fieldpass.db-wal` and `fieldpass.db-shm` next to it while the server has run. They are in the
  same folder, so `rm -r backend/data` removes them too.
- Encrypted enrollment photos: `backend/data/photos/` (setting `PHOTO_DIR`, default `data/photos`). One `.enc` file
  per enrollment. The folder is created by the first enrollment upload, so it does not exist if nobody enrolled.

Checked on 2026-10-10 in a fresh clone: after one enrollment upload, `backend/data/` held `fieldpass.db`,
`fieldpass.db-shm`, `fieldpass.db-wal` and `photos/` with one `.enc` file. After `rm -r backend/data` from the
repository root, `backend/data` and `backend/data/photos` no longer existed.

If you changed `DATABASE_PATH` or `PHOTO_DIR` in `backend/.env`, delete those locations too. The
database also holds each student's face embedding (a list of numbers that describes a face), so
deleting only the photos is not enough.

3. Delete any CSV files downloaded from the dashboard. They list real names and attendance statuses.
4. Delete any test faces in `backend/tests/samples/` (they are git-ignored, but they are still on disk).
5. On the phone, sign out and uninstall FieldPass, either from the phone's app settings or over USB:

```bash
adb uninstall com.fieldpass.student
```

   Selfies are kept in memory only and are not saved by the app.
6. Consider regenerating `JWT_SECRET` and `PHOTO_ENCRYPTION_KEY` (`.venv/bin/python -m app.gen_secrets`) and
   changing both seed passwords before the next run, especially if they were ever shown on a screen.

Only people who have agreed to it may have their faces enrolled. Ask each volunteer, tell them what the
system stores and for how long, and let them decline. Do not enrol other students "to make the demo bigger".

