# ADR 0009: Stable public API identity, selected-stop routing and freshness

- Status: accepted
- Date: 2026-08-29

## Context

Phases 3–6 established base routing, bounded candidate selection, real network detours and
arrival-time ranking. A mobile client now needs a stable contract for inspecting a station and
turning a selected candidate into an actual route. It also needs to distinguish an available
process from usable dependencies and old/missing dynamic data. Database keys and raw Valhalla
multi-leg payloads are not suitable public contracts.

## Decision

Compass retains `/api/v1` and uses the official MIMIT `idImpianto` string as public station identity.
Internal station IDs remain implementation details. `GET /api/v1/cng/stations/{mimit_station_id}`
returns MIMIT-anchored station data, all current CNG service-mode prices with observation/ingestion
times and freshness, and the accepted OSM enrichment/link provenance. Optional offset-aware
`arrival_at` evaluates OSM opening hours using the Phase 6 semantics.

`POST /api/v1/routes/with-cng-stop` accepts origin, destination and MIMIT station ID. Compass resolves
the current active station location and makes one Valhalla route request with three break locations.
The public response contains exactly two independent legs (`origin_to_cng_station` and
`cng_station_to_destination`), each with polyline6 geometry and maneuvers. The API does not splice
encoded polylines or expose raw provider JSON.

Unknown station identity is 404. An inactive station or one without usable coordinates is a distinct
409 conflict. No-route, provider and database failures retain the shared machine-readable
`{code,message}` envelope already used by routing APIs. Request models remain strict.

`GET /api/v1/data-freshness` evaluates the latest completed MIMIT ingestion, OSM ingestion and
reconciliation against configurable thresholds. Source observation time is preferred to ingestion
completion. Missing required MIMIT/reconciliation state makes readiness unavailable; stale or
future-observed data produces a degraded but HTTP-200 readiness state because the last successful
dataset remains explicitly usable. Traffic remains `not_configured`.

FastAPI remains the schema source. `scripts/export-openapi.py` generates `docs/openapi.json`; a
contract test fails if the checked artifact diverges from runtime OpenAPI.

## Consequences

- Mobile clients can persist official station identity without depending on database row IDs.
- Explicit leg boundaries make map rendering and later navigation transitions deterministic.
- One station detail query avoids N+1 work while preserving authoritative/enrichment provenance.
- Operators and clients can distinguish dependency failure, missing data and stale-but-usable data.
- Adding another stop class later can reuse the waypoint-route boundary without changing CNG
  candidate selection internals.
- Predictive fuel reachability, route alternatives, traffic providers and Android presentation remain
  later phases.

## References

- [ADR 0002: source authority and reconciliation boundary](0002-source-authority-and-reconciliation-boundary.md)
- [ADR 0005: Valhalla runtime and routing boundary](0005-valhalla-runtime-and-routing-boundary.md)
- [ADR 0008: arrival-time station availability and explainable ranking](0008-arrival-time-availability-and-ranking.md)
