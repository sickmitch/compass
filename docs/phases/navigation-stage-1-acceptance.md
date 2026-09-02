# Navigation Stage 1 acceptance record

Status: accepted on 2026-09-02 from operator live/device evidence.

The live API contract reconciled base and selected-stop timing, preserved maneuver shape indexes
and assigned a 1,200-second dwell to the selected station. Android screenshots confirmed the
navigation-ready screen for zero, one and two CNG stops; the two-stop example showed 1 h 54 min of
driving plus 40 min dwell as a 2 h 34 min trip. Rotation/background-resume was reported successful.

## Scope and acceptance criteria

- Base, single-stop and multi-stop route responses expose a stable route ID and separate driving,
  refuelling-dwell and total-trip durations.
- Each CNG stop contributes exactly 1,200 seconds without changing Valhalla driving duration.
- Selected CNG stops expose expected arrival and dwell time.
- Android preserves Valhalla maneuver shape indexes, verbal guidance fields and bearings.
- Android maps route legs and stops into one provider-independent `NavigationRoute`.
- Maneuver indexes from later legs are translated into the joined route geometry.
- The UI exposes `Avvia navigazione` for base and fuel-aware routes and opens a navigation-ready
  route screen with map, timing, fuel stops and first maneuver.
- Rotation/background-resume does not crash or discard the route-preview session.

Foreground location, GPS matching, camera follow, spoken guidance and rerouting are Stage 2/3
work and are not represented as completed by this gate.

## Local validation

From the repository root:

```bash
.venv/bin/pytest -q
.venv/bin/ruff check .
.venv/bin/python scripts/export-openapi.py --check
python3 -m py_compile scripts/validate-navigation-stage1-live.py
bash -n scripts/run-navigation-stage1-live.sh
```

From `android/` with JDK and SDK variables configured:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Live/device gate

Use the single runner documented in `docs/android.md`. Return its complete output and the two
requested screenshots. Mark this record accepted only after the operator reports the gate green.
