# Container-managed scheduled updates

`scheduled-updates` is a persistent Compose service in the `routing` profile. Its image
contains Python, timezone data and the Docker/Compose CLI. It starts with the deployment;
no cron, systemd units or host Python installation are required.

| Job | Europe/Rome schedule | Work |
| --- | --- | --- |
| Map | Daily at 03:00 | Fresh PBF and isolated graph generation in the stable volume, health/routing probes, activation |
| Velox/Tutor | Daily, after the 03:00 map job finishes (including failure) | OSM inventory import and all pending Valhalla associations, in batches of 500 |
| CNG | Hourly, 07:00 through 20:00 inclusive | One `etl cng` container: MIMIT station/prices, OSM enrichment, normalization |
| Offline Android ZIPs | Sunday at 01:00 | One Italy 3.9.0 graph, five compatible dated packages in `./offline_assets` |

The scheduler polls every 15 seconds and follows Italian daylight saving time. Map/CNG/offline jobs
are queued within the scheduled minute; enforcement follows the map job. Missed slots while
the container is off are not replayed.
Attempt slots and last-success timestamps persist in `scheduled_update_state`, preventing
a restart within the same minute from launching the same slot again. CNG runs independently.
Map and offline builds share a blocking build lock: if the weekly export is still running at
03:00, the map job waits and then continues with enforcement. Three scheduler workers keep
CNG available while a build is active and another is waiting. Use scheduler commands for
manual builds too; starting builder services directly bypasses this coordination.
Map and enforcement share a maintenance lock: a manual scheduler
invocation cannot race a graph replacement or another enforcement refresh. The nightly map
job always attempts enforcement afterwards, even if the map build/activation fails. The
previous graph is used when rollback succeeds. Direct ETL commands bypass this scheduler
lock and must not be run concurrently with maintenance.
A failed job is logged and the next scheduled slot retries. Health checks verify the
scheduler loop; last-success timestamps and logs report job outcomes separately.

MIMIT may publish only daily: hourly imports reuse unchanged snapshots and do not invent
new price observations. A failed MIMIT import stops the job. A failed OSM enrichment is
logged but normalization still uses the retained OSM snapshot.

The three CNG stages run sequentially inside **one temporary container per hourly cycle**,
using `compass-etl cng`. Each existing importer retains its own DB session and transaction
boundary. The final JSON contains stage results and `osm_refresh_failed`; MIMIT or
normalization failure makes the job fail, while OSM failure is logged and normalization
still runs. Standalone `mimit`, `osm` and `normalize` commands remain available.
Docker event views still show creation, start, exit and removal for that single container;
these are lifecycle events, not four separate updates. Downloads have the same frequency;
this change reduces container/process startup overhead, not source refresh frequency.

## Weekly offline packages

The separate one-shot `offline-assets` service (profile `offline-build`) uses the pinned
Valhalla **3.9.0** image required by Android. It downloads the Italy PBF, builds once in
`offline_build_data`, checks routes using a temporary router, then reuses the regional
exporter to produce schema-2 packages with the **same graphFamily**. It never activates
this graph in the serving backend or changes its persistent volume. The builder uses
Valhalla's [path_extension support](https://github.com/valhalla/valhalla/blob/3.9.0/docker/scripts/helpers.sh)
to isolate each attempt. Its build subprocess has a 12-hour timeout.

Output filenames use the **publication date in Europe/Rome, DDMMYYYY**, for example:

```text
nord-ovest_22112026.zip
nord-est_22112026.zip
centro_22112026.zip
sud-isole_22112026.zip
italia_22112026.zip
```

`tools/offline-italia-regions.json` defines overlapping rectangular regional coverage;
these are practical download areas, not exact administrative boundaries. `italia` includes
all original tiles (`allTiles: true`); each regional ZIP includes the shared hierarchy.
Every package preserves OpenStreetMap/ODbL attribution. There are no visual map tiles in these ZIPs. The API mounts this directory read-only to
serve the authenticated catalog/download endpoints used by Android 0.28.64 subscriptions.

