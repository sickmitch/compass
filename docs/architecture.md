# Architecture overview

## Boundaries

Compass uses six explicit server/domain boundaries and one first-class device client:

1. **Source adapters** fetch MIMIT CSV and OSM Overpass JSON without embedding source-specific
   behavior in routing code.
2. **ETL** retains exact payloads, parses source records and publishes counts. Normalization is a
   separate restartable command so source acquisition remains independently useful.
3. **PostgreSQL/PostGIS** stores raw source history, normalized/spatial station data, price history
   and reconciliation evidence.
4. **Routing provider** exposes a provider-neutral async interface. Its Valhalla adapter owns HTTP
   translation, response validation and failure classification.
5. **Routing intelligence** will own corridor pruning, detour math, arrival-time opening status,
   ranking and predictive reachability. It will not live in the Android UI or raw ETL adapters.
6. **FastAPI** exposes strict versioned mobile contracts without exposing provider response shapes.
7. **Android (Kotlin, Jetpack Compose, MapLibre Native)** owns presentation and interaction state.

Valhalla is the selected routing engine. Phase 3 adds its independently persisted tile bootstrap and
provider adapter; Valhalla remains inaccessible from host/public ports.

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

## Phase 3 base-routing flow

```text
POST /api/v1/routes ─> strict A/B request ─> RoutingProvider
                                               └─ Valhalla adapter ─> internal /route
                                                    └─ metres + seconds + polyline6 + maneuvers

Geofabrik Italy/regional PBF ─> one-shot tile build ─> named volume ─> Valhalla service
```

The public base-route operation supports exactly two coordinates and automobile costing. The
provider adapter converts Valhalla kilometres to metres and validates the response before creating
domain objects. No source station model is consulted in this phase.

## Deliberate Phase 3 limits

- No route corridor, CNG candidate selection, matrix call or detour calculation.
- No traffic input or claim of traffic-aware routing.
- No opening-hours evaluation, ranking, predictive range model or multi-waypoint public API.
- Tile refresh remains an explicit operator job; application startup never erases the tile volume.

## Runtime

The default Compose graph contains:

- `db`: pinned PostGIS 16 / PostGIS 3.5 family with a named data volume and readiness healthcheck;
- `migrate`: one-shot Alembic upgrade after the database is healthy;
- `api`: non-root FastAPI container with liveness/readiness healthcheck and loopback-only host bind;
- `etl`: profile-gated one-shot job, not a persistent service.

Routing adds two opt-in workloads:

- `valhalla-tiles`: `routing-build` profile, one-shot Italy/regional graph construction;
- `valhalla`: `routing` profile, persistent internal router with a status healthcheck.

Both routing workloads use the same `valhalla_data` named volume. The API does not require the
routing profile merely to start, but readiness is `not_ready` until both PostgreSQL and Valhalla are
available. `VALHALLA_VOLUME_NAME` makes the physical volume versionable so graph updates can be
built and validated before activation, while the previous graph remains available for rollback. The
builder checks the scripted image's PBF registration (`use_tiles_ignore_pbf=False`); only the serving
process trusts and reuses the completed graph (`use_tiles_ignore_pbf=True`). Exact input identity is
an explicit content SHA-256, because the image's internal registration hashes file paths.

All three application workloads use the same configurable `COMPASS_IMAGE`. Only `api` declares the
repository build, so one build produces the exact image used for migrations, the API and ETL. This
prevents a successful old migration image from reporting completion while newer application code
expects a schema it never created.

Secrets are environment supplied. No host-specific paths or privileged containers are required.
