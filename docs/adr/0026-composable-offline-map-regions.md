# ADR 0026 — Compose offline regions from one graph build

Status: implemented in 0.28.63; phone cross-region acceptance pending.

## Decision

Export regional packages as subsets of one complete Valhalla 3.9.0 graph. Give every subset
an immutable `graphFamily` computed from the full graph's sorted tile path/size/SHA-256
inventory. Never assign this identity manually or reuse it after rebuilding the graph.

The schema-2 ZIP contains `manifest.json` followed by the selected regular `.gph` files.
The manifest retains engine, coverage, source date, probe and ODbL attribution, adds the
full-build identity and a per-tile inventory, and hashes that inventory as package identity.
The client checks paths, sizes, duplicates, SHA-256, compatible overlaps and a native probe
before publishing the catalog entry. Schema-1 imports and installed graphs remain supported.

The exporter copies tiles without rewriting their IDs or contents. Every region contains
the original level-0/1 hierarchy plus intersecting level-2 tiles and a one-tile halo.
The old tar offset index is not copied. The exporter cannot enlarge the source graph.

Android groups installed regions by full-build identity and selects a group covering every
waypoint. All regions in that group are exposed through one private `tile_dir`, using hard
links to the verified installed files. A single native actor calculates the whole request,
including restrictions and maneuvers across package boundaries. This is not a concatenation
of independently calculated routes. The merged view contains no extra copies of tile bytes.
It is staged atomically and rebuilt after import/removal; the existing mutex prevents deleting
a graph while a native call uses it. Global native tile caching remains disabled. The normal
route codec, fallback repository, navigation and nominal ETA behavior are reused.

## Why independent graphs cannot just be overlaid

Valhalla's [GraphId](https://github.com/valhalla/valhalla/blob/a919bb6b64d61d12f79ea5466d5cf599a39714ad/valhalla/baldr/graphid.h)
contains tile-local node/edge indexes. Matching tile filenames do not establish matching
indexes or boundary links across independently built extracts. An overlapping file from a
different build must never silently replace another file.
The pinned [GraphReader](https://github.com/valhalla/valhalla/blob/a919bb6b64d61d12f79ea5466d5cf599a39714ad/src/baldr/graphreader.cc)
supports a loose tile directory, allowing composition without changes to the JNI dependency.
The grid sizes come from [TileHierarchy](https://github.com/valhalla/valhalla/blob/a919bb6b64d61d12f79ea5466d5cf599a39714ad/src/baldr/tilehierarchy.cc).

## Consequences

- Package count has no fixed cap; each package retains the 8 GiB size limit. Manifest size
  is capped at 4 MiB for bounded input parsing. Shared hierarchy/halo bytes can appear in more
  than one installed package; the derived view uses hard links, not a third byte copy.
- Old independently built packages cannot gain boundary connectivity through an app update.
  Re-export from a common graph, building one wider source graph if needed. Different dated
  builds remain separate groups and may coexist; they never fill each other's missing tiles.
- Bounds only prune candidates. All necessary road tiles along the journey must be installed;
  missing corridors fail explicitly and are never replaced by fabricated straight connections.
- Region updates/removal invalidate the derived view; a missing area may make a previously
  routable journey unavailable. Source licensing/attribution and provenance remain intact.
- No server/API/database or new Android dependencies are required. Export is a repository-local
  provisioning tool using Python's standard library, not a new running service.
