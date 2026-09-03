# Phase 12 acceptance record

Status: accepted on 2026-09-03 after repository-local validation and operator live confirmation.

## Scope

Phase 12 adds a normalized server-side place-search boundary and connects Android current-location
origin plus named destination selection to the accepted Compass routing/navigation stack. It also
makes CNG refuelling dwell part of chronological journey time and strengthens rerouting so a valid
fuel plan is retained while an invalid one is safely replanned.

## Acceptance criteria

- Address, locality, POI/business/place-name and coordinate queries return normalized results.
- Android communicates only with Compass, never directly with the configured geocoder.
- The user can grant location access, acquire a GPS/network fix into the visible origin fields,
  explicitly calculate from it, and choose a search result as destination.
- The selected A-to-B route is requested from Compass and rendered with Valhalla maneuvers.
- A zero-cost/zero-duration provider response is rejected rather than exposed as active guidance.
- Current/next maneuver, distance to maneuver, remaining distance/driving time and total ETA progress
  from accepted GPS fixes through the local matcher.
- On Android 13+, notification permission is requested independently of already-granted location;
  the foreground notification is visible throughout active navigation.
- Confirmed off-route movement triggers a backend reroute and retains reasonable/reachable planned
  CNG stops.
- The controlled debug deviation resumes demo GPS progress after the replacement route commits.
- A missing, unavailable or range-invalid retained stop triggers a new complete predictive fuel plan;
  failure keeps the downloaded route and exposes the error.
- Activity recreation/backgrounding retains the application-scoped session while the foreground
  service owns location and guidance. Durable process-death caching is Phase 13 scope.
- Driving time, nullable traffic delay, stop dwell and total trip duration remain distinct.
- `CNG_REFUEL_DWELL_SECONDS` defaults to 1,200 and is configurable through Docker/API settings.
- One stop adds 20 minutes; multiple stops add it once per stop.
- Later stop/destination ETAs and opening-hours checks include all prior dwell; detour cost does not.
- Android app version is `0.9.0` (`versionCode=10`).

## Repository-local validation required

From the repository root:

```bash
python3 -m py_compile scripts/validate-phase12-live.py
bash -n scripts/run-phase12-live.sh
.venv/bin/ruff check .
.venv/bin/pytest -q
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

These are local/static/fixture checks only. They do not prove external geocoding, full-Italy
Valhalla, live station data or device behavior.

## Live/device gate

The operator synchronizes the repository, rebuilds/restarts the API and runs:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase12-live.sh
```

If the backend is remote, keep this tunnel open in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

The API container must already have an identifying `HTTP_USER_AGENT`; no geocoding secret is
required for the default Nominatim adapter. The runner requires one authorized Android device or
`COMPASS_ADB_SERIAL`.

## Required operator evidence

Return the complete script output and screenshots A–H requested by the runner:

1. normalized address results;
2. normalized POI results;
3. selected destination A-to-B preview with maneuvers;
4. progressing active guidance;
5. driving/dwell/total timing for the multi-stop route;
6. preserved CNG waypoint after off-route rerouting;
7. replacement plan after simulated invalid station;
8. foreground notification while the Activity is backgrounded.

Also confirm the resumed Activity retained the session and final `Termina navigazione` removed the
foreground notification.

## Accepted live evidence

The operator returned the successful gate output plus screenshots A–H. The evidence showed
normalized address and POI results, a non-zero Verona-to-Bologna preview, progressing guidance, the
three-stop CNG plan with cumulative dwell, preserved and replaced fuel stops, and the foreground
notification. After an earlier failed iteration, the reroute/replay and notification defects were
corrected and the repeated gate reached its final checks. The operator explicitly confirmed the
Phase 12 behavior and authorized closure and progression to Phase 13 on 2026-09-03.

## Failure diagnostics

Return the bounded artifacts printed by the runner: `/tmp/compass-phase12-*.json`, client/events/UI,
service, notification and logcat dumps. Also return the two filtered `CompassApi` and
`CompassNavigation` commands printed at the end of the runner.
