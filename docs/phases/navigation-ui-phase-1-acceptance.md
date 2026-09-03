# Navigation UI upgrade — Phase 1 acceptance record

Status: accepted on 2026-09-03 after repository-local validation and the operator-assisted live
gate.

## Scope

This phase replaces the active-navigation diagnostic layout with an automotive driving surface. It
does not change routing, map matching, camera policy, route rendering, voice scheduling, CNG
planning or offline behavior. Those existing subsystems continue to publish one authoritative
`NavigationState`.

## Design contract

- MapLibre fills the complete active-navigation surface and remains mounted below all overlays.
- A high-contrast top card presents a stable maneuver-family symbol, distance, Valhalla
  instruction, target road and optional following maneuver.
- A compact bottom overlay presents remaining distance, total remaining trip duration, ETA and the
  next CNG stop with distance and planned arrival time.
- Progress, connection/cache/traffic/GPS detail, all planned CNG stops and normal trip actions live
  in an expandable Material bottom sheet.
- The main driving surface contains no raw state, route identifiers or debug actions.
- Debug-build-only route-update simulation, off-route injection and raw state live in a dedicated
  full-screen developer surface marked as unsafe to use while driving.
- Existing CNG-stop replacement confirmation and explicit navigation termination remain available
  from trip details.
- Non-navigation planner screens retain their existing layout and safe insets.
- Android version is `0.11.0` (`versionCode=12`).

The symbols in this phase are a compact, deterministic presentation over existing Valhalla
maneuver types. Complete iconography and the full maneuver-family visual system remain scoped to
Navigation UI Phase 6.

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-navigation-ui-phase1-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover the state-to-driving-UI presenter, primary trip/CNG values, explicit degraded
detail messages and stable Phase 1 maneuver symbols.

The live runner validates screen transitions through the bounded `CompassNavigationUi` event log.
UIAutomator XML capture remains best-effort because an animated MapLibre surface and replay stream
may never report Android's idle state; screenshots remain the operator evidence for visual content.

## Live/device gate

The operator synchronizes the repository and runs:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-ui-phase1-live.sh
```

For a remote backend, keep the SSH tunnel printed by the script open. Return the complete output
and screenshots A–D: primary automotive surface, top of trip details, lower detail actions and the
dedicated developer screen.

## Expected invariants

- The map occupies the full navigation surface behind compact overlays.
- Maneuver, distance, instruction, road, following action and primary trip values are readable at
  a glance.
- The next planned CNG stop is visible without opening a permanent panel.
- Detailed and degraded information remains reachable without occupying the driving surface.
- Neither debug action is present on the primary surface or normal details sheet.
- Both debug actions and raw state are present only inside `Strumenti sviluppatore`.
- Opening and closing either overlay does not stop guidance or its foreground service.
- Explicit termination removes the navigation foreground service and notification.

## Accepted live evidence

On 2026-09-03 the operator returned a successful run through all eight gate steps, ending with
`NAVIGATION UI PHASE 1 DEVICE CHECKS COMPLETED`. The supplied screenshots showed:

- the full-screen MapLibre driving surface with compact maneuver, trip and next-CNG overlays;
- the trip-details sheet with status, all three planned CNG stops and its lower trip actions (the
  requested B and C states were both visible in one screenshot);
- the dedicated developer screen, its driving-safety warning, raw diagnostics and both debug
  actions.

The runner also verified that the foreground navigation service remained active across overlay
transitions, explicit termination removed the service and notification, and no fatal Compass
exception was recorded. These results satisfy the Navigation UI Phase 1 acceptance criteria.

## Failure diagnostics

Return the bounded `/tmp/compass-navigation-ui-phase1-*` artifacts printed by the runner if a
future regression run fails.
