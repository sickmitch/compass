# Architecture overview

## Boundaries

Compass uses five explicit server/domain boundaries and one first-class device client:

1. **Source adapters** fetch MIMIT CSV and OSM Overpass JSON without embedding source-specific
   behavior in routing code.
2. **ETL** retains exact payloads, parses source records and publishes counts. Phase 1 stops before
   reconciliation.
3. **PostgreSQL/PostGIS** stores source history now and will own normalized/spatial station data in
   Phase 2.
4. **Routing intelligence** will own corridor pruning, detour math, arrival-time opening status,
   ranking and predictive reachability. It will not live in the Android UI or raw ETL adapters.
5. **FastAPI** will expose strict versioned mobile contracts when routing domain operations exist.
6. **Android (Kotlin, Jetpack Compose, MapLibre Native)** owns presentation and interaction state.

Valhalla is the selected routing engine but is not included in the Phase 0/1 runtime. Its reproducible
tile bootstrap and HTTP adapter belong to Phase 3. This avoids presenting a non-functional routing
container as foundation work.

## Phase 1 data flow

```text
MIMIT active-station CSV ─┐
                          ├─> fetch limit + exact snapshot ─> parse ─> select CNG IDs ─> raw tables
MIMIT price CSV ──────────┘

OSM Overpass JSON ──────────> fetch limit + exact snapshot ─> validate CNG features ─> raw table
```

Every retained exact payload has a SHA-256 identity. MIMIT's two related payloads receive a
deterministic combined identity for an ingestion run. OSM runs use a versioned canonical identity of
the parsed CNG feature collection, excluding the volatile Overpass response timestamp; otherwise an
unchanged query would create a new run every minute. The first exact payload for that logical content
is retained. A completed run with the same identity is returned as reused. Database constraints
independently protect snapshot, run and row identity.

The current raw model preserves:

- source URL and content type;
- payload bytes and SHA-256;
- source observation/extraction time and fetch/ingestion time separately;
- source IDs and original source record JSON;
- explicit CNG price currency (`EUR`) and unit (`kg`);
- OSM element type + ID and unmodified tags;
- per-run visible metrics.

## Deliberate Phase 1 limits

- MIMIT coordinates are source values and are not assumed verified.
- OSM data never overwrites MIMIT source data.
- No fuzzy/spatial join exists yet.
- No normalized station or PostGIS geometry exists yet.
- No route, detour, opening-hours evaluation, ranking, traffic or predictive logic exists yet.

These are enforced by keeping MIMIT and OSM in separate raw tables. Phase 2 will add normalized
spatial models and a testable reconciliation mechanism through a new migration.

## Runtime

The default Compose graph contains:

- `db`: pinned PostGIS 16 / PostGIS 3.5 family with a named data volume and readiness healthcheck;
- `migrate`: one-shot Alembic upgrade after the database is healthy;
- `api`: non-root FastAPI container with liveness/readiness healthcheck and loopback-only host bind;
- `etl`: profile-gated one-shot job, not a persistent service.

Secrets are environment supplied. No host-specific paths or privileged containers are required.
