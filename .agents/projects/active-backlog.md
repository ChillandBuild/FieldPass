# Active Roadmap & Technical Debt

## Backlog
| task | priority | status |
|---|---|---|
| Run the Android app on a physical phone over the hotspot (camera, GPS, Keystore, cleartext) | high (before any demo) | open |
| Document Postgres/Supabase in backend/README.md and update known-limitations #6 | medium | open |
| Add a CI pipeline (none exists; no .github) | medium | open |
| Playwright coverage for dashboard stale-enrollment message (limitation 15) | low | open |
| Duty zones P1: zones, seats, coordinator, zone-aware location (plan: duty-zones-plan.md) | high | open |
| Duty zones P2: random pings, explanations, triage, bulk confirm | high | open |
| Duty zones P3: contract Addendum + regenerate fixtures/openapi | high | open |
| Duty zones P4: dashboard zones form, triage summary, Confirm all | medium | open |
| Duty zones P5: Android seats, coordinator, ping notifications, "Where are you?" | medium | open |
| Duty zones P6: docs, known-limitations, decisions log | medium | open |
| Anti-cheat A (plan: anti-cheat-a-plan.md) | high | implemented 2026-10-11 on feat/anti-cheat-a; calibration + demo walkthrough open |
| Anti-cheat A calibration on 2 real phones/people: `YAW_SIGN_FOR_LEFT` (1f is a guess), `LIVENESS_MIRRORED`, front 0.12 / turn 0.25, `TURN_MIN_YAW_DEG`; then the 10-line hotspot walkthrough (Task 16 Steps 2-3) | high (before any demo) | open, NOT DONE |
| Deploy anti-cheat A to existing databases: the three `ALTER TABLE` statements in `contracts/API.md` (or schema reset) before deploying; `create_all` adds no columns. The production Supabase DB also holds leftover test data from two accidental test runs (student1 bound to a lost test key, a test enrolment, attempts): user decides how to clean up | high | open, needs user decision |
| Run the dashboard e2e specs after test 1 and the two real-engine tests (never run: sample photos `backend/tests/samples/*.jpg` absent) | medium | open |
| Anti-cheat B: open events with rotating screen QR and group label | high | open (needs its own design) |
| Anti-cheat C: rule-based risk score with a slot for Jev RLCD | medium | open (after duty zones) |
| End-to-end run of missing-checkout and attempt-expiry on a real phone (limitation 14; e2e timing specs now exist, verify doc is current) | low | open |

## Known Tech Debt
- Doc drift: known-limitations #16 vs the `e2e:firefox`/`e2e:webkit` scripts (intentionally unused, see decisions/log.md); API.md "Seeded demo accounts" vs Addendum for faculty1 password; runbook line 81 hard-codes a machine-specific Python path.
- Unvalidated: face threshold 0.363 and margin 0.1; thin same-person testing; no fairness testing (limitations 10–13).
- In-memory login rate limiter (resets on restart, single process) (limitation 8).
- No password reset or user management (7); faculty features are dashboard-only (19); activity radius not editable and no indoor handling (20).
- No security review or load test (21). Biometric data retention is a manual delete (9).
- Anti-cheat A deferred minors (ledger: `.superpowers/sdd/anti-cheat-a-plan/progress.md`):
  - Backend: no range check on `LIVENESS_*` settings (FRONT_MAX < TURN_MIN, finite); `captured_at.timestamp()` assumes tz-aware UTC; accuracy NaN/inf not validated at the model; lost-race `IntegrityError` path in `devices.py` untested and `bind_or_check` commits mid-request; N+1 `session.get` in `list_students`; "Reset phone" has no audit trail; `challenge` has no CHECK constraint and anything not `left` is treated as right.
  - Location repeat flag: generic fingerprint false positives, cached GPS fix could false-flag `location_repeated`, repeat check not atomic, test nudge drifts after ~100 submits.
  - Tests: pre-existing failing `test_activities.py::test_decision_becomes_non_current_when_later_event_arrives`; serial suite slow (SQLite busy_timeout 15 s, 40-attempt loop); isolation-guard loop in the e2e backend harness is tautological and env keys are case-insensitive on Windows; dashboard students-screen test is timing-sensitive on cold start (consider `{timeout:3000}`).
  - Android: `Instant.parse(capturedAt)` can throw inside `call{}`; `onFailed`/`onError` paths in the guide do not check disposed; 20 s guide timer is wall-clock; `unbindAll` is process-wide; fallback shows the same photo twice with identical TalkBack text; `assembleRelease` not re-run after the last Task 15 fix.
  - Docs/openapi: shared per-status error text lists codes a route cannot raise (login 403, 422); one-photo behaviour doc not run against the backend.
- Source of truth for limitations: `integration/known-limitations.md`.
