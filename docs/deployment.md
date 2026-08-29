# Docker deployment and live validation

This is the reference test-server workflow for repository bootstrap, raw source ingestion, Phase 2
normalized station reconciliation, Phase 3 base routing, Phase 4 spatial candidate pruning, the
accepted Phase 5 batched detour workflow and the accepted Phase 6 ranking workflow.

## Prerequisites

- Linux host with Docker Engine and the Compose plugin;
- outbound HTTPS access to `www.mimit.gov.it`, the configured Overpass endpoint, `ghcr.io` and the
  configured Valhalla PBF host;
- this repository synchronized onto the test server;
- no public reverse proxy is required.

From the repository root, create configuration:

```bash
cp .env.example .env
```

Edit `.env` so:

- `POSTGRES_PASSWORD` is a non-default test-server secret;
- the password in `DATABASE_URL` represents the same password (URL-encode reserved characters;
  for example, a literal `%` is `%25` in the URL);
- `HTTP_USER_AGENT` includes a meaningful application/operator contact;
- no secret is committed back to Git.

## Phase 0 bootstrap validation

```bash
docker compose config --quiet
docker compose build api
docker compose up -d db migrate api
docker compose ps
docker compose logs --no-color migrate
curl --fail --silent --show-error http://127.0.0.1:8000/health/live
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT extversion FROM pg_extension WHERE extname = '\''postgis'\'';"'
docker compose exec -T api alembic current
```

Expected invariants:

- `db` and `api` are healthy and `migrate` exited with code 0;
- liveness returns `status=ok` with both dependencies `not_checked`;
- PostGIS has a non-empty version;
- Alembic reports the repository's current head revision (`0002` once Phase 2 is synchronized).

## Phase 1 live acquisition and idempotency validation

Run MIMIT twice while the upstream daily files remain unchanged, then OSM twice:

```bash
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose --profile jobs run --rm etl osm
```

The first result for each source should have `status=completed`, `reused=false` and non-zero relevant
counts. The immediate repeat should have the same `run_id` and hash with `reused=true`. If MIMIT
publishes between runs, a new hash/run is correct; repeat once more promptly to prove reuse.

Inspect representative data and persisted metrics:

```bash
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT id, source_name, status, source_observed_at, completed_at, metrics FROM ingestion_runs ORDER BY id;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT mimit_station_id, name, municipality, province, latitude, longitude FROM raw_mimit_stations ORDER BY id LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT mimit_station_id, source_fuel_name, unit_price, currency, unit, is_self_service, price_observed_at FROM raw_mimit_cng_prices ORDER BY id LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT osm_type, osm_id, name, opening_hours, phone, latitude, longitude FROM raw_osm_cng_features ORDER BY id LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT source_name, sha256, source_url, source_observed_at, fetched_at, octet_length(content) AS bytes FROM raw_source_snapshots ORDER BY id;"'
```

Expected invariants:

- MIMIT retained rows contain only metano/CNG prices, with `currency=EUR` and `unit=kg`;
- representative MIMIT source IDs link prices to selected active-station source rows;
- OSM rows retain `osm_type` and `osm_id`, with enrichment fields nullable rather than invented;
- source observation times and fetch/ingestion times are both populated where upstream supplies them;
- exact raw snapshots have non-zero byte lengths;
- repeated unchanged imports do not increase counts.

## Diagnostics if an invariant fails

```bash
docker compose ps -a
docker compose logs --no-color --tail=300 db migrate api
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\\dt"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version_num FROM alembic_version;"'
```

For a download failure, also return the HTTP status/error printed by the ETL job and confirm outbound
DNS/TLS access from the Docker host. A transient Overpass `429`, `502`, `503`, or `504` is safe to
retry: the OSM job is independent and cannot erase a successful MIMIT import. Do not paste `.env` or
database passwords.

## Output to return

Return:

1. `docker compose ps`;
2. migration log plus both health response bodies;
3. the four ETL JSON lines (or enough prompt repeats to show `reused=true`);
4. all five inspection-query outputs above;
5. diagnostics/logs if any command fails.

