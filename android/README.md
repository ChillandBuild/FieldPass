# FieldPass Student (Android)

Student app for FieldPass: enrol a face photo, check in, answer checkpoints and check out of field
activities. The backend is the only judge of attendance; this app only collects evidence and shows
the server's decision. The API contract is `../contracts/API.md`.

Stack: Kotlin 2.4, Jetpack Compose (Material 3), Navigation Compose, CameraX (front camera, in-app
capture only), Fused Location Provider, Retrofit + OkHttp + kotlinx.serialization, coroutines.
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
./gradlew assembleRelease      # unsigned release APK (cleartext HTTP is blocked in release)
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

## Server address

The laptop's hotspot address changes, so there are two ways to set it.

- Build time default (baked into `BuildConfig.DEFAULT_API_BASE_URL`): edit `fieldpassApiBaseUrl` in
  `gradle.properties`, or build with `./gradlew assembleDebug -PfieldpassApiBaseUrl=http://192.168.43.10:8000`.
- At runtime: on the login screen edit "Server address" and tap "Test connection". It calls
  `GET /api/v1/health` and the address is saved on the phone. No rebuild needed.

Plain `http://` works in debug builds only (debug network security config). The release build
forbids cleartext traffic, so a release build needs an `https://` server.

Demo accounts come from the backend seed (`student1`, `student2`, `student3`; password is
`SEED_PASSWORD` in `backend/.env`). A faculty account is told to use the dashboard.

## Fixture mode (no server)

Debug builds only. Turn on "Fixture mode" on the login screen (or build with
`-PfieldpassFixtureMode=true` to make it the default). An in-memory fake replaces the server so
every screen can be tried with sample data: any non-empty password works, usernames starting with
`faculty` show the faculty message. A red "FIXTURE MODE: sample data" banner stays visible on every
screen. Release builds cannot enable it.

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

## Known limitations

- Camera capture is not liveness detection. A photo of a photo may pass.
- Location can be spoofed on the phone.
- Recorded times are points of observation, not proof of continuous presence.
- Not yet run on a physical device. The HTTP/repository layer has been run on the JVM against the real
  backend (see Contract tests); camera, Bitmap processing, Keystore and location are untested on hardware.
- `location_captured_at` is derived from the phone's clock minus the fix's age. A badly wrong phone
  clock could cause `location_stale` from the server.
- Faculty features (review, observations, roster) are not in this app; use the dashboard.
