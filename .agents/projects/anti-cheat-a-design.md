# Design: Anti-cheat piece A — trust the phone and the face

Status: approved in chat 2026-10-11 (sections 1–3). Plan: `anti-cheat-a-plan.md`.
Order of the wider programme: **A (this) → B open events → duty zones + C risk score**.

## Goal and constraints

FieldPass stays a **demo** on a laptop hotspot. Everything here works **offline** in a **sideloaded APK**
(no Play Store, no Play Integrity, no internet, no cloud ML). Policy is **record + flag**: a red flag adds a
review reason and the event becomes `needs_review`; faculty still make every decision (decisions/log.md).

## 1. Device lock (Android Keystore signature)

- First student login sends an EC P-256 public key generated in the Android Keystore. The server binds it
  (`student_devices`: one row per student, public key unique).
- Later login with another key → `403 device_mismatch`. A key already bound to another student →
  `403 device_taken`. Student login with no key → `422 device_key_required`. Faculty logins are unchanged.
- Each attempt carries a server `nonce`. Submit is signed (SHA256withECDSA) over a canonical message that
  includes the nonce, both photo hashes, location and the integrity flags. Bad signature → `403 bad_signature`.
- Faculty reset: `POST /students/{id}/device/reset`, shown on a new dashboard **Students** page.

Worked example: Arjun binds his Redmi Monday; Tuesday he logs in on Meena's phone → "This phone is linked to
another student." He loses the Redmi → faculty press **Reset device** → his next login binds the new phone.

## 2. Fake-GPS and rooted-phone flags

- Phone sends `location_is_mock` (Location.isMock on API 31+, isFromMockProvider below), `device_rooted`
  (su/Magisk paths, test-keys), `device_emulator` (build traits). All inside the signature.
- Server adds `location_repeated` when this location's fingerprint (SHA-256 of lat/lng at 6 decimals,
  truncated; coordinates are never stored) equals an earlier event of the same student in the same activity.
- New reasons: `location_mocked`, `device_rooted`, `device_emulator`, `location_repeated`.

## 3. Blink + server-chosen head turn

- The attempt returns `challenge` = `left` | `right` (server random, stored on the attempt).
- Phone (bundled ML Kit face detection, offline): look straight → photo A; blink; turn head as asked → photo B.
- Server: photo A is face-matched as today. Photo B must hold one face, be the same person as A (compare not
  `no_match`), and its nose offset (nose x minus eye midpoint, divided by eye distance, from YuNet landmarks)
  must point the challenged way past `LIVENESS_TURN_MIN_OFFSET` (0.25) while A is within
  `LIVENESS_FRONT_MAX_OFFSET` (0.12). `LIVENESS_MIRRORED` flips the sign. Fail → reason `liveness_failed`.
- Blink is checked on the phone only. Photo B is never stored.

## Known limits (go into known-limitations.md)

A hacked app on a rooted phone can lie about the flags (the root flag and `location_repeated` are the backstop;
full cover needs Play Integrity). A replayed video with the right turn can pass. Thresholds and the camera
mirroring direction are starting values, calibrated with team photos before the demo. Reinstalling the app
needs a faculty reset.
