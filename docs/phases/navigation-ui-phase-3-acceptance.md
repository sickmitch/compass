# Navigation UI upgrade — Phase 3 acceptance record

Status: accepted on device on 2026-09-05.

## Scope

This phase replaces instantaneous marker updates with an explicit, route-matched navigation
position and an interpolated MapLibre vehicle puck. It hardens the existing Android location
pipeline against stationary jitter, poor accuracy, stale delivery, implausible jumps and unstable
low-speed heading. Device feedback also required a bounded correction to follow-camera centering
and urban maneuver zoom. It does not change route rendering, Valhalla routing, CNG planning or
offline route persistence.

## Design contract

- `NavigationLocation` is the raw provider fix and remains available for diagnostics and reroute
  origin selection; it is never rendered as the active vehicle.
- `LocationFilter` validates accuracy and monotonic time, rejects fixes delivered more than ten
  seconds late and implausible jumps, smooths moving coordinate/speed, holds sub-three-metre
  stationary noise and ignores raw bearing below 2 m/s.
- The existing `RouteMatcher` remains authoritative for the on-route coordinate and segment.
- `NavigationPosition` is the single exposed matched pose: coordinate, segment, filtered speed,
  stabilized route heading, source accuracy and accepted timestamp. Former position properties are
  derived compatibility accessors, not separately stored state.
- `NavigationHeadingController` follows the matched route bearing with circular shortest-path
  smoothing, a bounded per-fix turn and a low-speed freeze.
- `NavigationPuckMotion` owns platform-independent transition policy and interpolation. Normal
  accepted fixes animate for 85% of their observed interval, bounded to 300–1300 ms; stationary
  deadband updates hold and initial/large discontinuities snap.
- `MapPuckAnimator` is the small Android/MapLibre frame adapter. It updates only the existing puck
  source and cancels with the composable lifecycle; it does not own navigation state.
- In follow mode the Phase 2 vehicle remains vertical to the viewport. In free/overview mode its
  stabilized matched bearing rotates smoothly relative to the map.
- The follow target is projected along the local route-heading centreline, preventing a bend beyond
  the immediate segment from pulling the puck sideways. Stronger bounded maneuver-proximity zoom
  and 22% top padding keep dense urban junctions legible and the vehicle low and centered.
- Accuracy, filtered speed, stabilized heading and rejection count are visible only in the
  dedicated developer screen.
- No external navigation SDK or new binary dependency is introduced. The missing capability was
  bounded interpolation and fix hardening, both adequately implemented over Compass's existing
  Valhalla-compatible matcher.
- Android version is `0.13.0` (`versionCode=14`).

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-navigation-ui-phase3-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Focused tests cover stationary deadband behavior, unstable low-speed GPS bearing, delayed and
implausible fix rejection, matched-heading freeze and circular rotation, interpolated coordinate
and heading, stationary display hold and large-discontinuity snap. Existing engine, reroute and
camera tests consume the consolidated navigation position.

## Live/device gate

The operator synchronizes the repository and runs:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
export COMPASS_MAP_STYLE_URL=https://tiles.openfreemap.org/styles/liberty
bash scripts/run-navigation-ui-phase3-live.sh
```

For a remote backend, keep the SSH tunnel printed by the script open. Return the complete output
and screenshots A–D: interpolated heading-up follow, interpolated map-relative free-camera puck,
developer-only pipeline diagnostics and restored follow.

## Expected invariants

- Across at least five demo fixes, vehicle position visibly moves instead of teleporting.
- The vehicle stays projected onto the route and turns through the shortest smooth angular path.
- In follow, it remains horizontally centered and low; nearby urban maneuvers produce a materially
  closer view than sparse instructions.
- Releasing the camera does not stop puck motion or navigation progress.
- Follow recovery requires no navigation restart and preserves the low, vertical vehicle.
- Developer diagnostics identify the authoritative position as route-matched and expose accuracy,
  filtered speed, stabilized heading and rejected-fix count.
- Navigation remains owned by the foreground service throughout the checks.
- Explicit termination removes both foreground service and notification.
- No Compass fatal exception appears in bounded logcat diagnostics.

## Remaining limitations

- Accuracy is retained in `NavigationPosition` and developer diagnostics but is not drawn as a
  radius during active route matching; doing so could misleadingly suggest that raw accuracy is
  matched-position confidence.
- The current platform source is Android `LocationManager`; provider fusion is not introduced by
  this phase.
- Puck interpolation is deliberately bounded and snaps discontinuities above 250 m. Confirmed
  off-route and rerouting behavior remains owned by the existing navigation engine.
- A static screenshot cannot prove motion quality. Operator observation plus bounded
  `puck_motion mode=animate ... source=matched` events is the acceptance evidence.

## First device-gate feedback

The initial run completed all automated checks and confirmed interpolation, matched-route state,
free-camera behavior and teardown. Screenshots A and D nevertheless showed a view that was too
wide and a laterally displaced puck. That evidence is treated as a failed visual gate: camera
centreline targeting and urban maneuver zoom were corrected, and the gate must be repeated before
this phase is accepted.

The next attempted run was blocked before navigation because Valhalla's time-dependent search
returned error 442 after reaching its iteration/convergence bounds for the otherwise routable
Milan–Bologna preview. The routing adapter now performs one explicit graph-speed retry only after a
traffic-aware no-path response. Waypoint routes use the same bounded fallback; a second no-path is
still returned normally. The live runner now probes the exact default route before building and
installing Android, so this server-side prerequisite fails early with a preserved response.

## Final device-gate result

The operator repeated the complete gate on 2026-09-05 after deploying the routing fallback. The
exact Milan–Bologna preflight reported `ready`, the Android build reused its validated configuration
cache, APK installation and cold launch succeeded, and all eight runner stages completed without a
reported fatal exception.

Returned screenshots and operator observation confirmed:

- the matched arrow moved continuously along the route and remained vertical, horizontally centered
  and low during heading-up follow;
- a maneuver 32 metres ahead produced a close junction view, while a maneuver 1.4 kilometres ahead
  eased to a wider view;
- manual camera release retained its viewport, kept the moving map-relative puck visible and exposed
  the readable `Ricentra` action;
- developer diagnostics reported `AGGANCIATA AL PERCORSO`, 4 m source accuracy, 22 m/s filtered
  speed, 131° stabilized heading and zero rejected fixes;
- explicit follow recovery resumed without restarting navigation; and
- foreground continuity, explicit termination, notification teardown and the bounded fatal-exception
  check all completed in the runner.

This evidence closes Navigation UI Phase 3. Navigation UI Phase 4 remains a separate gated increment.

## Failure diagnostics

Return the bounded `/tmp/compass-navigation-ui-phase3-*` artifacts printed by the live runner.
Do not proceed to Navigation UI Phase 4 until this gate is accepted or explicitly waived.
