# Historical Decisions & Migrations

## Migration Index
No migration tool. Schema is created by `create_all` at startup (SQLite and Postgres). Add rows here if Alembic or similar is ever introduced.

| date | change |
|---|---|
| 2026-10-10 | Optional Postgres (Supabase) backend added, commit bc893ee. Schema `fieldpass`, RLS on, anon/authenticated revoked. |
| 2026-10-11 | Anti-cheat A: new columns `attempts.nonce`, `attempts.challenge`, `attendance_events.location_fingerprint` (new table `student_devices`). Existing DBs need reset or `ALTER TABLE` (statements in `contracts/API.md`). |

## Decisions
| date | decision | why | rejected |
|---|---|---|---|
| 2026-10-10 | Dashboard e2e runs on **Chromium only** | Prototype demo; user said "no need of firefox and safari tests" (twice). Gap recorded in known-limitations 16 | Firefox/WebKit runs (scripts `e2e:firefox`, `e2e:webkit` exist in package.json but are not run) |
| 2026-10-10 | Faculty has a separate seed password from students | Faculty can approve photos and decide attendance | One shared SEED_PASSWORD |
| 2026-10-10 | Software never decides attendance; faculty make the final call | Selfie is not liveness, location is spoofable, times are observations | Auto-confirm, numeric confidence in API |
| 2026-10-10 | Contract frozen as v1; new behaviour goes in API.md Addendum | Three clients depend on one contract | Editing frozen sections in place |
| 2026-10-10 | Second brain scaffolded (`.agents/`, close script, lefthook) | Low-context agent memory | — |
| 2026-10-11 | Anti-cheat A adds columns (`attempts.nonce`, `attempts.challenge`, `attendance_events.location_fingerprint`); existing databases need a reset or `ALTER TABLE ... ADD COLUMN`. **Reverses** the earlier additive-only/no-DB-reset decision (decision 7 in `.agents/projects/duty-zones-plan.md`) | Nonce and head-turn challenge belong to the attempt and the repeated-location hash to the event; a side table for each would add joins for no gain, and `create_all` cannot add columns. Statements are in `contracts/API.md` | Side tables (additive-only); Alembic |
| 2026-10-11 | Anti-cheat piece A: phone lock (Keystore signature), fake-GPS/root/emulator flags, blink + server-chosen head turn | Stops phone sharing, replayed uploads and printed photos in an offline APK demo | Play Integrity (needs Play Store + internet), cloud liveness, numeric risk score (piece C) |
| 2026-10-11 | Cheat signals are **record + flag**: they add a review reason, never block or auto-decline | Faculty make the final call (existing invariant) | Hard blocks on mock location or root |
| 2026-10-11 | Head-turn limits: front 0.12, turn 0.25, mirrored false, phone yaw (`TURN_MIN_YAW_DEG`) as coded, sign `YAW_SIGN_FOR_LEFT = +1f` — **not calibrated on a phone yet** | Values are defaults/guesses; no real phone, real face or real Keystore has met the server (Task 16 Steps 1-3 not done) | — |
| 2026-10-11 | Task 16 Steps 1-3 (fresh demo backend, 6-check-in calibration, hotspot walkthrough) deferred; only docs written | Need a person with phones and a decision about the production Supabase DB (`backend/.env` has a real `DATABASE_URL`) | Dropping/recreating the Supabase schema without asking |
| 2026-10-11 | Check-in takes ONE photo; head-turn step removed from the Android flow, and the server skips the head-turn check when `LIVENESS_REQUIRED=false` (default stays `true`) | Owner's call for the demo: the head-turn thresholds were never calibrated on a phone, and the ML Kit face camera crashed the release build (`NullPointerException` in `LivenessCamera`). One photo is judged on face + location only | Keep the turn and calibrate it; leave the turn in and accept every check-in as `needs_review`. Cost: a printed photo or a photo of a photo can now pass the face check, which the contract already says it can |
| 2026-10-11 | Android `versionCode`/`versionName` derived from git in `android/app/build.gradle.kts`: `n = git rev-list --count HEAD -- android/`, `versionCode = n`, `versionName = "0.1.n"` (falls back to 1 without git) | Owner wants the app version to bump on every app edit, and two people commit to `main`; a hand-edited version line would be forgotten or merge-conflict. Every clone at the same commit builds the same version | Hand bump per APK (old README rule); a pre-push hook that edits the file (conflicts, and lefthook is not installed on every machine). Cost: uncommitted edits do not bump it, so commit before building an APK to hand out |

Commit history (all 2026-10-10): 7c63204 prototype, d087e5a timing settings + e2e + Android fixes, 3ca27f6 Campus Minimal student app, 65fa39e Campus Minimal dashboard + faculty login, bc893ee optional Postgres, 93a491a remove generated screenshots.
