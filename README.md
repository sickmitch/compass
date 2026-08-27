# Compass

Compass is an open-source navigation system in development for fuel-aware CNG/metano routing in
Italy. The product target is route planning and navigation with dynamically inserted, reachable,
arrival-time-aware refuelling stops—not a generic fuel-station map.

This repository currently implements **Phases 0–2**:

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

It intentionally does **not** yet run Valhalla, expose routing APIs, evaluate opening hours, or rank
route candidates. Those begin in later gated phases.

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
curl --fail http://127.0.0.1:8000/health/ready
```

The service binds to loopback by default. Public exposure is deliberately outside this repository's
current scope.

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

## Repository layout

```text
src/compass/api/       health-level FastAPI scaffolding
src/compass/etl/       source acquisition, parsing and raw ingestion
src/compass/normalization/ normalized source values and coordinate validation
src/compass/reconciliation/ deterministic source matching and manual overrides
migrations/            Alembic schema history
tests/fixtures/        small network-free source fixtures
docs/adr/              accepted architecture decisions
compose.yaml           reference server-side deployment
```

## Project status and licensing

Phase 2 has passed repository-local checks, an isolated PostGIS integration test and the documented
operator-run live test against representative MIMIT and OSM snapshots. The accepted live counts and
evidence are recorded in `docs/phases/phase-2-acceptance.md`.

The project intends to be open source, but a repository code license has not yet been selected by
the owner. Source datasets retain their own licenses; see `docs/data-sources.md`.
