# 0.28.40 — traffic alerts without traffic-adjusted ETA

## Current policy (supersedes earlier traffic-aware ETA gates)

The deployed routing dependency now selects `traffic_alert_only=True`. Auto routes,
waypoint legs, maneuvers and CNG matrices use Valhalla graph speeds (`constrained`,
`freeflow`), explicitly excluding `current` and `predicted` speeds and time-dependent
costing. Compass returns these durations unchanged; planned stop dwell remains separate
and is still included in the total journey time. The chosen geometry also comes from
the graph-speed route, never from the live comparison.

The updater/overlay and on-demand refresh remain enabled for detection. A separate
best-effort Valhalla live-route comparison sets `traffic_detected` when its duration
exceeds the baseline by more than one second. It cannot replace the route or its costs;
failure leaves the valid baseline intact. API metadata exposes an alert only when
provider health is fresh/mock with an observation timestamp. Production mock configuration
must not be used as real traffic evidence.

Detection remains limited by traffic coverage and the route-comparison heuristic:
the live comparison may choose a different path, and a negative result is not proof
of uncongested roads. It is not a measured delay on every edge of the selected route.
The diagnostic comparison still uses traffic, but no returned duration, ETA, route choice
or CNG detour ranking uses its costs. Provider refresh costs are not removed by this policy.

`navigation.traffic_detected` is additive, default false. New production responses have
`traffic_aware=false`, `traffic_delay_seconds=null`, `traffic_delay_state=unavailable`.
Android displays no traffic minutes. When detected it displays exactly:

> Traffico rilevato, l'ETA è riferito alle condizioni ottimali su questo percorso.

The boolean is persisted in downloaded routes. Existing response/cache formats remain
readable. The APK explicitly warns when an older backend still returns traffic-aware
durations, instead of mislabelling them as optimal. Recalculate any existing route after
updating: an old cached route retains its original durations.

## Validation

Local fixture tests cover baseline geometry/leg/maneuver preservation despite a slower
live response, probe failure, static matrix costs, freshness gating, unchanged dwell and
the exact Android alert without minute counts. OpenAPI is synchronized.

Local result: 454 Python tests passed (five PostGIS integration tests excluded), 280
Android unit tests passed, Ruff/OpenAPI/diff checks and Compose configuration validation
passed. APK build and signature verification passed; device/live validation is pending.
SHA-256: `8ef21bfd933b06bbe7d30806bb03811d8ae1d90595490585e40b750e88918358`.

```bash
.venv/bin/ruff check src tests
.venv/bin/pytest -q -m 'not integration'
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

## Operator deployment and acceptance

Both API and APK must be updated. On the live server, after syncing this repository,
from `~/docker/compass` (existing `.env`, auth and provider secrets retained):

```bash
docker compose build api
docker compose up -d --no-deps api
```

No migration or traffic-updater configuration change is required. Install
`dist/compass-0.28.40-debug.apk` directly on the device, or from the development repository:

```bash
adb install -r dist/compass-0.28.40-debug.apk
```

Calculate a NEW auto route, then one with an intermediate/CNG stop. These normal live
route calculations can trigger configured paid traffic-provider requests. Check:

- No traffic-delay minutes in summary/navigation.
- With detected traffic, the exact alert appears; ETA remains the graph-speed estimate.
- With stale/missing traffic, no positive traffic-detected claim.
- Route duration and maneuver/leg durations agree with the static Valhalla response;
  total journey time additionally includes planned dwell, not a traffic penalty.
- Walking/cycling remain unchanged.

If behavior differs, return screenshots and the `navigation` section of the route response,
plus relevant redacted logs:

```bash
docker compose logs --since 10m --tail 200 api traffic-updater
adb logcat -d -v threadtime CompassNavigation:I CompassNavigationUi:I AndroidRuntime:E '*:S'
```

No live deployment, provider calls or device validation are performed by the agent.
