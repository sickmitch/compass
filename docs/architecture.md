# Architecture overview

## Boundaries

Compass uses five explicit server/domain boundaries and one first-class device client:

1. **Source adapters** fetch MIMIT CSV and OSM Overpass JSON without embedding source-specific
   behavior in routing code.
2. **ETL** retains exact payloads, parses source records and publishes counts. Normalization is a
   separate restartable command so source acquisition remains independently useful.
3. **PostgreSQL/PostGIS** stores raw source history, normalized/spatial station data, price history
   and reconciliation evidence.
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

## Phase 2 normalized data flow

```text
latest completed MIMIT run ─> MIMIT-anchored stations ─> geography(Point, 4326) + price history
                                        │
                                        ├─ ST_DWithin 250 m candidate search (GiST)
                                        │          └─ deterministic distance/name policy
latest completed OSM run ───> stable OSM features ─> matched / ambiguous / unmatched evidence
                                                               └─ current enrichment link
```

`stations` represents official MIMIT station identity and fields. `osm_cng_features` represents
stable OSM type/ID and enrichment fields. `station_osm_links` is the current accepted relationship;
it does not copy or overwrite authoritative station fields. Price observations are semantically
deduplicated in `station_prices`, while `station_current_prices` points to the latest observation for
each station/fuel/service-mode tuple.

Each reconciliation run records its MIMIT/OSM input run IDs, algorithm version, effective policy,
manual overrides, configuration hash, decisions, ranked candidates and summary counts. Identical
inputs are reused. A changed override or policy creates a distinct run. An OSM feature cannot be
linked to two stations: automatic conflicts become ambiguous, while conflicting manual claims fail.

Source coordinates outside conservative Italian bounds, or missing coordinates, remain preserved in
raw tables but produce a null normalized geography and an explicit unmatched outcome.

## Deliberate Phase 2 limits

- MIMIT coordinates remain authoritative source values, not verified road-access points.
- OSM data never overwrites MIMIT source data; only accepted links expose enrichment.
- Reconciliation uses conservative deterministic proximity/name rules, not address geocoding or an
  opaque fuzzy model.
- No route, detour, opening-hours evaluation, ranking, traffic or predictive logic exists yet.

Phase 3 will add the reproducible Valhalla runtime and routing adapter without changing these source
authority boundaries.

## Runtime

The default Compose graph contains:

- `db`: pinned PostGIS 16 / PostGIS 3.5 family with a named data volume and readiness healthcheck;
- `migrate`: one-shot Alembic upgrade after the database is healthy;
- `api`: non-root FastAPI container with liveness/readiness healthcheck and loopback-only host bind;
- `etl`: profile-gated one-shot job, not a persistent service.

All three application workloads use the same configurable `COMPASS_IMAGE`. Only `api` declares the
repository build, so one build produces the exact image used for migrations, the API and ETL. This
prevents a successful old migration image from reporting completion while newer application code
expects a schema it never created.

Secrets are environment supplied. No host-specific paths or privileged containers are required.
