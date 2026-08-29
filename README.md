# Compass

Compass is an open-source navigation system in development for fuel-aware CNG/metano routing in
Italy. The product target is route planning and navigation with dynamically inserted, reachable,
arrival-time-aware refuelling stops—not a generic fuel-station map.

This repository implements the accepted **Phases 0–6** foundation:

- a Python/FastAPI service with liveness and database-readiness endpoints;
- a PostgreSQL/PostGIS Docker Compose foundation and Alembic migration path;
- one-shot acquisition/import jobs for the official MIMIT active-station and price datasets;
- one-shot OSM CNG enrichment acquisition through Overpass;
- immutable raw payload retention, source timestamps, ingestion timestamps, provenance and metrics;
- content-hash idempotency and checked-in parser fixtures;
- a MIMIT-anchored normalized CNG station model using PostGIS geography points and GiST indexes;
- semantic CNG price history plus explicit current-price pointers;
- versioned, deterministic MIMIT-to-OSM reconciliation with matched, ambiguous and unmatched states;
- auditable candidates, confidence/method values and operator-controlled manual overrides.
- a version/digest-pinned Valhalla tile bootstrap and internal routing runtime;
- a provider-neutral async routing boundary with fixture-tested Valhalla translation;
- a strict `POST /api/v1/routes` A-to-B contract returning metres, seconds, polyline6 geometry and
  maneuvers;
- dependency-specific liveness/readiness state and stable routing error codes.
- validated polyline6-to-PostGIS route geometry conversion;
- configurable autonomy-aware CNG corridors using the 20%-of-effective-range policy and caps;
- GiST-compatible spatial candidate pruning with explainable before/after metrics.
- provider-neutral one-to-many and many-to-one road-cost matrices;
- bounded Valhalla matrix batching over only the spatially pruned candidates;
- strict maximum-detour eligibility with road distance, two-leg costs and offset-aware ETAs;
- explicit no-traffic cost metadata and matrix/per-candidate routing metrics.
- OSM `opening_hours` evaluation at each station ETA in the explicit `Europe/Rome` timezone;
- distinct `open`, `closed` and `unknown` availability with missing/invalid validation state;
- deterministic ranking from detour, availability, MIMIT CNG unit price and price freshness;
- explicit score components, price/source timestamps and at-most-one-query candidate enrichment.

It intentionally does **not** yet ingest traffic, recompute a selected route through a station,
publish the broader Phase 7 mobile API, or implement predictive refuelling. Those remain later
gated phases.

## Quick local validation

Python 3.12+ and Docker with Compose are required.

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/ruff check .
.venv/bin/pytest
docker compose config --quiet
docker compose build api
```

Start the database, migration job and health API:

```bash
cp .env.example .env
# Change POSTGRES_PASSWORD and the matching password inside DATABASE_URL.
docker compose build api
docker compose up -d db migrate api
curl --fail http://127.0.0.1:8000/health/live
```

The service binds to loopback by default. Public exposure is deliberately outside this repository's
current scope. Readiness requires Valhalla after Phase 3; follow the routing bootstrap below before
expecting `/health/ready` to return 200.

## Source imports and Phase 2 normalization

Set `HTTP_USER_AGENT` in `.env` to identify the operator and provide a contact, then run:

```bash
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose --profile jobs run --rm etl normalize
```

Each command prints one JSON object. `metrics` contains the input and retained record counts;
`source_observed_at` is distinct from the database ingestion time. Repeating a command while the
upstream payload is unchanged prints `"reused": true` and does not add parsed records.
Repeating `normalize` against the same two source runs, reconciliation policy and overrides likewise
returns `"reused": true`. Its metrics expose matched, ambiguous and unmatched station counts.

See [deployment and live validation](docs/deployment.md),
[architecture](docs/architecture.md), and [data sources](docs/data-sources.md).

## Phase 3 base routing

Build Italy tiles once into the named volume, then start the internal router and API:

```bash
docker compose --profile routing-build run --rm valhalla-tiles
docker compose --profile routing up -d db migrate valhalla api
curl --fail http://127.0.0.1:8000/health/ready
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":45.4857,"longitude":9.2045}}' \
  http://127.0.0.1:8000/api/v1/routes
