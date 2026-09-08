# Navigation UI upgrade — Phase 10 acceptance record

Status: accepted on the live backend and physical Android device on 2026-09-08.

## Scope

Phase 10 refines the existing Compass off-route path rather than adding a second matcher or routing
engine. Accepted GPS fixes are evaluated locally; only a confirmed episode enters the existing
Compass repository/API recalculator. Valhalla remains authoritative for the replacement route, and
predictive CNG constraints remain part of that request.

## Detection contract

- General lateral evidence exceeds the greater of 20 metres and 1.5 times reported accuracy.
- Three consecutive poor accepted fixes spanning at least two seconds are required. A burst of
  several callbacks in less than that interval remains only suspected.
- A moving heading conflict begins at 4 m/s and 65 degrees, and is considered only outside the
  separate uncertainty boundary of the greater of 10 metres and reported accuracy.
- More than 60 metres of backwards route progress remains independent evidence.
- Explicitly stationary fixes suppress ordinary lateral drift. A displacement greater than three
  times the normal accuracy-aware threshold can still become evidence, preventing an indefinitely
  trusted gross position jump.
- Two consecutive reliable fixes are required to clear a confirmed deviation. A suspected episode
  still clears immediately on a reliable fix.
- The state exposes route distance, a bounded local match-confidence diagnostic and episode
  duration. These are local navigation diagnostics, not a provider probability.
- While a match is suspected or confirmed, the old route's progress, maneuver, ETA and next CNG
  stop do not advance from an unreliable projection. The raw accepted fix remains available as the
  origin of an off-route request.
- Scheduled traffic refresh waits for a reliable on-route position and cannot race a suspected
  deviation.

## Rerouting and failure contract

- A confirmed episode starts one `OFF_ROUTE` update. A failed request may retry after the existing
  60-second backoff while the deviation remains confirmed.
- The off-route origin is the accepted raw GPS coordinate, never the coordinate snapped onto the
  obsolete route.
- The destination is retained. Remaining CNG stops, effective range, residual range, reserve,
  maximum detour and exclusions are preserved; a stop rejected by Compass is replanned through the
  existing predictive policy rather than silently downgraded to unconstrained A-to-B routing.
- A network/server failure retains the downloaded route, last reliable local progress, maneuver
  guidance, voice state and CNG plan. Connectivity becomes `REROUTING_UNAVAILABLE`; navigation is
  not ended.

## Driving feedback

Every in-progress route update exposes one compact surface immediately below the maneuver card. It
contains an indeterminate circular spinner and the text `Ricalcolo rotta`, with the combined Italian
accessibility description `Ricalcolo rotta in corso`. It does not block map interaction, replace the
maneuver or contribute fake route progress. The foreground notification uses the same short status
while recalculation is active.

After a successful off-route replacement, the existing ten-second `Prima` / `Ora` / `Differenza`
notice remains authoritative for the travel-time impact. Traffic and manual refreshes do not create
that notice.

Android version is `0.20.0` (`versionCode=25`).

## Repository-local validation

From the repository root:

```bash
bash -n scripts/install-android-0.20.0.sh scripts/run-navigation-ui-phase10-live.sh
.venv/bin/pytest -q tests/test_navigation_ui_phase10_script.py
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Tests cover minimum episode duration, stationary drift, recovery hysteresis, frozen unreliable
progress, delayed traffic refresh, raw reroute origin, CNG plan preservation/replanning, downloaded
route retention and the state that drives the recalculation indicator.

## Live gate

The live server needs no migration or new secret. It must remain reachable using the server profile
already saved in Compass. From the Android workstation:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER=<existing-api-user>
export COMPASS_CHECK_PASSWORD=<existing-api-password>
bash scripts/run-navigation-ui-phase10-live.sh
```

The runner preserves application data and therefore the encrypted server credentials and saved
vehicle profiles. It performs no UIAutomator or screenshot inspection. The operator confirms the
spinner, successful direct and CNG-aware replacement, and offline retention; bounded log checks
only verify that the corresponding production state transitions occurred.

Return the complete runner output, pass/fail notes for its five operator checks, and these artifacts
only if a check fails:

```text
/tmp/compass-navigation-ui-phase10-navigation.txt
/tmp/compass-navigation-ui-phase10-ui.txt
/tmp/compass-navigation-ui-phase10-fatal.txt
/tmp/compass-navigation-ui-phase10-service.txt
```

## Acceptance evidence

On 2026-09-08 the operator reported that the complete Phase 10 gate passed. This confirms the five
operator-owned checks: visible non-blocking `Ricalcolo rotta` feedback, successful direct-route
replacement, continuation with the existing duration-difference notice, preservation or safe
replanning of the CNG-aware route, and downloaded-route retention when rerouting is unavailable.

Repository-local unit, lint, assembly, runner-contract and shell checks had already passed before
the handoff. No UIAutomator or screenshot result is claimed; visual and behavioral acceptance comes
from the operator's physical-device confirmation.

Navigation UI Phase 10 is complete. Phase 11 remains unstarted until explicitly requested.
