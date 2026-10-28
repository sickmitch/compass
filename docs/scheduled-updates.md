# Container-managed scheduled updates

`scheduled-updates` is a persistent Compose service in the `routing` profile. Its image
contains Python, timezone data and the Docker/Compose CLI. It starts with the deployment;
no cron, systemd units or host Python installation are required.

| Job | Europe/Rome schedule | Work |
| --- | --- | --- |
| Map | Daily at 03:00 | Fresh PBF and isolated Valhalla graph build, configuration, health/routing probe, activation |
| CNG | Hourly, 07:00 through 20:00 inclusive | MIMIT station/prices, OSM enrichment, normalization |

The scheduler polls every 15 seconds and follows Italian daylight saving time. Jobs start
within the scheduled minute. Missed slots while the container is off are not replayed.
Attempt slots and last-success timestamps persist in `scheduled_update_state`, preventing
a restart within the same minute from launching the same slot again. Jobs are independent,
but a map build or import is never overlapped with another invocation of that same job.
A failed job is logged and the next scheduled slot retries. Health checks verify the
scheduler loop; last-success timestamps and logs report job outcomes separately.

MIMIT may publish only daily: hourly imports reuse unchanged snapshots and do not invent
new price observations. A failed MIMIT import stops the job. A failed OSM enrichment is
logged but normalization still uses the retained OSM snapshot.

## Map activation and retention

The schedule applies to the **routing graph**. OpenFreeMap controls the remotely hosted
visual basemap; the Android client uses its normal resource cache. No APK update is needed.

The old router serves while a fresh named volume is prepared. A candidate container must
pass health and a real route probe before activation. Switching briefly recreates Valhalla;
requests during that interval can fail. Failed activation attempts restore the previous
volume and restart it; check logs if rollback also fails. 03:00 is the build start, not a
guaranteed completion time. Provision disk/RAM for a candidate alongside the serving graph.

Only `VALHALLA_VOLUME_NAME` is changed atomically in `.env`; credentials, permissions and
ownership are preserved. Do not edit `.env` during activation or override the graph-volume
setting externally. Automatic volumes use `-scheduled-<UTC timestamp>`. The previous
working graph is retained; the older recorded automatic backup is removed after a successful
update. The original operator-created volume is never automatically deleted.

The default route probe is inside Milan. For a regional extract excluding Milan set
`COMPASS_MAP_PROBE_LOCATIONS` in `.env` to a JSON array of two points inside its coverage,
then recreate the scheduler. Its built-in default also works when this setting is omitted.

## Compose integration and privileges

The scheduler mounts the repository at `/workspace` and the Docker socket. It discovers
the host repository path through its own container mounts so sibling Compose jobs resolve
bind mounts correctly even when the project moves to a different host directory. Keep the
standard container hostname (Docker container ID); do not override it. The Compose file
and `.env` must remain available in the mounted deployment directory.

Access to the Docker socket gives this maintenance service control of the Docker daemon.
No `privileged` container is used; the API and client receive no Docker socket. Treat the
scheduler image and mounted repository as trusted administrative code. The supported setup
is the local Linux Docker daemon at `/var/run/docker.sock` with the checked-in Compose file;
remote daemons and custom override files require explicit adaptation. For a local rootless
daemon, set `COMPASS_DOCKER_SOCKET` to its actual host Unix socket in `.env`. The socket
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

Expect a healthy scheduler and the log `Scheduler ready: Europe/Rome; map 03:00; CNG hourly
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
Valhalla healthy; the CNG job must complete normalization. Verify a route and a CNG search
from the app. Return relevant bounded logs, timestamps and service status, without secrets.

For an immediate operator test, run each separately. These download data; the map test
can take hours and causes the activation restart:

```bash
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py cng
docker compose exec -T scheduled-updates python3 /opt/compass/scheduled-update.py map
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
The default is `/var/run/docker.sock`; rootless/context-based installations may use a
different location. Startup checks distinguish missing/non-socket paths, permission
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
