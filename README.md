# Compass

Compass is an open-source Android navigator with CNG/metano-aware route planning for
Italy. It combines FastAPI, PostgreSQL/PostGIS and Valhalla with a native Kotlin,
Jetpack Compose and MapLibre client.

## Current capabilities

- A → B routes, ordinary waypoints, highway preferences, walking and bicycle costing.
- Manual CNG stops and predictive refuelling chains with road-distance reachability,
  reserve margins and user-defined maximum detour time.
- MIMIT station/pricing data enriched by explicitly reconciled OSM metadata; source
  timestamps, price units and unknown opening hours remain explicit.
- Arrival-aware station availability and explainable ranking after spatial pruning.
- GPS guidance, voice, stop management, PiP, saved-route recovery and automatic APK updates.

Durations use nominal Valhalla graph costs plus planned dwell. Android shows a pastel
green ETA and journey progress ring. Loaded routes can support guidance during an outage;
new routes still require the backend. CNG inventory coverage and routing graph coverage
are distinct constraints.

## Documentation

| Guide | Purpose |
| --- | --- |
| [Architecture](docs/architecture.md) | Component boundaries and data flow |
| [Deployment](docs/deployment.md) | Docker setup, data imports, graph operations and diagnostics |
| [Scheduled updates](docs/scheduled-updates.md) | Maps at 03:00; CNG hourly 07:00–20:00, Europe/Rome |
| [API](docs/api.md) / [OpenAPI](docs/openapi.json) | Requests, responses, units and errors |
| [Android](docs/android.md) | Toolchain, configuration and device diagnostics |
| [Client updates](docs/client-updates.md) | APK publishing, signing and installation |
| [Data sources](docs/data-sources.md) | Provenance, attribution and licensing |
| [History](docs/history.md) / [ADRs](docs/adr/) | Condensed milestones and design decisions |
| [Current release gate](docs/phases/android-0.28.46-nominal-routing-gate.md) | Operator deployment/device acceptance |

## Local validation

With Python 3.12+ and development dependencies installed:

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/ruff check src tests scripts
.venv/bin/pytest -q -m 'not integration'
.venv/bin/python scripts/export-openapi.py --check
docker compose --profile routing config --quiet
```

Database integration tests require an isolated PostGIS database. Android JVM tests and
build instructions are in the Android guide. Local checks do not certify live providers,
deployment or physical-device navigation: the operator runs those gates separately.

## Layout and licensing

`src/compass` contains the API/domain/ETL code; `migrations` contains database migrations;
`android` contains the client; `tests` contains backend and repository contracts;
`scripts` contains local tooling and optional operator diagnostics; `dist` contains
versioned APKs and the update pointer. Docker Compose is the reference server deployment.

Code is GPL-3.0-only; see [LICENSE](LICENSE). Dataset, map and external-provider terms
remain separate and are documented in the data-source guide. Keep credentials out of Git.
