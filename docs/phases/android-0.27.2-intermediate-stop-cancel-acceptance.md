# Android 0.27.2 — ordinary-stop cancellation regression gate

## Scope

This patch fixes the phantom ordinary stop created when the user leaves **Aggiungi tappe** without
confirming a place. It also makes all four personalization actions use the same neutral Material
outlined-button styling.

## Device checks

1. Calculate a route and open **Personalizza viaggio**. **Aggiungi tappe**, **Sosta CNG** and
   **Piano CNG** must use the same text/icon color as **Cambia percorso** when enabled.
2. Open **Aggiungi tappe**, then immediately press Android Back. The route preview must show all four
   planning actions enabled and must not show the warning about an existing ordinary stop.
3. Open **Aggiungi tappe → Selezione dalla mappa**, press Back to the stop editor and Back again.
   The result must be identical to check 2.
4. Open **Aggiungi tappe → Ricerca**, press Back to the stop editor and Back again. The result must
   be identical to check 2.
5. Add and explicitly confirm a real ordinary stop with **Scegli**. Only now must Compass record the
   stop, display it in the itinerary and disable the mutually exclusive CNG planning actions.
6. Remove the confirmed stop. All planning actions must become available again and the final
   destination must remain unchanged.

Return pass/fail for all six checks and a screenshot if one fails.
