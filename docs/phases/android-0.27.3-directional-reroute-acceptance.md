# Android 0.27.3 — directional off-route recalculation gate

## Scope

When a moving driver leaves the active route, Compass now sends the observed GPS bearing with the
new origin to Valhalla. The constraint applies only to confirmed `OFF_ROUTE` recalculations at
speeds of at least 4 m/s. Direct, ordinary-stop and CNG-aware plans all preserve the same behavior.

## Preparation

Rebuild and redeploy the API before installing the APK because the route contracts gained two
optional fields. Keep the existing database, Valhalla tiles and Android profiles.

## Device checks

1. Create a direct route with a safe, legal opportunity to continue past or turn away from the
   planned maneuver. Start navigation and drive at more than 15 km/h.
2. Intentionally leave the route. During **Ricalcolo rotta**, the replacement must begin in the
   vehicle's actual direction of travel. It must not ask for an immediate U-turn or draw the first
   segment behind the puck merely because the opposite carriageway is geometrically closer.
3. Repeat on a divided road or beside a parallel road if safely possible. Confirm that the first
   maneuver remains compatible with the carriageway and direction actually travelled.
4. Repeat with an ordinary intermediate stop. The stop and final destination must remain present,
   in order, after the directional reroute.
5. Repeat with a CNG-aware route. Remaining mandatory fuel stops, range/reserve constraints and the
   final destination must remain present after the directional reroute.
6. Trigger a traffic refresh or connectivity recovery without leaving the route. It must still
   refresh normally and must not become over-constrained by an old heading.
7. If a deviation is confirmed while nearly stationary, routing must still complete. Compass
   deliberately omits the heading constraint below 4 m/s because GPS bearing is unstable there.

Return pass/fail for all seven checks. If check 2 or 3 fails, return a screenshot of the first new
route segment and the bounded diagnostics printed by the gate script.
