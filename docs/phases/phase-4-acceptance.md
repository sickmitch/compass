# Phase 4 acceptance criteria

Phase 4 adds autonomy-aware spatial CNG candidate pruning. It does not calculate station detours,
road distance to a station, opening status, ranking, traffic or predictive fuel reachability.

## Implemented gate

- Valhalla polyline6 is decoded into bounded, validated WGS84 coordinates.
- Request routes become transient PostGIS `LINESTRING` geometry; arbitrary route text is never
  interpolated into SQL.
- The default radius is 20% of effective CNG range, with configurable 5 km and 50 km caps.
- Active stations with locations are filtered using geography `ST_DWithin`, compatible with the
  existing `ix_stations_location_gist` index.
- Candidates are cheaply ordered by straight-line route distance and approximate along-route
  fraction, with a configurable response limit.
- Metrics expose all-active, geocoded, missing-location, in-corridor, returned and pruned counts,
  reduction ratio, candidate-limit state and exactly one base-route call.
- `POST /api/v1/cng/corridor-candidates` exposes this stage explicitly and rejects Phase 5 detour
  inputs.
- API descriptions state that spatial distances are not road distances or detours.

## Local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest
TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@HOST:5432/compass_test \
  .venv/bin/pytest -q tests/test_phase4_postgis.py
docker compose --profile routing config --quiet
docker compose build api
```

The database name guard requires the integration target to end in `_test`; the test resets that
database. Normal tests remain network-free and skip this one when `TEST_DATABASE_URL` is absent.

## Local validation result

Validated on 2026-08-28:

- Ruff passed;
- the network-free suite reported 62 passed and two opt-in PostGIS tests skipped;
- the complete suite reported 64 passed when both PostGIS tests ran against a disposable database;
- the Phase 4 integration plan explicitly selected `ix_stations_location_gist` for the geography
  `ST_DWithin` predicate, and the fixture proved Milan/Bologna inclusion with Florence exclusion;
- Compose validated with the routing profile enabled;
- `compass-app:0.1.0` built successfully from the pinned Python 3.12.11 base;
- the built image generated OpenAPI containing `POST /api/v1/cng/corridor-candidates`.

No query against the operator's full Italy station dataset or live Valhalla service was attempted
in the repository environment.

## Live gate

Follow the Phase 4 section of `docs/deployment.md`. Acceptance requires operator evidence that a
representative Italy route returns HTTP 200, uses the expected radius policy, reports one routing
call, returns only corridor candidates with valid projection/distance values, and substantially
reduces the 1,505 geocoded stations observed at the Phase 2 gate. A reduction ratio of at least 0.50
is the concrete sample-route invariant.

## Live validation result

The operator completed the full-Italy live gate on 2026-08-28:

- `POST /api/v1/cng/corridor-candidates` returned HTTP 200 with
  `stage=spatial_pruning`;
- Valhalla returned a Milan-to-Bologna base route of 210,925 metres and 6,773.406 seconds with
  non-empty polyline6 geometry and Italian maneuvers;
- the 300 km effective range produced a 60 km uncapped radius, the configured 50 km maximum and
  `cap_applied=maximum`;
- 1,512 active stations became 1,505 geocoded stations, then 325 corridor candidates;
- 200 candidates were returned under the configured limit, with
  `candidate_limit_applied=true`;
- 1,180 geocoded stations were pruned, for a reduction ratio of `0.7840531561461794` (78.4%);
- metrics reported `routing_calls=1`, and server logs showed one successful Valhalla `/route` call
  for the request rather than per-station routing;
- returned candidate spatial distances were non-negative and projection fractions were within
  `[0, 1]` under the strict response schema;
- API readiness was HTTP 200 immediately before the request, and Alembic remained at `0002 (head)`;
- the expected missing `traffic.tar` warnings remained consistent with the explicit no-traffic
  Phase 3/4 fallback and did not affect the route or pruning result.

All Phase 4 acceptance criteria are satisfied. Phase 5 detour computation remains a separate gate.
