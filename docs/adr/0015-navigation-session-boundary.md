# ADR 0015: Server route calculation and Android navigation-session boundary

Status: Accepted boundary; Stage 1 through Stage 4 live/device gates passed on 2026-09-02.

## Context

Compass already returns Valhalla route geometry and maneuvers, but Android treats them as a static
preview. Turn-by-turn navigation must continue locally between the relatively rare server events
that require a new route, traffic refresh or fuel-plan recalculation.

## Decision

The backend extends route responses with provider-independent `navigation` timing metadata and a
stable route ID. Existing `duration_seconds` remains Valhalla driving time. Compass adds 1,200
seconds per CNG stop only to `total_trip_duration_seconds`; this dwell never alters edge costs,
detour ranking or Valhalla ETA calculations.

Android owns a dedicated `navigation` package. Its `NavigationRoute` contains ordered legs,
globally indexed maneuvers, one joined geometry and first-class fuel stops. The application-scoped
`NavigationSession` publishes explicit state through `StateFlow` and is shared by the Compose UI
and foreground location service. Filtering, matching and progress remain pure Kotlin; Android and
MapLibre adapters only supply locations and render the resulting state.

## Consequences

- Valhalla remains authoritative for road paths, maneuver semantics and driving duration.
- The server is not contacted for ordinary position updates.
- API clients can continue reading `duration_seconds` unchanged.
- Verbal instructions, bearings and maneuver shape indexes now survive Android DTO mapping.
- Route IDs identify route geometry plus ordered CNG stop plan, not a persisted server session.
- Stage 2 implements local filtering, snapping, progress and basic camera follow.
- Stage 3 implements service-owned TextToSpeech, staged announcement deduplication, dynamic camera,
  confirmed off-route rerouting and five-minute traffic route refresh through Compass.
- Stage 4 uses the same service/session boundary for explicit range-safe replacement of the next CNG
  stop, with server-acknowledged MIMIT-ID exclusion.
- A failed network update retains the downloaded route; a successful response replaces it without
  restarting the Activity or navigation session.

## Stage 2 implementation note

The application-scoped session is now shared by the Compose UI and a foreground location service.
Filtering, route projection, progress and state transitions remain pure Kotlin. MapLibre receives
only the snapped coordinate during active navigation; raw GPS is retained in state for diagnostics
but is never rendered as the vehicle puck. Stage 2 was accepted from physical-device evidence on
2026-09-02.

## Stage 3 implementation note

`ManeuverController`, `RouteUpdateController` and `NavigationCameraController` are pure Kotlin and
fixture-testable. The foreground service owns their Android adapters and TextToSpeech lifecycle.
Off-route confirmation requires three poor fixes; default policy uses a 35 metre minimum distance,
1.5 times reported accuracy, heading mismatch above 110 degrees at 4 m/s or faster, and a 60 metre
backwards-progress guard. Announcement stages use time-to-maneuver plus distance thresholds and are
keyed by route, maneuver and stage to prevent repeats caused by GPS oscillation.

Every route update uses `RoutingRepository`: the Android client never calls Valhalla directly.
Traffic refresh is eligible after five minutes of active navigation, while failures use a bounded
one-minute retry backoff. The existing route continues locally during network loss. Stage 3
acceptance required physical-device evidence for speech, camera modes, automatic rerouting and
foreground-service continuity. That gate passed on 2026-09-02, including downloaded-route fallback,
background/hot-resume continuity and foreground-notification teardown.

## Stage 4 implementation note

The navigation route retains the predictive effective range, initial remaining range, safety
reserve, maximum detour and accumulated station exclusions. When the driver skips or reports the
next stop unavailable, the service recalculates the remaining range at the snapped route progress
and requests a fresh predictive plan from Compass. The rejected official station is filtered in the
PostGIS corridor query, so it is neither ranked nor sent to Valhalla matrices. Only a complete safe
plan replaces the active route. Routes without caller-provided range state remain unchanged, and
the UI explains that a predictive plan is required. Physical-device acceptance passed on
2026-09-02 with replacement, foreground-service continuity, manual-route guard and teardown
evidence.
