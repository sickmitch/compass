# Phase 11 acceptance record

Status: accepted on 2026-08-31.

## Scope

Phase 11 removes the fixed-route Android limitation without changing backend routing, CNG ranking,
predictive reachability, data ingestion or database schema. The Android client now starts from the
Milan-to-Bologna default but lets the operator edit origin and destination coordinates.

## Acceptance criteria

- The route preview exposes a clear `Modifica percorso` action.
- The coordinate form accepts latitude/longitude for both endpoints and rejects out-of-range values
  without submitting a route request or crashing.
- Applying valid coordinates recalculates the base route and changes the map/summary away from the
  default Milan-to-Bologna route.
- Route-dependent state is cleared after endpoint changes: ranked CNG candidates, predictive
  suggestions and selected routes must not survive across a different base route.
- Manual Metano search uses the edited route's origin/destination.
- Selected-stop route recomputation uses the edited route's origin/destination.
- Predictive CNG planning and accepted itinerary routing use the edited route's origin/destination.
- Route labels for edited routes must not retain fixed Milan/Bologna copy; destination sections use
  generic destination wording until named places/geocoding exist.
- Android keeps root safe drawing insets; no content may render under the status bar.
- Android app version is `0.4.0` (`versionCode=5`).

## Repository-local validation required

Run from the repository root:

```bash
python3 -m py_compile scripts/validate-phase11-live.py
bash -n scripts/run-phase11-live.sh
.venv/bin/ruff check .
.venv/bin/python scripts/export-openapi.py --check
```

Run from `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Live/device gate

After synchronizing the repository to the operator's test workstation/server, run:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase11-live.sh
```

If the backend is remote, keep this tunnel open in a separate terminal first:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

The runner generates the Rome-to-Florence preflight request and response under
`/tmp/compass-phase11-*.json`, builds the Android APK, installs it, launches Compass and checks for
an immediate fatal exception.

## Required operator evidence

Return the complete runner output plus exactly these screenshots:

1. edited Rome-to-Florence preview with summary and map visible;
2. manual CNG candidates for the edited route;
3. selected-stop route for the edited route;
4. predictive CNG result for the edited route;
5. invalid-coordinate validation message.

## Accepted evidence

Repository-local validation passed before the live gate:

- `python3 -m py_compile scripts/validate-phase11-live.py`;
- `bash -n scripts/run-phase11-live.sh`;
- `.venv/bin/ruff check .`;
- `.venv/bin/python scripts/export-openapi.py --check`;
- `git diff --check`;
- `.venv/bin/pytest -q`: 149 passed, 5 skipped;
- Android `testDebugUnitTest lintDebug assembleDebug`: build successful.

The operator then ran `bash scripts/run-phase11-live.sh` against the live backend/device. The runner
reported:

- `/health/ready` returned ready database/routing, degraded data and `traffic=not_configured`;
- the generated Rome-to-Florence API preflight returned Valhalla distance `273804.0` metres,
  duration `8776.295` seconds and 21 maneuvers;
- Android unit tests, lint and debug APK assembly passed;
- debug APK installation succeeded;
- cold launch returned `Status: ok`;
- no immediate fatal Compass exception was detected.

The first device pass exposed fixed `Bologna` destination copy in the predictive edited-route
screen. The repository was corrected to use generic destination labels. The operator confirmed the
corrected Phase 11 flow now behaves as expected, including edited-route preview, manual CNG
candidates, selected-stop route, predictive result and invalid-coordinate validation.

Phase 11 is accepted.
