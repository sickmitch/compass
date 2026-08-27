# Docker deployment and live validation

This is the reference test-server workflow for repository bootstrap, raw source ingestion and Phase
2 normalized station reconciliation.

## Prerequisites

- Linux host with Docker Engine and the Compose plugin;
- outbound HTTPS access to `www.mimit.gov.it` and the configured Overpass endpoint;
- this repository synchronized onto the test server;
- no public reverse proxy is required.

From the repository root, create configuration:

```bash
cp .env.example .env
```

Edit `.env` so:

- `POSTGRES_PASSWORD` is a non-default test-server secret;
- the password in `DATABASE_URL` represents the same password (URL-encode reserved characters;
  for example, a literal `%` is `%25` in the URL);
- `HTTP_USER_AGENT` includes a meaningful application/operator contact;
- no secret is committed back to Git.

## Phase 0 bootstrap validation

```bash
docker compose config --quiet
docker compose build api
docker compose up -d db migrate api
docker compose ps
docker compose logs --no-color migrate
curl --fail --silent --show-error http://127.0.0.1:8000/health/live
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT extversion FROM pg_extension WHERE extname = '\''postgis'\'';"'
docker compose exec -T api alembic current
```

Expected invariants:

- `db` and `api` are healthy and `migrate` exited with code 0;
- liveness returns `status=ok` with `database=not_checked`;
- readiness returns `status=ok` with `database=ready`;
- PostGIS has a non-empty version;
- Alembic reports the repository's current head revision (`0002` once Phase 2 is synchronized).

## Phase 1 live acquisition and idempotency validation

Run MIMIT twice while the upstream daily files remain unchanged, then OSM twice:

```bash
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose --profile jobs run --rm etl osm
```

The first result for each source should have `status=completed`, `reused=false` and non-zero relevant
counts. The immediate repeat should have the same `run_id` and hash with `reused=true`. If MIMIT
publishes between runs, a new hash/run is correct; repeat once more promptly to prove reuse.

Inspect representative data and persisted metrics:

```bash
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT id, source_name, status, source_observed_at, completed_at, metrics FROM ingestion_runs ORDER BY id;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT mimit_station_id, name, municipality, province, latitude, longitude FROM raw_mimit_stations ORDER BY id LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT mimit_station_id, source_fuel_name, unit_price, currency, unit, is_self_service, price_observed_at FROM raw_mimit_cng_prices ORDER BY id LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT osm_type, osm_id, name, opening_hours, phone, latitude, longitude FROM raw_osm_cng_features ORDER BY id LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT source_name, sha256, source_url, source_observed_at, fetched_at, octet_length(content) AS bytes FROM raw_source_snapshots ORDER BY id;"'
```

Expected invariants:

- MIMIT retained rows contain only metano/CNG prices, with `currency=EUR` and `unit=kg`;
- representative MIMIT source IDs link prices to selected active-station source rows;
- OSM rows retain `osm_type` and `osm_id`, with enrichment fields nullable rather than invented;
- source observation times and fetch/ingestion times are both populated where upstream supplies them;
- exact raw snapshots have non-zero byte lengths;
- repeated unchanged imports do not increase counts.

## Diagnostics if an invariant fails

```bash
docker compose ps -a
docker compose logs --no-color --tail=300 db migrate api
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\\dt"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version_num FROM alembic_version;"'
```

For a download failure, also return the HTTP status/error printed by the ETL job and confirm outbound
DNS/TLS access from the Docker host. A transient Overpass `429`, `502`, `503`, or `504` is safe to
retry: the OSM job is independent and cannot erase a successful MIMIT import. Do not paste `.env` or
database passwords.

## Output to return

Return:

1. `docker compose ps`;
2. migration log plus both health response bodies;
3. the four ETL JSON lines (or enough prompt repeats to show `reused=true`);
4. all five inspection-query outputs above;
5. diagnostics/logs if any command fails.

