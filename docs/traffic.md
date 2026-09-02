# Live traffic architecture

Compass treats traffic as an external observation stream that modifies Valhalla edge costs through
Valhalla's native live-traffic overlay. Compass does not perform independent traffic-aware routing
and does not add post-hoc traffic penalties to CNG ranking.

## Current Valhalla deployment

The repository currently deploys Valhalla through Docker Compose:

- image: `ghcr.io/valhalla/valhalla-scripted:3.8.3`;
- graph input default: `https://download.geofabrik.de/europe/italy-latest.osm.pbf`;
- shared data volume: `/custom_files`;
- tile directory: `/custom_files/valhalla_tiles`;
- tile extract: `/custom_files/valhalla_tiles.tar`;
- Valhalla config: `/custom_files/valhalla.json`;
- traffic extract target: `/custom_files/traffic.tar`;
- tile build job: `valhalla-tiles` profile `routing-build`;
- runtime service: `valhalla` profile `routing`.

The scripted Valhalla container already looks for `/custom_files/traffic.tar` at runtime. Missing
traffic is a warning, not a Compass outage; Valhalla falls back through its normal speed hierarchy.

## Subsystem boundaries

Traffic code lives under `compass.traffic`:

```text
compass/traffic/
  domain.py              provider-independent traffic records and edge updates
  health.py              atomic updater runtime health and staleness derivation
  service.py             provider/config wiring and cost-basis reporting
  cli.py                 operational/debug entry point
  route_refresh.py       route sampling, five-minute ledger and internal client
  routing.py             best-effort refresh/re-route boundary
  updater_api.py         private on-demand updater and local expiry sweep
  providers/mock.py      deterministic offline provider
  providers/tomtom.py    TomTom HTTP adapter boundary
  matching/base.py       safe unmatched implementation
  matching/openlr.py     native Valhalla OpenLR decoder boundary
  matching/valhalla.py   OpenLR-verified geometry-to-directed-edge matcher
  valhalla/overlay.py    native traffic extract command helper
  valhalla/graph_id.py   packed/string GraphId normalization
  valhalla/planner.py    deterministic set/reset/expiry planning and state schema
  valhalla/executor.py   transactional native batch plus durable-state commit
```

The native debug writer lives outside the Python package:

```text
tools/valhalla-traffic/
  Dockerfile             builds against the same pinned Valhalla image
  traffic_tar_tool.cc    inspect/set/reset TrafficSpeed records in traffic.tar
```

The normalized provider-independent record is `TrafficFlowSegment`. It can carry:

- provider and provider segment ID;
- observation and expiry timestamps;
- OpenLR;
- optional OSM way references;
- optional geometry;
- direction;
- current/free-flow speed;
- current/free-flow travel time;
- confidence;
- congestion;
- explicit road closure flag;
- prediction flag.

Missing provider fields stay `null`/`None`. The subsystem does not manufacture values.

## TomTom mode currently supported

The current TomTom adapter targets the base TomTom Traffic Flow API available with the project key,
specifically the `flowSegmentData` endpoint. In normal operation Compass samples bounded probe
points from the Valhalla route geometry and requests `openLr=true`. Static configured points remain
available only for CLI diagnostics.

This is not equivalent to TomTom Intermediate Traffic / Orbis:

- Traffic Flow API base returns the road fragment nearest to each requested point.
- It can include current speed, free-flow speed, travel times, confidence, road-closure state,
  coordinates and OpenLR.
- It is useful for deterministic development, corridor probes and validating the provider boundary.
- It is not a nationwide server-to-server bulk feed by itself.

Intermediate/Orbis remains a future adapter option if the subscription changes. It must still feed
the same `TrafficFlowSegment` model.

## Configuration

Traffic is disabled by default.

