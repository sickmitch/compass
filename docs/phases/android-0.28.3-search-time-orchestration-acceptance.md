# Android 0.28.3 — search/time orchestration live gate

Status: superseded by Android 0.28.4 after device validation exposed intermittent invalid
along-route contexts, a stale ordinary-stop compatibility flag, insufficient Android diagnostics,
and destination searches not consistently biased from the selected origin.

## Scope

This increment keeps the existing ordinary-stop time input and makes it authoritative during
candidate discovery. The default is still one third of the direct Valhalla driving duration; a
custom minute value remains a cumulative allowance from that same baseline.

- All destination fields use one trailing-edge debounce of 600 ms.
- Typing origin/destination uses Google Autocomplete New. Cerca/IME search cancels that timer and
  uses Text Search New once. Results with a Google `distanceMeters`/location are ordered from the
  stable recent device position; unknown distances remain last.
- Only `ADD_STOP_ALONG_ROUTE` uses Search Along Route. Generic candidates are evaluated through full
  Valhalla waypoint routes and only candidates within the cumulative limit are listed, ordered by
  marginal added driving time.
- Specific queries use global Text Search. An ambiguous query expands globally only after an
  explicit Cerca action. Over-limit or unverified matches remain explicit preview choices.
- Evaluation is bounded to 10 candidates and 20 seconds. At most two route-segment recovery calls
  seek three useful candidates. The segment search is candidate discovery, not eligibility.
- TomTom destination calls and fallbacks remain disabled. TomTom traffic remains independent.

## Device checks

1. Set a valid recent GPS position near Verona. In both Partenza and Destinazione type `farmacia`.
   Confirm there is no request before 600 ms of inactivity; local results precede distant results.
   Press Cerca after editing and confirm the list updates once, without a delayed duplicate.
2. Enter an explicit distant query such as `Via Roma 10 Bologna`. Confirm it is still discoverable;
   local bias must not turn into a local restriction.
3. Calculate a direct route, open Aggiungi tappe, keep the default time allowance and search
   `pizzeria`. Every displayed generic result must include added driving time and be within the
   cumulative allowance. The list must be ascending by added time.
4. Add one eligible stop, return to Aggiungi tappe and repeat the generic search. Confirm the final
   destination and first stop remain unchanged and the allowance has not grown by another third.
5. Set a deliberately small custom limit. Search a specific address/business. A result may be marked
   over limit or unverified; tapping it must open the Valhalla preview and require Scegli before any
   itinerary mutation. Cancel and confirm the prior itinerary is intact.
6. Type an ambiguous name such as `Bar Centrale`, wait for the route search, then press Cerca.
   Confirm explicit submit can expose external matches and does not overwrite the route by itself.
7. Disable network or stop Valhalla during a candidate evaluation. Confirm the existing itinerary
   remains intact and Compass distinguishes failure from a valid empty list. Restore service and
   retry.
8. Check `/api/v1/places/search-along-route/metrics`: provider/evaluation counters increase and no
   query, Place ID or polyline is present as a metric label. Confirm destination-search TomTom calls
   remain zero.

## Known policy/architecture boundary

The guide supplied for this increment assumes an independent address geocoder. The accepted Compass
EEA destination architecture instead enforces `GEOCODING_PROVIDER=none` and uses the permitted
Google coordinate/Place ID while preventing Google name/address from reaching MapLibre. The dormant
Nominatim adapter was not silently re-enabled. General-place opening-at-ETA is also not claimed:
the minimal Places field masks omit commercial opening-hours fields.
