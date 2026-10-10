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

Commit history (all 2026-10-10): 7c63204 prototype, d087e5a timing settings + e2e + Android fixes, 3ca27f6 Campus Minimal student app, 65fa39e Campus Minimal dashboard + faculty login, bc893ee optional Postgres, 93a491a remove generated screenshots.