On the current Phase 3 checkout, `/health/ready` intentionally returns HTTP 503 with
`routing=unavailable` until the routing bootstrap below has completed. These Phase 0/1 invariants
are prerequisites for Phase 2 unless the operator explicitly waives them.

## Phase 2 migration and reconciliation validation

Run these commands from the repository root after synchronizing the Phase 2 changes. Existing Phase
1 raw records are inputs; they do not need to be downloaded again.

```bash
docker compose build api
docker compose up -d db migrate api
docker compose logs --no-color migrate
docker compose exec -T api alembic current
docker compose --profile jobs run --rm etl normalize
docker compose --profile jobs run --rm etl normalize
```

Expected invariants:

- migration exits zero and Alembic reports `0002 (head)`;
- the first normalize result is `completed`, normally with `reused=false`;
- `stations_seen`, `price_rows_seen`, and `osm_features_seen` are non-zero;
- `matched + ambiguous + unmatched == stations_seen`;
- the prompt repeat has the same reconciliation run/configuration hash and `reused=true`;
- no live download is performed by `normalize`.

Inspect the schema, outcome counts, and representative current data:

```bash
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = '\''public'\'' AND indexname IN ('\''ix_stations_location_gist'\'', '\''ix_osm_cng_features_location_gist'\'') ORDER BY indexname;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT status, count(*) FROM reconciliation_results WHERE reconciliation_run_id = (SELECT max(id) FROM reconciliation_runs WHERE status = '\''completed'\'') GROUP BY status ORDER BY status;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT id, algorithm_version, status, configuration_sha256, metrics FROM reconciliation_runs ORDER BY id DESC LIMIT 3;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT s.mimit_station_id, s.name, s.municipality, ST_Y(s.location::geometry) AS latitude, ST_X(s.location::geometry) AS longitude, p.unit_price, p.currency, p.unit, p.service_mode, p.observed_at AS price_observed_at, o.osm_type, o.osm_id, o.opening_hours, o.phone, l.match_method, l.confidence, l.distance_meters FROM stations s JOIN station_current_prices cp ON cp.station_id = s.id AND cp.fuel_type = '\''cng'\'' JOIN station_prices p ON p.id = cp.station_price_id LEFT JOIN station_osm_links l ON l.station_id = s.id LEFT JOIN osm_cng_features o ON o.id = l.osm_feature_id WHERE s.is_active ORDER BY (o.id IS NOT NULL) DESC, s.id LIMIT 20;"'
```

The two index definitions must use `gist`. The representative query must return active Italian CNG
stations with EUR/kg prices and timestamps. At least some rows should include OSM identity and any
available opening-hours/phone enrichment; null enrichment is valid for unmatched stations and must
not be invented.

Manual overrides are available for operator-reviewed cases. Do not run these merely for smoke
testing because they intentionally change reconciliation state:

```bash
docker compose --profile jobs run --rm etl override \
  --mimit-station-id MIMIT_ID --action link --osm-type node --osm-id OSM_ID \
  --reason "operator-verified identity" --created-by OPERATOR
docker compose --profile jobs run --rm etl override \
  --mimit-station-id MIMIT_ID --action unmatch \
  --reason "operator-verified non-match" --created-by OPERATOR
docker compose --profile jobs run --rm etl normalize
```

### Phase 2 diagnostics

```bash
docker compose ps -a
docker compose logs --no-color --tail=300 migrate api db
docker compose exec -T api alembic current
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT id, status, error_message, metrics FROM reconciliation_runs ORDER BY id DESC LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT r.status, r.decision_reason, count(*) FROM reconciliation_results r WHERE r.reconciliation_run_id = (SELECT max(id) FROM reconciliation_runs) GROUP BY r.status, r.decision_reason ORDER BY r.status, count(*) DESC;"'
```

Return the migration log/current revision, both normalize JSON lines, and all four Phase 2 inspection
query outputs. If anything fails, also return the Phase 2 diagnostic output. Do not include `.env`.

