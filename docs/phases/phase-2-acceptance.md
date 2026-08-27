# Phase 2 acceptance criteria

Phase 2 adds normalized CNG station storage and source reconciliation. It does not add routing,
opening-hours evaluation or station ranking.

## Implemented gate

- Alembic revision `0002` creates normalized MIMIT stations and stable OSM feature models.
- Locations are nullable PostGIS geography points in SRID 4326 with explicit GiST indexes.
- MIMIT remains authoritative; OSM enrichment stays separately linked with field provenance.
- CNG price history is semantically idempotent and has explicit current-price pointers.
- Reconciliation inputs, version/configuration, decisions, candidates, metrics and current links are
  persisted.
- Outcomes distinguish matched, ambiguous and unmatched records.
- Duplicate automatic OSM claims become ambiguous; conflicting manual claims are rejected.
- Manual link/unmatch overrides are operator/reason attributed and cause a new configured run.
- Network-free fixtures cover deterministic proximity, name, ambiguity, unmatched and conflict cases.
- An opt-in integration test migrates an isolated PostGIS database and verifies spatial indexes,
  counts, current price/enrichment joins, reuse and manual override behavior.

## Local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest
TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@HOST/compass_test \
  .venv/bin/pytest tests/test_phase2_postgis.py
docker compose config --quiet
docker compose build api
```

The integration database name must end in `_test`; the test deliberately migrates it down to base and
back to head. It must never target an operator or production database.

## Live gate

Follow the Phase 2 section of `docs/deployment.md`. Phase 2 is accepted only after the operator
returns evidence that migration `0002`, normalization reuse, outcome counts, both GiST indexes and a
representative current price/OSM enrichment query all satisfy their documented invariants.

## Live validation result

Accepted on 2026-08-27 using the operator-managed Docker test server:

- API, migration and ETL resolved to the same `compass-app:0.1.0` image.
- Alembic upgraded `0001 -> 0002`; both `heads` and `current` reported `0002 (head)`.
- Reconciliation run 1 used MIMIT ingestion run 1 and OSM ingestion run 4 with algorithm
  `mimit-osm-distance-name-v1`.
- The immediate repeat reused reconciliation run 1 and the same configuration SHA-256.
- 1,512 active stations normalized; 1,505 had valid geography points.
- 1,583 current CNG price rows were available.
- Outcomes were 1,001 matched, 23 ambiguous and 488 unmatched, totaling all 1,512 stations.
- 1,001 accepted OSM links left 350 of 1,351 OSM features unmatched.
- Both station and OSM location indexes were present as GiST indexes.
- The representative live join returned Italian stations with EUR/kg current prices, price
  observation timestamps, OSM identity, opening hours, phones, brand/operator data, match confidence
  and geography distance.

These results satisfy the Phase 2 gate. Phase 3 may begin only when explicitly requested.
