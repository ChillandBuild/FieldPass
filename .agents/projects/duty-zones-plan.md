# Plan: Duty Zones, Random Pings and Review Triage

## Context

**Problem.** A field event runs 09:00–11:00 on the Ground. A few students have real event work somewhere
else, for example printing the fixtures list in the Sports Office. Today FieldPass knows only one venue.
Anyone found elsewhere gets `location_outside` → `needs_review` with no context. With 60 students,
faculty can't chase every flag. Checkpoints are also opened by hand, so students know when to be in place.

**Agreed idea (design session, 2026-10-11; status: approved, not started):**
1. The main venue counts for everyone.
2. Other places ("duty zones") count only for students who hold a **seat** there. Seats are capped,
   for example "Sports Office: 2 seats, 09:30–10:30, fixtures desk".
3. A coordinator approves seat claims and confirms the work was done. Faculty set up one line per zone
   and never one line per student.
4. The server schedules **random pings** during the event.
5. A student found away with no seat answers "Where are you?" with one tap.
6. The dashboard shows a **triage summary**: clean students get one "Confirm all"; flagged students get a
   one-line reason each.
7. Faculty still make every final decision.

**Outcome.** The faculty workload stays the same whether the class has 10 students or 60. Honest students
on duty are not flagged. A student who goes to a zone without a seat is flagged automatically.

### The scenario every section below refers to

```
Event: Inter-college Football, 09:00–11:00, Ground (main, 100 m)
Zones: Sports Office  2 seats  09:30–10:30  "fixtures desk"
       Store Room     1 seat   10:00–10:20  "collect cones"
Coordinator: Priya (a student on the roster)
Random pings: 3, each open 10 min
Students: Ravi, Priya, Arjun, Meena + 6 others
```

---

## Decisions this plan makes (each one is reversible)

