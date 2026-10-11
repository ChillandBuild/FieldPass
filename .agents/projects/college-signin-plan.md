# Plan: Student List Import and College Sign-in (Microsoft first)

Revision 2 (2026-10-11). Revision 1 was "Microsoft sign-in setup for FieldPass" (three Entra apps, manual
linking by object ID). Status: direction agreed, not started. Nothing below is built.

## Context

**Why.** FieldPass is entering a hackathon about problems at Amrita. If it is picked, it has to be connected to
Amrita quickly. So it must work with a college's own class lists and its own accounts, and connecting a college
must be a settings job, not a coding job.

**What is true today (checked in the code, 2026-10-11):**

| Fact | Where |
|---|---|
| A user has only id, username, full name, role, password hash. No email | `backend/app/models.py:17-23` |
| Users come only from a hard-coded seed list (`student1`, `faculty1`, ...) | `backend/app/seed.py` |
| Every user must have a password hash | `backend/app/models.py:23` |
| Login is username + password; students also bind one phone | `backend/app/routers/auth.py:30-46` |
| `roster` already means "students on one activity" | `backend/app/models.py:74-77` |
| New tables appear on startup; new columns on old tables do not | `.agents/projects/duty-zones-plan.md` decision 7 |

**Agreed idea (2026-10-11):**
1. **The student list is the gate.** Faculty upload a class list. Only people on it can get in. This part is
   the same for every college.
2. **Sign-in is a swappable part.** It answers "is the person typing really that student?". Microsoft is the
   first one, because Amrita already gives every student a Microsoft account.
3. **Nothing college-specific in code.** Amrita's tenant ID, client ID and email domains are settings.
4. **One running copy per college.** No "many colleges in one database".
5. **Password login stays** for the demo and as a fallback, with a switch to turn it off.

**Changes from revision 1:**

| Revision 1 | Revision 2 | Why |
|---|---|---|
| Three Entra app registrations | One registration (dashboard callback + Android callback + `access_as_user` scope) | A third of the ask to college IT. Roles come from our database, so we lose nothing |
| Someone links each account by object ID with a command | Auto-link on first sign-in by matching the list email; command kept for repairs | Nobody at a college has a list of object IDs |
| Users assumed to exist already | Student list import added | Without it the first real student matches nobody |
| Amrita-specific | Any college, by settings | Same code, different tenant ID |

### The scenario every section below refers to

Prof. Meera (faculty) teaches a class of 60. Asha Verma is on her list as `asha.verma@<college-domain>`.
Ravi is a student of the same college who is **not** on the list.

## 1. The student list (the gate)

**Mechanism.**
- The username **is** the college email, stored lower-case. No new column, no change to the `users` table.
- New endpoint `POST /api/v1/students/import` (faculty only). Takes a CSV file with two columns: `email`,
  `full_name`. Creates **students only**. Limits: 1 MB, 2000 rows.
- An imported student gets a password hash made from a random secret that is thrown away. So the row is valid,
  but nobody can ever log in to it with a password.
- Optional setting `ALLOWED_EMAIL_DOMAINS`: rows outside these domains are rejected.
- The answer lists what happened: created, already present, rejected (line number + reason).
- Faculty accounts are **never** made from a CSV. A backend command makes them:
  `python -m app.manage add-faculty --email ... --name ...`. Otherwise one upload could mint faculty.
- New dashboard screen "Students": upload the file, see the result, see the list.

**Worked example.** Prof. Meera uploads `class.csv` with 60 rows. Row 12 has no `@`. Asha is already there from
last week. Answer: "58 added, 1 already on the list, 1 rejected (line 12: not an email address)". She fixes
line 12 and uploads again: "1 added, 59 already on the list". Uploading twice never duplicates anyone.

**But what if...** a student leaves the class? Out of scope for now: no delete. Faculty simply do not put them
on an activity. (Deleting a user who has attendance history would break the audit rows.)

## 2. Microsoft sign-in (the first swappable part)

**Mechanism.**
- New endpoint `POST /api/v1/auth/microsoft`. Request: Microsoft `access_token`, optional
  `device_public_key`. Response: the existing FieldPass login response. Added to `contracts/API.md` first.
- Render checks the token: signature, issuer, expiry, tenant ID equals the configured one, audience equals our
  API, scope `access_as_user`. Signing keys are cached, fetched with short timeouts, refreshed on key rotation.
- Rate-limited like password login. Tokens never appear in logs.
- New table `microsoft_identities` (`tenant_id`, `object_id`, `user_id`). One Microsoft account per user, one
  user per Microsoft account. RLS on, public roles revoked, like every other table.
- **Linking.** First look up tenant ID + object ID in the table. If absent, take the sign-in name from the
  token, lower-case it, and find a user with that username who has no link yet. If found, save the link. From
  then on only the IDs are used. The role always comes from the user row, never from the token.
- Students still bind one phone, exactly as today.
- Repair commands: `python -m app.manage unlink-microsoft --email ...` and `link-microsoft`.
- Settings: `MICROSOFT_LOGIN_ENABLED` (default off), `MICROSOFT_TENANT_ID`, `MICROSOFT_API_CLIENT_ID`,
  `PASSWORD_LOGIN_ENABLED` (default on).

