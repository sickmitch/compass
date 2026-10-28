# Android client

Compass uses Kotlin, Jetpack Compose and MapLibre Native. The client owns interaction,
location filtering, route progress and guidance; the backend owns route costs and CNG
eligibility. Auto, pedestrian and bicycle modes use dedicated backend costing.

The navigation session supports foreground guidance, Picture in Picture, saved-route
recovery, ordinary and CNG stops, voice guidance and manual/off-route recalculation.
ETA uses nominal route duration plus remaining dwell. The ETA badge and whole-journey
progress ring are green. Cached geometry permits guidance on an already loaded route;
it does not provide an offline routing engine or guarantee downloaded map coverage.

## Build and local validation

Use JDK 17, Android SDK Platform 37, compatible AGP-selected Build-Tools and the pinned
Gradle wrapper. Set JAVA_HOME and ANDROID_SDK_ROOT to your local toolchains. From the
repository root:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug assembleDebug
```

Assembly publishes `dist/compass-<version>.apk` and replaces `dist/last.txt` after a
successful copy. The internal debug build output name is not the distributed name.
Installation scripts have been retired: existing installations use the
[automatic update workflow](client-updates.md). First installation still requires the
operator to open the published APK on the device and approve Android's installer.

## Backend and map configuration

The debug build defaults to the emulator host alias:

```text
COMPASS_API_BASE_URL=http://10.0.2.2:8000/
```

This value is only the first-launch default. If no authenticated profile exists, Compass opens the
server form automatically. Enter the externally reachable endpoint and the backend's
`API_AUTH_USERNAME`/`API_AUTH_PASSWORD`, then choose `Salva e connetti`. The same form opens after a
connection or authentication error. The endpoint may include a reverse-proxy path and its trailing
slash is added automatically. Credentials embedded in the URL, query strings and fragments are
rejected.

Override it at build time; the trailing slash is mandatory:

```bash
cd android
./gradlew --no-daemon \
-PCOMPASS_API_BASE_URL=https://compass.example.test/ \
assembleDebug
cd ..
```

The app defaults to the bundled `asset://compass-day.json` and `asset://compass-night.json` styles.
They reference keyless OpenFreeMap/OpenMapTiles data and fonts. A deployment can replace either
complete style independently:

```bash
cd android
./gradlew --no-daemon \
-PCOMPASS_MAP_DAY_STYLE_URL=https://maps.example.test/day.json \
-PCOMPASS_MAP_NIGHT_STYLE_URL=https://maps.example.test/night.json \
assembleDebug
cd ..
```

For backwards compatibility, `COMPASS_MAP_STYLE_URL` still overrides both modes when neither
mode-specific property is present. Use it only when one external style deliberately serves both
themes. An operator self-hosting OpenFreeMap must ensure the custom style's tile, font and sprite
URLs also point at the intended host; Compass treats the supplied document as authoritative.

MapLibre caches resources visited during normal use. Its size defaults to 100 MiB and can be changed
at build time (16–1,024 MiB):

```bash
cd android
./gradlew --no-daemon -PCOMPASS_MAP_AMBIENT_CACHE_MB=200 assembleDebug
cd ..
```

No token is committed. If a chosen style requires credentials, inject a protected style URL using
the operator's normal secret/configuration mechanism and do not add it to Git.

Use HTTPS for any LAN/public hostname or IP. Android permits dynamic HTTP endpoints only because the
runtime `Server` form blocks saving them until the user accepts the visible cleartext-credential
warning. A physical-device gate can retain the backend's safe loopback-only bind by using `adb
reverse`, which the checked-in runner configures automatically.

## Device validation and diagnostics

The [current release checklist](phases/android-0.28.46-nominal-routing-gate.md) covers
route creation, CNG planning, navigation and saved-route compatibility. Local tests
are not physical-device acceptance. Older scenario runners remain optional diagnostic
tools, not the installation/update workflow; some build and replace the APK, so inspect
them before running. Their original instructions are in [historical gates](phases/).

For device diagnostics, with Android Platform-Tools installed:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" devices -l
"$ANDROID_SDK_ROOT/platform-tools/adb" reverse --list
"$ANDROID_SDK_ROOT/platform-tools/adb" logcat -d -t 300
```

Return the failing scenario, screenshot and relevant bounded logs, excluding credentials.
Basemap availability is independent of the Compass API. Server diagnostics are in
[deployment](deployment.md); previous milestones are summarized in [history](history.md).
