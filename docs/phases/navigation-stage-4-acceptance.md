# Navigation Stage 4 acceptance record

Status: accepted from physical-device/live-backend evidence on 2026-09-02.

## Scope

Stage 4 adds an explicit in-navigation workflow for a planned CNG station that the driver wants to
skip or has found unavailable. It does not infer station closure and it never removes a fuel stop
without proving that the resulting route still preserves the caller's range and reserve.

The smallest safe interaction is:

1. the driver selects `Salta / sostituisci tappa CNG` for the next planned stop;
2. a confirmation explains that the stop will be excluded and that the current route remains if no
   complete safe alternative exists;
3. Android estimates remaining range from the accepted predictive plan and local route progress;
4. Compass reruns predictive planning from the snapped current position, with the unavailable MIMIT
   ID in `excluded_mimit_station_ids`;
5. only a `suggested` complete itinerary or a proven `not_needed` direct route is calculated and
   atomically installed in the existing navigation session;
6. no-safe-plan, missing-range-plan and network/server failures retain the downloaded route and show
   a distinct message.

Manual single-stop routes intentionally do not invent a tank state. They retain their route and ask
for a predictive range plan instead of silently treating the tank as full.

## Backend/API contract

`POST /api/v1/cng/predictive-candidates` accepts up to 32 distinct numeric official IDs in
`excluded_mimit_station_ids`. Exclusion is applied inside the PostGIS corridor query before the
candidate limit, enrichment and Valhalla matrices. The response echoes the applied IDs, allowing a
strict client to reject a server response that did not acknowledge the exclusion.

## Acceptance criteria

- A predictive itinerary exposes the replacement action only for the next planned CNG stop.
- Confirmation names the station when a name is available and describes the safe fallback.
- The old MIMIT ID is excluded by the Compass API and cannot occur in the replacement itinerary.
- Effective range, locally estimated remaining range, reserve, maximum detour and prior exclusions
  are preserved in subsequent replacement requests.
- A successful response replaces the route without restarting navigation or its foreground service.
- Debug replay restarts on the replacement geometry rather than continuing along the obsolete route.
- A no-safe-alternative or unavailable backend leaves route, maneuver, progress and stop controls
  usable.
- A manual route without a predictive fuel-range plan is not modified and explains why.
- `Termina navigazione` still removes the foreground service and notification.

## Repository-local validation required

Run from the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
.venv/bin/python scripts/export-openapi.py --check
bash -n scripts/run-navigation-stage4-live.sh
git diff --check
```

Run from `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Live/device gate

After synchronizing and deploying the API on the live test server, run from the repository root on
the workstation connected to the Android device:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage4-live.sh
```

If the API is remote, keep the SSH tunnel printed by the runner open. The device gate requires a
live predictive Milan-to-Bologna plan with at least one safe alternative after excluding its first
stop; this depends on the deployed station snapshot and is not claimed by repository-local tests.

## Required operator evidence

Return the complete runner output and three screenshots:

1. the replacement confirmation with the current CNG stop visible;
2. active navigation after replacement, showing a different next stop and retained progress;
3. the range-plan-required guard on a manually selected single-stop route.

Also confirm that the original station disappeared from the new plan, navigation did not restart,
and the foreground notification disappeared after `Termina navigazione`.

If an Android request fails, also return `/tmp/compass-navigation-stage4-client.txt`. Debug builds
log only the Compass endpoint path, HTTP status, elapsed milliseconds and exception class chain
under the `CompassApi` tag; request payloads, coordinates and response bodies are not logged.
The predictive endpoint has a 240-second client read/call timeout while ordinary API calls retain
their existing shorter limits. This covers the bounded multi-batch calculation without making all
connectivity failures slow to surface.

## Accepted live evidence

The operator ran `scripts/run-navigation-stage4-live.sh` against the loopback-tunnelled Compass API
on a physical Android device. All nine checks completed, including the deployed OpenAPI preflight,
Android tests/lint/APK assembly, cold launch, replacement start/commit events, foreground-service
continuity, manual-route range guard, final service/notification teardown and fatal-exception scan.

Screenshot A showed the confirmation naming `ANTEGNATICA ENERGIA SRL` and explaining that the
current route would be retained without a complete safe alternative. Screenshot B showed active
navigation and retained progress after the first planned stop changed to `BEYFIN`. Screenshot C
showed the manual-route guard, `Per sostituire questa tappa serve un piano autonomia predittivo.`,
while route, maneuver, progress, next stop and termination controls remained available.

The first attempt had exposed the Android client's 45/60-second network limit during the bounded
multi-batch predictive calculation. After limiting a 240-second timeout to this endpoint and adding
payload-free `CompassApi` diagnostics, the complete Stage 4 gate passed. Ordinary API calls retain
their shorter failure limits.