```env
TRAFFIC_ENABLED=false
TRAFFIC_PROVIDER=none
TRAFFIC_REFRESH_MODE=on_demand
TRAFFIC_REFRESH_SECONDS=60
TRAFFIC_ROUTE_REFRESH_MIN_INTERVAL_SECONDS=300
TRAFFIC_ROUTE_PROBE_SPACING_KM=25
TRAFFIC_ROUTE_MAX_PROBES=16
TRAFFIC_ROUTE_REFRESH_TIMEOUT_SECONDS=45
TRAFFIC_UPDATER_URL=http://traffic-updater:8003
TRAFFIC_REFRESH_LEDGER_PATH=/custom_files/compass_traffic_state/route_refresh.json
TRAFFIC_EXPIRY_SWEEP_SECONDS=30
TRAFFIC_UPDATE_SEGMENT_LIMIT=1000
TRAFFIC_MAX_AGE_SECONDS=300
TRAFFIC_MIN_CONFIDENCE=0.50
TRAFFIC_MIN_MATCH_CONFIDENCE=0.75
TRAFFIC_MATCH_SEARCH_RADIUS_METERS=75
TRAFFIC_MATCH_GPS_ACCURACY_METERS=15
TRAFFIC_OPENLR_DECODER_PATH=/usr/local/bin/compass-valhalla-traffic-tool
TRAFFIC_OPENLR_DECODER_TIMEOUT_SECONDS=2
TRAFFIC_OPENLR_ENDPOINT_TOLERANCE_METERS=300
TRAFFIC_WRITER_TIMEOUT_SECONDS=60
TRAFFIC_MAX_SPEED_KPH=180
TRAFFIC_VALHALLA_OVERLAY_ENABLED=false
TRAFFIC_VALHALLA_TILESET_VERSION=
TRAFFIC_MAPPING_VERSION=unbuilt
TRAFFIC_STATE_PATH=/custom_files/compass_traffic_state/state.json
TRAFFIC_HEALTH_PATH=/custom_files/compass_traffic_state/health.json
TRAFFIC_MOCK_FIXTURE_PATH=
TOMTOM_TRAFFIC_API_MODE=flow_segment
TOMTOM_TRAFFIC_URL=
TOMTOM_FLOW_SEGMENT_POINTS=
TOMTOM_FLOW_SEGMENT_STYLE=absolute
TOMTOM_FLOW_SEGMENT_ZOOM=10
TOMTOM_FLOW_SEGMENT_UNIT=kmph
TOMTOM_FLOW_SEGMENT_OPENLR=true
TOMTOM_API_KEY=
TOMTOM_MAX_CONCURRENCY=2
```

For the base TomTom API, production route-scoped mode does not require static points:

```env
TRAFFIC_ENABLED=true
TRAFFIC_PROVIDER=tomtom
TRAFFIC_REFRESH_MODE=on_demand
TOMTOM_TRAFFIC_API_MODE=flow_segment
TOMTOM_API_KEY=...
```

`TOMTOM_FLOW_SEGMENT_POINTS` is an optional semicolon-separated `lat,lon` fallback for commands
such as `fetch-once`, `match-once` and `plan-once`. It is ignored when a route-scoped request
supplies dynamic probes.

## Route-scoped update policy

The public API never receives TomTom credentials and never calls TomTom directly. A current
`POST /api/v1/routes` request follows this sequence:

```text
initial time-dependent Valhalla route
  -> private traffic-updater route-refresh request
  -> sample at most TRAFFIC_ROUTE_MAX_PROBES points from that route
  -> TomTom base Flow Segment requests
  -> normalize, direction-verify, match and atomically update traffic.tar
  -> repeat the same Valhalla route only when the overlay changed
```

The updater records successful refreshes by a stable hash of origin, waypoints, destination and
costing. The same itinerary is skipped until
`TRAFFIC_ROUTE_REFRESH_MIN_INTERVAL_SECONDS` has elapsed (300 seconds by default). The route is
still returned on every API request: only the external traffic fetch is suppressed. A selected CNG
stop or multi-stop itinerary has a different scope key and therefore refreshes the geometry of the
actual route once selected.

Scheduled departures more than five minutes from the current instant do not fetch current traffic.
They continue to use Valhalla's appropriate time-dependent fallback hierarchy. Provider/updater
failure also returns the initial Valhalla route and does not take Compass offline.

The updater contains a lightweight expiry sweep. It performs no TomTom HTTP requests; it only
resets expired Compass-managed `TrafficSpeed` records to Valhalla `UNKNOWN`. This prevents an old
speed from surviving indefinitely when the app is closed.

The Android client currently triggers the initial refresh naturally by requesting A -> B. Compass
does not yet expose a real “navigation active” lifecycle or current-position tracking. The
five-minute backend contract is ready for that lifecycle, but the app must not poll merely because
the route-preview screen is visible. A later navigation increment will send
`active_navigation` refreshes only between explicit start/stop navigation events.

To run deterministic provider ingestion without commercial credentials:

```bash
TRAFFIC_ENABLED=true \
TRAFFIC_PROVIDER=mock \
TRAFFIC_MOCK_FIXTURE_PATH=tests/fixtures/traffic/mock_flow_segments.json \
.venv/bin/python -m compass.traffic.cli fetch-once
```

To inspect API-facing traffic state:

```bash
curl --fail --silent --show-error http://127.0.0.1:8000/api/v1/traffic/health
```

The updater atomically publishes its last runtime snapshot to `TRAFFIC_HEALTH_PATH`. The API mounts
the Valhalla volume read-only and never fetches TomTom itself. The endpoint reports fetch timestamps,
feed age, normalization/matching counts, updated/expired/managed edges, provider failures, mapping
version and tileset identity. A successful observation becomes `stale` after
`TRAFFIC_MAX_AGE_SECONDS`; malformed or mismatched health becomes `unavailable`. Neither condition
makes `/health/ready` fail while database, station data and Valhalla remain available.

To fetch configured provider records and map-match them without writing the overlay:

```bash
compass-traffic match-once --limit 10
```

The output includes the ordered directed GraphIds, match confidence, direction status, runtime
tileset identity, matcher version, warnings and a separate `write_eligible` safety decision. The
read-only diagnostic also includes the normalized OpenLR and geometry needed to verify direction;
it deliberately omits provider speed values from this matching evidence.

To convert a provider snapshot and its verified matches into whole-edge operations without changing
the overlay or persisting updater state:

```bash
compass-traffic plan-once --limit 10
```

This dry run reports proposed Valhalla `set` updates, expired GraphIds that would be reset to
unknown, rejected records and the state that would remain active. It intentionally starts from an
empty in-memory state; durable updater execution is a separate gated increment.

The initial live TomTom base-Flow-Segment diagnostic was run against the deployed Valhalla tileset
`valhalla-3.8.3:1788156046`. Three segments were matched by `geometry_trace` with confidence
`0.9541`, `0.9540` and `0.8783`; all three exposed ordered directed GraphIds and passed the
quality threshold (`0.75`). Direction was deliberately left unverified until the native OpenLR
gate below was accepted. No provider speed was written to `traffic.tar`.

The integrated matcher gate was subsequently accepted for the same three records: native OpenLR
endpoint verification ran inside the Compass matcher, all three results had `direction_match=true`
and all three were `write_eligible`. The mapping version was
`valhalla-openlr-geometry-v1`, provider overlay writes remained disabled, and the before/after
`traffic.tar` SHA-256 remained
`c9dc81c60833b9cd72436aa15e2ee9c8a6d27f7d75bd57a51c097f07a4764884`.

## Docker services

The API process does not fetch provider traffic. Traffic ingestion is separated into
`traffic-updater`:

```bash
docker compose --profile traffic up -d traffic-updater
```

`traffic-updater` uses `Dockerfile.traffic`, whose base is the exact pinned Valhalla image used by
the router. It contains Compass plus the native OpenLR/traffic helper linked to that image's
`libvalhalla`. The generic API image intentionally does not contain this helper. The updater listens
only on the private Compose network at port 8003. When both traffic and overlay writes are enabled,
it fetches, normalizes, matches, plans and commits only after a route-scoped request from the API;
it does not run a provider polling loop.

The state file configured by `TRAFFIC_STATE_PATH` binds every Compass-owned GraphId to the active
tileset and its expiry. By default it lives at
`/custom_files/compass_traffic_state/state.json`, alongside the matching routing/traffic extracts
in the private `valhalla_data` volume. Keeping state and extracts in the same lifecycle prevents a
detached state volume from surviving replacement of the graph it describes. `plan-once` never
writes it.

The independent `TRAFFIC_REFRESH_LEDGER_PATH` is bound to the same tileset identity. It controls
only route-request deduplication and contains no provider credentials. A graph rebuild invalidates
both the edge state and the refresh ledger.

The Valhalla image creates `traffic.tar` as root. The dedicated updater therefore runs as root
*inside its container only*, without privileged mode, with all Linux capabilities dropped,
`no-new-privileges`, a read-only root filesystem, and writable access restricted to the Valhalla
data volume plus a small temporary filesystem.

Create the native Valhalla traffic extract skeleton with:

```bash
docker compose --profile traffic-build run --rm valhalla-traffic-extract
```