These Phase 0/1 invariants are prerequisites for Phase 2 unless the operator explicitly waives them.

## Phase 2 migration and reconciliation validation

Run these commands from the repository root after synchronizing the Phase 2 changes. Existing Phase
1 raw records are inputs; they do not need to be downloaded again.

```bash
docker compose build api
docker compose up -d db migrate api
docker compose logs --no-color migrate
docker compose exec -T api alembic current
docker compose --profile jobs run --rm etl normalize
docker compose --profile jobs run --rm etl normalize
```

Expected invariants:

- migration exits zero and Alembic reports `0002 (head)`;
- the first normalize result is `completed`, normally with `reused=false`;
- `stations_seen`, `price_rows_seen`, and `osm_features_seen` are non-zero;
- `matched + ambiguous + unmatched == stations_seen`;
- the prompt repeat has the same reconciliation run/configuration hash and `reused=true`;
- no live download is performed by `normalize`.

Inspect the schema, outcome counts, and representative current data:

```bash
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = '\''public'\'' AND indexname IN ('\''ix_stations_location_gist'\'', '\''ix_osm_cng_features_location_gist'\'') ORDER BY indexname;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT status, count(*) FROM reconciliation_results WHERE reconciliation_run_id = (SELECT max(id) FROM reconciliation_runs WHERE status = '\''completed'\'') GROUP BY status ORDER BY status;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT id, algorithm_version, status, configuration_sha256, metrics FROM reconciliation_runs ORDER BY id DESC LIMIT 3;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT s.mimit_station_id, s.name, s.municipality, ST_Y(s.location::geometry) AS latitude, ST_X(s.location::geometry) AS longitude, p.unit_price, p.currency, p.unit, p.service_mode, p.observed_at AS price_observed_at, o.osm_type, o.osm_id, o.opening_hours, o.phone, l.match_method, l.confidence, l.distance_meters FROM stations s JOIN station_current_prices cp ON cp.station_id = s.id AND cp.fuel_type = '\''cng'\'' JOIN station_prices p ON p.id = cp.station_price_id LEFT JOIN station_osm_links l ON l.station_id = s.id LEFT JOIN osm_cng_features o ON o.id = l.osm_feature_id WHERE s.is_active ORDER BY (o.id IS NOT NULL) DESC, s.id LIMIT 20;"'
```

The two index definitions must use `gist`. The representative query must return active Italian CNG
stations with EUR/kg prices and timestamps. At least some rows should include OSM identity and any
available opening-hours/phone enrichment; null enrichment is valid for unmatched stations and must
not be invented.

Manual overrides are available for operator-reviewed cases. Do not run these merely for smoke
testing because they intentionally change reconciliation state:

```bash
docker compose --profile jobs run --rm etl override \
  --mimit-station-id MIMIT_ID --action link --osm-type node --osm-id OSM_ID \
  --reason "operator-verified identity" --created-by OPERATOR
docker compose --profile jobs run --rm etl override \
  --mimit-station-id MIMIT_ID --action unmatch \
  --reason "operator-verified non-match" --created-by OPERATOR
docker compose --profile jobs run --rm etl normalize
```

### Phase 2 diagnostics

```bash
docker compose ps -a
docker compose logs --no-color --tail=300 migrate api db
docker compose exec -T api alembic current
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT id, status, error_message, metrics FROM reconciliation_runs ORDER BY id DESC LIMIT 10;"'
docker compose exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT r.status, r.decision_reason, count(*) FROM reconciliation_results r WHERE r.reconciliation_run_id = (SELECT max(id) FROM reconciliation_runs) GROUP BY r.status, r.decision_reason ORDER BY r.status, count(*) DESC;"'
```

Return the migration log/current revision, both normalize JSON lines, and all four Phase 2 inspection
query outputs. If anything fails, also return the Phase 2 diagnostic output. Do not include `.env`.

Do not begin Phase 3 until these Phase 2 live invariants pass or the operator explicitly waives the
gate.
