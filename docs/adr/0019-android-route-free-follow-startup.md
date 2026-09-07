# ADR 0019: Android route-free GPS-follow startup

- Status: accepted for implementation; physical-device gate pending
- Date: 2026-09-07

## Context

The original Android foundation loaded a deterministic Milan-to-Bologna route at startup. That was
useful for staged API and navigation validation, but it performs network/routing work before the
driver has chosen a destination and presents a trip the driver did not request.

Compass already has a mature route-guidance location pipeline. Reusing its route matcher without a
route would be incorrect: a route-free position must remain the raw GPS position and must not imply
that the driver is following an itinerary.

## Decision

Production Android startup enters an explicit `FOLLOW` planner stage with no route and no routing
operation. While the Activity is foregrounded, a route-free location listener renders the raw GPS
position on a dedicated MapLibre surface. It stops before the existing navigation foreground
service starts. The route-free camera retains the accepted follow anchor and gesture policy but
does not expose route overview.

`Crea viaggio` opens a shared endpoint selector. Each endpoint can be filled by current position,
the existing normalized place search or explicit coordinates. A visible disabled saved-positions
button reserves that future choice without inventing persistence behavior. Only validated endpoint
coordinates trigger the existing backend route request.

The endpoint selector has no planner header or introductory card. A successful route calculation
keeps it open and unlocks three explicit continuations: direct route, one stop, or extended
planning. Editing either endpoint invalidates those continuations until the route is recalculated.
Extended planning lists saved vehicle profiles and a custom-values option, but always requires the
driver to supply remaining range and maximum detour.

Server configuration is exceptional setup rather than a planner action. Compass opens it when an
authenticated profile is missing or when routing/search reports a connection or authentication
failure. A permanent manual entry point is deferred to settings.

## Consequences

- Fresh startup makes no default route request.
- Route-free follow is foreground-only; background tracking remains limited to active navigation.
- The active-navigation `Viaggio` and `Panoramica` controls do not change.
- Explicitly active cached navigation may still be recovered after process death.
- Milan/Bologna constants remain deterministic test fixtures, not production startup selections.
- Favourite positions need a later persistence and editing contract.
- Settings need the permanent server-profile entry point.

## References

- [Android architecture](../architecture.md#android-route-free-startup-boundary)
- [Android client development](../android.md)
- [Acceptance gate](../phases/android-0.19.3-route-free-follow-acceptance.md)