Do not begin Phase 3 until these Phase 2 live invariants pass or the operator explicitly waives the
gate.

## Phase 3 Valhalla bootstrap and base-route validation

Run from the repository root after synchronizing Phase 3. Confirm `.env` contains the pinned
`VALHALLA_IMAGE` from `.env.example`. The default PBF is the full Italy extract. Set
`VALHALLA_TILE_URLS` to a Geofabrik regional extract before the first build only if a regional graph
is intended. `VALHALLA_THREADS` controls build/server concurrency; the default is deliberately two.

The initial download and tile build can be long-running and needs substantially more disk than the
compressed PBF. Do not interrupt it merely because output pauses, and do not use
`docker compose down -v`: that would delete both database and routing named volumes.

The tile job checks the scripted image's PBF registration and rebuilds when its graph is missing or
unregistered. The persistent service only reuses the graph after that one-shot job exits
successfully. Keep
`VALHALLA_FORCE_REBUILD=False` for normal operation.

```bash
docker compose --profile routing --profile routing-build config --quiet
docker compose build api
docker compose --profile routing-build pull valhalla-tiles
docker compose --profile routing pull valhalla
docker compose --profile routing-build run --rm valhalla-tiles
docker compose --profile routing up -d db migrate valhalla api
docker compose --profile routing ps -a
docker compose --profile routing exec -T valhalla \
  curl --fail --silent --show-error http://127.0.0.1:8002/status
docker compose --profile routing exec -T valhalla sh -c \
  'ls -lh /custom_files/*.pbf /custom_files/valhalla_tiles.tar && sha256sum /custom_files/*.pbf'
curl --fail --silent --show-error http://127.0.0.1:8000/health/live
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":45.4857,"longitude":9.2045}}' \
  http://127.0.0.1:8000/api/v1/routes
```

The representative route stays within Milan so it is valid for the default Italy graph and for
regional graphs that include Milan. Expected invariants:

- `valhalla-tiles` exits zero and leaves data in the `valhalla_data` named volume;
- the identity command reports the retained PBF filename, size and content SHA-256;
- `valhalla`, `db` and `api` are healthy; `migrate` exits zero;
- Valhalla `/status` returns a JSON object;
- liveness returns `database=not_checked` and `routing=not_checked`;
- readiness returns HTTP 200 with `database=ready` and `routing=ready`;
- the route returns HTTP 200, `provider=valhalla`, positive `distance_meters` and
  `duration_seconds`, `geometry.format=polyline6`, a non-empty `encoded_polyline`, and at least one
  maneuver with an instruction and shape indexes.

Valhalla is intentionally not published on a host port. Its status is tested from inside the
container; only the loopback-bound Compass API is host-accessible.

### Recovery after an interrupted or partial tile build

If the builder reports `Couldn't find usable tiles`, synchronize the current Compose policy and
force exactly one reconstruction in the same volume:

```bash
VALHALLA_FORCE_REBUILD=True \
  docker compose --profile routing-build run --rm valhalla-tiles
```

The command-scoped override also forces replacement of a stale tile archive; it does not modify
`.env`. Do not set `VALHALLA_FORCE_REBUILD=True` permanently, because doing so would rebuild on every
container start. After the job exits zero, continue with `docker compose --profile routing up ...`
and the status/readiness/route checks above.

### Routing graph update and rollback

Do not overwrite the active graph when refreshing a `latest` extract. Choose a new physical volume
name, build into it, and smoke-test it with an isolated candidate container. The example date is a
label; use the actual build date or another unique release identifier:

```bash
VALHALLA_VOLUME_NAME=compass_valhalla_data_20260827 \
  docker compose --profile routing-build run --rm valhalla-tiles

VALHALLA_VOLUME_NAME=compass_valhalla_data_20260827 \
  docker compose --profile routing run -d --name compass-valhalla-candidate \
  --no-deps valhalla

docker inspect --format '{{.State.Health.Status}}' compass-valhalla-candidate
docker exec compass-valhalla-candidate \
  curl --fail --silent --show-error http://127.0.0.1:8002/status
docker exec compass-valhalla-candidate sh -c \
  'ls -lh /custom_files/*.pbf /custom_files/valhalla_tiles.tar && sha256sum /custom_files/*.pbf'
docker rm -f compass-valhalla-candidate
```

