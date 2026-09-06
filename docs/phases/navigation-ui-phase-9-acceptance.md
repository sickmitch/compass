# Navigation UI upgrade — Phase 9 acceptance record

Status: accepted on the live backend and physical Android device on 2026-09-06.

## Scope

Phase 9 turns the accepted graph-backed speed limit and filtered matched speed into a stable visual
over-limit state. It does not define a legal enforcement tolerance, emit sound or vibration, infer
a limit when Valhalla has none, change routing cost, report an offence, or add lane guidance.

## Design contract

- The only speed input is the accepted `NavigationPosition.speedMetersPerSecond`; raw fixes are not
  read by the warning UI.
- Compliance is unavailable unless GPS is active and the current matched segment has a numeric
  graph-backed limit between 1 and 250 km/h.
- The visual warning enters at `limit + 5 km/h` and clears at `limit + 2 km/h`. This hysteresis is a
  presentation-stability buffer, not a statement about Italian enforcement tolerance.
- Missing, stale or invalid speed/limit input immediately returns to `UNAVAILABLE` and never creates
  an alert.
- The accepted regulatory sign keeps its white face and red border. While over limit, it adds a
  second bright-red outer ring, changes the number from black to red, and exposes an Italian
  accessibility description containing both current speed and limit.
- The warning is visual only. Audible or haptic warnings require an explicit user preference and a
  later phase.
- Camera, puck, traffic, route progress, CNG controls and foreground-service ownership are
  unchanged.
- Android version is `0.19.0` (`versionCode=20`).

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-navigation-ui-phase9-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover unavailable inputs, exact warning entry, hysteretic clearing, changed limits,
km/h display rounding and invalid policy configuration.

## Live gate

The accepted Phase 8 backend remains sufficient; this phase has no server migration or new secret.
Keep the backend tunnel open, attach one authorized Android device, then run:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-ui-phase9-live.sh
```

## Expected invariants

- The live route still exposes `speed_limit_source=valhalla_graph` and a non-empty numeric profile.
- The deterministic 79 km/h replay on a 30 or 50 km/h segment logs an `over_limit` transition only
  after the five-kilometre-per-hour entry buffer.
- The sign retains its regulatory face and gains a clearly distinct double red ring and red number.
- The warning remains legible in day and night themes and has an Italian over-limit accessibility
  description in the implementation contract. Visual acceptance is reported directly by the
  operator; the live runner does not inspect UI hierarchy or screenshots.
- Opening the trip panel keeps the puck above its measured obstruction; guidance remains usable.
- Explicit termination removes the foreground service and active notification.

## Failure diagnostics

Return the runner output and the operator's visual confirmation. Screenshots are optional. On a
technical failure, also return `/tmp/compass-navigation-ui-phase9-*`. Never return `.env`, provider
keys or tokenized map URLs. The runner deliberately does not call UIAutomator, inspect screenshots
or infer visual success from foreground-window state.

## Acceptance evidence

On 2026-09-06, the operator returned the complete physical-device gate result:

- all 53 Gradle test, lint and assembly tasks completed successfully;
- the debug APK installed and Compass cold-launched with `Status: ok`;
- the real route contract exposed its graph-backed speed-limit profile;
- deterministic replay produced one validated source-backed `over_limit` transition;
- foreground-service and active-notification teardown passed;
- fatal diagnostics passed;
- the operator explicitly confirmed the warning's visual behavior and accepted Phase 9.

The Navigation UI Phase 9 gate is complete. Navigation UI Phase 10 may begin only when explicitly
requested.
