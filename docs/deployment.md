# Docker deployment and operations

Run deployment commands only on the operator's server, from the synchronized repository.
Local fixture tests do not establish live provider or device acceptance.

## Prerequisites

- Linux host with Docker Engine and the Compose plugin;
- outbound HTTPS access to `www.mimit.gov.it`, the configured Overpass endpoint, `ghcr.io` and the
  configured Valhalla PBF host;
- this repository synchronized onto the test server;
- no public reverse proxy is required.

For a first installation only, create configuration from the repository root. On an existing
installation preserve `.env` and compare it with `.env.example`:

```bash
cp .env.example .env
```

Edit `.env` so:

- `POSTGRES_PASSWORD` is a non-default test-server secret;
- the password in `DATABASE_URL` represents the same password (URL-encode reserved characters;
  for example, a literal `%` is `%25` in the URL);
- `HTTP_USER_AGENT` includes a meaningful application/operator contact;
- no secret is committed back to Git.

## External API access and authentication

The Compose API port remains bound to `127.0.0.1:8000` by default. If the operator's ingress runs on
the same host, route its HTTPS virtual host to that loopback address. Set
`API_BIND_ADDRESS=0.0.0.0` only when the chosen ingress must reach the published port through another
interface, and restrict that port with the host firewall. Compass does not create DNS records,
certificates or ingress rules.

Before exposing the service, create dedicated mobile credentials in the server's uncommitted
`.env`. A hexadecimal password avoids Compose interpolation surprises:

```bash
openssl rand -hex 32
```

```dotenv
API_AUTH_ENABLED=true
API_AUTH_USERNAME=compass-mobile
API_AUTH_PASSWORD=<generated-value>
API_BIND_ADDRESS=127.0.0.1
API_PORT=8000
```

Rebuild and recreate the API, then verify the boundary locally. Enter the password interactively so
it is not returned in diagnostics:

```bash
docker compose build api
docker compose up -d api
curl --fail --silent --show-error http://127.0.0.1:8000/health/live
curl --silent --output /tmp/compass-auth-missing.json \
  --write-out '%{http_code}\n' http://127.0.0.1:8000/api/v1/auth/check
read -r -p 'Compass API username: ' COMPASS_CHECK_USER
read -r -s -p 'Compass API password: ' COMPASS_CHECK_PASSWORD
printf '\n'
curl --fail --silent --show-error \
  --user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD" \
  http://127.0.0.1:8000/api/v1/auth/check
unset COMPASS_CHECK_USER COMPASS_CHECK_PASSWORD
```

The unauthenticated request must print `401`; the authenticated response must contain
`"authenticated":true` and never contains the password. HTTPS termination belongs at the operator's
ingress. HTTP is supported for constrained/private deployments, but Basic credentials are then sent
without transport encryption and the Android app requires an explicit warning acknowledgement.

## Automatic updates

The [container scheduler](scheduled-updates.md) runs map rebuilds at 03:00 and CNG
imports every hour from 07:00 through 20:00, Europe/Rome. Build `scheduled-updates`
and start the `routing` profile with Compose; no host timers are needed.

## Database and source data

```bash
docker compose config --quiet
docker compose build api
docker compose up -d db migrate api
docker compose logs --no-color migrate
docker compose exec -T api alembic current
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose --profile jobs run --rm etl normalize
```

Migrations must reach the repository head. Imports retain source and ingestion timestamps;
repeating an unchanged source payload reuses the ingestion rather than duplicating it.
Normalization is separate from acquisition. A failed OSM fetch must not erase valid
MIMIT data. Inspect job output and database logs if counts or idempotency differ.
Source policies and reconciliation are described in [data sources](data-sources.md).

## Updating an existing deployment

Preserve database and graph volumes, synchronize code, review `.env.example` changes,
build the API and run migrations before recreating services:

```bash
docker compose build api
docker compose run --rm migrate
docker compose --profile routing up -d --build api valhalla scheduled-updates
docker compose --profile routing ps -a
```

Follow the [current release gate](phases/android-0.28.46-nominal-routing-gate.md) for
release-specific recreation and migration requirements. Publish Android updates using
[the distribution workflow](client-updates.md). Do not delete volumes during upgrades.

## Valhalla graph bootstrap and recovery

For the authenticated route check below, set `COMPASS_CHECK_USER` to your API username;
curl will prompt for the password. Do not include credentials in shared output.

Run from the repository root after synchronizing Phase 3. Confirm `.env` contains the pinned
`VALHALLA_IMAGE` from `.env.example`. The default PBF is the full Italy extract. Set
`VALHALLA_TILE_URLS` to a Geofabrik regional extract before the first build only if a regional graph
is intended. `VALHALLA_THREADS` controls build/server concurrency; the default is deliberately two.

