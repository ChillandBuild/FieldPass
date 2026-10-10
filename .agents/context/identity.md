# Identity, Dev Persona & Code Style

## Who is working on this
Solo dev (Git user "K Prem") building a demo prototype of FieldPass: field-attendance check-in with selfie + location, faculty review dashboard. Leans on the agent for implementation across all three parts (backend, dashboard, Android). Not for real attendance decisions — see [[stack-and-rules]] invariants.

## Response Conventions
- ELI5, show-don't-describe, with real names from this project (student1 Asha Verma, faculty1 Dr. Meera Rao). Global rules in ~/.claude/CLAUDE.md apply.
- Ask before building; present a plan first for real code changes.
- Commit straight to main (no feature branches). Commit types: feat, fix, refactor, docs, test, chore, perf, ci.
- Dashboard browser coverage is **Chromium only** — do not propose Firefox/Safari runs. See decisions/log.md 2026-10-10.

## Code Style Rules
- API contract is the source of truth: `contracts/API.md` (v1 frozen; later behaviour in its Addendum). Change the contract first, then backend/dashboard/android.
- Backend: pure rules live in `rules.py` (no DB/IO); routers stay thin; errors go through the `ApiError` envelope (`errors.py`). Tests inject the clock (`clock.py`) and the fake face engine — never real models.
- Never return or log a numeric face score; never add a liveness field.
- Wording toward faculty: automated results are evidence, never an accusation.
- Dashboard: React 19 + TypeScript strict, ESLint clean, vitest for units, Playwright for e2e.
- Android: Kotlin + Compose M3, repo layer has Real/Fake/Switchable implementations.