| Decision | Why | Rejected |
|---|---|---|
| The coordinator is a **flag on the roster entry**, not a new role | No new login type, JWT, or seed change. Faculty pick 1–2 coordinators per event. Today only student and faculty exist (schemas.py:9) | A new global "coordinator" role (touches auth everywhere) |
| Random pings are **generated as future CheckpointWindow rows when the activity is created** | The backend has no scheduler, and every time check goes through the injectable `clock.now()`. Pre-generated rows are testable with the existing Clock fixture | A background scheduler (breaks the test clock, dies when the process restarts) |
| The phone **polls** while an event is running, using a foreground service (a visible "FieldPass: event in progress" notification) | It's a hotspot demo with no Firebase project, and push needs internet and Google setup | FCM push |
| Time away is measured as **pings found away**, not minutes | Project invariant: "times are points of observation, the system never calculates hours" (README, known-limitations #3). A "40 min away" rule would claim something we can't prove | Minutes-away limit |
| The triage summary is **rule-based and deterministic** in phase 1 | Works offline on the hotspot, gives the same answer every time, is testable, and sends no student location to a third party | An LLM call per review (could be phase 2, see Open questions) |
| The contract change goes into the **API.md Addendum** | The contract is frozen as v1 (decisions/log.md) | Editing frozen sections |
| **Reset the demo DB** instead of adding a migration tool | The project has no migrations; `create_all` adds new tables but not new columns (db.py:128). Prototype data only. Record in decisions/log.md | Adding Alembic now |

---

## Phase 1 — Backend: zones, seats, coordinator, zone-aware location (TDD)

**New tables** in [backend/app/models.py](backend/app/models.py). Use `new_id(prefix)`, `UTCDateTime`, and
partial indexes with both `sqlite_where` and `postgresql_where` as at models.py:88-97.

- `Zone`: id, activity_id, name, lat, lng, radius_m, seats, opens_at, closes_at, duty_label.
- `SeatClaim`: id, zone_id, student_id, status (`pending` | `approved` | `declined` | `released`),
  decided_by_id, decided_at, work_confirmed_by_id, work_confirmed_at. Unique (zone_id, student_id).
- `RosterEntry.is_coordinator` (bool, default false). This is a new column, so the demo DB needs a reset.

**Endpoints** (POST and GET only, because CORS allows nothing else, per subsystem-notes):

| Endpoint | Who | What |
|---|---|---|
| `POST /activities` (extended) | faculty | Optional `zones[]` (max 10) and `coordinator_ids[]` (must be on the roster) |
| `GET /activities/{id}/zones` | rostered | Zones with seats taken/free, plus my claim |
| `POST /zones/{id}/claims` | student | Claim a seat. Fails `409 zone_full` if the approved + pending count has reached `seats` |
| `POST /claims/{id}/release` | the claimant | Give the seat back |
| `POST /claims/{id}/decision` | coordinator or faculty | `{approve: bool}` |
| `POST /claims/{id}/work-confirmed` | coordinator or faculty | "Fixtures printed ✅" |

**Seat race:** two students tapping the last seat at the same moment. Count and insert in one transaction,
with a row lock on the Zone (`with_for_update`, no-op on SQLite) and a re-count. Test it the same way
`test_enrollment_races.py` does.

**Location verdict is now zone-aware.** Change [backend/app/routers/attempts.py:168-169](backend/app/routers/attempts.py#L168-L169):

1. Compute the verdict against the main venue with the existing `geo.location_verdict`.
2. If it isn't `inside`, test each zone that is open at `attempt.created_at`.
3. Store the result in the new `AttendanceEvent.zone_id`. Add a pure helper `rules.match_zone(...)` so this
   can be unit-tested.

New `review_reasons` tokens are appended in `_build_event` (attempts.py:80-94):

| Situation | location_verdict | reason | status |
|---|---|---|---|
| Ravi in the Sports Office, approved seat, inside its time window | `inside` + zone_id | none | recorded |
| Arjun in the Sports Office, no seat | `inside` + zone_id | `zone_without_seat` | needs_review |
| Ravi in the Sports Office at 10:45 (seat window ended) | `inside` + zone_id | `zone_outside_seat_time` | needs_review |
| Meena 2.1 km away | `outside` | `location_outside` (unchanged) | needs_review |

**Worked example.** At the 09:52 ping, Ravi's phone reports a spot 6 m from the Sports Office with ±15 m
accuracy. That's outside the Ground's 100 m. Sports Office check: 6+15 ≤ 30 m radius, so `inside`. Ravi
holds an approved claim and 09:52 falls inside 09:30–10:30, so the event is saved as recorded with zone
"Sports Office". Arjun, standing next to him with no claim, gets needs_review with `zone_without_seat`.

**Tests:**
- `tests/test_zones.py`: create activity with zones, claim, full, approve, release.
- Coordinator authorization: a non-coordinator student gets 403.
- Seat race.
- Zone-aware verdict table (above), via `Api.do(...)` and `offset_lat` from tests/helpers.py.
- Pure `match_zone` tests in test_units.py style.

---

## Phase 2 — Backend: random pings, "Where are you?", triage, Confirm all (TDD)

**Random pings.** `ActivityIn` gains `random_pings: {count: 0–6, minutes_open: 1–60, min_gap_minutes}`.
- On create, a pure function `rules.plan_pings(starts_at, ends_at, count, minutes_open, min_gap, rng)` picks
  times. It never uses the first or last 10 minutes and keeps windows from overlapping.
- The result is inserted as `CheckpointWindow` rows with `opened_by_id = NULL` and a new `source = "random"`
  column. `opened_by_id` becomes nullable (another column change; the demo DB is reset anyway).
- `window_out` already handles a missing opener (views.py:86-91).
- **Leak fix (required):** `build_activity` sends every window to every user today
  ([backend/app/views.py:146](backend/app/views.py#L146)). For students, filter to `opens_at <= now`.
  Otherwise a student reads tomorrow's ping times in advance.
- Manual "Open checkpoint" stays as it is. `rules.open_checkpoint` already ignores future rows.
- Tests inject a seeded `random.Random`, so ping times are deterministic.

**Worked example.** Activity 09:00–11:00, 3 pings of 10 min, minimum gap 20 min. The rng picks 09:52,
10:15 and 10:31. Before 09:52 the student's activity shows `checkpoint_windows: []` and
`open_checkpoint: null`. At 09:52:00 `open_checkpoint` is the 09:52 window and `next_actions` includes
"checkpoint".

**"Where are you?" explanation.** Events stay immutable, so the answer goes in a new append-only table:
- `Explanation`: id, event_id, student_id, reason_code (`event_duty` | `sent_by_staff` | `medical` | `other`),
  zone_id (nullable), note (≤ 200 chars), sent_by_name (nullable), created_at.
- `POST /events/{id}/explanation` (owner only, once, only when the event is needs_review for a location reason).
- It doesn't change the status. It's evidence for the triage step and for faculty.

**Triage summary.** A pure function `rules.triage(record, zones, claims, explanations, settings) -> Triage`.
It is added to `AttendanceRecordOut` as `triage: {bucket: "clean" | "look", lines: [str]}`. No numbers and
no scores, per invariant 2.

- **clean:** has check-in and check-out; every event is recorded (main venue, or a zone with an approved
  seat in its time window); no missing checkout; no current decision.
- **look:** anything else. One plain line per problem, for example:
  - "Away at 10:31 ping, 2.1 km from venue, no explanation"
  - "In Sports Office at 09:52 without a seat"
  - "Said Sports Office, location says outside every zone"
  - "Missed 2 of 3 pings"
  - "Seat held, work not confirmed by coordinator"
- `settings.max_missed_pings` (default 1) goes in [backend/app/config.py](backend/app/config.py) with
  `_number` parsing and a default, because tests build `Settings(...)` directly.

**Confirm all.** `POST /activities/{id}/decisions/bulk {student_ids[], reason}` (faculty).
- The server re-runs `triage` for each student and only confirms the ones still "clean".
- It returns `{confirmed[], skipped[{student_id, why}]}`.
- The reason defaults to "All observations at allowed places" in the UI, but it's still sent. The required-reason
  rule is unchanged. Invariant 1 holds, because this is a faculty decision.

**Tests:**
- `test_pings.py`: spacing, no leak to students, opens on time with `test_clock`.
- `test_explanations.py`.
- `test_triage.py`: the pure table, one case per line above.
- `test_bulk_decision.py`: a student who becomes un-clean between view and click is skipped.

---

## Phase 3 — Contract

- [contracts/API.md](contracts/API.md) Addendum:
  - new types (Zone, SeatClaim, Explanation, Triage)
  - new endpoints and error codes (`zone_full`, `not_coordinator`, `claim_not_pending`, `explanation_not_allowed`)
  - new reason tokens
  - the student window-visibility rule
- Regenerate with `backend/scripts/generate_fixtures.py` and `export_openapi.py` (subsystem-notes: these are
  generated, never hand-edited).

---

## Phase 4 — Dashboard (faculty)

- [dashboard/src/screens/ActivityCreateScreen.tsx](dashboard/src/screens/ActivityCreateScreen.tsx):
  - A "Duty zones" repeater: name, lat/lng typed or "use my location" (reuse `UseLocationButton`), radius,
    seats, time window, duty label.
  - Coordinator picker (from the selected students) and the random pings settings.
  - Validation goes in `src/lib/validation.ts`. Each input uses the existing `Field` component.
  - No map library (none exists today, and adding one is a new dependency).
  - `BoundarySummary` lists the zones too.
- [dashboard/src/screens/RegisterScreen.tsx](dashboard/src/screens/RegisterScreen.tsx) summary block (:54-58):
  - "✅ 57 clean → [Confirm all]" and a "Needs a look" list with the triage lines.
  - After the bulk call, run `refresh()` once rather than waiting for the 5 s poll.
  - Show "skipped" students and why.
- [dashboard/src/components/RegisterRow.tsx](dashboard/src/components/RegisterRow.tsx): zone name in
  `EventCell`, explanation text, seat/claim status.
  - New labels go in `src/lib/labels.ts`; new error codes in `KNOWN_CODES` (realClient.ts:21-26).
- Zone seats panel on the register screen: who holds each seat, approve/decline, work confirmed. Faculty can
  act here too, for when the coordinator is busy.
- `src/api/types.ts` `Api` interface → implement in both `realClient.ts` and `fixtureClient.ts`
  (TypeScript enforces both). Seed fixtures with the football scenario.
- Tests:
  - Vitest in the `registerSummary.test.tsx` style (Confirm all, skipped list, triage lines).
  - A new serial Playwright spec `e2e/zones.spec.ts` that seeds its own activity, so it doesn't shift the
    counts in `fieldpass.spec.ts`. Chromium only (see `.agents/decisions/log.md`).

**Worked example (what the faculty member sees at 11:05):**
```
Register summary   Roster 10 · Checked in 10 · Clean 8 · Needs a look 2
[ Confirm all 8 ]  reason: "All observations at allowed places"
Needs a look
  Arjun   In Sports Office at 09:52 without a seat
  Meena   Away at 10:31 ping, 2.1 km from venue, no explanation
Zones
  Sports Office 2/2  Ravi ✅ approved by Priya · work confirmed
                     Priya ✅ approved by faculty1 · work confirmed
```

---

## Phase 5 — Android (student)

All paths are under `android/app/src/main/java/com/fieldpass/student/`.

**Adding each endpoint follows the existing pattern:**
1. DTO in `core/model/Models.kt` / `Enums.kt`.
2. `data/api/ApiService.kt`.
3. `FieldPassRepository` interface.
4. Implement in **four** places: `RealFieldPassRepository`, `SwitchableRepository`, `FakeFieldPassRepository`,
   and the test `StubRepository` (`TestSupport.kt`).
5. Wire in `AppContainer.kt`, add routes in `ui/Navigation.kt`, and add new error codes in
   `core/error/ErrorCode.kt` with a string resource each.

1. **Duty seats.** On `activities/ActivityDetailScreen.kt`, a "Duty zones" card below the progress tracker
   shows each zone with "2/2 seats", a "Claim seat" / "Release" button, and the claim status
   (pending / approved / declined).
2. **Coordinator screen.** A new route, shown only when my roster entry `is_coordinator`. It lists pending
   claims (approve/decline) and has "work done" toggles.
3. **Ping alerts. This is all new infrastructure;** the app has no notifications or background work today.
   - A foreground service (type `dataSync`) runs from check-in until check-out or `ends_at + grace`. It
     polls `GET /activities/{id}` every 30 s.
   - When `open_checkpoint` appears, it posts a high-priority notification: "Checkpoint open, 10 min left".
   - Add a notification channel. The manifest gains `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and
     `FOREGROUND_SERVICE_DATA_SYNC`. Add a NOTIFICATIONS entry to `ui/PermissionGate.kt`.
   - **Keep the "no background tracking" stance** written in `capture/FusedLocationProvider.kt:24-27`. The
     service never reads location. Location is read only inside the existing capture screen, so there's no
     "allow all the time" prompt.
   - `MainActivity` doesn't handle intents today. Add notification-intent handling that navigates to
     `capture/{id}/CHECKPOINT`.
   - **Trap:** the capture ViewModel key is `capture-$id-${action.name}` (`Navigation.kt:85`), so two pings
     in one event would reuse one ViewModel. Add the window id to the route and the key.
   - **Trap:** list enums don't coerce unknown values. Add any new `Action` values to the app before the
     server sends them, or keep pings as plain `checkpoint` (recommended: pings *are* checkpoint windows,
     so no new action value is needed).
   - Fix while here: the "checkpoint open" badge in `ActivitiesScreen.kt:94-98` ignores next_actions, unlike
     the detail screen.
4. **"Where are you?" sheet.** The capture result state (`capture/CaptureState.kt`) gets a follow-up step.
   If the submit returns needs_review with `location_outside` or `zone_without_seat`, a bottom sheet appears
   with chips: [Event duty → zone picker] [Sent by staff → name] [Medical] [Other…]. One tap sends
   `POST /events/{id}/explanation`.
5. **Tests:**
   - ViewModel tests in the `AttendanceCaptureViewModelTest.kt` style (fake clock, `tickerEnabled=false`).
   - Poll-to-notify logic as a pure class, unit-tested without Android.
   - MockWebServer path tests in `data/RealRepositoryTest.kt`.
   - `android/scripts/integration-test.sh` has **no checkpoint path at all** today. Add one, plus zones,
     to `BackendContractTest`.

**Worked example (Ravi's phone):** He claims a Sports Office seat at 09:10 and Priya approves it at 09:12.
At 09:52 his phone buzzes: "Checkpoint open, 10 min left". He taps it, takes a selfie and sends. The result
is "Recorded – Sports Office". Arjun does the same, gets "Needs review", and the sheet asks where he is.
He taps "Event duty → Sports Office". Triage still says "In Sports Office at 09:52 without a seat", because
the explanation doesn't replace a seat.

---

## Phase 6 — Docs and brain

- [integration/known-limitations.md](integration/known-limitations.md):
  - Zone GPS has the same spoofing and indoor-accuracy limits (#2, #20).
  - Polling pings depend on the phone keeping the foreground service alive (battery savers can kill it).
  - Seats prove location, not work.
  - Explanations are self-reported.
- README "What FieldPass does NOT prove", runbook (the DB reset step), demo-script (the football scenario).
- `.agents/decisions/log.md`: the seven decisions above. `.agents/context/stack-and-rules.md`: new tables,
  new reason tokens, and the student window-visibility rule as an invariant.
  `.agents/projects/active-backlog.md`: these phases as rows.

---

## Order and dependencies

```
Phase 1 (backend zones) ──► Phase 2 (pings, triage, bulk) ──► Phase 3 (contract)
                                                               ├─► Phase 4 (dashboard)
                                                               └─► Phase 5 (Android)
Backlog item "run the app on a physical phone" ─────────────────► must happen before Phase 5.3 is trusted
Phase 6 runs alongside each phase
```

Each phase lands as its own commit on main (git-workflow rule) after `/verify`.

---

## Verification

**Backend**
- `cd backend && .venv/bin/python -m pytest --cov=app --cov-report=term-missing`. All green, new modules ≥ 80%.
- Run the Postgres suite too with `TEST_DATABASE_URL` set (new partial indexes).

**Contract**
- Regenerate the fixtures and openapi. `git diff contracts/` shows only Addendum-scope changes.

**Dashboard**
- `npm run typecheck && npm run lint && npm test && npm run e2e` (Chromium).
- Screenshots of the register summary at 320, 768, 1024 and 1440, light and dark, looked at before reporting.

**Android**
- `./gradlew testDebugUnitTest lintDebug` and `android/scripts/integration-test.sh` with the zones path.
- On a **physical phone** over the hotspot: claim, approve, ping notification arrives with the screen off,
  capture, explanation sheet.

**End to end (the scenario):**
1. Seed the football event on a short clock: pings 2 min apart, TTL as in the timing e2e.
2. Students:
   - Ravi: approved seat, in the zone → recorded.
   - Arjun: zone, no seat → `zone_without_seat`.
   - Meena: far away, no answer → `location_outside`.
   - The others: on the Ground → recorded.
3. The dashboard shows Clean 8, Needs a look 2. "Confirm all" confirms 8 and skips nobody. Arjun and Meena
   are still undecided.

---

## Open questions (they don't block phase 1)

1. **A real AI layer.** Phase 2 triage is rules, not a language model. A later option is to send each "Needs a
   look" timeline to Claude to write a friendlier paragraph. This needs internet on the laptop and sends student
   location and names to an outside service, which needs consent. Recommendation: skip it until the rules
   version has been used in a real demo.
2. **Who approves when no coordinator is set?** Default: claims go to faculty. The alternative is auto-approve
   up to the seat cap; it's faster but brings back the "wrong 2 grab seats" hole.
