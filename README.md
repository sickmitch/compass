# Compass

Compass is an open-source navigation system in development for fuel-aware CNG/metano routing in
Italy. The product target is route planning and navigation with dynamically inserted, reachable,
arrival-time-aware refuelling stops—not a generic fuel-station map.

This repository currently implements the accepted **Phases 0–3** foundation:

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

It intentionally does **not** yet select CNG candidates, calculate station detours, evaluate opening
hours, rank stations, ingest traffic or implement predictive refuelling. Those remain later gated
phases.

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

## Repository layout

```text
src/compass/api/       health-level FastAPI scaffolding
src/compass/etl/       source acquisition, parsing and raw ingestion
src/compass/normalization/ normalized source values and coordinate validation
src/compass/reconciliation/ deterministic source matching and manual overrides
src/compass/routing/   provider boundary and Valhalla HTTP adapter
migrations/            Alembic schema history
tests/fixtures/        small network-free source fixtures
docs/adr/              accepted architecture decisions
compose.yaml           reference server-side deployment
```

## Project status and licensing

Phases 2 and 3 have passed repository-local checks and their documented operator-run live tests.
Phase 3 validated the digest-pinned Valhalla 3.8.3 runtime against a full Italy graph and a
representative Milan A-to-B API route. Evidence is recorded in `docs/phases/phase-2-acceptance.md`
and `docs/phases/phase-3-acceptance.md`.

The project intends to be open source, but a repository code license has not yet been selected by
the owner. Source datasets retain their own licenses; see `docs/data-sources.md`.
