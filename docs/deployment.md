# Docker deployment and Phase 0/1 live validation

This is the reference test-server workflow. It is intentionally limited to repository bootstrap,
health scaffolding and raw source ingestion.

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
docker compose --profile jobs build
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
- Alembic reports revision `0001 (head)`.

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

Do not begin Phase 2 until these Phase 0/1 live invariants pass or the operator explicitly waives the
gate.
