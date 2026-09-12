# Android 0.27.1 — follow, local search and personalization fixes

## Scope

This device gate verifies Android-only fixes. It does not change backend search contracts,
Search Along Route, traffic, routing or CNG planning.

## Checks

1. Cold-launch Compass with location permission granted. It must open the route-free map in follow
   mode. Once Android supplies a fix, the GPS pill disappears and the camera moves from the Italy
   overview to the device position.
2. Disable location providers for at least five seconds, then enable them again without restarting
   Compass. The periodic poll must reacquire the fix and restore follow automatically.
3. While physically near Verona, open a normal origin or destination search and enter a generic
   category such as `farmacia` or `tabaccheria`. Nearby Verona results must be favored; Compass must
   not inject Milan or another route endpoint as search context.
4. With the system dark theme active, inspect follow, route creation and personalization. The app
   background is OLED black and elevated Material surfaces have no green tint. MapLibre retains its
   independent navigation style.
5. Calculate a route. Personalization must show the map first, then the summary and two balanced
   action rows: **Cambia percorso / Aggiungi tappe** and **Sosta CNG / Piano CNG**. **Percorso
   diretto** remains compact and centered.
6. Open and close every action once. Existing enable/disable rules, route endpoints, CNG planning
   and the dedicated along-route stop search must remain unchanged.

Return pass/fail for every check and a screenshot of checks 1, 4 and 5 if one fails.
