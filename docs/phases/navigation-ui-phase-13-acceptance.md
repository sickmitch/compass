# Navigation UI upgrade — Phase 13 acceptance record

Status: repository-local implementation complete; physical-device outage/recovery gate pending.

This record is for **Navigation UI Phase 13**. The older backend/mobile delivery milestone named
`phase-13` remains documented separately in `phase-13-acceptance.md`.

## Implemented scope

- The private route document advances to schema 2 and remains able to read schema-1 documents.
  Geometry, maneuvers, destination, CNG waypoints, timing metadata and routing/range parameters were
  already durable; schema 2 adds the active navigation checkpoint.
- The checkpoint contains the last map-matched pose and segment, progress, locally derived remaining
  distance/durations, current/next maneuver indexes, voice setting, completed CNG stop sequences and
  any active refuelling visit. It contains no raw Google Places text.
- Active guidance is checkpointed at most once every five seconds during ordinary GPS progress and
  immediately for lifecycle-critical transitions. This bounds private-storage writes without losing
  an entire trip segment after process recreation.
- Process recreation restores an active route directly into local guidance, with GPS explicitly lost
  until the next fix. `MainActivity` restarts the foreground location service when its permissions
  remain available. An active demo replay is restored as the position source and resumes at the
  saved segment without requiring the operator to start it again.
- An active CNG visit keeps its original arrival/completion wall-clock boundary and remains blocked
  on explicit driver confirmation after restoration. Completed stops stay completed.
- The foreground service observes Android default-network availability. Loss changes navigation to
  an explicit offline state without discarding or rematching the downloaded route. Server update
  scheduling is disabled while offline.
- Network return enters `RECOVERING` and requests a reason-labelled refresh through the existing
  Compass route recalculator. That path preserves destination, remaining fuel stops and the original
  CNG range/detour policy. A failed recovery remains on the downloaded route and retries with bounded
  backoff; only a successful replacement returns to `ONLINE`/live route state.
- While offline/recovering, the UI labels traffic as not refreshable, keeps local ETA/progress, and
  suppresses opening/price claims whose freshness cannot be established. Route, maneuver, voice and
  CNG waypoint guidance continue from local state.
- MapLibre keeps the existing bounded ambient cache for already visited resources. Compass does not
  promise that an arbitrary uncached map region is available offline, and routing remains server-side.
- The debug replay position source is part of the checkpoint. After process recreation it resumes
  from the saved route segment instead of silently switching to the stationary device GPS source.
- Connectivity is considered restored only after Android reports a validated Internet network. If
  the first Compass refresh still fails, bounded 5/15/60-second backoff retries preserve the local
  route and avoid a tight recalculation loop.
- Android version is `0.23.1` (`versionCode=30`).

The locally assembled, directly installable debug APK is
`dist/Compass-0.23.1-debug-installabile.apk`. SHA-256:
`0d3e6cc2dc77e124c651115526efe3a33d48b28da7205f2355154959ef1255c0`.

## Architectural decisions

The downloaded `NavigationRoute` remains the only route authority. Offline handling does not add an
on-device router, duplicate progress calculator or second Compose-owned state. The checkpoint is a
small input to `NavigationEngine.restore`; the engine reconstructs fuel progress and resumes normal
map matching on the next accepted location.

Connectivity is modeled separately from GPS and route source. A network callback is only an early
availability signal: HTTP/server failures can still produce `REROUTING_UNAVAILABLE`, and no Google,
TomTom or Valhalla request is required for local guidance. Recovery is a real, explicit route-update
reason rather than an unconditional UI refresh.

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-navigation-ui-phase13-live.sh
API_AUTH_ENABLED=false .venv/bin/pytest -q
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The Android tests cover schema-2 round trips, schema-1 compatibility, restored map-matched progress,
refuelling-visit restoration, explicit connectivity states, update suppression while offline, the
CNG-aware recovery reason and its 5/15/60-second retry schedule. They do not prove OEM
process/service behavior, radio transitions or reuse of real cached map tiles.

## Physical-device/live gate

Required working directory and existing secrets:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER='...'
export COMPASS_CHECK_PASSWORD='...'
bash scripts/run-navigation-ui-phase13-live.sh
```

Use `COMPASS_ALLOW_HTTP_LIVE_GATE=true` only for an explicitly accepted HTTP fallback. The runner
does not clear server credentials or vehicle profiles and performs no UIAutomator/screenshot
inspection. The operator validates three visible invariants: continued local guidance during a real
radio outage, automatic active-session/foreground-service recovery after process recreation, and one
safe CNG-aware refresh after connectivity returns.

Return the runner's complete output plus pass/fail notes for checks A-C. Do not proceed to Navigation
UI Phase 14 until this gate is accepted or explicitly waived.

## Failure diagnostics

Return the bounded artifacts printed by the runner:

```text
/tmp/compass-navigation-ui-phase13-navigation.txt
/tmp/compass-navigation-ui-phase13-fatal.txt
/tmp/compass-navigation-ui-phase13-service.txt
```

If recovery does not start, also return:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" shell dumpsys connectivity
"$ANDROID_SDK_ROOT/platform-tools/adb" logcat -d -t 500
```

## Remaining limitation

The ambient cache is opportunistic, not a downloadable offline-region product. A completely new map
area may have no basemap tiles while offline. Creating offline regions and local routing graphs would
be a separate, materially larger capability and is not implied by this phase.
