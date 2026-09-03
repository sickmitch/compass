# ADR 0017: server-side place search and waypoint service time

Status: accepted after the Phase 12 live gate on 2026-09-03.

## Context

Android must search addresses, localities and named places without binding the mobile release to an
external geocoding vendor. Navigation also needs journey time that includes refuelling activity,
while Valhalla road cost must remain usable for physical detour comparison.

## Decision

Compass exposes normalized place results from a `PlaceSearchProvider` boundary. Nominatim is the
initial configured adapter and is replaceable or disableable. Decimal coordinates are parsed by
Compass itself and require no external request. Provider payloads and errors do not cross the API;
results expose only stable identity, display/address text, coordinates, kind, optional POI/category
metadata and provider provenance.

CNG refuelling is modeled as waypoint dwell/service time. The server configuration default is
1,200 seconds per planned stop. Driving duration remains Valhalla road time. Total trip duration is
driving plus accumulated dwell. Each later stop ETA, destination ETA and opening-hours evaluation
uses prior dwell. The dwell is not added to detour cost.

Traffic delay has an explicit state and nullable value. When Compass cannot separate a live overlay
delay from Valhalla's route duration it reports `unavailable` and no numeric delay instead of
inventing one.

## Consequences

- Android depends only on Compass contracts and can replace geocoders without an app release.
- Coordinates remain available when external geocoding is disabled.
- A deployment using public Nominatim must provide an identifying `HTTP_USER_AGENT` and respect the
  provider's operational policy; a dedicated provider can be configured later.
- Route and predictive contracts can reconcile driving, dwell and total duration exactly.
- Phase 13 may add cached recent results without changing the provider boundary.
