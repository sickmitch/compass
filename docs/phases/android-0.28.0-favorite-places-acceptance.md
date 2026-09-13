# Android 0.28.0 — favorite places live gate

## Scope

This increment activates the existing `Posizioni preferite` choices without changing Google
search, Valhalla routing or CNG planning. A favorite contains only the private name entered by the
driver and its coordinate. Google titles, addresses, identifiers, attribution and payloads are not
persisted.

## Device checks

1. From `Crea viaggio`, open destination `Posizioni preferite`. With a clean app store the empty
   state appears and selecting a favorite is impossible without a saved item.
2. Choose a destination from the map, return to `Posizioni preferite`, save it as `Casa`, then use
   it. The endpoint shows `Casa` and `Calcola percorso` creates the expected route.
3. Repeat for the departure endpoint and confirm the selected favorite becomes the origin rather
   than the destination.
4. Select a Google search result, return to the endpoint selector and save its coordinate with a
   deliberately different private name. Force-stop and reopen Compass: only that private name and
   coordinate appear in favorites; Google title/address text must not appear there.
5. Rename the favorite, force-stop/reopen again and confirm the new name persists. Delete it and
   confirm it does not return after another restart.
6. On an already calculated route open `Aggiungi tappe`, choose a saved favorite and verify that
   Compass first shows the normal insertion preview. Back must leave the old itinerary untouched;
   repeating and pressing `Scegli` must add exactly one zero-dwell ordinary stop while preserving
   the final destination.
7. Put the device offline and select an existing favorite as origin or destination. The choice must
   work locally. Opening and selecting it must not claim a provider error. Route calculation itself
   may of course require the configured Compass server.
8. Confirm duplicate private names differing only by case or surrounding spaces are rejected, and
   that an empty name cannot be saved.

Return pass/fail for all eight checks. If persistence fails, also return the bounded `Compass`
logcat output printed by the gate script and state whether the app was merely force-stopped or its
storage was cleared/uninstalled.
