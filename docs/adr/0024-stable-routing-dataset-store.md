# ADR 0024: stable routing storage with immutable graph generations

Status: accepted for implementation; operator acceptance pending.

## Problem

The old scheduler created a Docker volume for every map build and rewrote
`VALHALLA_VOLUME_NAME` in `.env` on activation. Losing that deployment setting selected an
empty default volume. The operator observed missing config, a serving container rebuilding
Italy, and competing builder/server processes. The existing graph was recovered by selecting
the retained November 13 volume and validating a real route. Dataset selection must survive
routine container recreation without editing deployment configuration after each update.

## Decision

Use one explicitly named physical volume: **`compass_valhalla_datasets`**.
Graph generations live under `datasets/map-<UTC>-<random>/` inside it.
`compass-datasets.json` records active, previous and an optional activation transaction.
JSON replacement and parent-directory fsync make pointer updates atomic. Compose no longer
reads `VALHALLA_VOLUME_NAME`; no maintenance operation writes deployment configuration.

A one-time `map-import` copies the current graph from the old volume mounted read-only,
relocates its config paths, checks its archive and probes it in a candidate container.
Only a successful probe publishes the initial selection. Import never recreates the existing
router; the operator switches it with the normal deployment command and validates live
routing. The source volume remains intact. Failed imports do not publish partial copies.
Flat graphs and the last confirmed generation of a managed source store are supported.

The pinned Valhalla 3.8.3 image supports per-build directories through
[`path_extension`](https://github.com/valhalla/valhalla/blob/3.8.3/docker/scripts/helpers.sh).
Only the explicit builder invokes its entrypoint. Serving invokes `valhalla_service` directly
with the selected generation's absolute config path, on a read-only data mount. Missing or
changed archives fail closed; the server cannot download or build. The existing nominal
routing configuration policy is reused during preparation.

A build must finish successfully, pass archive/config checks, and answer a real route query
in a candidate container before selection. Activation retains the old state until the live
container passes health and route checks. Failure restores it; normal preparation after an
interruption restores the last confirmed selection. Confirmation permits pruning older managed
generations. Active, previous and graphs with live reader locks are protected. No historical
Docker volume or adopted root graph is deleted automatically.

## Consequences

- The volume name is stable and independent of dates and `.env`. Migration needs additional
  space for a copy, but no download or graph rebuild. Back up the store and its manifest.
- Nightly updates may briefly interrupt routing at restart. Builds run alongside the old graph.
- Disk must hold the active/previous generations plus the candidate and any adopted root files.
- `map-status` and `map-rollback` replace manual volume selection. Rollback validates the previous
  graph before activating it and re-associates enforcement records afterwards.
- The bootstrap command is the scheduler's `map` job. Plain `up` of `valhalla-tiles` fails for
  lack of an explicit generation rather than overwriting an active graph.
- Networking, API contracts, MIMIT/OSM records, Android, and refresh times are unchanged. The
  scheduler never writes the repository; its runtime state remains in its named volume.
- Local tests use temporary datasets and mocked Docker; full Italy builds, Docker health,
  volume permissions and native Valhalla reader-lock inheritance require operator validation.