Wait and repeat the `docker inspect` command until it reports `healthy` before the two `docker exec`
checks. Then set `VALHALLA_VOLUME_NAME=compass_valhalla_data_20260827` in `.env` and activate it:

```bash
docker compose --profile routing up -d --force-recreate valhalla api
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
```

Repeat the representative route request above. If it fails, restore the previous
`VALHALLA_VOLUME_NAME` in `.env` and run the same `--force-recreate valhalla api` command. The old
volume remains intact. Removing retired graph volumes is intentionally not automated; list and
confirm an exact volume target before any later operator-controlled deletion.

### Phase 3 diagnostics

If tile construction or startup fails, return:

```bash
docker compose --profile routing --profile routing-build ps -a
docker compose --profile routing logs --no-color --tail=300 valhalla api db migrate
docker volume inspect compass_valhalla_data
docker compose --profile routing exec -T valhalla sh -c \
  'find /custom_files -maxdepth 2 -type f -printf "%p %s bytes\n" | sort | head -100'
```

If Valhalla is healthy but the API is not ready or routing fails, also return:

```bash
docker compose --profile routing exec -T api python -c \
  "import urllib.request; print(urllib.request.urlopen('http://valhalla:8002/status', timeout=5).read().decode())"
docker compose --profile routing exec -T api python -c \
  "import json,urllib.request; data=json.dumps({'locations':[{'lat':45.4642,'lon':9.1900},{'lat':45.4857,'lon':9.2045}],'costing':'auto','shape_format':'polyline6'}).encode(); request=urllib.request.Request('http://valhalla:8002/route',data=data,headers={'Content-Type':'application/json'}); print(urllib.request.urlopen(request,timeout=60).read().decode())"
docker compose --profile routing logs --no-color --tail=300 api valhalla
```

Return the tile job's final output, `ps -a`, PBF identity line, both status/health bodies and the
complete Compass route response. If an invariant fails, include the matching diagnostics. Do not
return `.env`, credentials or the full PBF. A deployment that fails these invariants must be fixed
before proceeding into functionality that depends on routing.

## Phase 4 autonomy-aware corridor validation

Run from the repository root after synchronizing Phase 4. The Phase 2 normalized data and Phase 3
Italy graph must still be present. No migration beyond `0002` is expected because this phase reuses
the station geography GiST index and creates request route geometry transiently.

```bash
docker compose build api
docker compose --profile routing up -d --force-recreate migrate api valhalla
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300}' \
  http://127.0.0.1:8000/api/v1/cng/corridor-candidates \
  -o /tmp/compass-phase4-corridor.json

python3 -c 'import json; p=json.load(open("/tmp/compass-phase4-corridor.json")); print(json.dumps({"stage":p["stage"],"corridor":p["corridor"],"metrics":p["metrics"],"candidate_sample":p["candidates"][:5]},indent=2))'
```

Expected invariants:

- readiness is HTTP 200 with database and routing both `ready`;
- the corridor request is HTTP 200 with `stage=spatial_pruning`;
- `uncapped_radius_km=60`, `radius_km=50`, and `cap_applied=maximum`;
- `routing_calls=1`—there is no per-station routing in Phase 4;
- `active_station_count >= active_station_with_location_count > corridor_candidate_count`;
- `excluded_missing_location_count` equals the difference between the first two counts;
- `pruned_with_location_count` equals geocoded count minus corridor count;
- `reduction_ratio >= 0.50` for this representative northern-Italy route;
- returned count equals the candidate array length and is no more than the configured 200 limit;
- every candidate has non-negative `straight_line_distance_to_route_meters`, a `route_fraction`
  from 0 through 1, and coordinates within the 50 km corridor.

