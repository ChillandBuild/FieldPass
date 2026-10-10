# Active Roadmap & Technical Debt

## Backlog
| task | priority | status |
|---|---|---|
| Run the Android app on a physical phone over the hotspot (camera, GPS, Keystore, cleartext) | high (before any demo) | open |
| Document Postgres/Supabase in backend/README.md and update known-limitations #6 | medium | open |
| Add a CI pipeline (none exists; no .github) | medium | open |
| Playwright coverage for dashboard stale-enrollment message (limitation 15) | low | open |
| End-to-end run of missing-checkout and attempt-expiry on a real phone (limitation 14; e2e timing specs now exist, verify doc is current) | low | open |

## Known Tech Debt
- Doc drift: known-limitations #16 vs the `e2e:firefox`/`e2e:webkit` scripts (intentionally unused, see decisions/log.md); API.md "Seeded demo accounts" vs Addendum for faculty1 password; runbook line 81 hard-codes a machine-specific Python path.
- Unvalidated: face threshold 0.363 and margin 0.1; thin same-person testing; no fairness testing (limitations 10–13).
- In-memory login rate limiter (resets on restart, single process) (limitation 8).
- No password reset or user management (7); faculty features are dashboard-only (19); activity radius not editable and no indoor handling (20).
- No security review or load test (21). Biometric data retention is a manual delete (9).
- Source of truth for limitations: `integration/known-limitations.md`.
