# Android client development

## Android scope

The Android app is a native Kotlin/Jetpack Compose client in `android/`. It calls the accepted
`POST /api/v1/routes` backend operation, decodes its polyline6 geometry and renders a fixed
Milan-to-Bologna route preview with MapLibre. The screen also shows distance, duration, provider and
the backend maneuver list.

Phase 9 retains that fixed endpoint pair and adds the manual `Aggiungi tappa → Metano` workflow. It
collects maximum detour and effective range, displays arrival-aware ranked station markers/cards,
and recalculates the route through a selected official MIMIT station.

Phase 10 adds a separate predictive flow driven by a user-supplied remaining-range estimate and
safety reserve. It shows a complete ordered reserve-preserving refuelling itinerary or an explicit
no-refill/no-safe-itinerary state. Each planned stop assumes a full refill to the effective range.
It still has no destination editor, vehicle telemetry, navigation session, background location,
live traffic or rerouting.

## Required toolchain

- JDK 17;
- Android SDK Platform 37.0;
- Android SDK Build-Tools 36.0.0 or the AGP-selected compatible version;
- Android Platform-Tools for `adb`;
- an Android API 26+ emulator or physical device for the live gate.

Gradle 9.4.1 is supplied by the checked-in wrapper and its distribution SHA-256 is pinned. On the
current development workstation the isolated toolchain is installed under `/home/mike/toolchains`.
Set paths explicitly so no system Java selection is assumed:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
```

## Repository-local validation

Run these commands from the repository root:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug assembleDebug
cd ..
```

The generated APK is:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Backend and map configuration

The debug build defaults to the emulator host alias:

```text
COMPASS_API_BASE_URL=http://10.0.2.2:8000/
```

Override it at build time; the trailing slash is mandatory:

```bash
cd android
./gradlew --no-daemon \
-PCOMPASS_API_BASE_URL=https://compass.example.test/ \
assembleDebug
cd ..
```

The default public demo map style is also replaceable:

```bash
cd android
./gradlew --no-daemon \
-PCOMPASS_MAP_STYLE_URL=https://maps.example.test/style.json \
assembleDebug
cd ..
```

No token is committed. If a chosen style requires credentials, inject a protected style URL using
the operator's normal secret/configuration mechanism and do not add it to Git.

Debug HTTP in cleartext is limited to `127.0.0.1` and `10.0.2.2`. Use HTTPS for any LAN/public
hostname or IP. A physical-device gate can retain the backend's safe loopback-only bind by using
`adb reverse`, which the checked-in runner configures automatically.

## Phase 9 physical-device live gate

The runner must execute on the machine that has the repository, Android SDK, one authorized Android
device and access to the backend. The simplest setup is a device attached to the test server. If the
device is attached to another workstation, first open a local SSH tunnel from that workstation to
the server's loopback API in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

On the device/build machine, export the toolchain and run the single handoff command:

```bash
cd /path/to/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase9-live.sh
```

When multiple devices are connected, select one before starting:

```bash
export COMPASS_ADB_SERIAL=DEVICE_SERIAL
```

The script performs eight separate gates:

1. backend readiness;
2. a real full-Italy ranked-candidate request with an offset-aware departure instant;
3. top-ranked official-ID selection and independent two-leg route validation;
4. device authorization and `adb reverse` when using loopback;
5. unit tests, lint and APK assembly with the selected API URL;
6. APK installation;
7. application launch with Android's `Status: ok` invariant;
8. an immediate fatal-exception check.

Automated completion is not by itself a live acceptance result. On the device follow the five short
items printed by the runner: open the Metano form, run the ranked search, inspect the required card
fields, select a station and exercise change/remove plus lifecycle behavior. Return the complete
script output and screenshots of both the candidate list and selected-stop route. The initial gate
was accepted on 2026-08-29; this procedure remains the reproducible regression check.

The accepted historical Phase 8 preview-only gate remains reproducible with
`bash scripts/run-phase8-live.sh`; the accepted Phase 9 regression uses
`bash scripts/run-phase9-live.sh`. New acceptance runs should use the Phase 10 procedure below.

## Phase 10 predictive physical-device live gate

Run this on the workstation attached to the Android device. If the backend is on a different test
server, first open the tunnel in its own terminal and leave that terminal running:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

Then open a second terminal and run these commands from the repository root:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
```

```bash
bash scripts/run-phase10-live.sh
```

The runner prints the tunnel reminder before its first request. It first checks live OpenAPI so an
old API image is rejected before the longer route scenarios. It then validates standard, not-needed,
no-reachable and mandatory 65/30/100 multi-stop profiles. It recalculates one route through all
ordered official MIMIT stops and rejects any actual Valhalla leg that consumes reserve. Finally it
runs Android tests/lint/assembly, installs and cold-launches the APK, and checks for an immediate
fatal exception. All request bodies are generated as named JSON artifacts; no inline JSON needs to
be copied into the shell.

The automated gate is not the device acceptance. The runner prints complete scenario instructions,
including the exact meaning of each input and expected screen. Return the complete output plus its
four requested screenshots: 65/30/100 multi-stop plan, selected multi-stop route, not-needed state
and no-reachable safety state.

## Diagnostics

If no authorized device is found:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" devices -l
```

If the app reports that Compass is unreachable, confirm the host API and reverse mapping:

```bash
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
"$ANDROID_SDK_ROOT/platform-tools/adb" reverse --list
```

If the API preflight fails, inspect the unambiguous saved artifacts before rerunning anything:

```bash
python3 -m json.tool /tmp/compass-phase9-ranked-request.json
python3 -m json.tool /tmp/compass-phase9-ranked-response.json
python3 -m json.tool /tmp/compass-phase9-selected-request.json
python3 -m json.tool /tmp/compass-phase9-selected-response.json
```

For Phase 10 inspect each request and response independently:

```bash
python3 -m json.tool /tmp/compass-phase10-standard-request.json
python3 -m json.tool /tmp/compass-phase10-standard-response.json
```

```bash
python3 -m json.tool /tmp/compass-phase10-not-needed-response.json
python3 -m json.tool /tmp/compass-phase10-unreachable-response.json
```

```bash
python3 -m json.tool /tmp/compass-phase10-multi-stop-response.json
python3 -m json.tool /tmp/compass-phase10-itinerary-route-response.json
```

Return the failing artifact plus bounded service logs:

```bash
docker compose --profile routing logs --no-color --tail=200 api valhalla db
```

If install or launch fails, collect package/activity state and recent logs:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" shell pm list packages | grep org.compass.cng
"$ANDROID_SDK_ROOT/platform-tools/adb" shell dumpsys activity activities | grep org.compass.cng
"$ANDROID_SDK_ROOT/platform-tools/adb" logcat -d -t 300
```

If the route data appears but the basemap does not, verify that the device can reach the configured
map-style URL and return the last diagnostic command's output. Map-style availability is independent
from the Compass API and is not treated as backend route failure.
