# Docker deployment and operations

Run deployment commands only on the operator's server, from the synchronized repository.
Local fixture tests do not establish live provider or device acceptance.

## Container names

Services declare explicit `compass-<service>` names, including `compass-api`, `compass-db`,
`compass-valhalla`, `compass-scheduled-updates`, `compass-migrate`, `compass-etl`,
`compass-valhalla-tiles` and `compass-valhalla-config`. Service names and network settings
are unchanged, so existing Compose commands and internal service URLs remain valid.
The scheduler discovers containers by service ID, without relying on the old generated names.

One-shot `docker compose run --rm` jobs still use temporary generated names, unless `--name`
is supplied ([Compose implementation](https://github.com/docker/compose/blob/v2.32.4/pkg/compose/run.go#L109)).
This allows concurrent CNG/enforcement jobs; the candidate map container already uses its own
explicit temporary name. `container_name` applies to containers created with `compose up`.

After syncing in `~/docker/compass`, with the existing `.env` and no active maintenance job:

```bash
git pull --ff-only
docker compose --profile routing config --quiet
docker compose --profile routing up -d
docker compose --profile routing ps -a
```

Compose recreates services whose names changed, causing a brief interruption. No image build,
volume deletion or new import is needed. Expect the persistent containers to use the declared
names and become healthy. If not, return `docker compose --profile routing ps -a` and
`docker compose logs --tail 80 api valhalla scheduled-updates`. Local validation covers
Compose configuration and scheduler unit tests; live recreation is an operator check.

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

The [container scheduler](scheduled-updates.md) runs map rebuilds at 03:00, followed by autovelox/Tutor
import and road association, and CNG imports every hour from 07:00 through 20:00, Europe/Rome. Build `scheduled-updates`
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

Valhalla always uses the named persistent volume **`compass_valhalla_datasets`**.
Dataset generations and active/previous selections live inside it. `VALHALLA_VOLUME_NAME`
is obsolete and ignored; no volume selection belongs in `.env` anymore.
Before recreating an existing installation, follow the
[one-time stable-store migration](scheduled-updates.md#upgrade-an-existing-graph-to-the-stable-store):
`map-import` copies and probes the old graph, preserving its source volume as a backup.
It does not download or rebuild the map and leaves the existing router running until redeploy.

For a first installation only, from the repository root with `.env` configured and the
pinned Valhalla image available:

```bash
docker compose --profile routing build api scheduled-updates
docker compose up -d db migrate
docker compose --profile routing run --rm --no-deps scheduled-updates map
docker compose --profile routing up -d
```

The explicit map job builds an isolated generation, probes a candidate, selects the graph,
and verifies the live router. It then imports/associates enforcement. If that last import
fails, the map can already be active: inspect `map-status` and logs before requesting another
lengthy build. Initial Italy downloads/builds may take substantial time and disk space.
For a regional graph, set `VALHALLA_TILE_URLS` and a matching `COMPASS_MAP_PROBE_LOCATIONS`
before bootstrap. `VALHALLA_THREADS` defaults to two.

### Refresh and rollback

The container scheduler starts the map cycle at 03:00 Europe/Rome. To explicitly refresh,
inspect the selection, or restore the previous graph:

```bash
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map-status
# Downloads/builds a new generation, then refreshes enforcement:
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map
# Only when intentionally rolling back; validates and switches the previous generation:
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map-rollback
```

Serving mounts data read-only and never builds or downloads. An empty or invalid store
fails explicitly. The old `VALHALLA_FORCE_REBUILD` override is obsolete: every explicit map
job starts in a fresh generation, and a builder without `--generation` refuses to start.
Do not run all profiles with `up` or delete volumes to repair a graph. Pruning retains
active/previous/in-use generations and does not remove adopted root files or old Docker volumes.

After activation, expect healthy Valhalla and a successful route. From the server repository:

```bash
docker compose --profile routing ps -a
docker compose exec -T valhalla curl --fail --silent --show-error http://127.0.0.1:8002/status
docker compose exec -T valhalla curl --fail --silent --show-error \
  -H 'Content-Type: application/json' http://127.0.0.1:8002/route \
  --data '{"locations":[{"lat":45.4642,"lon":9.1900},{"lat":45.4857,"lon":9.2045}],"costing":"auto"}' \
| jq '{status:.trip.status,distance:.trip.summary.length,seconds:.trip.summary.time}'
```

The example route is inside Milan; use the configured regional probe if Milan is outside
coverage. Expect `status: 0` and positive distance/time. A healthcheck alone does not certify
the route. Follow the [upgrade checks](scheduled-updates.md#upgrade-an-existing-graph-to-the-stable-store)
for native build/activation/recreation and rollback acceptance.

### Routing diagnostics

If tile construction or startup fails, return:

```bash
docker compose --profile routing --profile routing-build ps -a
docker compose --profile routing logs --no-color --tail=300 valhalla api db migrate
docker compose --profile routing run --rm --no-deps valhalla-config status
docker compose --profile routing exec -T valhalla sh -c \
  'find /custom_files -maxdepth 3 -type f -printf "%p %s bytes\n" | sort | head -100'
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
