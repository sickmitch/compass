# Phase 7 acceptance criteria

Phase 7 stabilizes the versioned public API around the accepted Phase 3–6 domain behavior. It adds
station detail, route recomputation through an explicitly selected CNG stop, data freshness,
consistent machine errors and a maintained OpenAPI artifact. It does not add predictive refuelling,
traffic ingestion, navigation sessions or Android UI work.

## Implemented gate

- Official `mimit_station_id` is the stable public station identity; internal row IDs are not exposed
  by station detail or selected-stop routing.
- Station detail reads the MIMIT station, accepted OSM enrichment/link provenance and every current
  CNG service-mode price in one query.
- Price observation/ingestion times, reference instant, age and freshness are explicit.
- Optional timezone-aware `arrival_at` reuses Phase 6 opening semantics; omission is explicitly
  `not_requested`, and missing coordinates are `location_unavailable`.
- Selected-stop routing resolves an active station and sends one three-location Valhalla request.
- The selected route returns exactly two named legs, each with its own endpoints, polyline6 and
  maneuver list.
- Unknown, inactive and ungeocoded stations produce distinct 404/409 machine errors.
- MIMIT, OSM and reconciliation freshness expose observation/completion time, age, threshold and
  `fresh`/`stale`/`future_observation`/`missing` state.
- Missing required normalized data makes readiness 503; stale-but-present data is declared degraded
  and remains queryable with readiness HTTP 200. Traffic remains `not_configured`.
- `docs/openapi.json` is generated from FastAPI and a test prevents contract drift.
- A checked-in live runner creates unambiguous request files and invokes the verifier for success
  contracts, error envelopes, leg sums, freshness and required OpenAPI paths.
- No schema migration is required; Alembic remains at `0002`.

## Local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest
.venv/bin/python scripts/export-openapi.py --check
TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@HOST:5432/compass_test .venv/bin/pytest -q tests/test_phase7_postgis.py
docker compose --profile routing config --quiet
docker compose build api
```

The database name guard requires the integration target to end in `_test`; the integration test
resets that database. The network-free suite never calls MIMIT, Overpass or Valhalla.

## Local validation result

Validated on 2026-08-29:

- Ruff passed over the complete repository;
- the network-free suite reported 126 passed and five opt-in PostGIS tests skipped;
- all five Phase 2/4/5/6/7 integration tests passed against the isolated PostGIS 16 / PostGIS 3.5
  `compass_test` database;
- the Phase 7 integration test loaded normalized MIMIT station `1001`, its PostGIS location, current
  EUR/kg price, accepted OSM hours/phone/link confidence and all three non-missing freshness sources
  using exactly one station-detail SQL query;
- adapter tests prove one three-break Valhalla request maps to two validated legs and reject a wrong
  provider leg count;
- API contract tests cover station detail, ETA opening evaluation, price freshness, selected-stop
  leg boundaries, inactive/missing-location conflicts, stable 404, strict 422, freshness states and
  data-aware readiness;
- `bash -n scripts/run-phase7-live.sh` passed for the operator-facing live-gate runner;
- `scripts/export-openapi.py --check` passed and the checked artifact exactly matched runtime OpenAPI;
- Compose validated with the routing profile;
- `compass-app:0.1.0` built successfully from the pinned Python 3.12.11 base;
- the built image reported `GPL-3.0-only` and exposed all seven `/api/v1` endpoints, including station
  detail, selected-stop routing and data freshness.

No query against the operator's full Italy station dataset or live Valhalla service was performed
in the repository environment.

## Live gate

Follow the Phase 7 section of `docs/deployment.md`. Acceptance requires operator evidence for base
routing, ranked candidates, official-ID station detail, selected-stop two-leg routing, data-aware
readiness/freshness, stable 404/422 envelopes and the published OpenAPI paths. The operator runs
`bash scripts/run-phase7-live.sh`; no inline JSON or shell command substitutions are required.
Phase 8 must not start until that evidence passes the checked-in verifier or the operator explicitly
waives the gate.

## Live validation result

Accepted on 2026-08-29. The operator reported that `bash scripts/run-phase7-live.sh` reached
`Phase 7 live gate completed successfully.` The runner reaches that terminal state only after:

- readiness, base routing, ranked candidates, official-ID station detail, selected-stop routing,
  freshness and live OpenAPI requests succeed;
- the expected station-not-found and strict-validation requests return 404 and 422 respectively;
- `scripts/validate-phase7-live.py` validates the response contracts, two ordered route legs and
  their totals, source freshness, required OpenAPI paths and shared error codes;
- bounded API/Valhalla evidence is collected and the live application can execute Alembic and read
  its installed license metadata.

The Phase 7 gate is complete. Phase 8 may begin only when explicitly requested.