The spatial distance is intentionally not a detour or road-network distance. Do not use these
results as proof that a station satisfies a user detour limit.

### Phase 4 diagnostics

If the endpoint fails or counts are inconsistent, return:

```bash
curl --silent --show-error -i \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300}' \
  http://127.0.0.1:8000/api/v1/cng/corridor-candidates
docker compose --profile routing logs --no-color --tail=300 api valhalla db
docker compose exec -T api alembic current
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT count(*) FILTER (WHERE is_active) AS active, count(*) FILTER (WHERE is_active AND location IS NOT NULL) AS geocoded FROM stations; SELECT indexname, indexdef FROM pg_indexes WHERE indexname = '\''ix_stations_location_gist'\'';"'
```

Return the summarized JSON printed by Python and readiness output. If any invariant fails, also
return the HTTP response with headers and the three diagnostics. Keep the full JSON in `/tmp` only;
do not return the long route polyline/maneuver payload unless requested.

### Phase 4 accepted live result

The operator completed this procedure on 2026-08-28. The Milan-to-Bologna request returned HTTP
200, a 210,925 metre route, the expected capped 50 km corridor, 325 candidates from 1,505 geocoded
stations, 200 returned candidates, a 78.4% reduction ratio and exactly one routing call. Alembic
remained at `0002 (head)`. These results satisfy the Phase 4 live gate.

## Phase 5 batched network detour validation

Run from the repository root after synchronizing Phase 5. Keep the accepted Phase 2 normalized data
and Phase 3 Italy graph. No migration beyond `0002` is expected. `departure_at` must include an
explicit UTC offset; use an offset appropriate for the requested civil time.

