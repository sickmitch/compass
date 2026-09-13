# Android 0.28.1 — navigation controls and mute persistence gate

## Scope

This increment reserves red for actual errors, makes the trip and voice controls compact icon-only
actions, and keeps the operator's voice choice across route preview and live route replacement.
It does not change routing, traffic, CNG planning or MapLibre rendering.

## Device checks

1. Open a route preview. `Modifica`, ordinary/CNG edit actions and other normal controls use the
   standard neutral Material colors; they are not red. A real validation failure must still use the
   error color.
2. On the route-free follow map, the create-trip action is a circular icon-only control. It still
   opens `Crea viaggio`, and TalkBack identifies it as `Crea viaggio`.
3. Start navigation. The voice and trip-details controls are circular icon-only actions. The trip
   icon opens the existing details sheet without changing the route.
4. Mute voice guidance. The muted icon has the stronger filled primary treatment and no spoken
   maneuver is emitted afterward.
5. While still muted, trigger a route recalculation, preferably by a real deviation. When the new
   route replaces the old one, the icon remains muted and no `Percorso ricalcolato` or maneuver is
   spoken.
6. With navigation still active and muted, force-stop and reopen Compass. Restored guidance remains
   muted. Explicitly unmute and confirm voice announcements resume.
7. Verify degraded traffic/cache, closed-station and positive added-time indications use warning or
   neutral colors rather than red. A failed reroute and inline validation/provider errors remain red.

Return pass/fail for all seven checks. If mute changes without operator input, also return the
bounded `CompassNavigation` logcat output printed by the gate script and identify whether it
happened on reroute, process recreation or an ordinary recomposition.
