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
| End-to-end run of missing-checkout and attempt-expiry on a real phone (limitation 14; e2e timing specs now exist, verify doc is current) | low | open |

## Known Tech Debt
- Doc drift: known-limitations #16 vs the `e2e:firefox`/`e2e:webkit` scripts (intentionally unused, see decisions/log.md); API.md "Seeded demo accounts" vs Addendum for faculty1 password; runbook line 81 hard-codes a machine-specific Python path.
- Unvalidated: face threshold 0.363 and margin 0.1; thin same-person testing; no fairness testing (limitations 10–13).
- In-memory login rate limiter (resets on restart, single process) (limitation 8).
- No password reset or user management (7); faculty features are dashboard-only (19); activity radius not editable and no indoor handling (20).
- No security review or load test (21). Biometric data retention is a manual delete (9).
- Source of truth for limitations: `integration/known-limitations.md`.
