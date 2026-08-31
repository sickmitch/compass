# ADR 0013: Android route endpoint editing

Status: Accepted on 2026-08-31 after operator-run live/device validation.

## Context

Phases 8–10 intentionally used the accepted Milan-to-Bologna route as the deterministic Android
entry point while backend contracts, CNG ranking and predictive refuelling were validated. That fixed
pair blocks the core product loop from being useful for arbitrary trips even though the backend
already accepts generic coordinates.

## Decision

Add an Android route-configuration stage that edits explicit origin and destination latitude and
longitude. Keep Milan and Bologna as startup defaults only. When coordinates are applied, reload the
base route from `POST /api/v1/routes` and reset any route-dependent CNG state.

All downstream Android calls use the active route coordinates returned through the planner state:

- manual ranked CNG candidates;
- selected-stop route recomputation;
- predictive CNG planning;
- predictive multi-stop route recomputation.

## Rationale

Raw coordinate editing is intentionally limited but useful for the next gate. It proves that Android
no longer hard-codes Milan/Bologna without adding geocoding, permissions, map picking, saved places
or a current-location model. Those features need their own UX and privacy decisions.

## Consequences

- The backend/schema remain unchanged.
- Android version advances to `0.4.0`.
- Unit tests assert that edited coordinates are propagated to preview, manual CNG, selected-stop,
  predictive and itinerary-route calls.
- The Phase 11 live gate must include a non-default route on device and reject invalid coordinates.

## Deliberate limits

- No address search or reverse geocoding.
- No location permission or live current-position source.
- No saved places or route history.
- No navigation session, rerouting or background tracking.

## References

- [Architecture overview](../architecture.md#phase-11-android-route-endpoint-editing)
- [Phase 11 acceptance criteria](../phases/phase-11-acceptance.md)
