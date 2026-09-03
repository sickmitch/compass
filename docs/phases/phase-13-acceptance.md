# Phase 13 acceptance record

Status: accepted on 2026-09-03 after repository-local validation and the operator-assisted live gate.

## Scope

Phase 13 preserves navigation through temporary Compass/API loss and makes every supported degraded
state explicit. Android now caches the active route, maneuvers, planned CNG waypoints/range policy,
recent exact-query search results and MapLibre resources already visited. Routing remains
server-side.

## Acceptance criteria

- GPS matching, maneuver progression, remaining distance/time and ETA continue on the downloaded
  route while Compass is unavailable.
- A failed reroute retains route and CNG stops and separately reports that local navigation remains
  available while rerouting is unavailable.
- A complete active route survives process death and is restored as a cached preview with geometry,
  maneuvers and CNG waypoints. Guidance is restarted explicitly by the user.
- Deliberate navigation termination clears the persisted active-route document.
- Up to ten recent successful non-empty normalized search result sets are stored. An exact query can
  fall back on network/server failure and displays the cache timestamp/source.
- Invalid search requests/responses never fall back silently.
- Missing traffic is labelled and route duration remains a no-live-traffic estimate.
- Cached CNG waypoints are identified as cached; their prices/opening/live enrichment are not
  presented as current. Planning cards retain source timestamps, stale-price labels and distinct
  missing/invalid opening-hours states.
- MapLibre uses a configurable ambient cache (100 MiB default) for already visited resources. The UI
  and docs do not claim full offline-region coverage.
- Connectivity restoration commits a fresh Compass route in-place, clears degraded/cache state and
  leaves navigation active.
- Android app version is `0.10.0` (`versionCode=11`).

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-phase13-live.sh
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

These checks validate codecs, state transitions, repository fallback and UI state with fixtures;
they do not prove device persistence, Android foreground lifecycle or real MapLibre cache reuse.

## Live/device gate

The operator synchronizes the repository and runs:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase13-live.sh
```

For a remote backend, keep the SSH tunnel printed by the script open. The deterministic gate
requires readiness to report `traffic=unavailable` and uses `adb reverse` removal to interrupt only
Compass access, without disabling the device's general network.

Return complete output and screenshots A–G: cached search; explicit stale/unknown station data;
continued downloaded-route navigation; foreground notification during outage; route/CNG/maneuver
recovery after process death; active cached guidance; and normal in-session recovery after reconnect.

## Failure diagnostics

Return the bounded `/tmp/compass-phase13-{client,events,map-cache,ui,service,notification,logcat}.*`
artifacts printed by the runner. Phase 14 must not begin until the gate is accepted or explicitly
waived by the operator.

## Accepted live evidence

On 2026-09-03 the operator returned a complete successful run through step `[9/9]` and screenshots
A–G. The evidence showed an exact place-search fallback labelled with its device-cache timestamp;
stale CNG prices and unknown opening-hours enrichment; continued maneuver/progress guidance with
Compass unavailable; the foreground notification during the outage; route, maneuver and CNG-stop
recovery after process death; guidance using the recovered route and previously visited MapLibre
resources; and a successful live route replacement after reconnect with degraded/cache warnings
removed. The runner also verified explicit navigation termination, cache teardown, service and
notification teardown, and absence of fatal Compass exceptions.
