# FieldPass demo script (about 8 minutes)

A spoken walkthrough of the eight scenarios in [checklist.md](checklist.md). Run the demo from
[runbook.md](runbook.md). Fill in the "Seen" and "Staged?" columns of the checklist as you go, or have
the Presenter do it right after.

## People and screens

| Role | Does what | Screen |
|---|---|---|
| Presenter | Talks, points at screens, keeps time | Projector or shared screen (usually the dashboard) |
| Phone operator | Plays the students | Android phone. Mirror it or hold it up so the audience can see it |
| Faculty operator | Plays Dr. Meera Rao (`faculty1`) | Laptop, dashboard at `http://localhost:5173` |

One person can hold two roles, but the 90-second wait in section 3 works best with two people.

Volunteers: two consenting people. **Volunteer A** is enrolled as `student1` (Asha Verma). **Volunteer B**
is enrolled as `student2` (Rohan Iyer). `student3` (Fatima Khan) is on the roster but never enrolled, so her
row stays empty and a check-in attempt would give `enrollment_not_approved`.

## Before you start (not timed)

Do these at least 40 minutes before the demo. Never skip the labels in the "Staged" section.

1. Complete the runbook, steps 1 to 11. Fixture mode off on both clients.
2. Enrol and approve both volunteers.
   - Phone: sign in as `student1`, open "My photo", "Take my photo", "Send for approval". Repeat as `student2`
     with Volunteer B.
   - Dashboard: Enrollments, tick "I have confirmed this person's identity", press "Approve photo".
3. **Activity A, "Demo Site Visit"**: created by the seed command with the room's real coordinates and a
   100 m radius (runbook step 4). Roster: all three students. Starts when you run the command, lasts 4 hours
   from then. Run it on the day, and make sure the demo ends before those 4 hours are up.
4. **Activity B, far venue** (for the poor-location scenario). Dashboard, Activities, "New activity". Name
   it clearly, for example "Far venue (staged)". Set the latitude about 0.01 degrees away from the room's
   latitude (roughly a kilometre) and keep the room's longitude. Radius 100. The form starts with a start 30
   minutes from now: change the start to now, and the end to about 4 hours later. Roster: `student1`.
   The server computes `distance - accuracy > radius` for "outside", so any phone accuracy under about 1000 m
   gives "outside" here.
5. **Activity C, short activity** (for the missing-checkout scenario). Create it at least 40 minutes before the
   demo: dashboard, "New activity", name it "Short activity (staged)", the room's real coordinates, radius 100,
   start 5 minutes ago, end 5 minutes from now, roster: `student1`. The server accepts a start time in the
   past, so you can shorten the wait this way. Then:
   - Within those 5 minutes (check-in closes at the end), as `student1` on the phone, check in to Activity C.
     Do **not** check out.
   - Check-out is accepted until 30 minutes after the end, so the window closes about 35 minutes after you
     created the activity. After that the register shows "Missing checkout: incomplete" for this row.
   - Do not make the end earlier than the moment you check in. Check-in closes at the end, so you could not check in.
6. Do one full rehearsal of the phone steps. The exact app screens for a check-in are: pick the activity,
   Check in, wait for "Getting your current location", take a selfie, press Send. Accept the location and camera
   permission prompts during this rehearsal. A student can check in once and check out once per activity, so a
   rehearsal uses them up. Rehearse on a spare activity, or run the `--demo-activity` seed command again
   afterwards to get a fresh "Demo Site Visit" for the real run. (Two activities with the same name are
   confusing; check which one you open.)

## Staged scenarios: what you must say

These scenarios cannot be shown completely live and honestly in 8 minutes. Say so out loud when you show them,
and write "Yes" in the "Staged?" column of the checklist with the note below.

| # | Scenario | How it is staged | Label to say and write |
|---|---|---|---|
| 2 | Wrong face | Volunteer A signs in as `student2` and takes the selfie. Student2's approved photo is Volunteer B's face | "Staged: a different person took the selfie on purpose" |
| 3 | Expired attempt | The phone operator starts a check-in and then waits more than 90 seconds on purpose. The app shows its own timeout; the server is not asked | "Staged: we waited on purpose" |
| 4 | Duplicate submission | Double-tap Send. The app blocks the second tap, so the server never sees a duplicate. A real lost reply cannot be forced live | "Staged: deliberate double tap. The app blocked the second tap; this is not a network drop" |
| 5 | Poor location | Activity B's venue is set about a kilometre from the room, so a student in the room is "far" | "Staged: the venue was set far away on purpose" |
| 6 | Missing checkout | Activity C was created and checked into before the demo, then left alone until its check-out window closed | "Prepared in advance, using a short activity. Real server rules, real wait, not shown live" |
| 8 | Faculty correction | The decision is flipped to show history, using a reason that says so | "Staged: a rehearsed correction" |