`latest.json` is replaced atomically **after all five ZIPs are ready**, and lists their
SHA-256 checksums, lengths, creation time and common graph family. `set-DDMMYYYY.json`
marks each complete set. Consumers should use this manifest, not glob files during
publication, because the five individual directory entries cannot appear atomically.
Build, probe or export failures leave the previous publication available. Repeating a
successful run on the same date reuses that immutable set; an interrupted publication can
be retried. Working directories are removed on exit and abandoned staging directories
are removed on the next run. Only the **latest complete set** is retained: five ZIPs and its set manifest. Previous
recognized sets are removed after successful publication, also when rerunning the same day.
Failed builds keep the prior complete set. Cleanup can resume after interruption.

To remove old published builds immediately without rebuilding, synchronize the repository
and run from `~/docker/compass` (existing `.env` and networks, no new secrets):

```bash
docker compose --profile offline-build run --rm --no-deps offline-assets --prune-only
ls -lh ./offline_assets/*_*.zip ./offline_assets/set-*.json
```

Expected: only the five files listed in `latest.json` and their one set manifest remain.
Unrelated archives are not deleted. If the latest set is missing or incomplete, cleanup
fails without removing the previous builds; return the command output and `latest.json`.
The tool is bind-mounted, so no API/scheduler rebuild or APK update is required. Clients
trying to resume an older removed publication must fetch the latest catalog again.

### Enable and validate on the server

From `~/docker/compass`, after synchronizing the repository, with the existing `.env`,
Docker socket and external networks already configured. No new secrets are required.
Compose creates `offline_assets` in the deployment project automatically, including with
rootless Docker. Relative paths resolve against the Compose project directory. The scheduler
already passes the discovered host deployment directory as `--project-directory`, so both
manual and automatic runs write to the same host path (for example
`/home/mike/docker/compass/offline_assets`). No `sudo` directory setup is needed:

```bash
docker compose --profile routing build scheduled-updates
docker compose --profile routing up -d --no-deps --force-recreate scheduled-updates
# First generation now, using the same lock and pipeline as the weekly schedule:
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py offline-assets
ls -lh ./offline_assets/*_*.zip
cat ./offline_assets/latest.json
docker compose exec -T scheduled-updates cat /state/offline-assets.last-success
```

The host output path is fixed relative to the project: `./offline_assets`. The worker
continues to access that bind mount as `/offline_assets` internally. An old
`COMPASS_OFFLINE_ASSETS_DIR` in `.env` is no longer read and can be removed. For deployments
that already run the weekly scheduler, synchronizing the updated Compose file is sufficient;
no scheduler rebuild/restart is needed for this path correction. Generated assets are excluded
from Git and Docker build contexts.

Configuration in `.env`: `COMPASS_OFFLINE_THREADS` defaults to 2;
the old `COMPASS_OFFLINE_KEEP_SETS` setting is ignored (retention is always one set). This directory is
managed output, not a general-purpose backup directory. Allow disk space for the full
Italy build, temporary files, all five new ZIPs and the previous set until the replacement is ready. Duration, peak memory
and actual space usage require measurement on the deployment host.

Expected: five dated ZIPs, matching `graphFamily` in their internal manifests, matching
SHA-256 values in `latest.json`, and a new `offline-assets.last-success`. A second run on
the same date reports `Offline set already published today; no rebuild`. Import the ZIPs
on Android 0.28.63+ and verify a route crossing Nord-Ovest/Nord-Est with just those two
regions installed; also verify `italia` alone. New weekly sets must not be mixed with
older graph families. No APK rebuild, API deployment or DB migration is required here.

If generation fails, return the failing command output and:

```bash
docker compose logs --since 24h --tail 100 scheduled-updates
docker compose --profile offline-build ps -a offline-assets
df -h ./offline_assets
docker compose exec -T scheduled-updates cat /state/offline-assets.last-success
```

The final timestamp can be absent before the first success. Manual `exec` job output is
in that terminal, while automatic job output appears in scheduler logs. Local tests use
small synthetic graphs and mocked native processes; full Italy building and Android
cross-region routing remain operator acceptance checks.

## Nightly autovelox and Tutor

The 03:00 cycle runs the map job first, then `etl enforcement`, then
`etl enforcement-associate --all --limit 500`. The start of the camera refresh depends on
map-build duration; it is not a second fixed-time job that could run against the old graph.
Manual scheduler `map` runs also perform this follow-up. `enforcement` can be invoked
separately without rebuilding the map.

The existing OSM importer preserves the prior inventory on download/parse failure. Even
when the import command fails, association is attempted on that retained inventory so a
newly activated graph does not unnecessarily leave all existing controls stale. Such a run
still reports failure and does **not** advance `enforcement.last-success`. Association
failure also leaves this timestamp untouched; a successful map timestamp remains independent.

