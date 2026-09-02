# Navigation Stage 3 acceptance record

Status: accepted from physical-device evidence on 2026-09-02.

## Implemented scope

- Android TextToSpeech is owned by the foreground navigation service and uses Italian guidance.
- Maneuver announcements have early, prepare and immediate stages selected from both distance and
  estimated time to the maneuver. Spoken event IDs prevent repetitions caused by GPS oscillation.
- Fuel-stop approach/arrival and final arrival are first-class spoken events.
- Off-route confirmation combines route distance, GPS accuracy, heading conflict, backwards
  progress and three consecutive poor fixes. One noisy sample cannot reroute the trip.
- Every reroute and five-minute traffic refresh goes through the Compass repository/API. Android
  never contacts Valhalla directly.
- A route request failure leaves the downloaded route, local progress, maneuvers and voice guidance
  active. A success replaces the route in the existing navigation session.
- Remaining selected CNG stops and the multi-stop fuel-range plan are carried into recalculation.
- The MapLibre camera has smoothed bearing, speed/maneuver-aware zoom and pitch, follow/recenter and
  route-overview modes. The overview frames only the route from the snapped position to the
  destination; travelled and remaining route portions stay distinct.
- The debug replay is deterministic and, after the accepted gate exposed that its original pace
  was too fast to inspect comfortably, now advances one geometry point every 1.5 seconds.

## Default policies

- Traffic route refresh: every 300 seconds of active GPS navigation.
- Failed update retry backoff: 60 seconds.
- Off-route distance floor: 35 metres, increased to 1.5 times reported GPS accuracy.
- Heading conflict: at least 110 degrees while moving at least 4 m/s.
- Backwards-progress conflict: more than 60 metres.
- Off-route confirmation: 3 consecutive poor accepted fixes.
- GPS loss: 15 seconds without an accepted fix.
- Voice stage thresholds: early at 40 seconds/700 metres, prepare at 14 seconds/180 metres, and
  immediate at 4 seconds/35 metres.

## Device acceptance

Run `scripts/run-navigation-stage3-live.sh` from the repository root with the documented Android
toolchain and backend connection. The gate uses the normal debug replay for deterministic local
progress, then a debug-only simulated deviation that traverses the real filter, matcher,
off-route detector and Compass rerouting path.

Acceptance requires:

- audible Italian guidance and no repeated announcement for the same stage;
- a moving snapped puck, split travelled/remaining route and maneuver/progress panels;
- working `Panoramica` and `Ricentra` camera controls, with only the remaining route framed in
  overview mode;
- visible rerouting followed by a committed replacement without restarting navigation;
- the foreground notification and session surviving background/resume;
- no fatal Android exception;
- the route remaining usable if a manual network-loss check makes recalculation fail.

## Accepted live evidence

The operator ran `scripts/run-navigation-stage3-live.sh` against the loopback-tunnelled Compass API
on a physical Android device. The runner completed all 12 checks, including a successful Gradle
build covering 53 actionable tasks, a cold launch, confirmed off-route update start/commit,
downloaded-route fallback,
foreground-service persistence, hot Activity resume and the app-process fatal-exception check.

Returned screenshots showed follow mode with a snapped puck and voice/progress state, overview mode
framing the remaining route with the active camera control highlighted, the existing route remaining
usable after a failed manual refresh, and the Compass foreground notification while backgrounded.
The operator additionally confirmed audible non-repeating Italian guidance and removal of the
foreground notification after `Termina navigazione`. The operator subsequently reported that the
debug replay was too fast for comfortable visual inspection; its pacing was slowed before the
Stage 4 device gate.

## Deliberate boundary

This increment preserves already planned remaining CNG stops during rerouting. User-driven station
skip/replacement and an unexpectedly closed station workflow remain Stage 4; those actions are not
silently inferred by the Android client.
