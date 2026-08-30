# Phase 10 acceptance record

Status: accepted on 2026-08-30.

Phase 10 implements caller-estimated, reserve-aware CNG planning for the fixed accepted
Milan-to-Bologna device scenario. It does not claim telemetry, live traffic, active navigation,
background location or consumption prediction.

## Corrected implementation gate

- `POST /api/v1/cng/predictive-candidates` strictly requires full range, estimated remaining range,
  reserve, maximum detour and an offset-aware departure time.
- The first usable road distance is `remaining - reserve`; every post-refuelling usable distance is
  `effective full range - reserve`.
- A destination inside the first usable distance returns `not_needed` after one base route and skips
  station queries, matrices and enrichment.
- Otherwise the existing PostGIS corridor and bounded Valhalla detour pipeline finds eligible
  stations. Candidate enrichment is loaded once, never N+1.
- Bounded candidate-to-candidate Valhalla matrices drive a complete forward itinerary search. Search
  progress uses decreasing road-network distance to the destination.
- `suggested` is returned only with a complete ordered stop chain and final destination leg. A safe
  first station without a complete chain returns `no_complete_itinerary`.
- `not_needed`, `suggested`, `no_reachable_station`, `no_eligible_station` and
  `no_complete_itinerary` are distinct machine states.
- Every itinerary stop exposes previous-leg road distance/duration, ETA, opening state at that ETA,
  price/freshness, available/remaining range and a non-negative reserve margin.
- The response explicitly declares `full_effective_range_after_each_stop`, `road_network`,
  `request_origin`, `caller_estimated_remaining_range` and no configured live traffic.
- `POST /api/v1/routes/with-cng-itinerary` resolves ordered MIMIT IDs in one query, requests one
  Valhalla multi-waypoint route and revalidates every actual leg. A range violation returns HTTP 409
  `cng_itinerary_out_of_range`.
- Android shows the complete ordered refuelling plan rather than independent first-stop options,
  draws every stop, separates selected-route maneuvers by leg and applies safe drawing insets so
  content does not render under system bars.
- Android application version is `0.3.1` (`versionCode=4`).
- The manual Add Stop single-station flow remains backward compatible.

## Regression that invalidated the initial gate

On 2026-08-30 the operator completed the first automated/device run and supplied screenshots for
120/30/300, 300/30 and 31/30 profiles. The operator also tested 65 km remaining, 30 km reserve and
100 km full range. Compass offered reachable first stations but routed through only one, making the
rest of the 210.9 km trip impossible while preserving reserve.

That evidence rejects the original single-stop Phase 10 design. It is not recorded as acceptance.
The corrected gate makes 65/30/100 mandatory: the live predictive response must contain at least
three ordered refuelling stops on Milan–Bologna, and both the planning response and actual selected
route must prove every reserve margin non-negative.

## Repository-local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
.venv/bin/python scripts/export-openapi.py --check
python3 -m py_compile scripts/validate-phase10-live.py
bash -n scripts/run-phase10-live.sh
```

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
cd ..
```

## Repository-local validation result

Validated on 2026-08-30:

- 149 Python tests passed; five PostGIS integration tests were explicitly skipped because the local
  environment has no `TEST_DATABASE_URL`;
- backend tests cover inclusive boundaries, first/subsequent range arithmetic, the exact
  65/30/100 three-stop chain, no-complete-chain safety state, pairwise matrix behavior and actual
  multi-waypoint route rejection when a provider leg consumes reserve;
- 30 Android JVM tests passed, including strict predictive-itinerary and multi-route DTO mapping,
  domain invariants and the 65/30/100 ViewModel workflow;
- Ruff, checked-in OpenAPI synchronization, Python validator compilation and Bash syntax passed;
- Android lint and debug APK assembly passed for `0.3.1` (`versionCode=4`);
- repository-local checks did not run a full-Italy route, live Valhalla matrix, SSH tunnel or
  physical-device interaction.

## Corrected live gate

After synchronizing the repository, the operator rebuilds/restarts the API and runs
`bash scripts/run-phase10-live.sh` from the device workstation. When the backend is remote, keep the
documented SSH tunnel open in a separate terminal before running the script.

The automated gate first verifies that live OpenAPI exposes the corrected Phase 10 itinerary
contract, then validates four profiles:

- 120/30/300: a complete standard refuelling itinerary;
- 300/30/300: explicit `not_needed` with candidate work skipped;
- 31/30/300: explicit `no_reachable_station`;
- 65/30/100: at least three ordered stops plus a separately recalculated multi-waypoint route whose
  every actual leg preserves 30 km reserve.

The runner then builds, installs and launches Android. Its final instructions describe four device
scenarios in full sentences and request screenshots of the multi-stop plan, selected multi-stop
route, not-needed state and no-reachable warning.

## Live validation result

On 2026-08-30 the corrected automated live gate passed after API rebuild/recreate:

- live OpenAPI exposed the corrected Phase 10 contract;
- the standard 120/30/300 profile returned one itinerary stop;
- the 65/30/100 regression returned three ordered stops: `43690`, `46660`, `44264`;
- the selected route contained four legs and reported `all_legs_preserve_reserve`;
- Android tests, lint, APK assembly, install, cold launch and immediate fatal-exception check
  passed.

Manual screenshots confirmed the complete three-stop plan and initially exposed a device-rendering
defect where content overlapped the Android status bar. The repository now applies root safe drawing
insets, and the operator confirmed the status-bar rendering is fixed. Phase 10 is accepted.
