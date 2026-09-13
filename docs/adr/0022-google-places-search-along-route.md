# ADR 0022: Google Places Search Along Route for ordinary stops

## Status

Accepted for the Google-only destination-search profile.

## Context

The ordinary “Aggiungi tappa” flow previously sent the bounding rectangle of a Valhalla route to
Google Autocomplete. A rectangle is not a route corridor and becomes especially misleading for
long, curved or diagonal trips. It also coupled a generic destination operation to a route-aware
interaction.

Google Places API (New) exposes Search Along Route through Text Search. It accepts a caller-owned
Google encoded polyline, so Compass can keep Valhalla authoritative and does not need Google Routes.
The public contract describes this as route-biased discovery, not a strict lateral-distance or
maximum-detour guarantee.

## Decision

- Only the explicit Android intent `ADD_STOP_ALONG_ROUTE` calls
  `POST /api/v1/places/search-along-route`. Origin and destination continue to use the existing
  destination endpoint: Autocomplete while typing and Text Search after an explicit submit.
- Android sends a versioned context for the currently selected route because the backend does not
  persist route documents. Each leg is encoded as Valhalla-compatible E6. The backend validates the
  ordered leg/waypoint relationship, concatenates duplicate joints, optionally limits the selected
  leg, trims only by an authoritative map-matched shape index, then converts E6 to E5.
- Google Text Search receives `searchAlongRouteParameters.polyline.encodedPolyline`. Compass sends
  neither `routingSummaries` nor invented distance/time parameters. Repeated identical Google
  Place IDs are removed.
- A deterministic classifier separates generic, specific and ambiguous waypoint text. Generic
  queries stay route-oriented. Specific queries use global Text Search; an ambiguous query expands
  globally only after an explicit submit.
- The Android route context carries direct-route duration (`T0`), current-itinerary duration and the
  existing cumulative added-time limit. Google supplies candidates; Valhalla evaluates the complete
  ordered itinerary. Generic results outside `T0 + limit` are omitted and eligible results are
  ordered by marginal added driving seconds. Specific/global results remain visible with an
  explicit within/over/unverified state and still require the existing route preview confirmation.
- If the first route-oriented response has fewer than the configured useful count, Compass can make
  at most two additional segment searches by default. These are candidate-recovery calls, never
  proof of eligibility; every returned candidate still goes through Valhalla. This also avoids
  treating a near-closed route as categorically unsearchable.
- Text Search coordinates and address are held only in the transient search session. The same
  resolved coordinate is used for candidate evaluation and the existing selection preview; the
  explicit confirmation remains the only commit point.
- Page tokens never reach Android. A short opaque Compass cursor is bound to owner, session, query,
  query revision and route fingerprint and is consumed only by an explicit “Carica altri” action.
- TomTom destination search/fallback remains disabled. TomTom traffic configuration is independent.

## Policy boundary

The deployed account uses the verified EEA configuration already established by ADR 0021. Search
results remain on the mapless selection screen. After confirmation, MapLibre receives coordinates,
the permitted provider reference and a neutral Compass label; Google name/address is not copied into
the map model. Results are transient and raw provider payloads, queries and polylines are not logged.

Search Along Route does not guarantee a hard geometric corridor. Compass does not perform a
point-in-polygon analysis on Google coordinates. Its eligibility rule is instead an independent
road-network calculation by Valhalla against the cumulative time budget.

The supplied implementation guide described an independent address geocoder as an existing Compass
prerequisite. That premise conflicts with ADR 0021 and the current verified EEA profile, which
deliberately requires `GEOCODING_PROVIDER=none` and permits the Google location field to cross the
map boundary while withholding Google name/address. The dormant Nominatim adapter is therefore not
silently re-enabled. Changing that decision requires a separate policy and quality review; this
increment does not claim an independent-geocoder live gate.

## Consequences and current limit

The planned-route flow is supported. Compass does not currently expose “Aggiungi tappa” while active
guidance is running; the backend context already accepts an authoritative remaining-shape index for
that future UI, but this phase does not add a navigation control or infer progress from nearest GPS.
Alternative-route selection is likewise not exposed by the current planner; the context always uses
the route actually shown by the planner.

General-place opening-at-ETA filters are not enabled: the minimal Places mask intentionally excludes
opening-hours fields and the existing arrival-hours evaluator is sourced for CNG stations. Adding
commercial-place hours would change fields, billing and policy surface and is not represented as
complete by this increment.

References: [Search Along Route](https://developers.google.com/maps/documentation/places/web-service/search-along-route),
[Text Search REST](https://developers.google.com/maps/documentation/places/web-service/reference/rest/v1/places/searchText),
[encoded polyline](https://developers.google.com/maps/documentation/utilities/polylinealgorithm),
[Places policies](https://developers.google.com/maps/documentation/places/web-service/policies), and
[EEA integration guidance](https://developers.google.com/maps/comms/eea/places).