That command runs Valhalla's own `valhalla_build_extract --with-traffic --overwrite` against a
temporary config. Both generated archives use temporary paths; only the completed
`traffic.tar` is atomically moved into place, while the active `valhalla_tiles.tar` is left
byte-for-byte untouched.

Build the native debug writer with:

```bash
docker compose build valhalla-traffic-tool
```

The writer is compiled against the same pinned Valhalla image as the router. It uses Valhalla's
`TrafficTileHeader`, `TrafficSpeed`, `GraphId` and `midgard::tar` definitions directly, including
support for concatenated TAR sections separated by empty blocks. Its image build runs a binary
`inspect`/`set`/`reset` test against that archive layout.

The same native helper can decode a base64 OpenLR reference using Valhalla 3.8.3's own
`baldr/openlr.h` implementation:

```bash
docker compose --profile traffic-tools run --rm --no-deps valhalla-traffic-tool \
decode-openlr --reference 'CwajFyA9fAEJRxG5+OgBGw=='
```

It reports the canonical round trip, ordered LRPs, bearings, FRC, form-of-way, encoded distance
and offset buckets. For line locations the order is explicitly `first_lrp_to_last_lrp`. This
decoder does not itself resolve the LRPs to GraphIds; that mapping still belongs to the matcher.
The helper is compiled as C++20 because the pinned Valhalla headers use standard ranges and
`string_view::starts_with`.

The same helper exposes operator/debug actions in addition to the updater's transactional batch
command:

```bash
docker compose --profile traffic-tools run --rm --no-deps valhalla-traffic-tool \
inspect --traffic-tar /custom_files/traffic.tar --graph-id LEVEL/TILE/EDGE

docker compose --profile traffic-tools run --rm --no-deps valhalla-traffic-tool \
set --traffic-tar /custom_files/traffic.tar --graph-id LEVEL/TILE/EDGE \
--speed-kph 5 --congestion 1.0 --incidents

docker compose --profile traffic-tools run --rm --no-deps valhalla-traffic-tool \
reset --traffic-tar /custom_files/traffic.tar --graph-id LEVEL/TILE/EDGE
```

`reset` writes Valhalla's unknown-speed representation. It does not write speed zero. Speed zero is
reserved for explicit closures.

## Stateful edge planning and expiry

`TrafficOverlayPlanner` is the provider-independent boundary between matching and the native
writer. Given normalized segments, verified matches and the previous managed-edge state, it:

- emits one whole-edge `set` operation for every accepted directed GraphId;
- requires match confidence, verified direction and an exact tileset identity;
- retains an omitted prior observation only until its explicit expiry;
- emits a `reset` for each expired managed edge, restoring Valhalla's unknown live speed;
- never converts a low speed into a closure;
- encodes speed zero only for an explicit provider closure;
- resolves overlapping observations deterministically, preferring an explicit closure and then the
  newest/highest-confidence observation.

The JSON state schema stores GraphId, provider segment ID, observation/expiry timestamps, encoded
values, match confidence, mapping version and tileset identity. Loading a state file for a different
tileset fails closed; old GraphIds are not silently reused after a graph rebuild. State is written
atomically only after the native batch transaction has applied all operations successfully. If
state persistence fails, the executor builds and applies the inverse plan before reporting failure.

The current live gate exercises planning only:

```bash
bash scripts/run-traffic-plan-live.sh
```

It forces provider overlay writes off, validates the proposed edge operations and proves that
`traffic.tar` has the same SHA-256 before and after the run.

The operator accepted this planner gate for tileset `valhalla-3.8.3:1788156046`: all three TomTom
probes were accepted, 25 directed-edge updates were planned, no reset was required and the overlay
hash remained unchanged.

## Controlled provider writer

The native helper also supports an `apply-plan` batch command. It parses and validates the complete
plan before the first write, rejects duplicate GraphIds, snapshots the original TrafficSpeed records
and tile headers, and rolls the batch back in-process if a write fails. The first managed
transaction can require every target edge to contain Valhalla's unknown live speed, preventing the
gate from overwriting traffic owned outside Compass.

`compass-traffic apply-once` performs one provider fetch/match/plan/write transaction and persists
the resulting tileset-bound state. `compass-traffic clear-managed` resets only GraphIds present in
that state and then persists an empty state. Both commands refuse to run unless
`TRAFFIC_VALHALLA_OVERLAY_ENABLED=true`.

