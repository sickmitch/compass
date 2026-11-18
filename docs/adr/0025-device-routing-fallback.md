# ADR 0025: on-device Valhalla fallback for active car navigation

Status: implemented for local verification; device acceptance pending.

## Context

The operator requested routing on Android when connectivity is unavailable. The cached
route from ADR 0018 preserves guidance but cannot calculate a new path after a deviation.
The existing navigation engine, CNG stop ordering and route-domain converters already
provide the presentation and progress tracking needed for a local route.

## Decision

Add a narrow `OfflineRouter` interface returning existing `RoutePreview` legs and a native
Valhalla adapter. Use the pinned Rallista `valhalla-mobile:0.6.6` Kotlin/JNI wrapper around
Valhalla 3.9.0. Keep HTTP routing primary and the production backend on 3.8.3. This partially
supersedes ADR 0018's restriction that every reroute requires the server; CNG candidate
ranking, source reconciliation and initial planning still belong to the backend.

The first increment covers rerouting an active car navigation only. An installed package
allows direct local calculation without connectivity, or fallback on network/server failure
and an eight-second online timeout. Authentication/validation errors and cancellation do
not trigger fallback. Preserve remaining waypoints, CNG dwell/range constraints and hard
highway exclusions. A failed local route leaves the previous navigation intact.

Import operator-built regional 3.9.0 graphs into private, non-backed-up storage. The strict
package manifest declares coverage/version and a SHA-256 digest. Verify the archive and run
a real native probe before atomically publishing it. Reject imported code/configuration.
Serialize imports and calculations off the UI thread; close native actors only after calls
finish. Reclaim abandoned imports on startup without deleting the active package.

## Consequences

- Reuses Valhalla semantics and existing navigation models instead of adding another engine.
- Adds native library APK size and regional graph storage; device latency, memory and battery
  cost require measurement. The JNI call is not interruptible; cancelled results are discarded.
- Packages need a separate pinned graph build. Production volumes, networks, APIs and scheduled
  jobs are unchanged. No backend redeploy or schema migration is necessary.
- A graph is not a visual basemap or an offline geocoder. Offline initial planning, stop editing,
  new CNG selection and automatic dataset downloads remain outside this increment.
- The package hash detects corruption, not authenticity; only operator-built packages are supported.

The exact build/import procedure and phone acceptance gate are in
[offline routing](../offline-routing.md). Do not expand this phase before its device results.