```

The first full-Italy build is a substantial operator task. Image pulls, resource expectations,
regional overrides, rollback-safe graph updates, exact acceptance invariants and diagnostics are documented in
[deployment and live validation](docs/deployment.md#phase-3-valhalla-bootstrap-and-base-route-validation).

## Phase 4 spatial candidate pruning

With normalized stations and routing available, request a base route plus a spatial CNG corridor:

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300}' \
  http://127.0.0.1:8000/api/v1/cng/corridor-candidates
```

The default 300 km effective range produces an uncapped 60 km radius and applies the configured
50 km maximum. Response metrics distinguish the all-Italy station inventory from geocoded,
in-corridor and returned candidates. Candidate distance is a straight-line spatial prefilter value,
not road distance or detour. See the
[Phase 4 live gate](docs/deployment.md#phase-4-autonomy-aware-corridor-validation).

## Phase 5 road-network detour eligibility

Evaluate only the returned corridor candidates against a user-defined detour maximum:

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-28T08:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/detour-candidates
```

Results include both road legs, road distance from the origin/previous waypoint, total and extra
time/distance, station/destination ETAs and eligibility metrics. The current graph has no external
traffic feed, so the response explicitly reports `traffic_aware=false`. See the
[Phase 5 live gate](docs/deployment.md#phase-5-batched-network-detour-validation).

## Phase 6 arrival-time availability and ranking

Request ranked CNG candidates after the same spatial and road-network gates:

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-30T10:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/ranked-candidates
```

Closed-at-ETA stations are excluded by default; `"include_closed":true` retains them with an
explicit zero opening score and configurable score multiplier. Missing or invalid hours remain
`unknown`, not open. Prices remain optional and expose MIMIT observation/ingestion timestamps plus
freshness at station ETA. See the
[Phase 6 live gate](docs/deployment.md#phase-6-arrival-time-availability-and-ranking-validation).

## Repository layout

```text
src/compass/api/       health-level FastAPI scaffolding
src/compass/etl/       source acquisition, parsing and raw ingestion
src/compass/normalization/ normalized source values and coordinate validation
src/compass/reconciliation/ deterministic source matching and manual overrides
src/compass/routing/   provider boundary and Valhalla HTTP adapter
src/compass/candidates/ corridor policy, geometry decoding and PostGIS pruning
src/compass/detours/  batched network-cost evaluation and deterministic detour math
src/compass/ranking/  arrival-time opening evaluation, price freshness and explainable ranking
migrations/            Alembic schema history
tests/fixtures/        small network-free source fixtures
docs/adr/              accepted architecture decisions
compose.yaml           reference server-side deployment
```

## Project status and licensing

Phases 2–6 have passed repository-local checks and their documented operator-run live tests.
Phase 3 validated the digest-pinned Valhalla 3.8.3 runtime against a full Italy graph and a
representative Milan A-to-B API route. Phase 4 validated autonomy-aware PostGIS corridor pruning on
a Milan-to-Bologna route against the full 1,512-station inventory. Phase 5 validated batched
road-network detour eligibility over 200 pruned candidates using one base route and ten Valhalla
matrix calls, with an independent known-station route comparison. Evidence is recorded in
`docs/phases/phase-2-acceptance.md`, `docs/phases/phase-3-acceptance.md` and
`docs/phases/phase-4-acceptance.md`, with the Phase 5 and Phase 6 gates in
`docs/phases/phase-5-acceptance.md` and `docs/phases/phase-6-acceptance.md`. Phase 6 validated
arrival-time opening states and explainable ranking over the same full-Italy bounded matrix pipeline,
including the real-world `Su, PH off` Sunday case.

Compass source code is licensed under the
[GNU General Public License version 3 only](LICENSE). Source datasets retain their own licenses;
see `docs/data-sources.md`.
