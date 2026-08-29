# ADR 0011: Android manual CNG stop-selection workflow

- Status: accepted
- Date: 2026-08-29

## Context

Phase 8 proved the native client boundary with a real base-route preview. The stable Phase 7 API
already exposes arrival-aware ranked CNG candidates and selected-stop route recomputation. Phase 9
must connect those contracts into the core manual Add Stop interaction without duplicating ranking,
opening-hours or routing decisions on the device and without starting predictive refuelling or an
active navigation session.

## Decision

The Phase 9 Android client retains the deterministic Milan-to-Bologna pair and adds four explicit
planner stages owned by one lifecycle-preserved ViewModel:

1. base-route preview;
2. CNG search configuration;
3. ranked CNG candidates;
4. route recomputed through the selected official MIMIT station ID.

The search form exposes the user's maximum detour and the effective CNG range used by the backend
corridor policy. Departure time is captured from the device clock with its UTC offset at submission.
The app requests `include_closed=false`; missing or invalid opening hours remain visibly `unknown`.

Strict data DTOs model the complete `ranked-candidates` and `routes/with-cng-stop` responses.
Repository mapping converts timestamps, opening/price states, scores, polyline6 and two ordered route
legs into Android-independent domain models. The client rejects non-contiguous ranks, inconsistent
candidate counts, unknown enums, a selected station ID that differs from the request, wrong leg
order, broken stop boundaries and leg totals that do not reconcile within Valhalla's serialized
source precision. The trip and two leg summaries are rounded independently, so the client permits
at most a two-metre/two-second aggregate delta and rejects anything larger.

The candidate screen renders the base route plus candidate markers and a ranked list. Each card
shows road distance from the previous waypoint, detour, station ETA, opening state/hours, optional
phone, MIMIT CNG unit price with observation/freshness and explainable score components. Selection
always calls the backend with `mimit_station_id`; the returned map has an explicit CNG waypoint and
the two maneuver sections remain separate.

The fixed endpoint pair is deliberate Phase 9 test scope, not a mobile navigation design decision.

## Consequences

- Ranking, eligibility and opening semantics remain backend-owned and consistent with Phase 5–7.
- Rotation/background restoration retains the current workflow because all planner state lives in
  the ViewModel rather than composable-local state.
- A missing price does not hide a candidate; a missing opening schedule remains unknown.
- The selected route is not synthesized from matrix estimates: it is recomputed by Valhalla through
  the official station waypoint.
- Destination editing, device location, predictive fuel reachability, traffic ingestion, navigation
  sessions, voice guidance and rerouting remain later phases.

## References

- [ADR 0008: arrival-time availability and ranking](0008-arrival-time-availability-and-ranking.md)
- [ADR 0009: stable public API and selected-stop routing](0009-stable-public-api-and-freshness.md)
- [ADR 0010: Android client foundation](0010-android-client-foundation.md)
- [Phase 9 acceptance criteria](../phases/phase-9-acceptance.md)
