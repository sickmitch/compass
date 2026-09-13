# Android 0.28.2 — ordinary-stop added-time gate

## Scope

This increment changes only the eligibility limit for ordinary intermediate stops. The limit is
now added Valhalla driving time rather than added road distance. Its default is one third of the
direct route duration and the operator may replace it with a custom number of minutes. Ordinary
stops keep zero dwell; CNG detour limits and Search Along Route behavior are unchanged.

## Device checks

1. Calculate a route whose displayed driving duration makes one third easy to verify, then open
   `Aggiungi tappe`. `Tempo aggiuntivo massimo` is expressed in minutes and is prefilled with one
   third of the direct driving duration, rounded only for the editable one-decimal display.
2. Add a waypoint that produces an acceptable added driving time. The exact Valhalla preview opens
   even when its added road distance would have exceeded the former kilometre limit.
3. Enter a deliberately small custom minute limit and select a waypoint that exceeds it. Compass
   keeps the previous itinerary intact and reports both the actual added driving minutes and the
   selected limit.
4. Increase the custom minute limit and repeat the same selection. The preview can now be confirmed,
   the final destination and existing stop order remain intact, and no dwell time is added.
5. Open `Sosta CNG` or `Piano CNG` and verify their existing maximum-detour input remains expressed
   in minutes and behaves as before; this increment must not alter CNG planning.

Return pass/fail for all five checks and the complete live-gate output. A difference caused by live
traffic is acceptable only when both direct and waypoint routes visibly use the same current
traffic state; return screenshots and route summaries if the threshold result appears inconsistent.
