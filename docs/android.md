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
Phase 11 adds editable coordinates. Navigation Stage 1 then introduces the server-backed
`NavigationRoute`, explicit navigation-session boundary and the `Avvia navigazione` route handoff.
Navigation Stage 2 adds foreground location, local route matching/progress and the MapLibre
navigation renderer. Navigation Stage 3 adds speed-aware Italian voice guidance, dynamic camera
follow/remaining-route overview, confirmed off-route rerouting through Compass, five-minute traffic
route refresh and in-place route replacement. After the physical-device gate passed on 2026-09-02,
the debug replay was slowed from eight geometry points per second to one point every 1.5 seconds;
the simulated fix still reports road speed independently for maneuver timing.
Debug API calls emit bounded `CompassApi` logcat events containing endpoint, outcome, duration and
exception classes. Payloads and response bodies are deliberately excluded from these diagnostics.
Ordinary calls retain the short global network limits. Predictive candidate evaluation alone uses a
240-second read/call limit because a bounded full-range itinerary may require multiple sequential
Valhalla matrix batches; its actual elapsed time is recorded in the same event stream.
Navigation Stage 4 adds an explicit next-stop skip/replacement confirmation. It replans from the
snapped position with the unavailable MIMIT ID excluded and commits only a complete range-safe
itinerary. Its physical-device gate passed on 2026-09-02.

Navigation Stage 5 adds local vehicle profiles and an explicit gasoline fallback. A profile stores
effective full range and reserve for both fuels and is selected across app restarts. Predictive CNG
planning still requires driver-entered remaining CNG range. Remaining gasoline is also entered by
the driver and is optional; when present, Compass may offer a direct gasoline fallback only after no
complete CNG itinerary exists and only if both reserves are preserved. The fallback remains labelled
in navigation preview and active guidance.

Phase 12 adds destination search and current-location origin selection. The search screen accepts
addresses, localities, named POIs/businesses and coordinates, then displays only normalized Compass
results. “Usa la posizione attuale” requests location permission when needed, acquires the first
available GPS/network fix and writes it into the visible origin fields; the driver confirms it with
“Calcola percorso”. Selecting a destination then reloads the base route and clears stale
route-dependent planning state. Navigation startup independently requires the Android notification
permission on API 33+, even when location was already granted. A debug off-route injection pauses
demo fixes only while rerouting and resumes replay on the committed replacement route. Zero-cost
routing responses are rejected as non-navigable. The
navigation preview separately shows driving time, traffic-delay availability, cumulative CNG dwell
and total trip duration.

Phase 13 adds durable, versioned private-device caching for the active route, geometry, maneuvers,
planned CNG waypoints and range policy. A process restart restores an explicitly cached preview;
`Termina navigazione` clears it. Exact recent place queries can fall back to one of the ten cached
result sets only after a network/server failure. The active screen distinguishes local cached-route
guidance, unavailable rerouting, unavailable traffic and cached CNG data. MapLibre's configurable
ambient cache retains resources already viewed but does not guarantee an arbitrary offline region.
Android version is `0.11.0` (`versionCode=12`).

Navigation UI Phase 1 makes the active MapLibre view a full-screen driving surface. The primary
overlays contain only the current/following maneuver, remaining trip values and next CNG stop.
Traffic, GPS, cache/connectivity state, planned stops and trip actions are available in an
expandable bottom sheet. Debug-build simulation controls and raw state have a separate
`Strumenti sviluppatore` screen and are not composed into the normal driving interface. The UI
derives its display model from the existing authoritative `NavigationState`; routing and navigation
logic remain outside Compose. The operator accepted the Phase 1 device gate on 2026-09-03 after
validating the driving surface, expandable details, developer-tool isolation, foreground-service
continuity and clean notification teardown.

## Navigation Stage 1 device gate

If the backend is remote, first keep this tunnel open in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

Then run from the repository root on the machine connected to the Android device:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage1-live.sh
```

The runner prints the two exact manual scenarios and screenshots required for acceptance.

## Navigation Stage 2 device gate

Stage 2 shares the navigation session between Compose and a foreground location service. During an
active session the app filters fixes, projects them onto a local window of the downloaded route,
updates progress/ETA/manoeuvres locally and renders only the snapped vehicle puck. Ordinary GPS
updates do not call Compass or Valhalla.

If the backend is remote, keep the same SSH tunnel shown above open. Then run:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage2-live.sh
```