Scenarios 1 and 7 are shown live and unstaged. Do not say anything stronger than that about the others.

## The script

Times are cumulative. If you are behind, drop the "double tap" in section 2 first, then shorten section 6.

### 1. Opening (0:00 to 0:40) - Presenter

On screen: the dashboard "What this proves" page.

Say: "FieldPass records evidence that a student was at a field activity. The student's phone sends a selfie and
a location. The server compares the selfie with a photo faculty approved earlier, and checks the location
against the venue. It never decides by itself. It marks the result as Recorded or Needs review, and faculty
confirm or decline. Three things this does not prove. A camera selfie is not liveness detection, so a photo of
a photo might pass. The location comes from the phone and can be altered. And times are moments when we
looked, not proof someone stayed. Everything you see today is a prototype."

### 2. Scenario 1 and 4: the normal path, plus a double tap (0:40 to 3:30)

Phone operator: signed in as `student1` (Volunteer A). Faculty operator: dashboard open on the register for
"Demo Site Visit" (Activities, "Open register").

1. **Check in (0:40).** Phone: open "Demo Site Visit", Check in. The screen says "Getting your current location",
   then asks for a selfie. Take it. Press Send.
   - Presenter: "The phone has a 90-second clock on this attempt. It sends the selfie and where it is."
   - Audience should see: the phone says "The server has recorded your attempt". Within about 5 seconds, the
     dashboard register row for Asha Verma shows an arrival time and a status badge, "Recorded" if location and
     face passed.
2. **Open a checkpoint (1:40).** Faculty operator: on the register press "Open checkpoint window" (default 10 minutes).
   - Phone: "Answer checkpoint", selfie, Send. The register shows the last recorded checkpoint.
   - Presenter: "A checkpoint is another observation in the middle. It is a point in time, not a recording
     of everything between."
3. **Check out with a double tap (2:20).** Phone: Check out, selfie, then tap Send twice quickly.
   - Presenter (scenario 4): "I tapped twice. The register must show one check-out, not two."
   - Audience should see: the button changes to "Sending" after the first tap, and exactly one departure time on
     the register.
   - Say honestly: the app ignores the second tap (the Send button is disabled as soon as the first tap is
     accepted), so the server receives one request. The server's own replay rule (it returns the saved event
     for a repeated submit) is covered by the backend tests and the dashboard end-to-end test, not by this tap.
     If a real lost reply happens, the app offers "Send again" with a note that it will not create a second record.
4. **Faculty confirms (2:50).** Faculty operator: on Asha Verma's row press "Confirm or decline", type a reason
   (for example "Seen on site"), press "Confirm attendance". The row shows Confirmed, the reason, the faculty
   name and the time.
   - Phone: open History. It shows Confirmed.
   - Presenter: "Only a person produced that word."

### 3. Scenario 2: wrong face (3:30 to 4:40)

Phone operator: sign out, sign in as `student2`. Hand the phone to **Volunteer A**.

- Phone: "Demo Site Visit", Check in, Volunteer A takes the selfie, Send.
- Presenter (staged label first): "This is staged. Rohan's approved photo is Volunteer B. Volunteer A is taking
  the selfie, so the face should not match."
- Audience should see: on the phone, a calm result with no accusation wording; on the dashboard, Rohan
  Iyer's row shows "Needs review" with the face reason in plain words. In the API the reason is `face_mismatch`.
- Presenter: "Needs review is routine, not an accusation. A person decides."

### 4. Scenarios 3, 7 and 8 together, while the clock runs (4:40 to 6:30)

The expiry needs 90 seconds. Use that time on the laptop.

Phone operator: sign out, sign in as `student1`. Open "Far venue (staged)" and press Check in. **Then stop.**
The app creates the attempt on the server the moment this screen opens, so the 90 seconds start now, not when
you take the selfie. Hold the phone up so the audience sees the "Time left for this attempt" countdown.
Do not take a selfie. Start the clock now.

Meanwhile, Faculty operator on the laptop:

- **Scenario 7: a student tries a faculty operation (about 30 s).** Sign out of the dashboard. On the login
  screen enter `student1` and its password. The dashboard signs you out with "This dashboard is for faculty".
  Presenter: "A student account cannot use the faculty screens. The server also refuses the calls: the API
  answers 403 `forbidden`." Then sign back in as `faculty1`.
- **Scenario 8: a faculty correction (about 40 s).** Open Demo Site Visit's register, Asha Verma's row, "Confirm or
  decline", reason "Staged correction to show history", press "Decline attendance". Presenter (staged label):
  "I changed my mind on purpose. The new decision shows its reason, my name and the time. The earlier Confirmed
  decision is not erased; it stays in the history."