```bash
docker compose build api
docker compose --profile routing up -d --force-recreate migrate api valhalla
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-28T08:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/detour-candidates \
  -o /tmp/compass-phase5-detours.json

python3 - <<'PY'
import json
from datetime import datetime

p = json.load(open("/tmp/compass-phase5-detours.json"))
s = p["spatial_pruning"]
n = p["network_evaluation"]
candidates = p["candidates"]
assert p["stage"] == "network_detour"
assert p["cost_basis"]["traffic_state"] == "not_configured"
assert p["cost_basis"]["traffic_aware"] is False
assert p["cost_basis"]["distance_model"] == "road_network"
assert n["spatial_candidate_count"] == s["corridor_candidate_count"]
assert n["matrix_candidate_count"] == s["returned_candidate_count"]
assert n["reachable_candidate_count"] + n["unreachable_candidate_count"] == n["matrix_candidate_count"]
assert n["eligible_candidate_count"] + n["excluded_by_detour_count"] == n["reachable_candidate_count"]
assert n["eligible_candidate_count"] == len(candidates)
assert n["base_route_calls"] == 1
assert n["per_candidate_route_calls"] == 0
minimum_calls = 0 if n["matrix_candidate_count"] == 0 else 2 * ((n["matrix_candidate_count"] + n["matrix_batch_size"] - 1) // n["matrix_batch_size"])
assert n["matrix_calls"] == minimum_calls + 4 * n["matrix_fallback_splits"]
assert n["matrix_location_failures"] <= n["unreachable_candidate_count"]
for candidate in candidates:
    assert candidate["detour_duration_seconds"] <= 600.000001
    assert abs(candidate["detour_minutes"] * 60 - candidate["detour_duration_seconds"]) < 0.001
    assert abs(candidate["route_via_station_distance_meters"] - candidate["distance_from_previous_waypoint_meters"] - candidate["station_to_destination_distance_meters"]) < 0.001
    assert abs(candidate["route_via_station_duration_seconds"] - candidate["duration_from_previous_waypoint_seconds"] - candidate["station_to_destination_duration_seconds"]) < 0.001
    assert datetime.fromisoformat(candidate["station_eta"]).utcoffset() is not None
    assert datetime.fromisoformat(candidate["destination_eta"]).utcoffset() is not None
assert [c["detour_duration_seconds"] for c in candidates] == sorted(c["detour_duration_seconds"] for c in candidates)
print(json.dumps({"stage": p["stage"], "corridor": p["corridor"], "spatial_pruning": s, "cost_basis": p["cost_basis"], "network_evaluation": n, "candidate_sample": candidates[:5]}, indent=2))
PY

# Independently validate one known candidate with ordinary Valhalla routes. These three route
# calls are acceptance diagnostics only; the Compass request above must still use matrix batches.
docker compose --profile routing exec -T api python - <<'PY' \
  > /tmp/compass-phase5-known-route.json
import json
import urllib.request

url = "http://valhalla:8002/route"
origin = {"lat": 45.4642, "lon": 9.1900, "type": "break"}
station = {"lat": 45.321004, "lon": 9.376063, "type": "break"}
destination = {"lat": 44.4949, "lon": 11.3426, "type": "break"}

def route(start, end):
    body = json.dumps({
        "locations": [start, end],
        "costing": "auto",
        "units": "kilometers",
        "directions_type": "none",
    }).encode()
    request = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        summary = json.load(response)["trip"]["summary"]
    return {"distance_meters": summary["length"] * 1000, "duration_seconds": summary["time"]}

base = route(origin, destination)
outward = route(origin, station)
onward = route(station, destination)
via_distance = outward["distance_meters"] + onward["distance_meters"]
via_duration = outward["duration_seconds"] + onward["duration_seconds"]
print(json.dumps({
    "mimit_station_id": "43690",
    "base": base,
    "origin_to_station": outward,
    "station_to_destination": onward,
    "route_via_station_distance_meters": via_distance,
    "route_via_station_duration_seconds": via_duration,
    "extra_distance_meters": max(0, via_distance - base["distance_meters"]),
    "detour_duration_seconds": max(0, via_duration - base["duration_seconds"]),
}, indent=2))
PY

python3 - <<'PY'
import json

p = json.load(open("/tmp/compass-phase5-detours.json"))
known = json.load(open("/tmp/compass-phase5-known-route.json"))
candidate = next(c for c in p["candidates"] if c["mimit_station_id"] == known["mimit_station_id"])
assert known["detour_duration_seconds"] <= 600
assert candidate["detour_duration_seconds"] <= 600
assert abs(candidate["route_via_station_distance_meters"] - known["route_via_station_distance_meters"]) <= 1000
assert abs(candidate["route_via_station_duration_seconds"] - known["route_via_station_duration_seconds"]) <= 120
print(json.dumps({"batched_candidate": candidate, "independent_route_check": known}, indent=2))
PY

docker compose --profile routing logs --no-color --since=5m api valhalla \
  | grep -E 'POST /route|POST /sources_to_targets|detour-candidates'
docker compose exec -T api alembic current
```

Expected invariants:

- readiness and the detour request are HTTP 200;
- `stage=network_detour`, the applied corridor policy remains the accepted Phase 4 policy and the
  request maximum is ten minutes;
- the matrix count equals Phase 4's returned count, not the all-Italy active count;
- reachable plus unreachable equals evaluated, and eligible plus detour-excluded equals reachable;
- matrix calls equal two per configured batch on the clean path (ten when 200 candidates use
  batches of 40), plus four observable calls per binary fallback split;
- an uncorrelatable station is counted as unreachable without discarding valid batch siblings;
- `base_route_calls=1` and `per_candidate_route_calls=0`;
- every returned candidate satisfies the inclusive 600-second maximum and its two leg sums;
- MIMIT station `43690` (San Zenone Ovest) is independently eligible under ordinary route calls,
  appears in the batched response and agrees within 1 km / 120 seconds; these tolerances account for
  CostMatrix versus bidirectional A* path/snap differences;
- distance from the previous waypoint is explicitly a road-network field;
- station and destination ETA values retain a UTC offset;
- cost metadata says traffic is not configured and not traffic-aware;
- metrics prove the Compass request made one base `/route`, zero per-candidate route calls and only
  batched `/sources_to_targets` calls. The final filtered log also contains the three deliberately
  independent `/route` diagnostics above, so four route lines are expected in this procedure.
  Healthcheck `/status` lines may also appear.