The runner pauses once. On the device open the navigation-ready screen, press the debug-only
`Riproduci percorso demo`, grant location/notification permission and return to the terminal. The
replay traverses the downloaded geometry through the real foreground service and navigation engine;
it sends no simulated GPS point to the backend. See
`docs/phases/navigation-stage-2-acceptance.md` for thresholds and the three requested screenshots.

## Navigation Stage 3 device gate

Stage 3 keeps ordinary GPS updates entirely on the device. Android contacts Compass only after a
confirmed deviation, at the five-minute active-navigation refresh boundary, or through a
debug-only manual test action. A route update always goes through Compass and preserves the last
downloaded route if the network request fails. The foreground service owns TextToSpeech, so spoken
guidance is independent from Activity recreation.

If the backend is remote, keep the SSH tunnel shown above open in a separate terminal. Then run:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage3-live.sh
```

The runner pauses for explicit device actions, then verifies the foreground service, the automatic
off-route route replacement and background/resume continuity. The debug replay still sends no GPS
point to Compass; the debug deviation instead passes three controlled fixes through the production
filter, matcher and off-route state machine. See
`docs/phases/navigation-stage-3-acceptance.md` for the accepted evidence and thresholds.

## Navigation Stage 4 device gate

Stage 4 requires a predictive itinerary because the replacement decision must retain the driver's
explicit effective range, remaining-range estimate, reserve and maximum detour. Android derives the
current remaining range from local route progress, calls Compass with the unavailable official ID
excluded, and keeps the downloaded route for every non-complete result. A manual selected-stop
route has no caller-supplied tank state and is therefore guarded instead of being silently changed.

Run from the repository root with the same toolchain and backend tunnel used for Stage 3:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage4-live.sh
```

The runner verifies the deployed OpenAPI exclusion field, builds/installs the app, checks the
replacement start/commit events, confirms foreground-service continuity, exercises the manual-route
range-plan guard, and checks final service/notification teardown. See
`docs/phases/navigation-stage-4-acceptance.md` for the three required screenshots and diagnostics.

## Phase 12 device gate

After rebuilding/restarting the synchronized API, run from the repository root with the same JDK,
SDK and optional SSH tunnel used by prior navigation gates:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase12-live.sh
```

The preflight performs real address/locality/POI/coordinate searches, requests a final A-to-B route
and validates one/multiple-stop journey chronology. It then builds, installs and pauses for the
current-location, destination-search, maneuver-progress, CNG-preserving reroute, invalid-stop
replacement and lifecycle checks. Filtered API/navigation logs are captured continuously during
the operator steps so early search evidence cannot be evicted from logcat. Return the complete
output and eight requested screenshots. See
`docs/phases/phase-12-acceptance.md`; the operator accepted this gate on 2026-09-03.

## Phase 13 degraded/offline device gate

Run from the repository root with the same toolchain and loopback/SSH setup:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase13-live.sh
```

The runner populates search and active-route caches, removes only `adb reverse`, validates degraded
navigation, force-stops/reopens Compass while offline, and then restores connectivity. It captures
bounded client/navigation/map-cache logs and asks for screenshots A–G. See
`docs/phases/phase-13-acceptance.md`. The operator accepted this gate on 2026-09-03.

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

MapLibre caches resources visited during normal use. Its size defaults to 100 MiB and can be changed
at build time (16–1,024 MiB):

```bash
cd android
./gradlew --no-daemon -PCOMPASS_MAP_AMBIENT_CACHE_MB=200 assembleDebug
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

## Phase 11 editable-route physical-device live gate

Run this on the workstation attached to the Android device. If the backend is on a different test
server, open the tunnel in a separate terminal and leave it running:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

Then run from the repository root:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase11-live.sh
```

The runner avoids inline JSON. It writes a non-default Rome-to-Florence route request to
`/tmp/compass-phase11-custom-route-request.json`, validates the live route response, builds and
installs Android `0.4.0`, cold-launches the app and checks for an immediate fatal exception.

Manual acceptance must prove that endpoint editing drives the rest of the planner:

1. default Milan-to-Bologna preview still renders;
2. `Modifica percorso` accepts Rome `41.9028, 12.4964` and Florence `43.7696, 11.2558`;
3. manual Metano search and selected-stop routing stay on the edited route;
4. predictive CNG evaluation stays on the edited route and uses generic destination labels, not
   fixed Milan/Bologna copy;
5. destination longitude `200` is rejected on the coordinate form without crash.

Return the complete runner output and the four screenshots requested by the runner.

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

For Phase 11 inspect the generated request and response:

```bash
python3 -m json.tool /tmp/compass-phase11-custom-route-request.json
python3 -m json.tool /tmp/compass-phase11-custom-route-response.json
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