**Worked example.**
1. Asha taps "Sign in with Microsoft" on her phone and uses her college account.
2. The app sends the token and her phone key to Render.
3. Render checks the token. Tenant matches. No link for her object ID yet.
4. The token's sign-in name is `asha.verma@<college-domain>`. That username is on the list with no link.
5. Render saves the link, binds her phone, and returns a FieldPass session with role `student`.
6. Next week her college email changes. She still gets in: Render now matches her object ID, not the email.

**But what if...**
- **Ravi signs in (not on the list)?** Rejected: "This account is not on a class list. Ask your faculty."
- **Someone from another college signs in?** Rejected at the tenant check, before any list lookup.
- **Asha's old email is later given to a new student?** That username already has a link to Asha's object ID,
  so the new person is rejected until faculty run the unlink command.
- **The sign-in name is not the email on the list** (some colleges use an alias)? Rejected, and the message
  shows the sign-in name so faculty can correct the list. To verify while building: which claim Microsoft v2
  access tokens carry for this (`preferred_username` expected; confirm against Microsoft's docs).
- **Microsoft is unreachable?** A clear "try again" error, not "wrong account".

## 3. The two clients

- **Dashboard (Vercel):** MSAL Browser, authorization code flow with PKCE, a dedicated callback page. Faculty-only
  check kept. Button shown only when the Microsoft settings are present.
- **Android:** MSAL Android, callback tied to package name + signing certificate. Student-only check kept.
- Both handle: cancel, consent denied, no network, double tap.
- Tests stay Chromium-only for the dashboard.

**Worked example.** Asha (a student) opens the dashboard and signs in with Microsoft. Render returns role
`student`; the dashboard shows the existing "faculty only" message. Prof. Meera does the same on the student
app and gets "students only".

## 4. What a college has to do (the handover)

One page in `integration/`, written for a college IT admin:

```
1. Register ONE app in Entra            -> send back: tenant ID, client ID
2. Add two callbacks + one scope        (values we give them)
3. Approve it for the college           (one click, if their policy needs it)
```

Then we paste the two values into Render, Vercel and the Android build, and faculty upload a class list.
The page also says what is stored (name, email, enrolment photo, attendance) and where.

**Worked example (Amrita).** IT sends `tenant ID = 1111...`, `client ID = 2222...`. We set five settings, build
the APK, and switch `MICROSOFT_LOGIN_ENABLED` on. No code changes. A second college repeats the same three steps
with its own copy.

## 5. Demo flow (about 9 minutes: 1 new minute + the existing 8)

New opening, before the existing script in `integration/demo-script.md`:

| Time | Who | What the room sees |
|---|---|---|
| 0:00 | Prof. Meera | Dashboard, "Sign in with Microsoft", lands on the register |
| 0:15 | Prof. Meera | Students screen, uploads a 2-row CSV, "2 added" |
| 0:30 | Asha | Phone, "Sign in with Microsoft", she is in. No password was ever handed out |
| 0:45 | Ravi | Second phone, signs in, "not on a class list" |
| 1:00 | Presenter | "The list is the gate. The college account is the key." Then the existing script |
| end | Presenter | The handover page: "one registration, two values" |

**Which accounts.** Three levels, best first:
1. **Real Amrita accounts.** Works only if Amrita lets its users approve an app registered outside the college.
   Unknown. Test: register the app in our own free tenant as multi-tenant, sign in with one Amrita account.
2. **Test accounts in our own tenant.** Always works. Say so in the demo.
3. **Password login.** The existing script unchanged, if Microsoft or the network fails.

**Demo constraints to check early.** The backend needs internet to fetch Microsoft's keys. The dashboard callback
must be `https` or `http://localhost` (a laptop IP address will not be accepted). The Android callback depends
on the signing certificate, so the debug and release builds need separate callbacks.

## Build order

1. `contracts/API.md` addendum: both endpoints and their errors.
2. Backend: student list import + `add-faculty` command. Tests first.
3. Backend: Microsoft token check, `microsoft_identities`, auto-link, repair commands. Tests first, with
   locally made signing keys.
4. Dashboard: Students screen, Microsoft button, callback page.
5. Android: Microsoft button.
6. Docs: handover page, settings templates, demo script opening.

Deploy later in this order: Supabase table, Render, Vercel, Android. **Do not start the backend against the
real Supabase `DATABASE_URL` without asking**: startup would create the new table there.

## Acceptance

- Password login works unchanged with Microsoft off. With `PASSWORD_LOGIN_ENABLED` off it is refused.
- Import: creates students only, is safe to repeat, rejects bad rows with line numbers, refuses non-faculty.
- An imported student cannot log in with any password.
- A listed student and a command-made faculty member sign in with Microsoft and get the right role.
- Rejected: bad signature, expired, wrong tenant, wrong audience, missing scope, not on the list, username
  already linked to another object ID.
- Student Microsoft sign-in binds the phone and refuses a second phone.
- Students cannot enter the dashboard; faculty cannot enter the student app.
- `microsoft_identities` is unique both ways and locked down on Postgres.
- Backend tests; dashboard typecheck, lint, unit and Chromium login tests; Android unit tests and build.

Without a college registration, "done" means code, settings templates, docs and locally simulated security
tests. Live sign-in against a real college is a separate check.

## Not in this plan

Google sign-in, emailed one-time codes, many colleges in one copy, removing students, importing faculty by CSV,
syncing with a college's student-records system.