`--all` processes missing/source-stale/graph-stale records until an empty batch, including
nodes with unknown direction. Ambiguous/unmatched results are retained as explicit states;
completion does not mean every camera becomes alert-eligible. Geometry omissions, graph
changes, source changes during a batch and routing/DB errors stop the job with a failure.
Committed batches survive and the next run can resume pending work. No road/direction
eligibility rules, Android code, DB schema or external commercial service is changed.

## Map storage, activation and retention

The physical volume is always **`compass_valhalla_datasets`**, explicitly named in Compose.
`VALHALLA_VOLUME_NAME` is no longer read; an old value in `.env` has no effect and can be
removed. No job rewrites `.env` or creates a new Docker volume for each update.
Existing installations must run the one-time `map-import` procedure below before recreating
Valhalla. It copies and probes the current graph without downloading or rebuilding it.
The old volume is retained as a backup, not used as permanent storage.

Inside this volume, `datasets/map-<UTC>-<random>/` contains isolated graph generations.
`compass-datasets.json` holds the active/previous selection and any unconfirmed activation.
Each generation includes config, tiles, supporting files and a preparation marker. The
serving container resolves one concrete generation at startup and mounts the data read-only.
It directly starts Valhalla: missing data produce an error, never an implicit graph build.

The old graph serves while a fresh generation is built. A candidate must pass health and
an actual route probe before the manifest is changed. Valhalla is then recreated and checked
again. Failure restores the previous selection and router. An interrupted activation is
reverted by the next normal `valhalla-config` preparation or managed map operation. A plain
restart of only Valhalla reads the current manifest; use normal Compose deployment/recovery
for transaction recovery. Initial bootstrap failure leaves no active graph.

After confirmation and candidate shutdown, older prepared managed generations are pruned.
Active and previous generations, plus files still leased by a running router, are retained.
Adopted root files, historical Docker volumes, and incomplete generations left by an abrupt
process/container kill are never deleted automatically. Ordinary failed builds are discarded
when possible. Disk must accommodate these retained data plus the next build; a successful
update is not a guarantee that every historical backup has been removed.

03:00 is the cycle start, not a guaranteed completion time. The route probe defaults to Milan;
for a regional extract excluding Milan configure `COMPASS_MAP_PROBE_LOCATIONS` in `.env` and
recreate the scheduler. No backend-controlled schedule applies to the remote OpenFreeMap
visual basemap. See [ADR 0024](adr/0024-stable-routing-dataset-store.md).

Inspect active/previous selections or request a validated rollback without editing `.env`:

```bash
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map-status
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map-rollback
```

The rollback command changes the active map and recomputes enforcement associations, so use
it only when rollback is intended. It fails clearly when no previous generation is available.

## Compose integration and privileges

The scheduler mounts the repository at `/workspace` and the Docker socket. It discovers
the host repository path through its own container mounts so sibling Compose jobs resolve
bind mounts correctly even when the project moves to a different host directory. Keep the
standard container hostname (Docker container ID); do not override it. The Compose file
and `.env` must remain available in the read-only mounted deployment directory.

Access to the Docker socket gives this maintenance service control of the Docker daemon.
No `privileged` container is used; the API and client receive no Docker socket. Treat the
scheduler image and mounted repository as trusted administrative code. The supported setup
is local Linux Docker, defaulting to the rootless UID 1000 socket at
`/run/user/1000/docker.sock`, mounted inside the container at `/var/run/docker.sock`.
Set `COMPASS_DOCKER_SOCKET` in `.env` for another UID or to `/var/run/docker.sock` for
rootful Docker. Remote daemons and custom override files require explicit adaptation. The socket
source must already exist: Compose must not silently create a directory in its place.

## Deploy — operator only

After synchronizing, from the deployment repository (for example `~/docker/compass`), with
`.env`, the database and an initial working Valhalla graph already present:

```bash
cd ~/docker/compass
docker compose --profile routing config --quiet
docker compose --profile routing build api scheduled-updates
docker compose --profile routing up -d
docker compose --profile routing ps
docker compose logs --tail 50 scheduled-updates
```

