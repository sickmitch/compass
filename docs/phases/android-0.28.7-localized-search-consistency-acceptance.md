# Android 0.28.7 — localized search consistency live gate

Status: awaiting operator live validation.

## Correction

The 600 ms settled-query path and the explicit `Cerca`/IME path now both use Google Text Search with
the same selected-origin context. For a single category query such as `farmacia`, the existing
backend adapter requests Google's distance ranking. Autocomplete remains supported by the backend
contract but is no longer used for the Android settled-query flow because its candidate set is
relevance-oriented and can omit nearer category results.

This does not change `ADD_STOP_ALONG_ROUTE`, which continues to use its dedicated route search.

## Device checks

1. Select San Giovanni Lupatoto as origin, open destination search, type `farmacia` and wait at least
   600 ms without pressing `Cerca`. The first results must be local and show distances from that
   selected origin.
2. Capture the visible first results, then press `Cerca`. The refreshed list must use the same local
   area and ordering policy; it must not jump from Verona-wide results to San Giovanni results.
3. Repeat with origin selected from GPS, map and a favourite. Each settled query must use that same
   origin context and must not require explicit submit to become localized.
4. Calculate a route and use `Aggiungi tappa` search. Confirm it still uses the along-route endpoint
   and retains the final destination.

Device/provider observation is required; a mocked test cannot establish Google's live result set.

