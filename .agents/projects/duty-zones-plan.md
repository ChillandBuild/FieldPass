# Plan: Duty Zones, Random Pings and Review Triage

Revision 2 (2026-10-11). Revision 1 was checked against the code; the corrections are in the table below.
Status: approved idea, not started.

## Context

**Problem.** A field event runs 09:00–11:00 on the Ground. A few students have real event work somewhere
else, for example printing the fixtures list in the Sports Office. Today FieldPass knows only one venue.
Anyone found elsewhere gets `location_outside` → `needs_review` with no context. With 60 students,
faculty can't chase every flag. Checkpoints are also opened by hand, so students know when to be in place.

**Agreed idea (design session, 2026-10-11):**
1. The main venue counts for everyone.
2. Other places ("duty zones") count only for students who hold a **seat** there. Seats are capped,
   for example "Sports Office: 2 seats, 09:30–10:30, fixtures desk".
3. A coordinator approves seat claims and confirms the work was done. Faculty set up one line per zone
   and never one line per student.
4. The server schedules **random pings** during the event.
5. A student found away with no seat answers "Where are you?" with one tap.
6. The dashboard shows a **triage summary**: clean students get one "Confirm all"; flagged students get a
   reason each.
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

## Corrections from checking revision 1 against the code

| # | Revision 1 said | The code says | Change |
|---|---|---|---|
| 1 | Zone matching only tests zones "open at created_at", yet a table row expects `zone_outside_seat_time` at 10:45 | A closed zone would never be tested, so that row would give `location_outside` | Match zones by geometry only; check seat and time afterwards |
| 2 | Contract regenerated in P3 | `tests/test_openapi.py:10` and `test_http_hardening.py:63` assert `len(paths) == 21`, and `test_exported_yaml_matches_live_app` diffs `contracts/openapi.yaml` against the live app. lefthook pre-push runs pytest | New endpoints break the suite until openapi.yaml and the counts are updated. Regenerate in the same commit as each backend phase |
| 3 | Seat race fixed with `with_for_update` ("no-op on SQLite") | A no-op lock means two SQLite writers both count 1 and both insert. The repo's own pattern is a no-op `UPDATE` that takes the write lock (`attempts.py` `_persist`) | Use a no-op `UPDATE zones` first, then count, then insert |
| 4 | New columns on `RosterEntry`, `CheckpointWindow`, `AttendanceEvent`, so "reset the demo DB" | `create_all` adds missing tables only (`db.py:128`). Resetting also means dropping the Supabase schema | Additive-only design: new tables only, nothing to reset (decision 7) |
| 5 | New RLS work was an open worry | `_lock_down_schema` enables RLS on every table in the schema at each init (`db.py:99-108`) | Nothing to add; new tables are covered automatically |
| 6 | Android trap was the `Action` enum | `ReviewReason` is a closed enum of 4 values, used in a `List<ReviewReason>` (`Enums.kt:50`, `Models.kt:72,109`). An unknown token fails parsing even with `coerceInputValues` | `zone_without_seat` and `zone_outside_seat_time` would break the Android app. Fix first (P5.0) |
| 7 | `triage` added to `AttendanceRecordOut` | The same model is the student's `my_attendance` and `/attendance/me` (`views.py:112`) | Triage is faculty-only; `None` in student responses |
| 8 | Triage `lines: [str]` with "10:31" | The server only knows UTC; the dashboard shows local time | Triage returns structured flags; the dashboard writes the text |
| 9 | Claim row mutated in place, no `released_at`, unique (zone, student) | In-place status overwrites history; the unique key blocks re-claim after release | Keep `released_at`; partial unique index on active claims only |
| 10 | Event outcome depends on claims (mutable) | A later approve or release would disagree with the stored event | Store the matched zone and claim with the event (`EventPlace`); triage reads the snapshot |
| 11 | "Missed pings" undefined | `check_out` is allowed at any time (`rules.py:89`), and nothing blocks leaving early | Define missed precisely; counting after check-out stops early check-out from dodging pings |
| 12 | Manual "Open checkpoint" "stays as it is" | `open_checkpoint` picks the latest-opened window (`rules.py:40`), so a manual window overlapping a scheduled one is unanswerable once the scheduled one opens | Trim a manual window at the next scheduled window |
| 13 | No endpoint listed for claims, no way for Android to know it is a coordinator | The coordinator screen and seats panel need claim lists | `GET /activities/{id}/zones` returns claims (coordinator/faculty) and `is_coordinator` |
| 14 | Explanation allowed for "a location reason"; Android sheet only for two tokens | Reasons can also be `location_uncertain` and `zone_outside_seat_time` | One shared list of four tokens for both sides, plus a way to answer later |
| 15 | "No numbers and no scores" for triage | Invariant 2 forbids a face score or confidence only | Reworded; distance and counts are allowed |
| 16 | "after `/verify`" | No such skill or command exists | Use the verification list in this plan |