Expect a healthy scheduler and the log `Scheduler ready: Europe/Rome; map 03:00 then enforcement; CNG hourly
07:00–20:00`. Starting Compose outside the scheduled minute does not trigger a build/import.
On a new host bootstrap the graph using [deployment](deployment.md), then use the same
Compose commands. There is no additional host scheduling installation.

If you installed the superseded systemd timers, disable them **once on that existing host**
to prevent duplicate work. Do not interrupt an already running map activation:

```bash
sudo systemctl disable --now compass-map-update.timer compass-cng-update.timer
```

New hosts do not need this command. Old `.scheduled-updates` files in the repository are
not used by the container scheduler; automatic runtime state now lives in the named volume.

## Acceptance and diagnostics

After scheduled runs:

```bash
docker compose logs --since 24h --tail 200 scheduled-updates
docker compose exec -T scheduled-updates sh -c \
  'for f in /state/*.last-success /state/*.last-attempt-slot; do [ ! -f "$f" ] || { echo "$f"; cat "$f"; echo; }; done'
docker compose --profile routing ps
```

Last-success timestamps are UTC and written only on completion. The map job must leave
Valhalla healthy; the CNG job must complete normalization; enforcement must finish both
import and all association batches. `enforcement.last-success` records their completion. Verify a route and a CNG search
from the app. Return relevant bounded logs, timestamps and service status, without secrets.

For an immediate operator test, run each separately. These download data; the map test
can take hours and causes the activation restart:

```bash
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py cng
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py enforcement
```

Manual runs use the same per-job lock. Leave the scheduler running during builds. To stop
future scheduling, stop the service when no job is active:

```bash
docker compose stop scheduled-updates
```

A subsequent Compose `up` starts it again. Local tests mock Docker and providers; deployment,
image build, actual import and graph switch still require operator validation.

## Scheduler cannot connect to Docker

The container must mount the socket of the same daemon used to deploy this project.
The default host source is `/run/user/1000/docker.sock`; the container target remains
`/var/run/docker.sock`. Existing `.env` settings override this default, so update an old
`COMPASS_DOCKER_SOCKET=/var/run/docker.sock` entry when using rootless Docker. Startup checks
distinguish missing/non-socket paths, permission
failures and connection failures. The scheduler remains unhealthy and retries every
30 seconds without launching jobs until initialization succeeds.

On the host, from the deployment repository:

```bash
printf 'DOCKER_HOST=%s\n' "${DOCKER_HOST:-unset}"
docker context inspect --format '{{.Endpoints.docker.Host}}'
docker version --format '{{.Server.Version}}'
```

If DOCKER_HOST is set, it takes precedence over the context. For a `unix://` endpoint,
put its path (without `unix://`) in `.env` as `COMPASS_DOCKER_SOCKET`. Do not replace
`.env` or change socket permissions globally. An SSH/TCP endpoint is not a local bind
path; run this Compose deployment on the actual daemon host instead.

After syncing the fix and setting the correct socket, rebuild and recreate only the
scheduler (recreation also refreshes a stale bind after the daemon replaces its socket):

```bash
docker compose --profile routing build scheduled-updates
docker compose --profile routing up -d --no-deps --force-recreate scheduled-updates
docker compose logs --tail 60 scheduled-updates
```

If startup still fails, return the host commands above and this one-shot read-only
inspection; it works even when the service is restarting:

```bash
docker compose --profile routing run --rm --no-deps --entrypoint sh scheduled-updates -c   'id; ls -ld /var/run/docker.sock; test -S /var/run/docker.sock; docker version'
```

Do not enable `privileged` or `chmod 666` the socket to mask an access error. Check
user-namespace/rootless ownership policies if the diagnostic reports permission denied.

For rootless UID 1000, set `COMPASS_DOCKER_SOCKET=/run/user/1000/docker.sock` in the existing
`.env`, then run from `~/docker/compass` (no image rebuild is needed for this mount change):

```bash
test -S /run/user/1000/docker.sock
docker compose --profile routing up -d --no-deps --force-recreate scheduled-updates
docker compose exec -T scheduled-updates sh -c 'test -S /var/run/docker.sock && docker version --format "{{.Server.Version}}"'
docker compose logs --tail 40 scheduled-updates
```

