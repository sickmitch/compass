# Navigation UI upgrade — Phase 4 acceptance record

Status: accepted by the operator on 2026-09-05 after live traffic activation and Android evidence.

## Scope

This phase makes the existing provider-backed Valhalla traffic calculation explicit in route
timing and Android presentation. It covers base preview, selected and predictive CNG routes, cached
navigation state and in-session route replacement. It does not introduce another traffic provider,
nationwide coverage, incident rendering or a new navigation engine.

## Design contract

- TomTom observations continue to enter only through the private `traffic-updater` and Valhalla's
  native, tileset-bound `traffic.tar`; the public API and Android never receive its credential.
- An omitted departure is sent as the current local minute with Valhalla `date_time.type=1`, current
  speed sources and `prioritize_bidirectional=true`. Explicit departures preserve their caller
  instant while being converted to the configured routing timezone.
- A traffic-aware error 442 still receives one graph-speed availability fallback. The fallback is
  visible as `traffic_aware=false`; it cannot satisfy this phase gate.
- Every successful traffic route is compared with one graph-speed Valhalla route for the same
  ordered locations and costing. `traffic_delay_seconds` is the non-negative duration difference;
  it is descriptive and is not added to the already traffic-aware driving duration.
- Traffic-aware timing requires all of: fresh/mock provider health, a successful time-aware route,
  no graph-speed fallback and a numeric baseline. Otherwise delay remains null and unavailable.
- `traffic_state`, `traffic_aware`, `traffic_observed_at`, delay value/state and existing dwell/ETA
  values cross the strict OpenAPI, Android DTO/domain and versioned cache boundaries.
- Android says `Traffico live incluso` only for a traffic-aware route and includes delay plus update
  time. Fresh data with route fallback, stale data, unavailable data and disabled traffic have
  distinct text.
- Debug route-commit diagnostics expose only bounded state and numeric timing, never provider
  payloads or credentials.
- Android version is `0.14.0` (`versionCode=15`).

## Repository-local validation

From the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
.venv/bin/python scripts/export-openapi.py --check
bash -n scripts/deploy-traffic-live.sh
bash -n scripts/run-navigation-ui-phase4-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover implicit/current and explicit departure serialization, prioritized bidirectional
requests, numeric base/waypoint delay comparison, bounded error-442 fallback, API health gating,
strict DTO decoding, cache round-trip and all Android traffic messages. Script tests cover the
persistent activation validator and CNG traffic validator without external calls.

## Live activation gate

On the server, keep the real TomTom key only in the uncommitted `.env`. Set
`TRAFFIC_VALHALLA_TILESET_VERSION` to the exact identity printed by Valhalla; never leave the
documentation placeholder. Then run:

```bash
cd ~/docker/compass
bash scripts/deploy-traffic-live.sh
```

The gate must finish with `ON-DEMAND TRAFFIC ACTIVATION COMPLETED`. It requires fresh TomTom health,
at least one managed directed edge, a single deduplicated route scope, prioritized bidirectional
Valhalla evidence and fresh numeric non-fallback timing in both route responses. Failure triggers a
best-effort reset of every newly managed edge and stops the updater.

### First activation feedback

The first operator run on 2026-09-05 correctly failed the strengthened gate. TomTom returned and
matched 10 segments, committed 151 directed edges and published fresh health for tileset
`valhalla-3.8.3:1788156046`, but both API routes reported `traffic_aware=false`. Valhalla logs showed
the requested bidirectional algorithm, while API logs showed that every `date_time.type=0` attempt
returned error 442 and used the explicit graph-speed fallback. The activation trap stopped the
updater and cleared its managed edges successfully.

The implicit-departure serialization now uses the current local minute as `date_time.type=1`, the
mode paired with prioritized bidirectional time-aware routing.

### Second activation feedback and root cause

The repeated operator run still correctly failed: TomTom again matched 10 segments and updated 151
edges without provider errors, while all three traffic attempts returned 442 and the API exposed
the graph-speed fallback. The same depart-at request was then reproduced against an independent
Valhalla service: the old Bologna endpoint (`44.4949,11.3426`) also returned 442 there, while its
time-invariant route succeeded. Bologna Centrale (`44.5057,11.3424`) succeeded with the same
depart-at and prioritized bidirectional options. This isolates the failure to temporal endpoint
reachability, not TomTom ingestion, tileset binding or native traffic encoding.

The production gate, traffic/CNG gate, synthetic gate and Android startup preview now use Bologna
Centrale. Arbitrary temporally unreachable user destinations keep the explicit graph-speed
fallback and cannot be mislabeled traffic-aware. Both failed activation runs remain diagnostic
evidence rather than phase acceptance.

### Accepted traffic activation

The operator repeated `scripts/deploy-traffic-live.sh` on 2026-09-05 with the corrected Bologna
Centrale endpoint. The strict gate completed with `ON-DEMAND TRAFFIC ACTIVATION COMPLETED` and
reported:

- TomTom provider state `fresh` with no detected credential logging;
- 298 tileset-bound managed Valhalla directed edges;
- exactly one route scope and an immediate repeat skipped by the five-minute interval;
- non-fallback traffic-aware timing with a numeric graph-speed delay baseline in both route
  responses;
- healthy API, private traffic updater and Valhalla services;
- running tileset identity `valhalla-3.8.3:1788156046`.

This accepts the server traffic activation. The printed tileset identity must be persisted in the
server's uncommitted `.env`.

## Android/device gate

If the backend is remote, leave this tunnel running in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

On the workstation attached to the Android device:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
export COMPASS_MAP_STYLE_URL=https://tiles.openfreemap.org/styles/liberty
bash scripts/run-navigation-ui-phase4-live.sh
```

Return the complete activation and device-runner output plus screenshots A–C: traffic-aware base
preview, traffic-aware CNG navigation details and the active route after a manual debug refresh.

## Expected invariants

- `/api/v1/traffic/health` reports TomTom `fresh`, traffic-aware routing enabled and a positive
  managed-edge count for the active Valhalla tileset.
- Milan–Bologna Centrale and its immediate repeat both report `traffic_state=fresh`,
  `traffic_aware=true`, an observation timestamp and a non-negative numeric delay.
- Preview and active navigation identify live traffic without adding delay twice to ETA.
- A complete 65/30/100/30 CNG itinerary retains the same traffic provenance.
- Manual active-route refresh commits in place, preserves navigation progress and logs
  `traffic_state=fresh traffic_aware=true`.
- Explicit termination removes the foreground service and notification, with no bounded fatal
  Android exception.

## Failure diagnostics

For server activation failure, return the `/tmp/compass-traffic-production-*` files printed by
`scripts/deploy-traffic-live.sh`, especially both route JSON files, health, updater log and Valhalla
log. For the device gate return the listed `/tmp/compass-navigation-ui-phase4-*` artifacts. Never
return `.env` or the provider key.

## Accepted Android evidence

The operator returned device evidence on 2026-09-05 and explicitly advanced the project to Phase 5.
The preview showed `Traffico live incluso`, a numeric zero-minute delay and observation time. Active
navigation details showed the same live-traffic contract alongside four planned CNG stops; a later
screenshot showed updated progress, duration and traffic observation time without losing the CNG
plan. Together with the explicit operator acceptance, this closes the remaining device gate. The
server activation output above remains the evidence for native-overlay routing and deduplication.
