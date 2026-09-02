# Navigation Stage 2 acceptance record

Status: accepted on 2026-09-02.

## Scope

- Application-scoped `NavigationSession` survives Activity recreation and temporary UI removal.
- A foreground service owns Android `LocationManager` updates while navigation is active.
- Location permission is requested only when the user starts GPS navigation.
- Filtering rejects fixes worse than 75 m, non-monotonic timestamps and implausible jumps above
  70 m/s after accounting for accuracy.
- `RouteMatcher` searches 8 segments behind and 60 ahead of the previous match, scores distance and
  heading, and penalizes backwards movement beyond 25 m.
- Off-route status requires three consecutive fixes farther than `max(35 m, 1.5 × accuracy)`.
- No fix for 15 seconds enters the explicit `GPS_LOST` state; a valid fix recovers locally.
- Distance, driving time, dwell-aware trip time, ETA, maneuver and next CNG stop progress are
  calculated locally from the downloaded route.
- MapLibre renders travelled and remaining route separately and shows only the snapped puck.
- Follow camera uses smoothed bearing, speed-dependent zoom and 55-degree pitch.
- No server request is made for ordinary location updates; confirmed off-route is reported but not
  rerouted until the next navigation gate.

## Automated fixtures

`basic-forward-replay.csv` reproduces a complete session without GPS or server access. Unit tests
also cover noisy fixes, implausible jumps, backwards movement, temporary drift, confirmed off-route,
GPS loss/recovery, route completion and CNG approach/arrival.

The debug APK also exposes `Riproduci percorso demo`. It feeds sampled points from the downloaded
geometry through the real foreground service and the same pure navigation engine. Release builds do
not expose this control.

## Device gate

Run `scripts/run-navigation-stage2-live.sh`. After the script pauses, start the debug replay and
return to the terminal. The runner checks the foreground service and notification, backgrounds and
resumes the Activity, then checks for fatal exceptions. Stage 2 must not contact the backend or
render raw GPS coordinates as the puck.

## Live result

The operator returned the complete successful runner output and the three requested device
screenshots on 2026-09-02. The evidence confirms:

- debug APK installation and cold launch;
- the navigation-ready UI with separate GPS and deterministic replay actions;
- local replay with maneuver, remaining distance/time, ETA and travelled/remaining route rendering;
- an active foreground location service and persistent Android notification;
- background/resume without restarting the navigation session or producing a fatal exception.

The backend reported traffic as unavailable during this gate. This does not invalidate Stage 2:
the route was downloaded successfully and live progress continued locally, which is the intended
network-independent navigation boundary.