Expected: both socket checks succeed, Docker prints a server version and the scheduler
logs `Scheduler ready`. If the host socket check fails, stop and inspect the active Docker
context with the commands above; do not create a placeholder file/directory. A `not a Unix
socket` error describes the mounted object, not a missing permission: do not mask it by
changing permissions. The long bind syntax keeps `create_host_path: false` so a missing
host socket fails clearly instead of creating a directory. Return the check output and
scheduler logs if startup still fails.

## Deploy the nightly enforcement increment

In `~/docker/compass` on the server, with the existing `.env`, initialized enforcement
schema, DB and Valhalla running; wait for any active maintenance job to finish:

```bash
git pull --ff-only
docker compose --profile routing build api scheduled-updates
docker compose --profile routing up -d --no-deps scheduled-updates
docker compose logs --tail 40 scheduled-updates
```

Building `api` updates the shared image used by the one-shot ETL jobs, including the new
`--all` option. Recreating API is not necessary; there are no API/schema changes or APK.
Expected startup log: `map 03:00 then enforcement`. To validate without another map build
(this downloads OSM inventory and updates associations), run:

```bash
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py enforcement
docker compose exec -T scheduled-updates cat /state/enforcement.last-success
docker compose exec -T api python3 < scripts/check-enforcement-associations.py
```

Expected: import result, per-batch progress ending with zero pending, exit code zero and a
new UTC success timestamp. Matching rates depend on source topology; ambiguous controls
must not be promoted to matched. If it fails, return command output, association diagnostic
and `docker compose logs --since 24h --tail 200 scheduled-updates`. Manual exec output is
not necessarily included in container logs. Confirm the next unattended nightly cycle
also advances the timestamp after the map job. Local mocked tests do not certify that run.

Local validation for this increment: 469 tests passed with six DB integration tests excluded
(`.venv/bin/pytest -q -m 'not integration'`). Ruff, OpenAPI synchronization and
`docker compose --profile routing --profile jobs config --quiet` passed. Tests cover nightly
ordering, map/import failures, independent success timestamps, shared locking, complete
batch draining and graph/source changes. Live download, database and nightly execution
remain operator checks.

## Upgrade an existing graph to the stable store

Run from `~/docker/compass`, with the existing `.env`, rootless Docker socket configuration
and working Valhalla container still present. Wait for active map/import maintenance to
finish before stopping the scheduler. The source volume must not be modified during copying.
Allow enough disk space for another copy of the current graph and supporting files (the
source PBF is not copied). Subsequent builds additionally need active, previous and candidate
storage. No API image rebuild, DB migration or APK is required.

```bash
git pull --ff-only
docker compose --profile routing stop scheduled-updates
docker compose --profile routing-build stop valhalla-tiles
docker compose --profile routing build scheduled-updates
docker compose --profile routing run --rm --no-deps scheduled-updates map-import
```

Run each command only after the previous one succeeds. `map-import` discovers the volume
mounted at `/custom_files` by the existing Valhalla container, checks it exists, mounts it
read-only, and copies the selected graph into `compass_valhalla_datasets`. A temporary
candidate must pass health and a route query before the selection is persisted. The existing
router is **not** restarted by import. A failed copy/probe leaves it untouched; retry after
resolving the reported failure. A repeated import into an initialized store does not replace
data. If the old container is already gone or has been recreated with the empty new store,
use the known source explicitly instead:

```bash
docker compose --profile routing run --rm --no-deps scheduled-updates \
  map-import --source-volume compass_valhalla_data-scheduled-20261113020009
```

After `Import verified`, switch the serving container to the permanent volume:

```bash
docker compose --profile routing up -d --wait --wait-timeout 180 valhalla
docker compose exec -T valhalla curl --fail --silent --show-error \
  -H 'Content-Type: application/json' http://127.0.0.1:8002/route \
  --data '{"locations":[{"lat":45.4642,"lon":9.1900},{"lat":45.4857,"lon":9.2045}],"costing":"auto","units":"kilometers"}' \
| jq '{status:.trip.status,km:.trip.summary.length,seconds:.trip.summary.time}'
docker compose --profile routing up -d scheduled-updates
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map-status
docker inspect compass-valhalla --format '{{range .Mounts}}{{if eq .Destination "/custom_files"}}{{.Name}}{{end}}{{end}}'
```

Expected: healthy Valhalla, route `status: 0` with positive distance/time, active
`map-import-...`, previous null, no pending transaction, and mount name
`compass_valhalla_datasets`. No PBF download/build should occur during import or serving.
Return the import result, map-status, mount name and route summary. The old volume is not
removed automatically; keep it until live acceptance. The obsolete `.env` volume variable
can be removed at any time because Compose ignores it.

