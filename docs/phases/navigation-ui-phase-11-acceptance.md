# Navigation UI upgrade — Phase 11 acceptance record

Status: accepted on the live backend and physical Android device on 2026-09-08.

## Scope

Phase 11 makes the existing CNG plan visible as native navigation guidance. It does not add another
station database, routing engine, live lookup or refuelling timer. Compass/Valhalla remain
authoritative for routing, while the Android navigation engine owns progress along the downloaded
waypoints.

## Data and freshness contract

- Manual selection copies the chosen ranked station's opening, phone, operator/brand and price
  snapshot into the selected route. Predictive selection does the same for every itinerary stop.
- Compatible traffic/off-route recalculation preserves that snapshot by official MIMIT ID. A newly
  selected replacement uses the new predictive result's snapshot.
- The version-1 private route cache stores these optional fields while remaining able to decode
  documents written before Phase 11.
- Opening is visible only when validation is `VALID` and its evaluation instant remains within 30
  minutes of the routed stop ETA. Both open and closed states remain explicit.
- Price is visible only when its domain freshness is `FRESH` and the freshness evaluation remains
  within 30 minutes of the routed stop ETA. Stale, future, unknown or ETA-displaced prices are
  omitted rather than presented as live.
- A cache-restored route keeps waypoint, distance, ETA and dwell guidance, but suppresses dynamic
  opening and price claims.
- Backend instants retain their offset for comparisons, but every user-facing stop ETA, traffic
  timestamp and preview time is converted to the Android device timezone before formatting.

No request is made from the driving UI. The snapshot is route data, not a persistent station
catalogue.

## Navigation state and UI

`NavigationState.fuelStopProgress` exposes each stop with a lifecycle. Phase 11 derives
`PLANNED`, `APPROACHING`, `ARRIVED` and `COMPLETED` from matched route progress and the centralized
engine thresholds. `REFUELING`, `SKIPPED` and `REPLACED` are reserved in the model for explicit
Phase 12 stop handling; Compose does not infer them.

The next stop is always available in a compact, accessible card below the maneuver card. It shows
station name, remaining route distance, ETA and lifecycle, plus valid arrival opening and fresh
price when available. Tapping it opens trip details, where the driver sees location/operator,
reason for the stop and routed dwell duration. The existing safe replacement action excludes the
current station and commits a replacement or direct route only when the predictive CNG plan allows
it. The generic route recalculation action remains available, and its progress pill is placed below
the persistent CNG card.

Refuelling duration is not hard-coded in Compose. The backend setting
`CNG_REFUEL_DWELL_SECONDS` (default 1,200 seconds) travels in route timing and each selected stop;
the Android default constant exists only for backward-compatible decoding.

Android version is `0.21.0` (`versionCode=26`).

## Repository-local validation

From the repository root:

```bash
bash -n scripts/install-android-0.21.0.sh scripts/run-navigation-ui-phase11-live.sh
.venv/bin/pytest -q tests/test_navigation_ui_phase11_script.py
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Tests cover lifecycle transitions, planner-to-navigation enrichment, cache round-trip and backward
compatibility, dynamic-evidence suppression for cached routes, metadata preservation across CNG
reroutes, UI formatting and live-runner invariants.

## Live gate

The backend needs no migration or new secret. It must contain current station data and remain
reachable through the server profile already saved in Compass. From the Android workstation:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER=<existing-api-user>
export COMPASS_CHECK_PASSWORD=<existing-api-password>
bash scripts/run-navigation-ui-phase11-live.sh
```

The runner preserves encrypted server credentials and saved vehicle profiles. It performs no
UIAutomator or screenshot inspection. The operator validates the compact guidance card, details,
lifecycle progression, safe stop replacement and cache-only freshness behavior.

Return the complete runner output, pass/fail notes for its five operator checks, and these artifacts
only if a check fails:

```text
/tmp/compass-navigation-ui-phase11-navigation.txt
/tmp/compass-navigation-ui-phase11-ui.txt
/tmp/compass-navigation-ui-phase11-fatal.txt
/tmp/compass-navigation-ui-phase11-service.txt
```

Do not proceed to Navigation UI Phase 12 until this gate is accepted.

## Acceptance evidence

On 2026-09-08 the operator reported that the complete Phase 11 gate passed on the live backend and
physical Android device. This confirms the five operator-owned checks: persistent compact CNG
guidance, complete stop details and dwell, lifecycle progression, safe CNG-aware replacement, and
cache-only behavior without live freshness claims. The follow-up checks also confirmed that stop
ETA and traffic timestamps use the phone timezone and that `Ricalcolo rotta` appears below the CNG
stop card.

Repository-local unit tests, lint, APK assembly, runner-contract checks and shell checks had already
passed before the final handoff. No UIAutomator or screenshot-parsing result is claimed; visual and
behavioral acceptance comes from the operator's physical-device confirmation.

Navigation UI Phase 11 is complete. Phase 12 remains unstarted until explicitly requested.
