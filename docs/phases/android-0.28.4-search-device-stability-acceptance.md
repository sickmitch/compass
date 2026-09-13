# Android 0.28.4 — search device-stability live gate

Status: awaiting operator live validation.

## Corrections

- The committed ordinary-stop list is authoritative. Recalculating endpoints clears the list,
  compatibility flag, legacy coordinate fields and pending preview atomically.
- `Aggiungi tappe` remains available after a first ordinary stop; only incompatible CNG planning
  actions remain disabled while ordinary stops are present.
- Destination search uses the selected origin for Google origin/bias and therefore for the
  straight-line distance shown on each result. A recent GPS fix is the fallback before selection.
- The keyboard and field focus are dismissed when a result set becomes readable.
- `CompassApi` and `CompassPlanner` expose bounded, payload-free Android diagnostics. Backend 422
  validation diagnostics expose only field paths/types; route geometry, coordinates and queries
  are never logged.
- The header target for map/favourite selection follows the actual endpoint being edited.

## Device checks

1. Build a route, add and confirm one ordinary stop, then press `Aggiungi tappe` again. It must open
   the editor. Backing out without confirming a second stop must leave exactly the first stop; after
   changing endpoints and recalculating, no empty/phantom stop or disabled add-stop action remains.
2. Select San Giovanni Lupatoto as origin using each available method practical on the device. Search
   `farmacia` as destination. Local results must precede distant ones and every returned selectable
   result must show its straight-line distance from the selected origin.
3. Keep the keyboard open while typing a valid query. Once results appear, focus and keyboard must
   collapse so the result list occupies the viewport. Tapping the field must still reopen editing.
4. Repeat an along-route search. The API must return 200 and Android must remain usable across back,
   retry and a second session. Inspect `CompassApi`/`CompassPlanner`: logs may show revision, counts,
   mode/status and error code, but never query text, coordinates, Place IDs or polylines.

If a 422 recurs, return both filtered Android logs and API lines from the same attempt. The API now
prints whether rejection occurred in request-schema validation or route-context validation without
recording the request body.