---

## Decisions this plan makes (each one is reversible)

| Decision | Why | Rejected |
|---|---|---|
| The coordinator is **per event, in its own table** (`ActivityCoordinator`), not a new role | No new login type, JWT or seed change. Faculty pick 1–5 coordinators per event. Only student and faculty roles exist (`schemas.py:9`) | A global "coordinator" role (touches auth everywhere); a column on `RosterEntry` (see 7) |
| Random pings are **generated as future `CheckpointWindow` rows when the activity is created** | The backend has no scheduler, and every time check goes through the injectable `clock.now()`. Pre-generated rows are testable with the existing `test_clock` | A background scheduler (breaks the test clock, dies on restart) |
| The phone **polls** while an event is running, using a foreground service | Hotspot demo, no Firebase project; push needs internet and Google setup | FCM push |
| Time away is measured as **pings found away**, not minutes | Project invariant: "times are points of observation" (README, known-limitations #3) | A minutes-away limit |
| The triage summary is **rule-based and deterministic** | Works offline, same answer every time, testable, sends no student location to a third party | An LLM call per review (see Open questions) |
| The contract change goes into the **API.md Addendum** | The contract is frozen as v1 (decisions/log.md) | Editing frozen sections |
| **Additive-only schema: new tables, no new columns on existing tables, no DB reset** (replaces "reset the demo DB") | `create_all` creates missing tables on SQLite and Postgres and the RLS lock-down covers them. Existing tables, `backend/data` and the Supabase schema stay untouched | New columns (need a reset or ALTER); Alembic now |
| **Zones apply to checkpoints only.** Check-in and check-out are judged against the main venue | Presence at the event is proven at the venue; a student could otherwise check in and out from the Sports Office and never be on the Ground | Zones for every action |
| **The event keeps a snapshot of where and why**: matched zone and seat claim are stored with the event, written in the same transaction | Events are immutable; claims change later. Triage must not disagree with what was stored | Recomputing from current claims |
| **Verdict precedence:** main venue `inside` wins. Otherwise the nearest `inside` zone. Otherwise the best of the main and zone verdicts (`uncertain` beats `outside`) | Defined so that overlapping zones and poor-accuracy readings have one answer | Per-zone verdicts in the API |
| **Triage is faculty-only** and returns structured flags | Students shouldn't see review reasoning; the server doesn't know the local time zone | Free-text lines |

---

## Rules that apply to every phase

- Additive schema only. Do not delete `backend/data`, do not run anything that alters the Supabase schema.
  Run backend dev and tests on SQLite. **Do not start the backend with a Supabase `DATABASE_URL`** without
  asking the user first, because `create_all` would create the new tables there. Use `TEST_DATABASE_URL`
  only if it points at a throwaway database (tests create and drop their own schema).
- Each backend phase regenerates `contracts/openapi.yaml` and fixtures in the same commit
  (`backend/scripts/export_openapi.py`, `generate_fixtures.py`) and updates the two path-count assertions.
- New error codes are added to `errors.py`, and later to dashboard `KNOWN_CODES` and Android `ErrorCode`.
- Endpoints are GET or POST only. Partial indexes need both `sqlite_where` and `postgresql_where`.
- Automated checks only produce `recorded` / `needs_review`. Seat approval and work confirmation by a
  coordinator are inputs to the automated check, never attendance decisions. Only faculty decide.
- On Windows the venv python is `backend/.venv/Scripts/python`.

---

## Phase 1 — Backend: zones, seats, coordinator, zone-aware location (TDD)

**New tables** in [backend/app/models.py](backend/app/models.py). Use `new_id(prefix)`, `UTCDateTime`.

- `Zone`: id, activity_id (index), name, lat, lng, radius_m, seats, opens_at, closes_at, duty_label.
  Unique (activity_id, name).
- `SeatClaim`: id, zone_id (index), student_id (index), status (`pending` | `approved` | `declined` |
  `released`), requested_at, decided_by_id, decided_at, released_at, work_confirmed_by_id,
  work_confirmed_at. **Partial unique index** on (zone_id, student_id) `WHERE status IN ('pending','approved')`,
  with both `sqlite_where` and `postgresql_where`, as at models.py:88-97. A student may re-claim after a
  release or decline: that creates a new row, and the old row stays as history.
- `ActivityCoordinator`: activity_id, student_id (composite primary key, like `RosterEntry`).
- `EventPlace`: event_id (primary key, FK `attendance_events.id`), zone_id, claim_id (nullable). One row
  per event that matched a zone. Written in the same commit as the event.

**Validation** (`ActivityIn`, `zones[]` max 10, `coordinator_ids[]` max 5):
- Zone window lies within the activity; `seats` 1–50; `radius_m` 10–500; names unique within the activity.
- Every coordinator must be in `student_ids`.

**Endpoints:**

| Endpoint | Who | What |
|---|---|---|
| `POST /activities` (extended) | faculty | Optional `zones[]` and `coordinator_ids[]` |
| `GET /activities/{id}/zones` | rostered student, or faculty | `{is_coordinator, zones[]}`. Each zone has seats taken/free and my claim. Coordinators and faculty also get every claim with student names. Other students never see who holds a seat |
| `POST /zones/{id}/claims` | rostered student | Claim a seat. `409 zone_full` when pending + approved has reached `seats`; `409 seat_conflict` when I already hold an active claim in a zone with an overlapping time window; `409 outside_activity_window` once the zone has closed |
| `POST /claims/{id}/release` | the claimant | Only from `pending` or `approved`; sets `released_at` |
| `POST /claims/{id}/decision` | coordinator of that activity, or faculty | `{approve: bool}`. Not for your own claim (`403 forbidden`). `409 claim_not_pending` if it is not pending |
| `POST /claims/{id}/work-confirmed` | coordinator of that activity, or faculty | Only for an approved claim, not your own, once only |

**Concurrency** (same style as `test_enrollment_races.py`: threads and a `Barrier`):
- *Claiming:* in one transaction run a no-op `UPDATE zones SET id = id WHERE id = :z` (takes the SQLite
  write lock and the Postgres row lock), then count active claims, then insert.
- *Decide, release, confirm:* a conditional `UPDATE ... WHERE status = 'pending'` and check `rowcount`,
  as the enrollment decision does. Two simultaneous decisions: one wins, the other gets `claim_not_pending`.

**Location verdict is now zone-aware.** Change [backend/app/routers/attempts.py:168-169](backend/app/routers/attempts.py#L168-L169)
and `_build_event` (attempts.py:80-94):

1. Compute the main-venue verdict with the existing `geo.location_verdict`.
2. If the action is not `checkpoint`, or the verdict is `inside`: unchanged.
3. Otherwise compute the verdict for **every zone of the activity, whether or not it is open**
   (pure helper `rules.match_zone(lat, lng, accuracy, zones)`). If some zone is `inside`, take the nearest.
   `distance_m` and `accuracy_m` on the event then describe that zone, and the response gains an optional
   `zone: {id, name} | null`.
4. Seat check, using the claim state at submit and the server time `attempt.created_at`:
   - no approved, unreleased claim for this student in that zone → reason `zone_without_seat`;
   - claim exists but `created_at` is outside `[opens_at, closes_at)` → `zone_outside_seat_time`;
   - otherwise no zone reason. Store `EventPlace(zone_id, claim_id)`.
5. No zone is `inside`: the verdict is the best of the main and zone verdicts (`uncertain` beats `outside`),
   with the main venue's distance, and the reason is the existing `location_uncertain` / `location_outside`.

| Situation | location verdict | reason | status |
|---|---|---|---|
| Ravi, Sports Office, approved seat, 09:52 | `inside` + zone | none | recorded |
| Arjun, Sports Office, no seat | `inside` + zone | `zone_without_seat` | needs_review |
| Ravi, Sports Office at 10:45 (seat window over) | `inside` + zone | `zone_outside_seat_time` | needs_review |
| Meena, 2.1 km away | `outside` | `location_outside` (unchanged) | needs_review |
| Ravi **checks in** from the Sports Office | `outside` | `location_outside` (zones are for checkpoints only) | needs_review |
| Reading near the Sports Office edge, accuracy 40 m | `uncertain` | `location_uncertain` | needs_review |

**Worked example.** At the 09:52 ping, Ravi's phone reports a spot 6 m from the Sports Office with ±15 m
accuracy. That's outside the Ground's 100 m. Sports Office check: 6+15 ≤ 30 m radius, so `inside`. Ravi
holds an approved claim and 09:52 falls inside 09:30–10:30, so the event is saved as recorded with zone
"Sports Office". Arjun, standing next to him with no claim, gets needs_review with `zone_without_seat`.

**Tests** (write first, show failing):
- `tests/test_zones.py`: create with zones; claim; full; approve; decline; release and re-claim; overlap
  conflict; a student can't see other claimants; validation errors.
- Authorization: plain student 403 on decision and work-confirmed; coordinator of another activity 403; a
  coordinator can't decide their own claim.
- Races: last seat claimed by two students; approve versus decline.
- Zone-aware verdict table above via `Api.do(...)` and `offset_lat` from tests/helpers.py. Zones go through
  `make_activity(zones=[...], coordinator_ids=[...])` (its `**extra` merges into the body).
- Snapshot: approve or release a claim after the event, and the stored reason and `EventPlace` don't change.
- Pure `match_zone` tests in `test_units.py` style, including overlapping zones and the `uncertain` case.
- `tests/test_postgres.py` style check that the new partial index exists (skips without `TEST_DATABASE_URL`).
- Update the path counts in `test_openapi.py:10` and `test_http_hardening.py:63`; regenerate openapi + fixtures.

---

## Phase 2 — Backend: random pings, "Where are you?", triage, Confirm all (TDD)

**Leak fix first (write its failing test before anything else).** `build_activity`
([backend/app/views.py:146](backend/app/views.py#L146)) sends every window to every viewer. Give it an
explicit `for_student` flag (set by `render_activity` for students) and, for students, return only windows
with `opens_at <= now`. `render_activity` also serves `GET /activities`, so one fix covers both endpoints; the
test must hit both. Faculty and the register still see everything.

**Random pings.** `ActivityIn` gains `random_pings: {count: 0–6, minutes_open: 1–60, min_gap_minutes: 0–60}`.
- Pure `rules.plan_pings(starts_at, ends_at, count, minutes_open, min_gap, rng)` returns whole-minute start
  times. It never uses the first or last 10 minutes and keeps windows at least `min_gap` minutes apart
  (gap measured from one window's close to the next one's open). Method: pick `count` sorted offsets in the
  slack that is left after the fixed widths, then add the widths back, which gives non-overlapping windows.
- If `count × minutes_open + (count − 1) × min_gap` does not fit in the span minus the margins, creation
  fails with `422 validation_error`.
- The result is inserted as `CheckpointWindow` rows (`opened_by_id` = the creating faculty, unchanged column)
  plus a `ScheduledPing(window_id)` row marking each as scheduled. `CheckpointWindowOut` gains
  `source: "manual" | "scheduled"`; `opened_by` stays the faculty name.
- Production randomness is `random.SystemRandom`; tests override a `get_rng` dependency (like
  `get_face_engine`) with a seeded `random.Random`.
- Manual "Open checkpoint" (`routers/activities.py:108`) keeps working but its window is **trimmed at the
  next scheduled window's `opens_at`**. If less than a minute would remain: `409 invalid_action_order`.
- `rules.open_checkpoint` already ignores future rows (`opens_at <= now`).

**Worked example.** Activity 09:00–11:00, 3 pings of 10 min, minimum gap 20 min. The rng picks 09:52,
10:15 and 10:31. Before 09:52 the student's activity shows `checkpoint_windows: []` and
`open_checkpoint: null`. At 09:52:00 `open_checkpoint` is the 09:52 window and `next_actions` includes
"checkpoint". Faculty see all three windows from the start.

**"Where are you?" explanation.** Events stay immutable, so the answer goes in a new append-only table:
- `Explanation`: id, event_id (unique), student_id, reason_code (`event_duty` | `sent_by_staff` | `medical` |
  `other`), zone_id (nullable, must belong to the activity), note (≤ 200 chars), sent_by_name (≤ 100,
  nullable), created_at.
- `POST /events/{id}/explanation`: owner only, once, only when the event is `needs_review` with at least one
  of `location_outside`, `location_uncertain`, `zone_without_seat`, `zone_outside_seat_time`, and only until
  the check-out deadline. Otherwise `409 explanation_not_allowed`.
- It doesn't change the status. It's evidence for faculty. The note is never logged.
- `AttendanceRecordOut` gains `explanations[]` (a student sees their own; the register shows all).

**Triage (faculty only).** A pure function
`rules.triage(activity, events, places, windows, zones, claims, explanations, decisions, settings, now) -> Triage`.
`AttendanceRecordOut.triage` is set only for register and CSV rows, and is `None` in `my_attendance`,
`/attendance/me` and every student response. `load_activity_data` gets a flag to load zones, claims, places
and explanations only for those paths.

`Triage = {bucket: "clean" | "look" | "decided", flags: [Flag]}`. A `Flag` is
`{code, at?, zone_name?, action?, missed?, total?, said?}`. The server sends no free text and no local times.
No face score appears anywhere (invariant 2). Distance already lives in the event's `location`.

- **decided:** a current decision exists (`rules.current_decision`); no flags needed.
- **clean:** not decided, a check-in and a check-out exist, **no flags at all**.
- **look:** everything else. Flags, one per problem:

| code | when |
|---|---|
| `not_checked_in` | no check-in |
| `not_checked_out` | no check-out |
| `away` | an event with `location_outside`; carries `at`, `action`, and `said` if explained |
| `location_unclear` | an event with `location_uncertain` |
| `zone_without_seat` / `zone_outside_seat_time` | those reasons; carry `at`, `zone_name` |
| `face_mismatch` / `face_unclear` | those reasons; carry `at` |
| `said_zone_not_found` | explanation names a zone but the event matched no zone |
| `missed_pings` | see below; carries `missed`, `total` |
| `seat_work_unconfirmed` | an approved, unreleased claim whose `zone.closes_at` has passed and `work_confirmed_at` is empty |
| `seat_released_after_check` | the claim behind a zone event (`EventPlace.claim_id`) was released afterwards |

**Missed pings.** Counted windows are those with `closes_at` later than the student's check-in time and no
checkpoint event from them. This holds even if the student checked out early, because after check-out nobody
can answer. Windows are manual or scheduled alike. A student with misses is still `clean` only when
`missed ≤ settings.max_missed_pings` **and** `missed < total` (answering none of the counted windows is never
clean). `max_missed_pings` (default 1) goes in [backend/app/config.py](backend/app/config.py) with `_number`
parsing and a default, because tests build `Settings(...)` directly.

Clean students have a check-out, so no later event can arrive for them (`rules.check_action_allowed` refuses
everything after check-out). A decision made on a clean student therefore can't go stale.

**Confirm all.** `POST /activities/{id}/decisions/bulk {student_ids[] (1–500), reason}` (faculty).
- One transaction. The server re-runs `triage` for each student and confirms only those still `clean`.
- Response `{confirmed[], skipped[{student_id, why}]}` where `why` is `not_clean`, `already_decided` or
  `not_on_roster`. Repeating the call skips everyone as `already_decided`.
- The reason is still required (the UI defaults it to "All observations at allowed places"). Invariant 1
  holds: this is a faculty decision.

**Tests** (write first, show failing):
- `test_pings.py`: spacing and margins, no-fit error, no leak to students (activity and list endpoints),
  opens on time with `test_clock`, manual window trimmed, seeded rng deterministic.
- `test_explanations.py`: each allowed reason, refused when recorded, refused twice, refused for someone
  else's event, zone must belong to the activity.
- `test_triage.py`: the pure table, one case per flag, including early check-out dodging pings, a late
  arrival not charged for earlier windows, and the `missed < total` rule.
- `test_bulk_decision.py`: a student who becomes un-clean between view and click is skipped; repeat call;
  student not on roster; reason required.
- Student responses never contain `triage`.
- Update path counts again and regenerate openapi + fixtures.

---

## Phase 3 — Contract Addendum

Contract files are generated, so P1 and P2 already regenerated them. This phase is the prose and a check.
- [contracts/API.md](contracts/API.md) Addendum only:
  - new types (Zone, SeatClaim, Explanation, Triage, Flag) and the optional `zone`, `source`, `explanations`
    fields;
  - new endpoints and error codes (`zone_full`, `seat_conflict`, `claim_not_pending`,
    `explanation_not_allowed`; `forbidden` is reused for non-coordinators);
  - new reason tokens `zone_without_seat`, `zone_outside_seat_time`, and the rule that clients must tolerate
    unknown tokens;
  - the student window-visibility rule and the "triage is faculty-only" rule.
- Re-run `generate_fixtures.py` and `export_openapi.py`; `git diff contracts/` must show no change beyond
  the Addendum prose.

---

## Phase 4 — Dashboard (faculty)

- [dashboard/src/screens/ActivityCreateScreen.tsx](dashboard/src/screens/ActivityCreateScreen.tsx):
  - A "Duty zones" repeater: name, lat/lng typed or "use my location" (reuse `UseLocationButton`), radius,
    seats, time window, duty label.
  - Coordinator picker (reuse the `StudentPicker` pattern, limited to the selected students) and the random
    pings settings.
  - Validation goes in `src/lib/validation.ts`; each input uses the existing `Field` component.
  - No map library. `BoundarySummary` lists the zones too.
- [dashboard/src/screens/RegisterScreen.tsx](dashboard/src/screens/RegisterScreen.tsx) summary block (:54-58):
  - **Keep the existing three tiles** (Roster, Checked in, Awaiting review); `fieldpass.spec.ts` may assert
    them. Add "Clean" and "Needs a look" tiles beside them.
  - "[Confirm all N clean]" with the reason field, then call `refresh()` once rather than waiting for the 5 s poll.
  - A "Needs a look" list. The text for each flag is built in `src/lib/labels.ts` from the structured flag,
    formatting times in local time with the existing `time.ts` helpers.
  - Show skipped students and why.
- [dashboard/src/components/RegisterRow.tsx](dashboard/src/components/RegisterRow.tsx) and `EventCell.tsx`:
  zone name, explanation, scheduled-versus-manual checkpoint label, seat status.
  - `REASON_LABELS: Record<ReviewReason, string>` in `labels.ts` is exhaustive: add the two zone tokens to
    `ReviewReason` in `src/api/types.ts` and TypeScript will force the labels.
  - New error codes go in `KNOWN_CODES` (realClient.ts:21-26).
- Zone seats panel on the register screen: who holds each seat, approve/decline, work confirmed. Faculty can
  act here when the coordinator is busy.
- `src/api/types.ts` `Api` interface → implement in both `realClient.ts` and `fixtureClient.ts`
  (TypeScript enforces both). Seed fixtures with the football scenario.
- Tests:
  - Vitest in the `tests/registerSummary.test.tsx` style (Confirm all, skipped list, flag text in local time).
  - A new serial Playwright spec `e2e/zones.spec.ts` that seeds its own activity, so it doesn't shift the
    counts in `fieldpass.spec.ts`. Chromium only (see `.agents/decisions/log.md`).

**Worked example (what the faculty member sees at 11:05):**
```
Register summary   Roster 10 · Checked in 10 · Awaiting review 2 · Clean 8 · Needs a look 2
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

**5.0 Prerequisite, before running the app against any backend that has P1.**
`ReviewReason` (`core/model/Enums.kt:50`) is a closed enum used in `List<ReviewReason>`, so an unknown token
fails parsing. Add `ZONE_WITHOUT_SEAT` and `ZONE_OUTSIDE_SEAT_TIME` (with strings and the exhaustive `when` in
`ui/StatusPresentation.kt:36`) **and** an `UNKNOWN` fallback through a tolerant serializer, with a test that
a response containing an unknown reason still parses. Do the same check for any new optional field.

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
2. **Coordinator screen.** A new route, shown only when `is_coordinator` in the zones response is true.
   It lists pending claims (approve/decline) and has "work done" toggles.
3. **Ping alerts. This is all new infrastructure;** the app has no notifications, no service and no intent
   handling today (`AndroidManifest.xml` has no `<service>`; `MainActivity.kt` has no intent code).
   - A foreground service (type `dataSync`) started from the foreground right after check-in succeeds
     (Android 14+ refuses background starts), running until check-out or `ends_at + grace`. It polls
     `GET /activities/{id}` every 30 s.
   - When `open_checkpoint` appears, post a high-priority notification: "Checkpoint open, 10 min left". Notify
     once per window id (remember the last id, also across a service restart).
   - Add a notification channel. The manifest gains `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and
     `FOREGROUND_SERVICE_DATA_SYNC`. Add a NOTIFICATIONS entry to `GatedPermission` in `ui/PermissionGate.kt`.
   - **Keep the "no background tracking" stance** written in `capture/FusedLocationProvider.kt:22-25`. The
     service never reads location. Location is read only inside the existing capture screen, so there's no
     "allow all the time" prompt.
   - `MainActivity` doesn't handle intents today. Add notification-intent handling that navigates to
     `capture/{id}/CHECKPOINT`.
   - **Trap:** the capture ViewModel key is `capture-$id-${action.name}` (`Navigation.kt:85`), so two pings
     in one event would reuse one ViewModel and show the first ping's result. Add the window id to the route
     and the key.
   - Pings are ordinary checkpoint windows, so no new `Action` value is needed.
   - Fix while here: the "checkpoint open" badge in `activities/ActivitiesScreen.kt` (~:92-96) tests only
     `openCheckpoint != null`, so it still shows after the student answered. Use `nextActions` like the
     detail screen.
   - **Known risk:** Doze and battery savers can delay or kill a polling service with the screen off. This is
     why the physical-phone test is a gate, not a nice-to-have. A missed alert is not a failure of the rule:
     the window stays open for its full length and the badge appears when the app is opened.
4. **"Where are you?" sheet.** The capture result state (`capture/CaptureState.kt`) gets a follow-up step.
   If the submit returns needs_review with any of `location_outside`, `location_uncertain`,
   `zone_without_seat`, `zone_outside_seat_time`, a bottom sheet appears with chips: [Event duty → zone
   picker] [Sent by staff → name] [Medical] [Other…]. One tap sends `POST /events/{id}/explanation`. If the
   student dismisses it, the activity detail screen keeps an "Explain" action on that event until the
   check-out deadline.
5. **Tests:**
   - ViewModel tests in the `AttendanceCaptureViewModelTest.kt` style (fake clock, `tickerEnabled=false`).
   - Poll-to-notify logic as a pure class, unit-tested without Android (dedupe per window id).
   - MockWebServer path tests in `data/RealRepositoryTest.kt`.
   - `android/scripts/integration-test.sh` has **no checkpoint path at all** today. Add one, plus zones,
     to `BackendContractTest`. It needs bash and an emulator or device; on this Windows machine report it
     as not run if it can't be run.

**Worked example (Ravi's phone):** He claims a Sports Office seat at 09:10 and Priya approves it at 09:12.
At 09:52 his phone buzzes: "Checkpoint open, 10 min left". He taps it, takes a selfie and sends. The result
is "Recorded – Sports Office". Arjun does the same, gets "Needs review", and the sheet asks where he is.
He taps "Event duty → Sports Office". Triage still says "In Sports Office at 09:52 without a seat", because
the explanation doesn't replace a seat.

---

## Phase 6 — Docs and brain

- [integration/known-limitations.md](integration/known-limitations.md):
  - Zone GPS has the same spoofing and indoor-accuracy limits (#2, #20).
  - Polling pings depend on the phone keeping the foreground service alive (Doze, battery savers).
  - Seats prove location, not work; a coordinator is a student and can approve or confirm for friends.
  - Explanations are self-reported.
  - Early check-out is only caught through missed pings; there is no other "left early" rule.
  - Zones apply to checkpoints only.
- README "What FieldPass does NOT prove", runbook, demo-script (the football scenario). The runbook needs
  no DB-reset step.
- `.agents/decisions/log.md`: the decisions table above (add the date). `.agents/context/stack-and-rules.md`:
  new tables, new reason tokens, the student window-visibility rule and the faculty-only triage rule as
  invariants. `.agents/projects/active-backlog.md`: mark each phase row done as it lands.

---

## Order and dependencies

```
Phase 1 (backend zones) ──► Phase 2 (pings, triage, bulk) ──► Phase 3 (Addendum)
                                                               ├─► Phase 4 (dashboard)
                                                               └─► Phase 5 (Android; 5.0 first)
Backlog item "run the app on a physical phone" ─────────────────► must happen before Phase 5.3 is trusted
Phase 6 runs alongside each phase
```

Each phase lands as its own commit on main after its verification list below passes. Commit messages are
conventional (`feat: ...`). Hooks run on commit (compile check, dashboard typecheck) and on push (full backend
and dashboard tests); never bypass them.

---

## Verification

**Backend**
- `cd backend && .venv/Scripts/python -m pytest --cov=app --cov-report=term-missing` (use `.venv/bin/python`
  on macOS or Linux). All green, new modules ≥ 80%.
- Run the Postgres suite too when `TEST_DATABASE_URL` is set to a throwaway database (new partial index).

**Contract**
- Regenerate fixtures and openapi each backend phase. `git diff contracts/` shows only the expected additions.

**Dashboard**
- `npm run typecheck && npm run lint && npm test && npm run e2e` (Chromium).
- Screenshots of the register summary and the create form at 320, 768, 1024 and 1440, light and dark,
  looked at before reporting.

**Android**
- `./gradlew testDebugUnitTest lintDebug` and `android/scripts/integration-test.sh` with the zones path.
- On a **physical phone** over the hotspot: claim, approve, ping notification arrives with the screen off,
  capture, explanation sheet. Say plainly that this is unverified until it has been done.

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
   up to the seat cap; it's faster but brings back the "wrong 2 grab seats" hole. (Pending claims also count
   toward the cap, so a student can hold a seat unapproved; a coordinator or faculty can decline to free it.)
3. **Editing after creation.** Zones and coordinators are set only at creation in this plan. If a
   coordinator is absent, faculty approve instead. Add edit endpoints only if a demo needs them.
