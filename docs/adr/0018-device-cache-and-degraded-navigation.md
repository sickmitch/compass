# ADR 0018: device cache and explicit degraded navigation

Status: accepted; live validation completed on 2026-09-03.

## Context

Mobile connectivity can disappear while guidance is active, the Compass API or geocoder can fail,
and traffic, MIMIT price or OSM enrichment can be unavailable or stale independently. A navigation
client must preserve a safe downloaded route without implying that cached dynamic information is
current. It must also avoid moving routing intelligence into Android.

## Decision

Android persists one complete navigation route document in private application storage. The
versioned document contains geometry, maneuvers, timing, planned CNG waypoints, range policy and an
optional gasoline fallback. It is updated after preview/start and every successful route
replacement. Explicit `Termina navigazione` or session abandonment clears it. After process death,
the client restores it as a visibly cached preview; the user explicitly restarts guidance.

The local navigation engine continues matching GPS and calculating route progress from that
downloaded document. A network/server reroute failure keeps the same route and enters an explicit
`REROUTING_UNAVAILABLE` state. A later successful Compass route response atomically returns the
session to `ONLINE` and marks the new route live.

Android stores at most ten successful, non-empty normalized search responses by exact normalized
query. Cache fallback occurs only for Compass network/server failures, never for invalid input or
malformed responses. Cached results carry their storage timestamp and are visibly labelled.

MapLibre's ambient resource cache is enabled with a configurable 100 MiB default. It is
opportunistic caching of resources already viewed, not a promise that an arbitrary geographic
region is fully downloadable offline. Compass continues to use server-side Valhalla for every new
route and reroute.

## Degraded-state contract

- downloaded route and local navigation available;
- rerouting unavailable after a failed Compass update;
- traffic delay unavailable when no defensible live delay exists;
- restored CNG waypoint data cached, with price/opening/live enrichment explicitly not current;
- MIMIT price timestamps/freshness and OSM opening validation remain visible in planning cards;
- successful reconnect removes cached/rerouting warnings only after a live route commits.

## Consequences

- Brief outages do not discard the active route or planned fuel stops.
- Process death can recover a route safely without silently restarting foreground tracking.
- Search remains useful for previously queried destinations while clearly degraded.
- New route calculation and rerouting remain unavailable without Compass; no local router is added.
- Ambient tiles improve already-visited map continuity but coverage depends on prior use and the map
  style's cacheability.
