# Phase 0 and Phase 1 acceptance record

## Objective

Establish reproducible server foundations and implement restartable, observable raw CNG acquisition
from MIMIT and OSM without crossing into normalized reconciliation or routing.

## Components and decisions

- Docker Compose: PostGIS, migration, API and profile-gated ETL workloads.
- API: process liveness and dependency readiness only.
- ETL: bounded/time-limited HTTP acquisition, fixture/file mode, parsers and raw persistence.
- Schema: migrations, exact snapshots, ingestion runs, separate MIMIT/OSM source tables.
- Configuration: safe defaults and `.env.example`; no committed secrets.
- Tests: parser, time semantics, CNG filtering, query construction, API health and idempotency.

The Phase 1 data flow and deliberate limits are documented in `docs/architecture.md`. Consequential
decisions are recorded in `docs/adr/`.

## Acceptance evidence required

| Requirement | Local evidence | Live evidence required |
| --- | --- | --- |
| Coherent skeleton and boundaries | docs and package structure review | repository synchronizes/builds |
| Compose validates/builds | `docker compose config --quiet`; local build if available | test-server build |
| Health scaffolding | API unit tests | healthy Compose service and curl responses |
| MIMIT parsers/filtering | checked-in CSV fixture tests | daily official downloads import |
| OSM adapter/parser | checked-in Overpass JSON tests | configured Overpass response imports |
| Raw provenance/timestamps | persistence tests and migration review | representative database queries |
| Idempotent repeated ingestion | SQLite unit tests for both sources | immediate repeated Docker jobs show reuse |
| Visible counts | metric assertions | returned JSON and `ingestion_runs.metrics` |

Local mocks/fixtures never satisfy the live-evidence column. The exact live procedure is in
`docs/deployment.md`.

