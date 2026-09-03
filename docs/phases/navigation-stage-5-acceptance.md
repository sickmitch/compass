# Navigation Stage 5 acceptance record

Status: accepted on a physical Android device against the live backend on 2026-09-03.

## Scope and safety contract

Stage 5 adds persisted Android vehicle profiles and an optional direct-route gasoline fallback. A
profile supplies full effective ranges and reserves, not current tank levels. The driver must enter
remaining CNG range and, to enable fallback, remaining gasoline range. The backend prefers any
complete CNG itinerary and returns `gasoline_fallback` only when CNG planning fails and the direct
route preserves both reserves.

## Acceptance criteria

- creating and selecting a profile pre-fills CNG full range/reserve and gasoline full range/reserve;
- the selected profile survives force-close and cold launch;
- current remaining ranges are not persisted or silently invented;
- omitting gasoline retains the previous CNG-only contract;
- an available complete CNG itinerary wins even when gasoline was supplied;
- an insufficient gasoline estimate preserves the specific CNG failure state;
- an accepted fallback displays required gasoline and destination margin in status, navigation
  preview and active navigation;
- rerouting a direct fallback route retains its fallback label;
- terminating navigation removes the foreground service and notification.

## Repository-local validation

From the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
.venv/bin/python scripts/export-openapi.py --check
bash -n scripts/run-navigation-stage5-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Live/device gate

After synchronizing and deploying the API, run from the repository root:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage5-live.sh
```

Return the complete output and the three screenshots requested by the runner. If it fails, also
return the bounded diagnostics printed by the script. Live success is not claimed until that
evidence is supplied.

## Accepted live evidence

The operator ran `scripts/run-navigation-stage5-live.sh` against the loopback-tunnelled Compass API
on a physical Android device and confirmed every runner requirement. The selected `Test dual fuel`
profile survived the requested force-close/cold launch and restored its configured range and reserve
values. The deliberately constrained CNG plan did not produce a complete CNG itinerary, while the
backend returned the explicit gasoline fallback and navigation continued on the direct route. The
operator also confirmed that terminating navigation removed the foreground notification.

Screenshot A showed `Mezzo: Test dual fuel` selected on the Milan-to-Bologna route preview.
Screenshot B showed `Fallback benzina disponibile`, 205.9 km estimated gasoline use and a 64.1 km
margin above the requested gasoline reserve, while preserving the 30 km CNG reserve. Screenshot C
showed active guidance with the maneuver, route progress, termination control and
`Fallback benzina attivo` label still visible.