Run the controlled live writer gate with:

```bash
bash scripts/run-traffic-writer-live.sh
```

The controlled writer gate uses one TomTom probe, inspects an encoded TrafficSpeed, resets all
managed edges to unknown, validates empty durable state and restarts Valhalla on the clean overlay.
An EXIT/INT/TERM trap attempts the same cleanup if any intermediate check fails.

The following is retained only as a legacy diagnostic for the old periodic runner; it is not the
production activation path:

```bash
bash scripts/run-traffic-updater-live.sh
```

It starts the legacy runner with one probe, waits for a committed cycle, stops it, inspects the
written speed, resets every managed edge to `UNKNOWN`, checks that credentials did not appear in
logs, validates fresh and post-cleanup fallback health through an isolated API instance, and
restarts Valhalla. It deliberately leaves the updater stopped and the overlay clean.

The operator accepted the periodic one-probe writer and dynamic-health gate on 2026-09-01: six
edges were committed, the 123 km/h provider speed encoded as 124 km/h, the isolated API reported
`fresh`, no credential appeared in logs, all six edges were reset to `UNKNOWN`, durable state was
empty, and the API then reported the safe `unavailable` fallback state.

## Persistent activation

The destructive acceptance gates above always clean up. Persistent activation is a separate,
stateful operator action. Before running it, configure these values in the server's uncommitted
`.env` file:

```env
TRAFFIC_ENABLED=true
TRAFFIC_PROVIDER=tomtom
TRAFFIC_REFRESH_MODE=on_demand
TRAFFIC_ROUTE_REFRESH_MIN_INTERVAL_SECONDS=300
TRAFFIC_ROUTE_PROBE_SPACING_KM=25
TRAFFIC_ROUTE_MAX_PROBES=16
TRAFFIC_VALHALLA_OVERLAY_ENABLED=true
TRAFFIC_VALHALLA_TILESET_VERSION=valhalla-3.8.3:RUNNING_TILESET_LAST_MODIFIED
TRAFFIC_MAPPING_VERSION=valhalla-openlr-geometry-v1
TRAFFIC_UPDATE_SEGMENT_LIMIT=1000
TOMTOM_TRAFFIC_API_MODE=flow_segment
TOMTOM_FLOW_SEGMENT_OPENLR=true
TOMTOM_MAX_CONCURRENCY=2
TOMTOM_API_KEY=stored-only-in-the-server-env
```

Obtain the exact identity before editing `.env`:

```bash
docker compose --profile routing exec -T valhalla \
curl --fail --silent http://127.0.0.1:8002/status \
| python3 -c 'import json,sys; s=json.load(sys.stdin); print(f"valhalla-{s[\"version\"]}:{int(s[\"tileset_last_modified\"])}")'
```

Then perform the guarded activation:

```bash
bash scripts/deploy-traffic-live.sh
```

The script safely migrates an existing periodic updater. It:

1. compares configured and running tileset identities;
2. verifies the native helper and non-empty `traffic.tar` without printing secrets;
3. stops the legacy poller and resets its managed edges to `UNKNOWN`;
4. starts the private on-demand updater and recreates the API;
5. requests Milan -> Bologna once, causing bounded route-derived TomTom probes;
6. repeats the identical route immediately and proves it is skipped by the five-minute ledger;
7. verifies fresh health, non-empty tileset-bound state and time-dependent Valhalla routing;
8. leaves API and updater running only after all checks pass.

The activation script treats Valhalla `/status` as the authoritative runtime identity and exports
that value to the recreated services. A stale value left in `.env` is reported and overridden for
the activation; it is never allowed to make mappings target a different graph.

If the persisted managed-edge state belongs to an older tileset, the script never applies those
stale GraphIds to the active graph. It stops Valhalla, builds a fresh native `traffic.tar` from the
current graph tiles without replacing `valhalla_tiles.tar`, discards the old edge state only after
that build succeeds, and restarts Valhalla before activation continues. A malformed state file
still fails closed and requires operator inspection.

First activation must start with zero Compass-managed edges. If a failure occurs after writes have
started, the EXIT trap stops the updater and calls `clear-managed`, which resets those new edges to
Valhalla `UNKNOWN`. The API remains available with normal fallback speeds. Inspect the generated
artifacts under `/tmp/compass-traffic-production-*` before retrying.

After success, inspect the persistent services and dynamic health separately:

```bash
docker compose --profile traffic ps api traffic-updater valhalla
```

```bash
curl --fail --silent --show-error \
http://127.0.0.1:8000/api/v1/traffic/health \
| python3 -m json.tool
```

```bash
docker compose --profile traffic logs --no-color --since=10m traffic-updater
```

Never rebuild Valhalla tiles while this updater is active. Stop it first, rebuild both routing
tiles and `traffic.tar`, clear/recreate the tileset-bound state, configure the new identity, and
repeat the activation gate. Valhalla routing tiles and `traffic.tar` are a matched set.

With the currently available TomTom base Traffic API, coverage is limited to the sampled points of
routes users actually calculate. This is valid live traffic for the returned directed segments,
but it is not nationwide coverage. `TRAFFIC_ROUTE_PROBE_SPACING_KM` and
`TRAFFIC_ROUTE_MAX_PROBES` form the explicit quota boundary. Nationwide dense coverage still
requires a bulk Intermediate/Orbis subscription through another provider adapter.

During a provider outage the updater retains unexpired observations. At every failed cycle it
evaluates the durable state and resets only expired Compass-owned edges to `UNKNOWN`; it never uses
zero as an expiry value. A matching failure rejects only the affected segment.

## Synthetic traffic proof

Before provider traffic is allowed to influence Compass routing, run the live synthetic overlay
check:

```bash
python3 scripts/run-traffic-synthetic-live.py
```

The script:

1. verifies that Valhalla and `traffic.tar` are present;
2. builds the traffic writer helper;
3. calculates a baseline Milan-to-Bologna route without `date_time`;
4. calculates the same route with current traffic semantics;
5. extracts route directed `GraphId` values through Valhalla `trace_attributes`, accepting both
   packed integer IDs and `LEVEL/TILE/EDGE` string IDs;
6. backs up `traffic.tar`;
7. injects a low synthetic speed into selected directed edges;
8. without restarting Valhalla, proves that the no-`date_time` route remains stable while the
   current-traffic route changes ETA or path;
9. resets those edges to Valhalla `UNKNOWN`, still without restarting, and proves that the current
   route returns to its baseline path and duration tolerance;
10. restores the original `traffic.tar` and performs one final safety restart.

Outputs are written to `/tmp/compass-traffic-synthetic-*.json`. The important final artifact is:

```text
/tmp/compass-traffic-synthetic-summary.json
```

The check passes only if the summary contains:

```json
{
  "accepted": true
}
```

The original restart-based live gate was accepted by the operator on 2026-08-31. The extended gate
additionally requires in-place updates and expiry/reset to be visible to the already-running
Valhalla process. For the original Milan-to-Bologna check:

- the no-`date_time` route stayed at `6773.406` seconds before and after injection;
- the current time-dependent route changed from `6773.400` to `7404.044` seconds;
- the live-traffic increase was `630.644` seconds;
- the original overlay was restored and Valhalla restarted by the script.

This proves the required chain through Valhalla. It does not prove provider matching or authorize
TomTom-driven overlay writes.

Optional environment variables:

```env
TRAFFIC_SYNTHETIC_ORIGIN=45.4642,9.1900
TRAFFIC_SYNTHETIC_DESTINATION=44.4949,11.3426
TRAFFIC_SYNTHETIC_EDGE_COUNT=12
TRAFFIC_SYNTHETIC_SPEED_KPH=5
TRAFFIC_SYNTHETIC_MIN_DELTA_SECONDS=60
TRAFFIC_SYNTHETIC_STATIC_TOLERANCE_SECONDS=30
```

## Valhalla request behavior

When both `TRAFFIC_ENABLED=true` and `TRAFFIC_VALHALLA_OVERLAY_ENABLED=true`, the Valhalla adapter
sends time-dependent route and matrix requests with:

- `date_time.type=0` for current departure when no explicit departure is present;
- `date_time.type=1` for an explicit scheduled departure;
- auto `speed_types`: `current`, `predicted`, `constrained`, `freeflow`.

Compass accepts a timezone-aware instant, converts it to the configured Italy routing timezone,
and sends Valhalla the required local `YYYY-MM-DDTHH:MM` value without a UTC suffix. Valhalla's
`date_time.value` is local wall time; sending the caller's `+02:00` suffix directly is invalid.

