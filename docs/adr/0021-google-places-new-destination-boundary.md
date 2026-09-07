# ADR 0021: Google Places New as the destination-search boundary

Status: accepted; live API and physical-device gate passed on 2026-09-07, with the documented
active-plan destination-edit case explicitly waived because the current UI does not expose it.

## Context

ADR 0020 used Google Text Search only to corroborate Nominatim records. Compass now needs activity
and address selection from Google Autocomplete (New), followed by resolution of the exact selected
Place ID. The Android map remains MapLibre and routing remains Valhalla.

The applicable Google Maps Platform contract depends on the Cloud billing account, not the driver's
GPS position. Under the EEA Places terms, latitude, longitude and Place ID are excluded from the
Places-content restriction for use with a map, while Google business names and addresses are not.
“With a map” includes content shown next to, visually associated with, or linked to a map.

## Decision

- `google_places_new` is the only registered destination-search provider during this test phase.
  Legacy adapters remain packaged but `GEOCODING_PROVIDER=none`, `TOMTOM_SEARCH_ENABLED=false` and
  `DESTINATION_SEARCH_FALLBACK_ENABLED=false` keep them outside the active flow. TomTom traffic has
  independent settings and remains available.
- Typing calls Places Autocomplete (New). Selection calls Place Details (New) once for the selected
  Place ID and reuses the same UUID-v4 Google session token.
- Suggestions, names, addresses and provider payloads stay in transient search/configuration UI
  without a map. They are neither persisted nor put in the old Android place-search cache.
- The map/navigation model contains only WGS84 coordinates, the permitted Place ID and the fixed
  label `Destinazione selezionata`. Valhalla consumes the returned coordinates directly; Compass
  performs no text geocoding or reverse geocoding after selection.
- Live enablement is fail-closed. `GOOGLE_PLACES_CONTRACT_REGIME=eea` must be set only after the
  operator verifies that the Cloud billing account is subject to the EEA terms. `unverified` and
  `non_eea` cannot enable this MapLibre integration without another terms review.
- Ranking preserves Google order. Exact duplicate `(provider, Place ID)` values retain the first
  occurrence. Different Place IDs remain distinct even when their names match.

## Consequences

- Google text is visible only on mapless screens and carries Google attribution.
- Selection text and map-safe navigation targets are different API and Android types, reducing the
  chance of accidental content transfer.
- Search state is process-memory-only and expires. Aggregate metrics never use queries, coordinates
  or Place IDs as labels.
- Text Search (New), cross-provider ranking and cross-provider deduplication remain disabled. Their
  deterministic design is documented for a later explicitly requested TomTom reactivation.
- A failed or rate-limited Google request produces a retryable error; it never activates another
  destination provider.

## Future multiprovider increment

Each provider will first deduplicate exact provider IDs. Cross-provider grouping will require strong
name plus address/locality evidence, using geographic distance only when both candidates genuinely
have coordinates. A 50 m threshold is only a value to validate, never proof by itself. Ambiguous
records stay separate; confirmed duplicates prefer the complete Google record while preserving
source references. Provider-native scores/ranks will not be compared as probabilities.

## References

- EEA Maps service terms: <https://cloud.google.com/terms/maps-platform/eea/maps-service-terms>
- EEA Places integration guidance: <https://developers.google.com/maps/comms/eea/places>
- EEA permitted Places uses: <https://cloud.google.com/terms/maps-platform/eea-places-api-permitted-uses>
- Autocomplete (New): <https://developers.google.com/maps/documentation/places/web-service/place-autocomplete>
- Place Details (New): <https://developers.google.com/maps/documentation/places/web-service/place-details>
- Session tokens: <https://developers.google.com/maps/documentation/places/web-service/using-session-tokens>
