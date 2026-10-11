# FieldPass faculty dashboard

React + TypeScript (strict) + Vite web app for faculty: approve enrollment photos, create activities,
watch the attendance register, open checkpoint windows, record in-person observations, confirm or decline
attendance, and download the register as CSV. The API contract is `../contracts/API.md`.

## Setup

Requires Node 22 and npm.

```bash
cd dashboard
npm install
cp .env.example .env.local     # then edit
npm run dev                    # http://localhost:5173, also reachable on the LAN (--host)
```

To use another port, run `npm run dev -- --port 5174` and add that origin (for example
`http://localhost:5174`) to `CORS_ORIGINS` in `backend/.env`, then restart the backend.

## Environment variables

Activity creation uses React Leaflet for venue selection. Search for a venue/address, choose a result,
drag the circle or its centre pin over the attendance area, choose its radius, and confirm it. Both move together. The map opens at building level
and defaults to a 25 m radius, with a continuous size slider. Moving or resizing the circle requires confirmation again;
its centre coordinates and radius are sent to the existing API automatically. Circles don't select floors.
Device location is an optional shortcut when you are physically at the venue.
The map is visible immediately; click or double-tap it to place a location without searching.
Use **+ Add location** to add another circle, then place it on the map. Select a location's tab to
adjust its own size slider or remove it. Each circle and centre pin move together, preserving zoom.
Confirm after every added location is placed. All circles are saved, and attendance may count inside any one.

Search defaults to the Photon public demo API (explicit searches only, with a small in-memory cache).
It permits reasonable request volumes but offers no availability guarantee; use your own Photon instance
for larger deployments: https://github.com/komoot/photon. Search queries are sent to that provider.
Map tiles use OpenStreetMap with visible attribution and normal browser caching, per
https://operations.osmfoundation.org/policies/tiles/. Search and tiles need an internet connection.
Override `VITE_VENUE_SEARCH_URL`, `VITE_VENUE_TILE_URL`, and `VITE_VENUE_TILE_ATTRIBUTION` for other providers.

| Variable | Meaning |
|---|---|
| `VITE_API_BASE_URL` | Backend origin with no trailing slash, e.g. `http://localhost:8000` locally or the public HTTPS API origin for deployment. The app adds `/api/v1`. |
| `VITE_USE_FIXTURES` | `true` switches to in-memory sample data. Anything else (default) uses the real API. |

Vite reads these when the dev server starts, so restart `npm run dev` after editing `.env.local`.

## Real mode vs fixture mode

- **Real mode (default)** talks to the backend over HTTP using the typed client in `src/api/realClient.ts`.
- **Fixture mode** (`VITE_USE_FIXTURES=true`) uses `src/api/fixtureClient.ts`: two activities, pending enrollments,
  and a register covering recorded, needs review, confirmed, not confirmed, a missing checkout, an outdated
  decision, and in-person observations. Changes you make last until you reload the page. A yellow banner
  reading "FIXTURE MODE: sample data · changes reset on reload" is shown on every screen, including login.
  Sign in as `faculty1` with any password. `student1` shows the "this dashboard is for faculty" message.

```bash
VITE_USE_FIXTURES=true npm run dev
```

## Connecting to the backend

Set `VITE_API_BASE_URL` in `.env.local` for local development. For a phone or another laptop on the same
network, use the backend's LAN address and restart Vite after changing the value. The login page shows only
username and password; it does not ask faculty to configure a server.

On Vercel, import the repository and set **Root Directory** to `dashboard`, Framework Preset to Vite, Build Command `npm run build`, Output Directory `dist` (`vercel.json` already rewrites every path to `index.html`). Configure `VITE_API_BASE_URL` as the public HTTPS origin of the deployed backend and
`VITE_USE_FIXTURES=false`. Vite embeds these values when it builds the frontend, so redeploy after changing
them. If the API is available through a same-origin `/api/v1` rewrite, `VITE_API_BASE_URL` may be omitted.
The existing backend handles authentication; its `DATABASE_URL` can point to Supabase Postgres.

The browser may still use a saved local API address during development for the second-backend timing test.
Production builds ignore that saved address.

The backend must allow cross-origin requests from the dashboard's origin (for example `http://localhost:5173`),
including the `Authorization` header and the `Content-Disposition` response header for CSV downloads
(`Access-Control-Expose-Headers`). If the file name is not exposed, the dashboard falls back to `register-<id>.csv`.

## Sessions and tokens

The access token is kept in memory and mirrored to sessionStorage (cleared when the tab closes). It is never
written to localStorage or logged. A `not_authenticated` response from any call returns you to the login screen.

## Commands

