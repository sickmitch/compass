# Navigation UI upgrade — Phase 14 acceptance record

Status: repository-local implementation complete; physical-device interaction gate pending.

The operator explicitly requested Phase 15 on 2026-09-09 without returning this runner's output.
That instruction advances development but is not recorded as a successful Phase 14 device gate.

## Implemented scope

- Active guidance starts in heading-up follow mode, with the existing matched puck anchored in the
  lower driving viewport and the existing speed/maneuver-aware zoom policy.
- A compact orientation control switches between heading-up and north-up position tracking. Both
  modes continue following the matched puck; north-up uses bearing and pitch zero and rotates the
  puck against the map instead of rotating the map around the puck.
- `Panoramica` frames only the untravelled route geometry at bearing and pitch zero. It does not
  reintroduce travelled geometry or reset navigation progress.
- Manual single-finger pan enters free mode. `Ricentra` is then the primary camera action and always
  returns to heading-up tracking; it has the same behavior when leaving overview. The established
  ten-second automatic return from free mode remains available.
- Automatic dynamic zoom remains the default. Pinch-to-zoom continues working in tracking mode and
  retains the chosen zoom while the matched puck advances. Permanent +/- controls were deliberately
  omitted because they duplicate that interaction and add driving-surface clutter.
- Voice ON/OFF and the compact trip panel remain directly available. The map now also has a 52 dp
  terminate control with an explicit confirmation dialog; cancelling the dialog leaves navigation
  and its foreground service untouched.
- Controls that compete with the current camera state are hidden: orientation and overview are
  offered while tracking, while overview/free mode exposes the prominent `Ricentra` action.
- All icon-only map actions have stable Compose test tags, at least 48 dp touch targets and complete
  Italian accessibility descriptions.
- No routing, Valhalla, CNG, traffic, map-matching, replay or offline-recovery behavior was replaced.
  No external navigation SDK or icon dependency was introduced.
- Android version is `0.24.0` (`versionCode=31`).

The directly installable debug APK is `dist/Compass-0.24.0-debug-installabile.apk`. SHA-256:
`ad7cf4ca3ada4011db6163567f929046786bc406527d1376218d0aa59abcce53`.

## Architectural decisions

`NavigationCameraMode` remains the single camera-mode vocabulary used by the Compose surface and
MapLibre renderer. `NORTH_UP` is position tracking, not an alternative route state. Pure camera
helpers define tracking membership, orientation transitions, bearing policy and visible controls;
MapLibre only executes the resulting camera behavior.

The terminate confirmation is presentation state because no navigation transition occurs until the
operator confirms. The confirmed action still calls the existing `RoutePlannerViewModel` and
foreground-service shutdown path, so Compose does not clear route or CNG state independently.

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-navigation-ui-phase14-live.sh
API_AUTH_ENABLED=false .venv/bin/pytest -q
.venv/bin/ruff check .
.venv/bin/python scripts/export-openapi.py --check
docker compose config --quiet
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover heading/north orientation transitions, north-up bearing, position-tracking modes
and the control visibility policy. MapLibre gesture recognition, physical touch targets and the
visual fit of the remaining-route bounds require the device gate.

## Physical-device/live gate

Required working directory and existing configuration:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER='...'
export COMPASS_CHECK_PASSWORD='...'
bash scripts/run-navigation-ui-phase14-live.sh
```

The runner preserves saved server and vehicle profiles and does not use UIAutomator or screenshot
inspection. Return its complete output plus pass/fail notes for operator checks A-C.

## Failure diagnostics

Return the bounded artifacts printed by the runner:

```text
/tmp/compass-navigation-ui-phase14-ui.txt
/tmp/compass-navigation-ui-phase14-navigation.txt
/tmp/compass-navigation-ui-phase14-fatal.txt
/tmp/compass-navigation-ui-phase14-service.txt
```

## Remaining limitation

Compass intentionally has no on-screen tilt control or persistent +/- buttons during active
guidance. Free-mode MapLibre gestures remain available, and Phase 16 will validate landscape and
very narrow layouts as part of the full visual-polish matrix.