Base routes, selected-stop routes and complete CNG-itinerary routes accept an optional
timezone-aware `departure_at`. Detour/ranking/predictive requests require it. The same instant is
propagated through the Compass routing boundary, including predictive pairwise matrices; a naive
timestamp is rejected instead of being interpreted in the server timezone.

Maximum-detour eligibility and the detour component of ranking consume the Valhalla durations
directly. Compass does not add a separate congestion weight. Station ETA is derived from the
traffic-aware origin-to-station duration, so opening-hours evaluation uses the same cost basis.

When either flag is false, no `date_time` or traffic `costing_options` are sent. This preserves the
existing graph-speed behavior and lets tests detect accidental traffic claims without Valhalla
traffic input.

Validate the complete Compass CNG routing boundary without changing the overlay:

```bash
bash scripts/run-traffic-cng-routing-live.sh
```

The gate builds the API, starts an isolated traffic-aware instance on `127.0.0.1:18081`, and uses
one departure instant for a base route, ranked candidates, the selected-stop route and the
multi-stop predictive request. It requires Valhalla time-dependent route and matrix evidence,
checks that ranking totals contain only the documented contributions, compares `traffic.tar`
SHA-256 before/after, and stops the isolated API. It never starts the provider updater or invokes
the native traffic writer. Provider credentials and Flow Segment probe configuration belong only
to `traffic-updater`; the isolated API consumes the overlay and its read-only health state without
receiving the TomTom API key. If startup or readiness fails, the gate prints the last health body
and saves the isolated process log to `/tmp/compass-traffic-cng-api.log`.

## Matching strategy

Do not equate an OSM way ID with a Valhalla directed edge ID. Valhalla may split an OSM way into
many directed edges, and direction matters.

The intended matcher hierarchy is:

1. prefer OpenLR location references decoded/map-matched against the current Valhalla graph;
2. use TomTom OSM way IDs as hints, validation and candidate discovery;
3. reject uncertain matches rather than poisoning the overlay.

Every mapping must be associated with the current Valhalla tileset identity. Rebuilding routing
tiles invalidates old edge mappings and requires a new `traffic.tar` plus mapping/index rebuild.

### Valhalla 3.8.3 capability finding

The pinned Valhalla image contains `baldr/openlr.h`, which parses OpenLR 1.5 references, and its
serializer can generate OpenLR references for matched edges. It does not expose a stock
OpenLR-to-GraphId command or HTTP endpoint. `valhalla_ways_to_edges` is present, but its output is
only useful as an OSM candidate index: it does not make an OSM Way ID a directed edge ID or prove an
ordered, directional match.

The matcher decodes each OpenLR with the native helper and verifies that the first/last provider
geometry points align with the first/last ordered LRPs. It then uses provider geometry with
Valhalla's own `/trace_attributes` map matching to obtain ordered directed GraphIds. OSM Way IDs,
when present, remain validation hints only. The mapping version
`valhalla-openlr-geometry-v1` means both checks passed; it does not claim that an independent
OpenLR-to-GraphId path decoder exists.

TomTom documents that base Flow Segment coordinates may be shifted depending on zoom for traffic
visualization. The trace search radius and OpenLR endpoint tolerance are therefore configurable.
A result is not `write_eligible` unless native OpenLR decoding verifies direction, trace confidence
passes policy, and runtime/configured tileset identities match. Failed or reversed alignment sets
direction false and confidence zero rather than silently reversing the provider geometry.

The runtime tileset identity format is:

```text
valhalla-<version>:<tileset_last_modified>
```

The matcher reads both fields from `/status`. If `TRAFFIC_VALHALLA_TILESET_VERSION` is configured
and differs, matching stops before `/trace_attributes` and returns `unmatched`.

Run the live, read-only TomTom matching check with:

```bash
bash scripts/run-traffic-matching-live.sh
```

The script reads `TOMTOM_API_KEY` from the environment or Compose `.env` without displaying it,
derives the exact live tileset identity, fetches configured TomTom base Flow Segment probes and
validates at least one confident geometry match. It never writes `traffic.tar`.

## Fixture coverage

`tests/fixtures/traffic/mock_flow_segments.json` includes sanitized records for:

1. normal free-flow road;
2. heavy congestion;
3. stationary traffic;
4. explicit closure;
5. bidirectional road with congestion in one direction;
6. multiple segments on the same OSM way;
7. OpenLR-only segment;
8. low-confidence observation;
9. stale observation.