The request evaluates at most the Phase 4 returned-candidate limit. If
`spatial_pruning.candidate_limit_applied=true`, it is intentionally not an exhaustive evaluation of
every pre-limit corridor station.

### Phase 5 diagnostics

If the endpoint fails or matrix/count invariants do not hold, return:

```bash
curl --silent --show-error -i \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-28T08:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/detour-candidates
docker compose --profile routing logs --no-color --tail=500 api valhalla db
docker compose --profile routing exec -T valhalla sh -c \
  'curl --fail --silent --show-error -H "Content-Type: application/json" -d '\''{"sources":[{"lat":45.4642,"lon":9.1900}],"targets":[{"lat":45.321004,"lon":9.376063},{"lat":45.141970,"lon":9.634009}],"costing":"auto","units":"kilometers","verbose":true,"shape_format":"no_shape"}'\'' http://127.0.0.1:8002/sources_to_targets'
docker compose exec -T api alembic current
```

Return both summarized JSON objects, the independent known-route comparison, filtered request logs
and Alembic output. If a check fails, also return the HTTP response with headers and all diagnostics.
Keep the full route/candidate JSON in `/tmp` unless a specific malformed item must be inspected.

## Phase 6 arrival-time availability and ranking validation

Run this gate from the repository root after synchronizing Phase 6. The accepted normalized data and
Italy Valhalla volume must still be present. No new migration is expected: Alembic remains at `0002`.
The API image must be rebuilt because Phase 6 adds pinned Python packages and application code.

The commands below deliberately use Sunday 30 August 2026. This makes normal weekday-only OSM
schedules visibly closed while `24/7` schedules remain open. It is a deterministic civil-time test,
not a claim about whether those stations are open today. The `+02:00` offset is correct for Italy on
that date; the service converts every station ETA to the configured `Europe/Rome` timezone.

Copy each fenced shell block as a whole and do not paste shell prompt characters into it. The full
API responses stay under `/tmp` because route geometry makes them large; the checked-in verifier
prints only the evidence that should be returned.

First rebuild and restart the application against the existing database/router:

```bash
docker compose build api
docker compose --profile routing up -d --force-recreate migrate api valhalla
docker compose --profile routing ps -a
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
```

Both calls below run the same route. The first exercises the user-facing default (closed stations
excluded); the second includes closed stations only so their explicit penalty can be inspected.
Keep `phase6_started_at` in the same terminal so the later log command isolates these two requests.

```bash
phase6_started_at="$(date --iso-8601=seconds)"

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-30T10:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/ranked-candidates \
  -o /tmp/compass-phase6-ranked-default.json

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-30T10:00:00+02:00","include_closed":true}' \
  http://127.0.0.1:8000/api/v1/cng/ranked-candidates \
  -o /tmp/compass-phase6-ranked-with-closed.json
```

Run the checked-in verifier below. It performs the count, network-batching, detour-leg,
timezone, opening-state, price-age, score-contribution and deterministic-order checks, then prints
only compact human-reviewable samples. Keeping this logic in the repository avoids copying a long
inline Python heredoc and makes the acceptance procedure versioned with the implementation.

Fetch the live OpenAPI document before invoking it:

