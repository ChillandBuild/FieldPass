# FieldPass Student (Android)

Student app for FieldPass: enrol a face photo, check in, answer checkpoints and check out of field
activities. The backend is the only judge of attendance; this app only collects evidence and shows
the server's decision. The API contract is `../contracts/API.md`.

Stack: Kotlin 2.4, Jetpack Compose (Material 3), Navigation Compose, CameraX (front camera for the selfie,
back camera for the optional code scan; in-app capture only), ML Kit face detection and QR scanning (bundled
models), Fused Location Provider, Retrofit + OkHttp + kotlinx.serialization, coroutines.
Android Gradle Plugin 9.4.1, Gradle 9.6.1, compileSdk 37, targetSdk 36, minSdk 26. Versions live in
`gradle/libs.versions.toml`.

## Toolchain setup (macOS)

```bash
brew install --cask android-commandlinetools          # SDK root: /opt/homebrew/share/android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME platform-tools "platforms;android-37.0" "build-tools;36.0.0"
```

Needs JDK 17 or newer (JDK 21 is fine). Put the SDK path in `android/local.properties` (git-ignored):

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Or just export `ANDROID_HOME` in your shell profile. The Gradle wrapper (`./gradlew`) is included.

## Build and test

Run from `android/`:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests (state machine, errors, time, DTOs, API wiring)
./gradlew lintDebug            # report: app/build/reports/lint-results-debug.html
./gradlew assembleRelease      # release APK, signed if a keystore is configured (see Release build); cleartext HTTP is blocked
```

## Contract tests against the real backend

`app/src/test/java/com/fieldpass/student/integration/` holds JVM tests that drive the app's production
`ApiClient`/`ApiService`, `RealFieldPassRepository`, DTOs and `ErrorParser` against a live FastAPI
backend (health, login, enrollment, faculty approval, activities, attempt, submit, replay, history and
the error codes). They are skipped unless these environment variables are set, so
`./gradlew testDebugUnitTest` stays hermetic:

```
FIELDPASS_API_URL=http://127.0.0.1:8001   # a throw-away backend
FIELDPASS_PASSWORD=<SEED_PASSWORD of that backend: the student accounts>
FIELDPASS_FACULTY_PASSWORD=<FACULTY_SEED_PASSWORD of that backend: faculty1>
FIELDPASS_FACE_IMAGE=/path/to/one-face.jpg
```

Easiest way (starts its own backend on port 8001 with a temp database, real OpenCV face engine, seeds
accounts, runs the tests, then stops the server and deletes the temp data; secrets are never printed):

```bash
android/scripts/integration-test.sh          # optional port argument, e.g. 8002
```

The script makes two runs, each on a fresh backend on the same port, and exits non-zero if either fails:

1. Default settings: `BackendContractTest` (the 20 steps above).
2. `ATTEMPT_TTL_SECONDS=4` and `CHECKOUT_GRACE_MINUTES=0`: `TimingContractTest`, which also needs
   `FIELDPASS_SHORT_TIMING=1` (the script sets it; the test is skipped without it). It waits in real time on the
   server's own clock, with nothing faked, and checks that a submit after expiry maps to `ErrorCode.ATTEMPT_EXPIRED`
   (410, nothing recorded, then a fresh attempt is recorded), that a record with `missing_checkout: true` parses in the
   repository (`missingCheckout`, not complete, no next actions) once the activity has ended, and that a check-out
   attempt after the end maps to `OUTSIDE_ACTIVITY_WINDOW`.

The steps share state, so use a FRESH database each run (student1 must still have enrollment `none`);
the script does this for you. It uses `backend/tests/samples/lena.jpg` if present, otherwise downloads
OpenCV's `lena.jpg`. Not covered on the JVM: CameraX, `JpegProcessor` (Bitmap), the Keystore token
store and the fused location provider; see Known limitations.

## Install on a phone

1. On the phone: Settings > About phone > tap Build number 7 times, then Settings > System >
   Developer options > turn on USB debugging.
2. Connect with USB and accept the "Allow USB debugging" prompt.
3. From `android/`:

```bash
adb devices                                   # the phone must be listed as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release build (signed APK to share)

Students install a signed APK by tapping the file. It talks to the hosted backend over https, so it works on any
network. Deploy the backend first (`../backend/README.md`, "Hosting"); you need its address, for example
`https://fieldpass-api.onrender.com`. The Play Store is not used.

**1. Create the keystore once** (JDK 17 or newer; if an old JDK is first on your PATH, call the new one by its full
path). Keep it outside the repo; `*.keystore`, `*.jks` and `android/keystore.properties` are git-ignored.

```bash
keytool -genkeypair -v -keystore ~/fieldpass-release.keystore -alias fieldpass \
  -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a store password, a key password and your details. **Back up the keystore file and both passwords in
two places** (for example a password manager and an offline copy). Android only installs an update over an
installed app if both are signed by the same key. If you lose the keystore, every student must uninstall the app and
install a new build; the uninstall deletes the phone's signing key, so faculty must also press "Reset phone" for each
student. Never commit it or paste its passwords into chat, an issue or a build log.

**2. Tell Gradle where it is**, either with environment variables:

```bash
export FIELDPASS_KEYSTORE=/path/to/fieldpass-release.keystore   # absolute path
export FIELDPASS_KEYSTORE_PASSWORD=...
export FIELDPASS_KEY_ALIAS=fieldpass
export FIELDPASS_KEY_PASSWORD=...
```

or with an untracked `android/keystore.properties` (use forward slashes on Windows):

```
storeFile=C:/keys/fieldpass-release.keystore
storePassword=...
keyAlias=fieldpass
keyPassword=...
```

Environment variables win over the file. With none set, `assembleRelease` still produces the unsigned APK, which
phones refuse to install. With only some set, the build stops and names the missing ones.

**3. Build**, from `android/`:

```bash
./gradlew assembleRelease -PfieldpassApiBaseUrl=https://fieldpass-api.onrender.com
# -> app/build/outputs/apk/release/app-release.apk (signed)
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The build refuses a missing or non-https address. `versionCode` and `versionName` (`0.1.<n>`) are derived from git:
`<n>` is the number of commits that touched `android/`, so every committed app change bumps the version automatically
and every clone at the same commit builds the same one. Commit before you build an APK you hand out: a phone will not
install an APK over an installed one with an equal or lower `versionCode`, and uncommitted edits do not bump it.