```bash
npm run dev          # dev server
npm run build        # tsc --noEmit, then vite build into dist/
npm run typecheck    # tsc --noEmit
npm run lint         # eslint
npm test             # vitest (unit and component tests)
npm run e2e          # end-to-end run against the REAL backend (see below)
npm run screenshots  # Playwright screenshots into screenshots/ (needs the app running in fixture mode)
```

Screenshots: start `VITE_USE_FIXTURES=true npx vite --port 5199`, then `npm run screenshots`
(or `node scripts/screenshots.mjs http://localhost:PORT`). Run `npx playwright install chromium` once first.
The script fails if any page overflows horizontally at 320, 768, 1024 or 1440 px.

## What the register shows

- One row per rostered student: Arrival, Last recorded checkpoint, Departure (local time with timezone), a status
  badge in words, review reasons in plain words, a "Missing checkout: incomplete" marker, the faculty decision with
  reason, who and when, and an "Outdated decision, new evidence arrived" marker when the decision is no longer current.
- In-person faculty observations are listed separately, in a purple box titled "In-person observations by faculty".
- A "Students" page lists every student and whether a phone is linked, with a two-step "Reset phone"
  button for a lost, replaced or reinstalled phone.
- New review reasons in plain words: "Head-turn check failed", "Fake GPS was on", "Same exact spot as
  an earlier check-in", "Rooted phone", "Running on an emulator".
- It refreshes every 5 seconds while the tab is visible, never overlaps requests, pauses when the tab is hidden,
  and keeps the last good data with a warning if a refresh fails.
- A "What this system does and does not prove" page is linked from the register and the header. No confidence
  percentages and no "live" wording are used anywhere.

## Layout

```
src/api/         types (mirror API.md), real client, fixture client, ApiError with contract code
src/lib/         time, labels, validation, download helpers (pure, unit tested)
src/hooks/       usePolling, useLoad, useAsyncAction, session context
src/components/  presentational and form components
src/screens/     Login, Enrollments, Activities, ActivityCreate, Register, About
tests/           Vitest + React Testing Library
```

## End-to-end tests against the real backend

`e2e/fieldpass.spec.ts` drives the dashboard in a browser while a script plays the student's phone over the
student API. It covers login (and the student refusal), enrollment approval with the photo rendered, activity creation,
check-in, checkpoint, check-out, faculty decisions and corrections, a wrong face, a wrong location, a replayed
submit, in-person observations, the CSV download and a 403 for a student token. It uses the real OpenCV face engine.

One-time setup:

```bash
cd backend && .venv/bin/python scripts/download_models.py      # if backend/models/*.onnx are missing
cd ../dashboard && npx playwright install chromium
```

Put three images in `backend/tests/samples/` (git-ignored, never commit faces): `person_a_1.jpg` and `person_a_2.jpg`
(two different photos of the same person) and `person_b.jpg` (a different person). The same files feed
`backend/tests/test_real_engine.py`.

Run:

```bash
npm run e2e
```

`npm run e2e` starts the backend on 127.0.0.1:8000 (override with `E2E_BACKEND_PORT`) using a throwaway database and photo
folder in the OS temp directory, seeds the demo accounts (password read from `backend/.env`, never printed), starts Vite
on 127.0.0.1:5173 in real mode (override with `E2E_DASHBOARD_PORT`), runs the spec, then stops both and deletes the
temporary data. `CORS_ORIGINS` is set for the run through the environment, so `backend/.env` is not touched. Ports 8000
and 5173 must be free. The last test also writes `screenshots/real-register-1440.png` and `real-register-320.png`.

### Timing spec (second backend)

`e2e/timing.spec.ts` runs after the main spec and starts its OWN real backend on 127.0.0.1:8001 (override with
`E2E_SHORT_BACKEND_PORT`; keep it free) with `ATTEMPT_TTL_SECONDS=4` and `CHECKOUT_GRACE_MINUTES=0` on throwaway data.
The main spec's backend (default settings) stays untouched. Nothing is faked: the tests wait in real time, polling the
server's own clock, and the development dashboard in that spec is pointed at port 8001 through a test-only
localStorage override. It proves:

- Attempt expiry: a selfie and location submitted after the attempt lifetime get `410 attempt_expired` in the error
  envelope, the register keeps showing no arrival, and a fresh attempt is then shown as Recorded.
- Missing check-out: a check-in on an activity that then ends shows "Missing checkout: incomplete" in the open register by
  its normal 5 s polling (no reload), with no duration or hours text in the row, `complete` false, and the student's
  `/attendance/me` showing `missing_checkout: true` and no `next_actions`.
- Stale enrollment: approving a card whose photo the student has since replaced gets `404`, shows "This student uploaded a
  new photo. The list has been refreshed.", reloads the list with the new pending photo, and the student stays `pending`.

The whole `npm run e2e` takes about a minute and a half because of these real waits. To run only this spec:
`npx playwright test -c e2e/playwright.config.ts timing`.
