# Android client development

## Phase 8 scope

The Android app is a native Kotlin/Jetpack Compose client in `android/`. It calls the accepted
`POST /api/v1/routes` backend operation, decodes its polyline6 geometry and renders a fixed
Milan-to-Bologna route preview with MapLibre. The screen also shows distance, duration, provider and
the backend maneuver list.

Phase 8 intentionally has no destination editor, CNG candidate workflow, route-stop selection,
navigation session, background location or rerouting. Those are later gated phases.

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

## Physical-device live gate

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
bash scripts/run-phase8-live.sh
```

When multiple devices are connected, select one before starting:

```bash
export COMPASS_ADB_SERIAL=DEVICE_SERIAL
```

The script performs six separate gates:

1. backend readiness;
2. a real base-route API preflight;
3. device authorization and `adb reverse` when using loopback;
4. unit tests, lint and APK assembly with the selected API URL;
5. APK installation;
6. application launch with Android's `Status: ok` invariant.

Automated completion is not the live acceptance result. On the device confirm all four items printed
by the runner: rendered route and endpoint markers, non-zero distance/duration with Valhalla,
scrollable maneuver list, and no crash after rotate/background/resume. Return the complete script
output and one screenshot.

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

If install or launch fails, collect package/activity state and recent logs:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" shell pm list packages | grep org.compass.cng
"$ANDROID_SDK_ROOT/platform-tools/adb" shell dumpsys activity activities | grep org.compass.cng
"$ANDROID_SDK_ROOT/platform-tools/adb" logcat -d -t 300
```

If the route data appears but the basemap does not, verify that the device can reach the configured
map-style URL and return the last diagnostic command's output. Map-style availability is independent
from the Compass API and is not treated as backend route failure.