**4. Share the `.apk` file** (chat, drive, link). On the phone, open it and allow "install unknown apps" for the app
that opened it. A debug build and a release build have different signatures, so uninstall one before installing the
other (this also makes a new phone key; see Behaviour summary).

The hosted backend on Render's free plan sleeps after 15 minutes without traffic, and the first request then takes
about a minute, so the first login or check-in after a quiet spell can fail. Open the backend's `/api/v1/health`
address in a browser first, or just retry. The release build, the signing and the whole hosted path have not been
run on a physical phone yet.

## Server address

The student login intentionally contains only username and password. Configure the server when
building; the value is baked into `BuildConfig.DEFAULT_API_BASE_URL`:

```bash
./gradlew assembleDebug -PfieldpassApiBaseUrl=http://192.168.43.10:8000
```

You can also set `fieldpassApiBaseUrl` in `gradle.properties`. Rebuild and reinstall the app when the
laptop's hotspot address changes. Always pass the real HTTPS deployment URL for release builds; the
LAN default is for local development only.

Plain `http://` works in debug builds only (debug network security config). The release build
forbids cleartext traffic, so a release build needs an `https://` server.

Demo accounts come from the backend seed (`student1`, `student2`, `student3`; password is
`SEED_PASSWORD` in `backend/.env`). A faculty account is told to use the dashboard.

## Fixture mode (no server)

Debug builds only. Build with `-PfieldpassFixtureMode=true` to use an in-memory fake instead of the
server. Every screen can then be tried with sample data: any non-empty password works, and usernames
starting with `faculty` show the faculty message. A red "FIXTURE MODE: sample data" banner stays
visible on every screen. Release builds cannot enable fixture mode.

## Behaviour summary

- Token stored AES-256-GCM encrypted with a non-exportable Android Keystore key. No tokens,
  passwords or images are logged; there is no HTTP logging interceptor.
- Location is read only during an attendance attempt, using a fresh high-accuracy request with a
  15 second timeout (no last-known fix, no background access). Camera permission is asked only when
  capturing.
- Photos are kept in memory only (never written to disk), rotated upright, scaled to a 1280 px long
  edge, JPEG quality 85, and wiped after upload or cancel.
- A lost response retries the same attempt ID; a new attempt is created only after the user chooses
  "Start again" following expiry.
- First login creates a signing key inside the phone's Keystore and links this phone to the student.
  Every check-in is signed with it. Reinstalling the app or clearing its data makes a new key: faculty
  must press "Reset phone" on the dashboard's Students page before the student can log in again.
- Check-in is guided: look straight (photo), blink, turn your head to the side shown (second photo).
  Face detection for the guide runs on the phone with the bundled ML Kit model — no internet, no Play
  Store download. The second photo is checked by the server and never stored.
- Rotating presence code (optional): after the location fix, the app opens the back camera and asks the student to
  scan the QR on the faculty screen (it changes every 10 s). A scanned string that starts with `fp1.` is sent once to
  `POST /api/v1/attempts/{id}/presence`; the server says whether it was fresh. Skipping, a too-old code, a network error
  or a phone with no back camera never blocks the selfie and submit. The step has no timer of its own; the attempt's
  90 s countdown keeps running. A verified scan shows as "Code scanned" on the result and in history.
- The app reports three flags with each check-in: fake-GPS location, rooted phone, emulator.

## Known limitations

- The blink and head turn are not liveness detection. A replayed video with the right turn may pass.
- The guided camera uses preview, photo and frame analysis together; a few very old phones cannot.
  They fall back to "Take one photo instead", which faculty see as "Head-turn check failed".
- The head-turn direction constant (`YAW_SIGN_FOR_LEFT = 1f` in `LivenessGuide.kt`) is a guess and the server
  thresholds (front 0.12, turn 0.25, `LIVENESS_MIRRORED`, `TURN_MIN_YAW_DEG`) were never calibrated: the guided
  check-in and the phone lock have not been run on a real phone or a real Keystore.
- QR scanning has only been run on the JVM (state machine and API wiring). The scanner itself has not been tried against
  a real faculty screen on a physical phone.
- Location can be spoofed on the phone.
- Recorded times are points of observation, not proof of continuous presence.
- Not yet run on a physical device. The HTTP/repository layer has been run on the JVM against the real
  backend (see Contract tests); camera, Bitmap processing, Keystore and location are untested on hardware.
- Run end to end on an API 36 emulator (2026-10-10) against a real backend with the real face engine: login,
  camera and location permission refusal and recovery, enrollment and approval, check-in, check-out, history
  refresh after a faculty decision, and the 90 s attempt expiry ("This attempt timed out", nothing recorded on
  the server). The emulator camera fed a fixed test image, so this says nothing about real selfies, rotation
  or indoor GPS. Screenshots stay local (`android/screenshots/emulator/` is git-ignored because they show a face).
- `location_captured_at` is derived from the phone's clock minus the fix's age. A badly wrong phone
  clock could cause `location_stale` from the server.
- Faculty features (review, observations, roster) are not in this app; use the dashboard.