The initial download and tile build can be long-running and needs substantially more disk than the
compressed PBF. Do not interrupt it merely because output pauses, and do not use
`docker compose down -v`: that would delete both database and routing named volumes.

The tile job checks the scripted image's PBF registration and rebuilds when its graph is missing or
unregistered. The persistent service only reuses the graph after that one-shot job exits
successfully. Keep
`VALHALLA_FORCE_REBUILD=False` for normal operation.

```bash
docker compose --profile routing --profile routing-build config --quiet
docker compose build api
docker compose --profile routing-build pull valhalla-tiles
docker compose --profile routing pull valhalla
docker compose --profile routing-build run --rm valhalla-tiles
docker compose --profile routing up -d --build db migrate valhalla api scheduled-updates
docker compose --profile routing ps -a
docker compose --profile routing exec -T valhalla \
  curl --fail --silent --show-error http://127.0.0.1:8002/status
docker compose --profile routing exec -T valhalla sh -c \
  'ls -lh /custom_files/*.pbf /custom_files/valhalla_tiles.tar && sha256sum /custom_files/*.pbf'
curl --fail --silent --show-error http://127.0.0.1:8000/health/live
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
curl --fail --silent --show-error --user "$COMPASS_CHECK_USER" \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":45.4857,"longitude":9.2045}}' \
  http://127.0.0.1:8000/api/v1/routes
```

The `valhalla-config` one-shot dependency updates the generated `valhalla.json` idempotently before
the routing service starts. It sets `service_limits.allow_hard_exclusions=true`, which is required
for Compass requests with `allow_highways=false`. A missing configuration fails early and means the
`valhalla-tiles` build step must be completed first.

The representative route stays within Milan so it is valid for the default Italy graph and for
regional graphs that include Milan. Expected invariants:

- `valhalla-tiles` exits zero and leaves data in the `valhalla_data` named volume;
- the identity command reports the retained PBF filename, size and content SHA-256;
- `valhalla`, `db` and `api` are healthy; `migrate` exits zero;
- Valhalla `/status` returns a JSON object;
- liveness returns `database=not_checked` and `routing=not_checked`;
- readiness returns HTTP 200 with `database=ready` and `routing=ready`;
- the route returns HTTP 200, `provider=valhalla`, positive `distance_meters` and
  `duration_seconds`, `geometry.format=polyline6`, a non-empty `encoded_polyline`, and at least one
  maneuver with an instruction and shape indexes.

Valhalla is intentionally not published on a host port. Its status is tested from inside the
container; only the loopback-bound Compass API is host-accessible.

### Recovery after an interrupted or partial tile build

If the builder reports `Couldn't find usable tiles`, synchronize the current Compose policy and
force exactly one reconstruction in the same volume:

```bash
VALHALLA_FORCE_REBUILD=True \
  docker compose --profile routing-build run --rm valhalla-tiles
```

The command-scoped override also forces replacement of a stale tile archive; it does not modify
`.env`. Do not set `VALHALLA_FORCE_REBUILD=True` permanently, because doing so would rebuild on every
container start. After the job exits zero, continue with `docker compose --profile routing up ...`
and the status/readiness/route checks above.

### Routing graph update and rollback

Do not overwrite the active graph when refreshing a `latest` extract. Choose a new physical volume
name, build into it, and smoke-test it with an isolated candidate container. The example date is a
label; use the actual build date or another unique release identifier:

```bash
VALHALLA_VOLUME_NAME=compass_valhalla_data_20260827 \
  docker compose --profile routing-build run --rm valhalla-tiles

VALHALLA_VOLUME_NAME=compass_valhalla_data_20260827 \
  docker compose --profile routing run -d --name compass-valhalla-candidate \
  --no-deps valhalla

docker inspect --format '{{.State.Health.Status}}' compass-valhalla-candidate
docker exec compass-valhalla-candidate \
  curl --fail --silent --show-error http://127.0.0.1:8002/status
docker exec compass-valhalla-candidate sh -c \
  'ls -lh /custom_files/*.pbf /custom_files/valhalla_tiles.tar && sha256sum /custom_files/*.pbf'
docker rm -f compass-valhalla-candidate
```

Wait and repeat the `docker inspect` command until it reports `healthy` before the two `docker exec`
checks. Then set `VALHALLA_VOLUME_NAME=compass_valhalla_data_20260827` in `.env` and activate it:

```bash
docker compose --profile routing up -d --force-recreate valhalla api
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
```

Repeat the representative route request above. If it fails, restore the previous
`VALHALLA_VOLUME_NAME` in `.env` and run the same `--force-recreate valhalla api` command. The old
volume remains intact. Removing retired graph volumes is intentionally not automated; list and
confirm an exact volume target before any later operator-controlled deletion.

### Routing diagnostics

If tile construction or startup fails, return:

```bash
docker compose --profile routing --profile routing-build ps -a
docker compose --profile routing logs --no-color --tail=300 valhalla api db migrate
docker volume inspect compass_valhalla_data
docker compose --profile routing exec -T valhalla sh -c \
  'find /custom_files -maxdepth 2 -type f -printf "%p %s bytes\n" | sort | head -100'
```

If Valhalla is healthy but the API is not ready or routing fails, also return:

```bash
docker compose --profile routing exec -T api python -c \
  "import urllib.request; print(urllib.request.urlopen('http://valhalla:8002/status', timeout=5).read().decode())"
docker compose --profile routing exec -T api python -c \
  "import json,urllib.request; data=json.dumps({'locations':[{'lat':45.4642,'lon':9.1900},{'lat':45.4857,'lon':9.2045}],'costing':'auto','shape_format':'polyline6'}).encode(); request=urllib.request.Request('http://valhalla:8002/route',data=data,headers={'Content-Type':'application/json'}); print(urllib.request.urlopen(request,timeout=60).read().decode())"
docker compose --profile routing logs --no-color --tail=300 api valhalla
```

Return the tile job's final output, `ps -a`, PBF identity line, both status/health bodies and the
complete Compass route response. If an invariant fails, include the matching diagnostics. Do not
return `.env`, credentials or the full PBF. A deployment that fails these invariants must be fixed
before proceeding into functionality that depends on routing.

## Destination search configuration

### Google Places (New) destination selector

First verify the country associated with the Google Cloud billing account. For the currently
implemented EEA/MapLibre separation configure:

```dotenv
DESTINATION_SEARCH_PROVIDERS=google_places_new
GOOGLE_PLACES_ENABLED=true
GOOGLE_PLACES_API_KEY=replace-with-a-server-restricted-key
GOOGLE_PLACES_CONTRACT_REGIME=eea
TOMTOM_SEARCH_ENABLED=false
DESTINATION_SEARCH_FALLBACK_ENABLED=false
GOOGLE_PLACES_TEXT_SEARCH_ENABLED=false
GEOCODING_PROVIDER=none
```

For ordinary-place discovery along an already calculated route, enable the dedicated Text Search
operation as well:

```dotenv
GOOGLE_PLACES_TEXT_SEARCH_ENABLED=true
DESTINATION_ALONG_ROUTE_ENABLED=true
DESTINATION_ALONG_ROUTE_PAGE_SIZE=10
DESTINATION_ALONG_ROUTE_MAX_GEOMETRY_POINTS=50000
DESTINATION_ALONG_ROUTE_MAX_ENCODED_CHARS=100000
```

This does not enable the legacy `/places/search` adapter: `GEOCODING_PROVIDER=none` keeps that path
disabled.

`GOOGLE_PLACES_CONTRACT_REGIME=unverified` is the safe default and prevents API startup when Google
destination search is enabled. A verified `non_eea` value is also blocked pending a separate terms
review; do not infer the regime from device position. Enable Places API (New), billing, quota and
server-compatible key restrictions, then rebuild/recreate only the API.

Autocomplete and Details share an ephemeral session token. The service rate limit, provider
concurrency, timeout, query threshold and bias are controlled by the documented
`DESTINATION_SEARCH_*` values in `.env.example`. Android debounce/minimum-character defaults can be
overridden at build time with Gradle properties of the same names.

Use the full operator procedure in
[`android-0.19.4-google-destination-search-acceptance.md`](phases/android-0.19.4-google-destination-search-acceptance.md).

Do not restore the obsolete `GEOCODING_PROVIDER=nominatim_google` corroboration configuration from
pre-0.19.4 deployments. It is deliberately incompatible with the active Google-only registry and
causes startup validation to fail. Keep the key only in the server `.env`; it is never an Android
build setting. Recreate the API after changing the configuration:

```bash
cd ~/docker/compass
docker compose build api
docker compose up -d --force-recreate api
```

The command reports only whether the key exists, never its value. The expected active values are
`providers=google_places_new`, `legacy_geocoder=none`, `google_enabled=true`,
`contract_regime=eea` and `tomtom_search=false`.

## Acceptance and diagnostic output

Validate liveness, readiness and an authenticated route before device testing. For the
route command above set `COMPASS_CHECK_USER` to the configured API username; curl prompts
for its password. The API image need not contain curl: use Python urllib for internal
connectivity inspection as shown above. Do not send credentials in returned artifacts.

If an invariant fails, return the failing request/response with secrets removed, job
exit status, `docker compose --profile routing ps -a` and bounded API/Valhalla/database
logs. For CNG scenarios check station counts, maximum detour, reserve reachability and
opening state at arrival, not just HTTP success. [API documentation](api.md) specifies
current contracts; old scenario-specific checks remain in [historical gates](phases/).
