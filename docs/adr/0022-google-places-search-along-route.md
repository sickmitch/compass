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
  Autocomplete/Details endpoints.
- Android sends a versioned context for the currently selected route because the backend does not
  persist route documents. Each leg is encoded as Valhalla-compatible E6. The backend validates the
  ordered leg/waypoint relationship, concatenates duplicate joints, optionally limits the selected
  leg, trims only by an authoritative map-matched shape index, then converts E6 to E5.
- Google Text Search receives `searchAlongRouteParameters.polyline.encodedPolyline`. Compass sends
  neither `routingSummaries`, distance filters nor ranking parameters.
- Provider order is preserved. Only repeated identical Google Place IDs are removed.
- Text Search coordinates and address are held only in the transient search session. Selection does
  not trigger Details or geocoding; it produces the existing map-safe navigation target. Valhalla
  calculates one insertion preview for the selected result, and the existing explicit confirmation
  commits it atomically.
- Page tokens never reach Android. A short opaque Compass cursor is bound to owner, session, query,
  query revision and route fingerprint and is consumed only by an explicit “Carica altri” action.
- TomTom destination search/fallback remains disabled. TomTom traffic configuration is independent.

## Policy boundary

The deployed account uses the verified EEA configuration already established by ADR 0021. Search
results remain on the mapless selection screen. After confirmation, MapLibre receives coordinates,
the permitted provider reference and a neutral Compass label; Google name/address is not copied into
the map model. Results are transient and raw provider payloads, queries and polylines are not logged.

Search Along Route does not guarantee a hard corridor. Post-filtering or reordering of Places
coordinates was intentionally not added. Exact detour is evaluated only after selection by the
existing Valhalla insertion preview.

## Consequences and current limit

The planned-route flow is supported. Compass does not currently expose “Aggiungi tappa” while active
guidance is running; the backend context already accepts an authoritative remaining-shape index for
that future UI, but this phase does not add a navigation control or infer progress from nearest GPS.
Alternative-route selection is likewise not exposed by the current planner; the context always uses
the route actually shown by the planner.

References: [Search Along Route](https://developers.google.com/maps/documentation/places/web-service/search-along-route),
[Text Search REST](https://developers.google.com/maps/documentation/places/web-service/reference/rest/v1/places/searchText),
[encoded polyline](https://developers.google.com/maps/documentation/utilities/polylinealgorithm),
[Places policies](https://developers.google.com/maps/documentation/places/web-service/policies), and
[EEA integration guidance](https://developers.google.com/maps/comms/eea/places).
