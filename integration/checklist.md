# FieldPass demo checklist

Run on the demo hotspot with the real backend, a real phone, real face comparison, and real
phone location. Fill in "Seen" with what actually happened. Anything staged or simulated must
be labelled as such in the "Staged?" column.

| # | Scenario | Expected | Seen | Staged? | Pass |
|---|---|---|---|---|---|
| 1 | Enrollment approved → check-in → checkpoint → check-out → faculty confirms | Each step shows on the register within about 5 s; final status `confirmed`; student history shows `confirmed` | | | |
| 2 | Wrong face (a different person takes the selfie) | Event is `needs_review` with "face comparison did not match"; no accusation wording on the phone | | | |
| 3 | Expired attempt (open the check-in screen and wait until the 90 s countdown reaches zero) | The app stops the attempt itself, explains it expired and offers to start again; no event recorded. The server's own `attempt_expired` answer is covered by backend tests, not shown live | | | |
| 4 | Duplicate submission (double tap on Send) | The app disables the button after the first tap, so one request is sent; exactly one event on the register. The server's replay rule (same attempt sent twice returns the same event) is covered by automated tests, not by this tap | | | |
| 5 | Poor location (far from the venue, or very low accuracy) | Event is `needs_review` with a location reason | | | |
| 6 | Missing checkout (check in, never check out, window closes) | Row shows "Missing checkout: incomplete"; no hours invented | | | |
| 7 | Student tries a faculty operation | Dashboard refuses a student login; API returns 403 `forbidden` | | | |
| 8 | Faculty correction | New decision with its reason, actor and time is shown; the earlier decision stays in the history | | | |

## Before the demo

- [ ] Backend laptop, dashboard laptop and phone are all on the demo hotspot (not campus Wi-Fi).
- [ ] Backend started on `0.0.0.0:8000`; `GET /api/v1/health` answers from the phone's browser.
- [ ] macOS firewall allows incoming connections to the backend.
- [ ] Dashboard and phone both point at the backend's current hotspot address.
- [ ] Fixture mode is OFF on the dashboard and the phone (no fixture banner visible).
- [ ] Enrollment photos are from consenting people; delete `backend/data/` after the demo.
- [ ] Demo activity venue is set to the actual demo room and tested there (indoor GPS accuracy).
