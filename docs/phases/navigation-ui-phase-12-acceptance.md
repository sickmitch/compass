# Navigation UI upgrade — Phase 12 acceptance record

Status: complete. Refuelling gate accepted on 2026-09-08; candidate-order supplement accepted on
the physical Android device on 2026-09-09.

## Scope

Phase 12 turns a planned CNG waypoint into a real stop visit. Reaching the waypoint no longer lets
GPS progress silently mark it complete. Compass automatically enters a refuelling visit, keeps the
station as the current intermediate destination and resumes guidance only after explicit driver
confirmation. Routing, station selection and the configured CNG plan remain unchanged.

## State and timing contract

- `NavigationFuelStopVisit` is owned by `NavigationEngine`, not Compose. It records station,
  arrival instant, planned completion instant, remaining dwell and the current explicit-completion
  policy.
- A reliable matched fix within the centralized station-arrival threshold enters `AT_FUEL_STOP`
  with lifecycle `REFUELING`. A normal GPS step that crosses the waypoint is captured too, avoiding
  missed arrivals between sparse fixes.
- While the visit is active, raw fix freshness continues to update, but matched route progress,
  maneuver and authoritative puck position stay at the station. Off-route and traffic rerouting are
  suppressed, so stationary noise or a replay step cannot advance the trip behind the driver.
- The engine reduces remaining dwell against wall-clock time. During the planned dwell the final
  ETA remains stable; finishing early removes unused dwell. Once the planned duration is exceeded,
  Compass still waits for confirmation and the excess delay pushes final ETA forward.
- Each stop uses its routed `dwellTimeSeconds`; Compose contains no duplicate twenty-minute literal.
  Future-stop ETAs include any still-pending earlier dwell.
- `completeFuelStop()` is idempotent: only an active visit can complete. It marks the stop
  `COMPLETED`, exposes the next planned CNG stop, updates trip duration/ETA and resumes maneuver
  guidance. Completion can be initiated from the driving card, details sheet or foreground
  notification.

The current completion mode is `USER_CONFIRMATION`. The explicit enum and visit boundary allow a
future automatic policy without changing UI or route models. Active visit/timer persistence across
process death remains Phase 13 work; the route and planned stops continue to use the existing
private route cache.

## Driver feedback

At the stop, the maneuver card identifies `Rifornimento in corso` and the station name. The compact
CNG card shows the remaining planned duration, changes to `Tempo previsto concluso` after zero and
offers `Completato`. Details expose the same action. The foreground notification displays the
refuelling state and provides `Rifornimento completato`, so the visit can be closed after returning
to a backgrounded app.

Voice guidance announces station approach and arrival once as before, then announces
`Rifornimento completato. Riprendi il percorso.` once after confirmation. Demo replay pauses at the
station and resumes only through the same production completion action.

Android version is `0.22.1` (`versionCode=28`). No backend schema, API, Docker configuration or new
secret is required.

## Candidate-order supplement

The operator's gate screenshot exposed that the backend's multi-factor rank could put a `+1,2 min`
station before a `+0,1 min` station. The backend score remains available for explanation, but the
selection presentation now applies a separate deterministic policy:

1. finite `detourMinutes`, strictly ascending, is the absolute primary key;
2. the backend rank breaks exact detour ties;
3. MIMIT ID is the final stable tie-breaker;
4. the displayed `#1`, `#2`, … badges reflect this selection order.

Prices visible in the same result set receive a dense rank based on their displayed three-decimal
value within the same currency/unit. The cheapest distinct value uses pastel green, the second
pastel yellow, and all later values pastel red. Equal displayed prices share a tier. A missing price
has no coloured price panel. Price never changes the station order.

## Repository-local validation

From the repository root:

```bash
bash -n scripts/install-android-0.22.1.sh scripts/run-navigation-ui-phase12-live.sh \
  scripts/run-navigation-ui-phase12-ranking-live.sh
.venv/bin/pytest -q tests/test_navigation_ui_phase12_script.py
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Automated tests cover arrival/cross-fix capture, progress freezing, dwell countdown, ETA behavior
before and after the planned duration, refusal of route updates during refuelling, idempotent manual
completion, next-stop selection, UI mapping and one-shot voice completion.

## Live gate

The live API must be reachable through the saved Compass server profile and provide a predictive
CNG route. From the Android workstation:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER=<existing-api-user>
export COMPASS_CHECK_PASSWORD=<existing-api-password>
bash scripts/run-navigation-ui-phase12-live.sh
```

The runner preserves encrypted server credentials and vehicle profiles. It performs no UIAutomator
or screenshot inspection. Return its complete output and pass/fail notes for all six operator
checks. If a check fails, also return:

```text
/tmp/compass-navigation-ui-phase12-navigation.txt
/tmp/compass-navigation-ui-phase12-ui.txt
/tmp/compass-navigation-ui-phase12-fatal.txt
/tmp/compass-navigation-ui-phase12-service.txt
```

The operator reported all six refuelling checks successful on 2026-09-08. That original gate is
accepted. After installing `0.22.1`, validate only the candidate-order supplement with:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER=<existing-api-user>
export COMPASS_CHECK_PASSWORD=<existing-api-password>
bash scripts/run-navigation-ui-phase12-ranking-live.sh
```

Return its output and four pass/fail notes. On failure, also return
`/tmp/compass-navigation-ui-phase12-ranking-ui.txt` and
`/tmp/compass-navigation-ui-phase12-ranking-fatal.txt`.

## Final acceptance evidence

On 2026-09-09 the operator reported a green result for the reduced `0.22.1` candidate-order gate.
Together with the previously accepted six refuelling checks, this confirms strict detour ordering,
renumbered badges, dense pastel price tiers and unchanged selection/map behavior on the physical
device. Navigation UI Phase 12 is complete.