```bash
curl --fail --silent --show-error \
  http://127.0.0.1:8000/openapi.json \
  -o /tmp/compass-phase6-openapi.json

python3 scripts/validate-phase6-live.py \
  --default /tmp/compass-phase6-ranked-default.json \
  --with-closed /tmp/compass-phase6-ranked-with-closed.json \
  --openapi /tmp/compass-phase6-openapi.json \
  --require-closed

docker compose exec -T api python -c \
  'from importlib.metadata import version; print("opening-hours-py", version("opening-hours-py")); print("tzdata", version("tzdata"))'
docker compose exec -T api python -c \
  'from datetime import datetime; from compass.ranking.opening_hours import evaluate_opening_hours; result=evaluate_opening_hours("Mo-Sa 06:30-12:30, 14:30-19:00; Su, PH off", eta=datetime.fromisoformat("2026-08-30T11:48:13+02:00"), latitude=44.492256, longitude=11.262711, timezone_name="Europe/Rome", country="IT", source_confidence=1.0); print(result); assert result.state == "closed"'

docker compose --profile routing logs --no-color --since="$phase6_started_at" valhalla \
  | grep -E 'POST /route|POST /sources_to_targets'
docker compose --profile routing logs --no-color --since="$phase6_started_at" api \
  | grep 'ranked-candidates'
docker compose exec -T api alembic current
```

Expected invariants:

- readiness and both ranked requests return HTTP 200;
- the default response contains no `closed` candidate, but preserves `unknown`; the opt-in response
  contains at least one visibly closed Sunday candidate with multiplier `0.25`;
- all state, validation, price and ranking counts reconcile exactly as the verifier asserts;
- every opening state is evaluated at the offset-aware station ETA in `Europe/Rome`;
- a comma-separated `Su, PH off` selector remains closed on Sunday even when the OSM value contains
  whitespace after the comma; the direct parser check must print `state='closed'`;
- missing/invalid hours are `unknown`, never open, and price absence does not remove a candidate;
- price values are explicit EUR/kg unit prices with source times and ETA-relative freshness;
- ranks are contiguous and total scores descend; the returned components reproduce each total;
- `enrichment_queries=1` confirms one joined enrichment read over eligible IDs;
- each API request retains one base route, batched matrices and zero per-candidate route calls. With
  the previously accepted 200-candidate / batch-40 route, two ranked requests normally produce two
  `/route` lines and twenty `/sources_to_targets` lines, unless observable fallback splits occur;
- the image reports `opening-hours-py=2.1.4`, `tzdata=2026.3`, and OpenAPI exposes the strict request;
- Alembic remains `0002 (head)`.

### Phase 6 diagnostics

If an endpoint or assertion fails, keep the `/tmp` response files and return these diagnostics:

```bash
curl --silent --show-error -i \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-30T10:00:00+02:00","include_closed":true}' \
  http://127.0.0.1:8000/api/v1/cng/ranked-candidates
docker compose --profile routing logs --no-color --tail=500 api valhalla db
docker compose exec -T api python -c \
  'from importlib.metadata import version; print(version("opening-hours-py"), version("tzdata"))'
docker compose exec -T api python -c \
  'from datetime import datetime; from compass.ranking.opening_hours import evaluate_opening_hours; print(evaluate_opening_hours("Mo-Sa 06:30-12:30, 14:30-19:00; Su, PH off", eta=datetime.fromisoformat("2026-08-30T11:48:13+02:00"), latitude=44.492256, longitude=11.262711, timezone_name="Europe/Rome", country="IT", source_confidence=1.0))'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT count(*) AS active, count(*) FILTER (WHERE l.station_id IS NOT NULL) AS linked, count(*) FILTER (WHERE o.opening_hours IS NOT NULL) AS with_hours, count(*) FILTER (WHERE cp.station_price_id IS NOT NULL) AS with_current_cng_price FROM stations s LEFT JOIN station_osm_links l ON l.station_id=s.id LEFT JOIN osm_cng_features o ON o.id=l.osm_feature_id LEFT JOIN station_current_prices cp ON cp.station_id=s.id AND cp.fuel_type='\''cng'\'' WHERE s.is_active;"'
docker compose exec -T api alembic current
```

Return the compact verifier JSON, package-version lines, filtered Valhalla/API request logs and
Alembic output. If anything fails, also return the HTTP response and diagnostics above. Do not paste
the complete 2+ GB graph, `.env`, credentials, or the unabridged ranked response unless a specific
candidate must be debugged. Stop at this gate; do not begin Phase 7 until these invariants pass or
the operator explicitly waives them.
