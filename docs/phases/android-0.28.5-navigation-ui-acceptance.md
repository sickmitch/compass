# Android 0.28.5 — navigation UI and maneuver cadence live gate

Status: awaiting operator live validation.

## Changes under test

- The follow/navigation puck is a transparent mint direction arrow with one dark central stroke;
  it has no circular fill or outer ring.
- Origin and destination choices in `Crea viaggio` use the same fixed height, equal column widths and
  the same selected-state container colour.
- Once an ordinary stop exists, `Organizza le tappe` collapses add methods and the maximum added-time
  control below one expandable `Aggiungi tappa` action. The time limit is normally an information
  badge and becomes an editable numeric field only when requested.
- Route preview does not expose the first maneuver. It shows an ordinary-stop recap when ordinary
  stops exist, retains the CNG-stop recap for CNG routes, and shows neither recap for direct routes.
- The maneuver card rounds distances to 50-metre increments through 50 m and to 10-metre increments
  below 50 m. Voice guidance announces a new maneuver once, then the 500 m, 250 m, 100 m and 10 m
  crossings once each.

## Device checks

1. Open Compass in route-free follow and then start navigation. In both surfaces verify that the puck
   is only the mint arrow plus dark centre stroke, rotates correctly and still scales with zoom.
2. Open `Crea viaggio` and exercise a selected method for both origin and destination. All eight
   choices must have equal height and equal half-row width; the selected destination must use the
   same dark-green selected treatment as the selected origin.
3. Add one ordinary stop and return to `Organizza le tappe`. Add methods and time limit must start
   collapsed. Tap `Aggiungi tappa`: the chevron and controls expand. Tap the time badge, edit the
   minute value, press the keyboard action or check icon, and verify that it returns to a badge.
4. Calculate the route. For an ordinary-stop route, `Riepilogo viaggio` must list the stop instead of
   `Prima indicazione`. A direct route must show neither section. Also check a CNG route still lists
   its refuelling stops.
5. Use a route with the next maneuver initially beyond 500 m and keep voice enabled. Verify one
   announcement when the active maneuver changes, then one at each crossing: 500 m, 250 m, 100 m and
   10 m. The card must show rounded values (for example about 79 m as 100 m and below 50 m in 10 m
   steps), without repeating a threshold when GPS distance oscillates.

Do not report this gate as passed from build output alone; items 1–5 require device observation.

