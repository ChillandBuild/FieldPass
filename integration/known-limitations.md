# FieldPass known limitations

This is an honest list of what FieldPass does not do and what has not been checked. Show it to anyone
deciding whether to rely on the system. Each item says whether it is a design limit or something not
yet tested.

## What the system cannot prove

1. **No liveness detection.** The app takes the selfie with the camera, but the backend cannot tell a real
   face from a photo of a photo. A picture shown to the camera may pass. Nothing in the app or the API claims
   otherwise, and the API has no liveness field.
2. **Location can be faked.** The location and its accuracy figure are reported by the phone. Someone can alter
   them on the phone. The backend only compares the reported position with the venue.
3. **Times are points of observation.** A check-in, a checkpoint and a check-out show three moments. They do
   not show the student stayed between them. The system never calculates hours.
4. **Faculty decide.** The software only produces `recorded` or `needs_review`. `confirmed` and `not_confirmed`
   come from a faculty member.

## Security and deployment

5. **Plain HTTP, on a private hotspot only.** Traffic between the phone, the dashboard and the backend is not
   encrypted. Passwords, tokens and selfies cross the network in the clear. Use only a network you control, and
   do not use real student data. The Android release build refuses plain HTTP; only the debug build works.
6. **One node, no backup.** By default the data is in one SQLite file on one laptop. With `DATABASE_URL` set it is in
   Supabase Postgres and the encrypted photos are in a private Supabase bucket (see `backend/README.md`, "Hosting").
   Either way it is one backend process with no replication and no backup of our own, and the Supabase free plan
   pauses an idle project. Render's free plan sleeps after 15 minutes, so the first request after a quiet spell
   takes about a minute. Memory on Render is unmeasured.
7. **No password reset or account management.** The four demo accounts come from the seed command. There is no way to
   reset a password, add a user or remove a user from the app or the dashboard. A password change is made by
   editing `backend/.env` and running the seed command again. Faculty and students use different seed passwords
   (`FACULTY_SEED_PASSWORD` and `SEED_PASSWORD`), which must differ.
8. **The login rate limit is held in memory.** The limit is 10 login tries per minute for one username from one
   address, and 30 per minute for one address, over a sliding 60 second window; over that, the server answers
   `429 too_many_attempts`. It resets when the backend restarts, so restarting clears it. It is not shared across
   several processes. It counts by the address the server sees.
9. **Biometric data is stored.** Enrollment photos (encrypted on disk) and face embeddings (in the database) are
   biometric data. They stay until someone deletes `backend/data/`. See "After the demo" in the runbook. Only people
   who have agreed may be enrolled. Losing `PHOTO_ENCRYPTION_KEY` makes stored photos unreadable.

## Face comparison

10. **The match threshold is a library default, not measured by us.** The cosine-similarity threshold 0.363
    (`FACE_MATCH_THRESHOLD`) comes from OpenCV's sample code. It has not been evaluated on a set of volunteers.
    We do not know the real false-accept or false-reject rate for our camera, lighting and people.
11. **The "uncertain" margin is a guess.** `FACE_UNCERTAIN_MARGIN` (0.1) is our own choice, not from OpenCV. It
    decides how wide the band between `match` and `no_match` is, and it has not been validated.
12. **Same-person matching was tested thinly.** The same-person check was run with one photo re-cropped, not
    with two separate photos of the same person. The automated test suite uses a fake face engine. The
    opt-in real-engine test expects two separate photos of one person (`person_a_1.jpg`, `person_a_2.jpg`) plus
    one of a different person in `backend/tests/samples/`, and that has not been done with a real volunteer set.
13. **No fairness testing.** We have not checked whether the matcher works equally well for different skin tones,
    ages, glasses, head coverings or lighting.

## Not verified end to end

14. **Missing checkout and attempt expiry** are verified by backend unit tests with a controllable clock, not by an
    end-to-end run. The dashboard end-to-end test does not cover them because they depend on the server clock. In
    the demo they are shown as staged scenarios.
    The server's refusal of a late submit (`attempt_expired`) is tested the same way. The phone app also expires
    an attempt by itself when its own 90 second countdown ends, so in the demo the server is not asked.
    The app blocks a second tap on Send, so the demo double tap does not exercise the server's replay rule; that
    rule is covered by the backend tests and the dashboard end-to-end test.

15. **The dashboard's stale-enrollment message is not covered by the browser end-to-end test.** When faculty try to
    decide an enrollment that the student has since replaced, the dashboard shows "This student uploaded a new photo.
    The list has been refreshed." This is unit-tested (`dashboard/tests/enrollmentStale.test.tsx`) but the
    Playwright end-to-end run does not reach that path.
16. **Dashboard tested in Chromium only.** The end-to-end tests and screenshots use Playwright's Chromium. Firefox
    and Safari were not tested.
17. **The Android app has not been run on a physical phone.** The HTTP and repository layers have been run on the
    JVM against the real backend. These are unverified on hardware: camera orientation (front camera preview and
    the saved selfie being upright), permission prompts, how long a GPS fix takes, the Android Keystore token
    store, and connectivity over a phone hotspot. Treat the first full run as a test, not a demo.
    The setup steps were also walked through from a fresh clone on 2026-10-10 (see "Verified on" at the top of
    the runbook), but only up to the point where a phone is needed. The macOS firewall in its enabled state and
    hotspot client isolation have not been tried.
18. **Phone clock matters.** The app derives `location_captured_at` from the phone's clock minus the age of the fix.
    A badly wrong phone clock can cause `location_stale` from the server.

## Scope

19. **Faculty features are only in the dashboard.** The Android app is for students: review, observations and
    rosters are not there.
20. **No indoor-location handling.** The location rule uses distance and reported accuracy. Indoors, accuracy is
    often large, which produces `location_uncertain` for students who really are in the room. The radius of an
    existing activity cannot be edited through the API contract; create a new activity instead.
21. **Prototype.** This was built for a demo. It has had no external security review or load testing.
22. **A modified app on a rooted phone can lie about the phone flags.** The fake-GPS, rooted and emulator flags
    come from the app itself. The rooted flag and `location_repeated` are the backstop. Full cover needs Google
    Play Integrity, which needs internet and Play Store distribution; this demo runs offline from a sideloaded APK.
23. **A replayed video with the right head turn can pass.** The blink is checked on the phone only; the server
    checks the turn direction on a second photo.
24. **Head-turn thresholds are starting values.** `LIVENESS_FRONT_MAX_OFFSET` (0.12), `LIVENESS_TURN_MIN_OFFSET`
    (0.25) and `LIVENESS_MIRRORED` are calibrated with team photos before the demo (`scripts/nose_offset.py`).
25. **Reinstalling the app or clearing its data needs a faculty reset.** The phone key lives in the Android
    Keystore and is lost with the app's data; the dashboard Students page has **Reset phone**.
26. **The QR presence code can be relayed.** The code on the faculty screen changes every 10 seconds and is accepted
    for up to 20. A photo of the screen sent to a friend within that time counts as a scan. So a scan only clears
    `location_uncertain`, and only when the phone's reported accuracy is 150 m or better (`PRESENCE_CODE_MAX_ACCURACY_M`);
    `location_outside` is never cleared, and a missing scan never blocks anyone. A relayed code plus a faked location
    close to the venue still passes: the scan does not replace the location check.

Where this list and `contracts/API.md` or the per-part READMEs disagree, fix the document that is wrong and tell the team.