These fixtures are for deterministic development only. They are not presented as live production
traffic.

## Failure behavior

Provider failure must not take Compass routing offline. Current behavior:

- disabled traffic reports `not_configured`;
- configured mock provider reports `mock`;
- configured TomTom provider reports `configured` until the first successful fetch;
- a successful updater cycle reports `fresh` (or `mock`) with feed age and edge counters;
- an old successful snapshot is derived as `stale` after the configured maximum age;
- fetch failures are logged and persisted as `unavailable`, preserving the last-success timestamp;
- unexpired observations survive a failed fetch; expired owned edges reset to `UNKNOWN`;
- API readiness still depends on database, Valhalla and normalized station data, not on traffic.

## Current limitations

- The native `traffic.tar` writer supports route-scoped whole-edge provider updates; sub-edge
  breakpoints are not implemented.
- Native OpenLR decoding and endpoint-direction verification run in `traffic-updater`. Independent
  LRP-to-GraphId path resolution is not implemented; directed GraphIds still come from Valhalla
  geometry tracing after OpenLR direction verification.
- OSM way ID to Valhalla directed-edge indexing is not implemented yet.
- TomTom Intermediate/Orbis protobuf schema decoding is not wired yet.
- TomTom base Flow Segment ingestion is point/probe based and does not replace a bulk traffic feed.
- The updater executes normalized whole-edge set/reset operations only for actual route requests.
  Current TomTom base API mode covers bounded route-derived probe points, not the whole country.
- Historical/predicted traffic ingestion is not implemented.
- Batched detour and predictive pairwise matrices currently share the request departure instant.
  They do not yet assign a distinct path-dependent departure time to every later station leg; the
  final selected waypoint route remains authoritative. This must be tightened before claiming
  exact future-leg traffic semantics for long multi-stop journeys.
- No commercial TomTom credentials are committed or required for tests.

The synthetic proof, OpenLR direction proof, integrated matcher, read-only planner, controlled
writer, periodic updater and dynamic-health gates are accepted:

```text
TomTom normalized geometry/OpenLR
  -> native OpenLR direction verification
  -> read-only Valhalla directed-edge match
  -> confidence and tileset identity
  -> deterministic set/reset/expiry plan
  -> transactional native batch and durable state
  -> atomic runtime health -> read-only API health
  -> controlled reset to UNKNOWN and fallback health
```

After a successful `scripts/run-traffic-matching-live.sh`, validate every live TomTom OpenLR
record with the native decoder:

```bash
bash scripts/run-traffic-openlr-live.sh
```

This gate is read-only. It must report `provider_overlay_write_enabled: false` and must not modify
`traffic.tar`.

The native OpenLR live gate was accepted against the three TomTom base Flow Segment records:

- 3 references decoded and round-tripped unchanged;
- 7 ordered LRPs decoded in total;
- line direction reported as `first_lrp_to_last_lrp`;
- provider overlay writes remained disabled.

To refresh the TomTom records and check that each provider geometry runs from the first decoded LRP
to the last decoded LRP, use the combined read-only gate:

```bash
bash scripts/run-traffic-direction-live.sh
```

Endpoint alignment must be within 300 metres and direct alignment must be better than reversed
alignment. This accounts for the provider's returned segment geometry while rejecting an opposite
direction. Passing this diagnostic does not itself enable provider overlay writes.

The operator accepted this standalone direction gate on 2026-08-31 for all three live references.
Start/end alignment was below one metre in every record; reversed alignment was 8.16 km, 11.50 km
and 29.74 km respectively. Provider overlay writes remained disabled.

The integrated matcher gate was then accepted with `direction_match=true` and
`write_eligible=true` for all three records. It still left
`TRAFFIC_VALHALLA_OVERLAY_ENABLED=false`, wrote no speeds and left `traffic.tar` byte-for-byte
unchanged. The read-only plan gate then accepted 25 updates for three segments without modifying the
overlay. The controlled and periodic writer gates subsequently passed with full cleanup. Rerun
`scripts/run-traffic-updater-live.sh` for the runtime-health acceptance gate.

TomTom base Traffic API credentials travel in a query parameter. Compass therefore forces the
`httpx` and `httpcore` transport loggers to WARNING even when application logging is INFO; request
URLs containing credentials are never emitted by normal successful fetches. Rotate any key that
appeared in logs produced by an older build.
