# Android 0.28.48 — Direct navigation, arrival and parking

Light-mode maneuver callouts now use the same dark green as the route and ETA.
The light primary-container accent also uses dark green with white foreground;
neutral pale-green surfaces and the dark theme are preserved.

Direct routes request navigation immediately through the existing permission and highway
confirmation flow. Itineraries with stops retain their summary. Back cancels pending
automatic start. Final destination arrival stops foreground guidance and returns the
activity to follow mode; arrival at an intermediate/fuel stop does not finish the journey.
Arrival clears saved active-navigation data to avoid resurrecting a completed journey.

The expanded navigation menu offers Save parking. Parking is a single reserved-ID entry
in the existing private favorite-place document, with no schema migration. It is available
for selection/deletion alongside ordinary favorites and has the fixed name Parcheggio.
Replacement requires confirmation; cancel leaves the old location intact. The captured
GPS fix must be at most 60 seconds old with reported accuracy within 50 metres. Success
explains that the parking location is available in favorites; persistence failure does
not report success. Parking does not consume one of the 50 ordinary-favorite slots.

## Device acceptance — one combined check

Install dist/compass-0.28.48.apk through the existing update flow; no backend change.

1. Light mode: check maneuver badge/border and primary controls are dark green and
   readable. Toggle dark mode and confirm its palette remains unchanged.
2. Choose Percorso diretto: navigation starts without pressing a second start button.
   Repeat with permissions needing consent and with highway confirmation/refusal.
   A trip with a stop must still show the summary.
3. Save parking from the chevron menu, acknowledge the favorites notice and verify its
   location in favorites. Save again: cancel preserves the old position; confirm replaces
   it, leaving one parking entry. Restart the app and select it as destination. Delete it.
4. Reach the final destination: guidance/notification ends and normal follow returns.
   Restart the app: the completed navigation must not be restored. Fuel/intermediate
   stop arrivals must still retain the ongoing itinerary.

Return the failing step, screenshot, Android/device version and relevant logs if needed:

```bash
adb logcat -d -t 300
```

Local unit tests/build/lint do not certify live GPS arrival detection, Android permission
dialogs or layout on a physical device. No live acceptance is claimed here.