At about 6:15 the countdown on the phone reaches zero. Phone operator: do nothing. The app shows its own timeout
by itself; it does not wait for you to press Send, and it throws away any selfie already taken.

- **Scenario 3.** Audience should see: the phone says "This attempt timed out. Attempts last about a minute and a
  half. Nothing was recorded." and offers "Start again". The register for the far-venue activity has no new event.
  Presenter (staged label): "We waited on purpose. The phone's own clock ran out, so the server was not asked.
  The server also refuses a late submit with attempt_expired; that is covered by tests, not shown here."

### 5. Scenario 5: poor location (6:30 to 7:15)

Phone operator: tap "Start again", take the selfie, Send. The student is in the room, but the venue is a
kilometre away.

- Presenter (staged label): "The server says: phone is far from the venue. It is not 'inside'."
- Audience should see: the dashboard row for the far-venue activity shows "Needs review" with a location reason
  (API: `location_outside`). The face can still be a match.
- If the app asks about precision first ("Your location is not very precise"), choose "Submit anyway (may need faculty
  review)". That is also a valid example.

### 6. Scenario 6: missing checkout (7:15 to 7:45)

Faculty operator: open the register of "Short activity (staged)".

- Presenter (label first): "I prepared this about forty minutes ago. Asha checked in, then never checked
  out, and the check-out window has closed."
- Audience should see: the row shows "Missing checkout: incomplete". There is a check-in time, no departure, and no hours.
- Presenter: "We never guess a departure time or a duration."

### 7. Close (7:45 to 8:00) - Presenter

Say: "To recap: Recorded and Needs review come from software. Confirmed and Not confirmed come from a person.
Selfies are not liveness detection, location can be altered, and times are moments, not presence. We have
not run this on a large group, and the face threshold is the OpenCV default, not tuned on a test set." Point to
[known-limitations.md](known-limitations.md).

After the demo, finish [checklist.md](checklist.md) and follow "After the demo" in [runbook.md](runbook.md).

## Fallback plan

Decide the cut-off in advance: if a problem is not fixed in 2 minutes, move to the next step below.
Whatever you fall back to, tell the audience what they are looking at. Never present sample data or a
recording as the live system.

### The hotspot fails or the phone cannot reach the laptop

1. Turn the hotspot off and on. Rejoin the laptop and the phone. Run `ipconfig getifaddr en0` again.
2. If the address changed: on the dashboard login screen, type the new "Server address" and press "Test connection";
   in the app login screen, do the same. If you restarted nothing else, the backend keeps running. If the
   dashboard is on another laptop, update `CORS_ORIGINS` and restart the backend (runbook step 8).
3. Check `http://<laptop-ip>:8000/api/v1/health` in the phone's browser before trying the app.
4. Still failing: use the troubleshooting table in the runbook (client isolation, firewall, binding).
   Try a different phone as the hotspot.
5. Still failing after the cut-off: switch to the walkthrough below.

### GPS fails or is very inaccurate

- No fix at all: the app shows "We need a location reading" and "Open location settings". Turn location on,
  go near a window or outdoors, and try again.
- Poor accuracy (indoors): see "The location rule" in the runbook. Move near a window and use "Try again for a better
  fix". If the result is still `needs_review` with a location reason, that is a legitimate result. Show it,
  explain `d + a <= r` with the numbers on screen, and say it is the rule working. Do not hide it.
- If every check-in is flagged, create a new activity with a larger radius (for example 200 m) in the dashboard and
  say that you did so and why. A larger radius is a weaker check.
- Do not use a mock-location app to fake a good position. That would be the exact manipulation this system
  cannot detect.

### Walkthrough without a live system

Use only if the live demo cannot run.

1. Dashboard fixture mode: run `VITE_USE_FIXTURES=true npm run dev` in `dashboard/`. A yellow banner
   "FIXTURE MODE: sample data, not the real server" shows on every screen. Sign in as `faculty1` with any password.
   It has examples of recorded, needs review, confirmed, not confirmed, a missing checkout and an outdated decision.
2. Android fixture mode (debug build only): turn on "Fixture mode (sample data, no server)" on the login screen.
   A red banner "FIXTURE MODE: sample data" stays visible.
3. Say: "This is sample data on made-up students. It shows the screens, not a real check."
4. Real evidence from earlier: `dashboard/screenshots/real-register-1440.png` and `real-register-320.png`
   were written by the end-to-end test against the real backend. A script played the student's phone in that
   run, not a real phone. Say that too.
5. Leave the "Seen" column of the checklist blank for any scenario you did not run live.

