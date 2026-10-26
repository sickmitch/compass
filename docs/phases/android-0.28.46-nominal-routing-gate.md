# Android 0.28.46 — Nominal routing

## Contract

All automotive routes, waypoint itineraries and matrices use Valhalla graph speeds
(`constrained`, `freeflow`). Duration is the engine result; journey duration adds
only planned dwell. Departure instants anchor arrivals and opening-hours checks.
Walking and cycling retain dedicated costing. Highway exclusions remain enforced.

Android displays ETA in pastel green, without an approximation prefix. The progress
ring stays green. No external-speed indicators, warnings, DTO properties, health
requests, provider settings or periodic route polling remain. Manual recalculation,
deviation recovery, connectivity recovery and automatic stop departure remain.
Saved route documents tolerate retired metadata while preserving version and domain
validation. Old optional metadata does not become an ETA adjustment.

Dedicated providers, native writer, updater service, CLI, fixtures and activation
scripts have been removed. The API no longer mounts the graph volume. The runtime
configuration job detaches a retired archive from reused Valhalla configuration;
the old files are left untouched and are no longer read by the runtime.

## Deployment — operator only

Synchronize the repository, preserve existing credentials and compare the local
untracked .env with .env.example to remove settings no longer used. In
`~/docker/compass`, with the existing database and graph volume available:

```bash
docker compose --profile routing config --quiet
docker compose build api
docker compose --profile routing run --rm --no-deps valhalla-config
docker compose --profile routing up -d --no-deps --force-recreate --remove-orphans api valhalla
docker compose --profile routing ps
```

The final command recreates only API and routing, removing services no longer
declared in Compose. It does not delete volumes or rebuild graph tiles. No database
migration is needed. Install `dist/compass-0.28.46.apk` alongside the updated backend;
older clients expect retired response properties. Publish the APK and dist/last.txt
together for client auto-updates.

## Acceptance

- Calculate an A → B route, an ordinary-waypoint route and a CNG itinerary.
- Verify preview/summary contain only distance and nominal duration plus relevant
  stop information. The driving ETA badge and expanded arrival value are green.
- Navigate for more than five minutes on-route: position and remaining duration
  update locally without periodic full-route queries. Test a manual reroute and
  an off-route event; remaining stops and destination must survive.
- Check walking/cycling mode, highway refusal and restore a saved navigation.
- After restart, verify runtime services are healthy and removed workers are absent.

If anything differs, return screenshots and the relevant response, plus:

```bash
docker compose logs --since 10m --tail 200 api valhalla
```

Unit tests and fixture-based checks are local only; deployment and device acceptance
remain with the operator. Android's HTTP transport permission and OSM signal-light
identifiers are unrelated to dynamic route costs and remain intact.

## Local validation

- Python non-integration suite: 329 passed, 5 database integration tests deselected;
  after the final runtime-config cleanup, 8 focused nominal/configuration tests passed.
- Android: 282 unit tests passed; lintDebug and assembleDebug succeeded.
- APK signature verified; dist/last.txt points to compass-0.28.46.apk.
- Ruff, OpenAPI export consistency, Compose configuration, shell syntax and
  git diff --check passed. No deployment, external provider request or device test
  was performed by the agent.
