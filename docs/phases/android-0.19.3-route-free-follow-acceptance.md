# Android 0.19.3 route-free follow acceptance

## Scope

This final `0.19.3` increment removes the startup route request and makes the no-destination state a
first-class GPS-follow mode. It does not change the server routing contract, off-route rerouting or
active-navigation controls.

Acceptance requires all of these invariants:

- a launch with no cached active navigation makes no default route request and discards an inactive
  cached preview; if the authenticated server profile is missing, setup is shown before follow;
- the map follows the device GPS without snapping to an itinerary;
- the puck remains on the established 75%-height follow anchor;
- route-free follow shows `Crea viaggio` and never shows `Panoramica`;
- `Crea viaggio` opens a selector with content-width buttons which may wrap across rows;
- the selector begins directly with `Partenza`, without a planner header, server shortcut or
  explanatory card;
- departure orders `Posizione attuale`, `Posizioni preferite`, `Ricerca`, `Coordinate`;
- destination orders `Posizioni preferite`, `Ricerca`, `Coordinate`, `Posizione attuale`;
- `Posizioni preferite` is visibly present but disabled as a placeholder;
- search and current position can fill either endpoint;
- each current-position pill shows a spinner while acquiring, a green check on success and a
  light-red X on failure, without a redundant label or global success/error line;
- coordinates remain available only after selecting `Coordinate`;
- `Calcola percorso` performs the first routing request without leaving the selector;
- direct route, single-stop and extended-planning actions stay disabled until that route succeeds,
  and become disabled again whenever either endpoint changes;
- extended planning lists saved vehicle profiles plus a custom-values choice; selecting a profile
  pre-fills its policy but never removes the required remaining-range and maximum-detour inputs;
- missing server credentials, authentication failures and connection failures open server
  configuration automatically; no manual server action is exposed in the planner;
- active navigation still shows `Viaggio` and `Panoramica` unchanged;
- `Termina navigazione` returns to route-free follow.

## Repository-local evidence

Run from `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The ViewModel suite covers production factory wiring, zero startup routing calls, GPS state updates,
both endpoint roles and back navigation to follow. Lint and APK assembly cover the new foreground
location listener and MapLibre surface.

## Operator device gate

Run from the repository root on the workstation with one authorized device:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
bash scripts/install-android-0.19.3.sh
```

The installer preserves the saved server and vehicle profiles. Compass discards an inactive cached
preview automatically; an actually active navigation remains recoverable after process death.

Check manually:

1. With the preserved authenticated server profile, Compass opens directly on the map, requests
   location permission if needed and follows the real GPS position without displaying a route or
   maneuver card. On a clean app-data install, configure the automatically opened server form first.
2. Only `Crea viaggio` is present in follow mode; `Panoramica` is absent. Pan once and verify
   `Ricentra` restores follow.
3. Open `Crea viaggio`. Confirm the screen begins at `Partenza`, with no title card or `Server`
   action; confirm both button orders above, wrapping on the device width and the disabled
   favourites placeholder.
4. Fill departure with `Posizione attuale` and destination with `Ricerca`; return and confirm both
   selected values. Confirm the current-position pill progresses from spinner to green check and no
   separate `Posizione ... acquisita` line appears. Repeat the inverse roles if practical.
5. Select `Coordinate` and confirm latitude/longitude fields appear only for that endpoint.
6. Before calculation, confirm direct route, single stop and extended planning are disabled.
   Calculate the route and confirm all three activate without leaving the selector. Change an
   endpoint and confirm they disable until recalculation.
7. Open `Pianificazione estesa`: saved vehicles, if present, must be listed along with
   `Nessun profilo · valori personalizzati`. With either choice, confirm autonomy remaining and
   maximum detour are still requested.
8. Open the direct route, start navigation and confirm `Viaggio` plus `Panoramica` retain their
   existing behavior. Terminate navigation and confirm Compass returns to route-free GPS follow.

Screenshots are optional. Return a short confirmation plus any unexpected UI behavior. No
UIAutomator or screenshot inspection is part of this gate.

## Diagnostics if it fails

From the repository root:

```bash
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
adb="$ANDROID_SDK_ROOT/platform-tools/adb"
"$adb" logcat -d -v threadtime CompassFollowUi:I CompassNavigationUi:I AndroidRuntime:E '*:S'
"$adb" shell dumpsys location
```

Return both outputs and state whether location permission was granted, whether the puck appeared,
which selector action failed and whether an old navigation session had been terminated before the
cold launch.

## Gate status

Repository-local validation passed on 2026-09-07:

- the complete Android unit-test suite passed;
- Android lint passed;
- the debug APK assembled successfully;
- the installer syntax/profile-preservation pytest passed (`2 passed`).

Physical-device acceptance is pending the operator result.