If import or startup fails, stop here and return the failed command output plus:

```bash
docker compose logs --tail 80 valhalla-config valhalla scheduled-updates
docker compose --profile routing config --format json | jq -r '.volumes.valhalla_data.name'
docker compose --profile routing run --rm --no-deps scheduled-updates map-status
```

Do not delete volumes or start an in-place rebuild. If import fails, the old serving container
still owns its original volume. If the subsequent serving redeploy fails, that backup is
still intact; report the logs before attempting recovery.

For the next nightly cycle (or a deliberately requested scheduler `map` run), expect a new
active `map-...` generation, previous `map-import-...`, healthy routing, the **same** physical
volume and refreshed enforcement associations. After another successful cycle, previous is
the earlier managed generation. Check ordinary container recreation retains the active
selection; exercise `map-rollback` only as a planned operator test. These checks, including
actual native read-only serving and build duration/disk use, are not established by mocks.

## Validation of the stable-store increment

Repository-local validation: `pytest -q -m 'not integration'` passed 510 tests; six PostGIS
integration tests were deselected because no isolated test database was provisioned.
The 70 dataset/scheduler tests include real temporary tar archives and files, manifest
recovery, read leases across process execution, source-copy preservation, path relocation,
failed candidate/activation rollback and migration without restarting the old router.
Docker calls and health/routing responses are mocked. `ruff check src tests scripts tools`
and Compose configuration validation passed; network assignments and container names were
preserved. Server migration, native Valhalla serving and a full nightly build remain the
operator acceptance checks described above.


## Scheduler image and Compose configuration must match

An updated scheduler with an older `compose.yaml` cannot migrate datasets. The signature is
`configure-valhalla-runtime.py: error: unrecognized arguments: recover`, often accompanied
by warnings still selecting a dated volume. Failure occurs before copying the source graph.
Rebuilding the scheduler image alone does not update the Compose file mounted at `/workspace`.
Synchronize the service entrypoints, commands, script mounts and fixed volume name together.
Preserve deployment-specific networks: all services currently use the external
`compass-backend` network; API additionally uses external `proxy`. These networks must already
exist on the operator's daemon. No automatic network creation or replacement is performed.

Startup now checks the resolved dataset configuration and required local tool files before
running jobs. A mismatch reports the incompatible fields and starts no dataset operation.
The check does not print resolved environment variables or secrets.

Before retrying import, from the server repository check:

```bash
docker compose --profile routing config --format json | jq '{volume: .volumes.valhalla_data.name, entrypoint: .services["valhalla-config"].entrypoint, command: .services["valhalla-config"].command}'
```

Expected volume: `compass_valhalla_datasets`, entrypoint:
`["python3", "/usr/local/bin/valhalla-datasets.py"]`, command: `["prepare"]`.
Keep the existing router running, rebuild `scheduled-updates`, then repeat `map-import`
using the upgrade procedure above. Do not recreate Valhalla until import is verified.
Overpass 502 errors in previous CNG updates are independent of this configuration mismatch.


## Deploy the single-container hourly CNG job

From `~/docker/compass`, synchronize the repository with the existing `.env` and external
networks preserved. Wait for any running CNG cycle to complete. Rebuild both the shared
API/ETL image and scheduler; updating only the scheduler would call an unavailable `cng`
command in the old image.

```bash
docker compose --profile routing build api scheduled-updates
docker compose --profile routing up -d --no-deps --force-recreate scheduled-updates
```

No API container restart, DB migration, Valhalla rebuild or APK is required. At the next
hourly slot (07:00–20:00 Europe/Rome), expect one `compass-etl-run-...` container, logs for
MIMIT/OSM/normalization in sequence and one final JSON summary. A retained last-attempt
marker prevents a scheduler restart from repeating the same hourly slot.

```bash
docker compose logs --since 70m --tail 150 scheduled-updates
```

Return that output if the job fails or more than one CNG container is created per slot.
Check for another scheduler/manual ETL run before attributing unrelated jobs to this cycle.
Local tests cover stage ordering, MIMIT/normalization failure, OSM fallback, retained
standalone commands, one Compose run per cycle and the existing Rome/DST schedule.
Live imports and Docker event counts require operator validation.
