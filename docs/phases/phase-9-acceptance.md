# Phase 9 acceptance criteria

Phase 9 implements the manual Android Add CNG Stop workflow over the accepted Phase 7 API. It keeps
the deterministic Milan-to-Bologna route from Phase 8 and does not implement destination editing,
predictive refuelling, traffic ingestion or active turn-by-turn navigation.

## Implemented gate

- The base preview exposes an `Aggiungi tappa` action and a distinct `Metano (CNG)` configuration
  stage.
- The user supplies maximum detour minutes and effective CNG range with bounded local validation.
- Search sends the device's offset-aware departure time to `POST /api/v1/cng/ranked-candidates` and
  excludes closed-at-ETA candidates through the backend default policy.
- Strict DTOs cover the complete ranking response rather than accepting unknown fields.
- Candidate mapping preserves MIMIT identity, road distance, detour, ETA, opening state/hours,
  phone, brand/operator, price timestamps/freshness and score components.
- The candidate map includes route and station markers; the list is ranked, scrollable and handles
  empty, loading and retryable failure states.
- A station card exposes the information required by the core UX and optionally launches the phone
  dialer without requiring phone permissions.
- Selection sends the official MIMIT ID to `POST /api/v1/routes/with-cng-stop`.
- The selected route rejects wrong IDs, wrong leg order/boundaries and totals outside the explicit
  two-metre/two-second Valhalla rounding tolerance, then renders a CNG waypoint and separate
  origin-to-station/station-to-destination maneuvers.
- Back, change-station and remove-stop transitions are explicit ViewModel state operations and
  survive normal Activity recreation.
- `scripts/run-phase9-live.sh` is an executable single-command API/device handoff with no inline JSON.

## Local validation required

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
cd ..
bash -n scripts/run-phase9-live.sh
python3 -m py_compile scripts/validate-phase9-live.py
```

## Local validation result

Validated on 2026-08-29:

- 17 JVM tests passed: five strict HTTP contract tests, three repository/domain mapping and
  precision-boundary tests, seven ViewModel workflow tests and two polyline6 tests;
- tests cover the exact ranking inputs, default closed exclusion, arrival/opening/price mapping,
  official-ID selection, two ordered legs, strict unknown-field rejection, input bounds, stable
  failures, back/change/remove transitions and offset-aware departure time;
- `lintDebug` completed with no errors and only the intentional pinned-Gradle version notice;
- `assembleDebug` produced Android application version `0.2.0` (`versionCode=2`);
- main and unit-test Kotlin compilation completed against MapLibre 13.6.0 and the pinned Phase 8
  toolchain;
- the Phase 9 Bash runner passed syntax validation;
- the standard-library Python live validator compiled and generated a correctly offset-aware,
  unindented-shell-free ranked request artifact;
- no live full-Italy ranking call, selected-stop Valhalla route or physical-device Phase 9
  interaction was executed by repository-local tests.

## Live gate

Follow `docs/android.md` and run `bash scripts/run-phase9-live.sh` on the build machine with the live
backend and one authorized Android device. The automated portion must validate a non-empty live
ranked response, select its top official MIMIT ID, validate the recomputed two-leg route, pass the
Android build, install the APK, cold-launch with `Status: ok` and find no immediate fatal exception.

Manual acceptance must then confirm the configuration form, ranked map/list, required station-card
fields, selected CNG waypoint/two maneuver sections, change/remove actions and lifecycle stability.
Return the complete runner output plus screenshots of the ranked candidates and selected route.

## Live validation result

Accepted from the operator's live backend and physical-device run on 2026-08-29:

- readiness reported database and routing `ready`, data explicitly `degraded`, and traffic
  explicitly `not_configured`;
- the live ranked request returned 16 eligible stations from the full-Italy dataset, with official
  MIMIT identities and a ten-minute maximum-detour policy;
- the saved live artifacts passed the Phase 9 validator, including the top MIMIT `3618` selection,
  exactly two ordered Valhalla legs and the explicit two-metre/two-second source-precision bound;
- the returned candidate-list screenshot shows the route and candidate markers plus ranked cards
  carrying detour, road distance from departure, station ETA, unknown-hours semantics, MIMIT price
  observation/freshness and explainable score components;
- the operator enumerated all 16 displayed candidates across MI, LO, PR, PC, BO and RE, confirming
  that the list was scrollable rather than limited to its initially visible cards;
- the selected-route screenshots show ARDA OVEST in Fiorenzuola d'Arda (PC), distinct origin/CNG/
  destination markers, a non-zero 211.0 km / 1 h 54 min Valhalla summary, remove/change actions and
  separate `Verso la stazione` and `Dalla stazione a Bologna` maneuver sections;
- after returning the live visual evidence, the operator instructed the work to continue, accepting
  the Phase 9 device gate.

The Phase 9 gate is complete. A later phase may begin only when explicitly requested.
