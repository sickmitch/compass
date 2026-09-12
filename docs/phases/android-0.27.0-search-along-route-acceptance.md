# Android 0.27.0 — Search Along Route acceptance

## Scope

This gate validates route-biased Google Places Text Search only for an ordinary intermediate stop.
It does not alter CNG candidate selection, generic destination search, traffic, MapLibre or follow.

## Server configuration

The API `.env` must retain the verified Google-only EEA settings and add:

```dotenv
DESTINATION_SEARCH_PROVIDERS=google_places_new
GOOGLE_PLACES_ENABLED=true
GOOGLE_PLACES_API_KEY=<server secret>
GOOGLE_PLACES_CONTRACT_REGIME=eea
GEOCODING_PROVIDER=none
TOMTOM_SEARCH_ENABLED=false
DESTINATION_SEARCH_FALLBACK_ENABLED=false
GOOGLE_PLACES_TEXT_SEARCH_ENABLED=true
DESTINATION_ALONG_ROUTE_ENABLED=true
DESTINATION_ALONG_ROUTE_PAGE_SIZE=10
```

Rebuild the API after synchronizing the repository. No new container or database migration exists.

## Manual cases

1. Search a new destination before calculating a route. Confirm the existing Autocomplete result
   list and selection still work.
2. Calculate a route, then edit the final destination. Confirm this remains a destination search and
   does not say “Cerca lungo il percorso”.
3. From the route preview choose **Aggiungi tappa → Ricerca**. Confirm the title is **Aggiungi tappa**
   and the field is **Cerca lungo il percorso**.
4. Search a common service such as `farmacia`. Confirm results carry Google attribution and are
   relevant to the route. The list is provider-biased, not a guaranteed geometric corridor.
5. If **Carica altri risultati** appears, press it once. Confirm results append without replacing the
   first page. Change the query and verify the old page cannot append later.
6. Select one result. Confirm Compass shows the existing insertion preview and recalculation feedback;
   the old route remains visible until this calculation succeeds.
7. Cancel the preview. Confirm destination and all pre-existing stops remain unchanged.
8. Repeat, confirm the preview with **Scegli**, and verify the final destination and existing stop
   order are preserved. The ordinary stop has no 20-minute CNG dwell.
9. Trigger a provider outage or use a temporarily invalid key. Confirm a distinct error appears and
   Compass neither performs a global search nor changes the current itinerary.
10. Check aggregate metrics before/after. `provider_calls` and `results_returned` may increase;
    `stale_responses_discarded` increases only when a deliberately superseded response returns.

The live gate passes only after the operator confirms the complete flow:

`calcolo percorso → ricerca destinazione invariata → Aggiungi tappa → ricerca lungo percorso → selezione → anteprima → conferma → destinazione finale preservata`.
