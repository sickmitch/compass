# Android 0.19.3 off-route rerouting acceptance record

Status: repository implementation complete; connected-device and independent road gates pending.

## Scope

Android `0.19.3` (`versionCode=23`) improves the established automatic off-route flow and adds a
bounded result notice. It does not add on-device routing, silently invent an alternative without the
Compass API, or change backend route/CNG contracts.

## Detection and rerouting contract

- Only accepted GPS fixes enter off-route evaluation.
- Three consecutive poor fixes remain mandatory.
- General lateral deviation must exceed the greater of 20 metres and 1.5 times reported accuracy.
- At a moving speed of at least 4 m/s, a heading difference of at least 65 degrees is evidence once
  lateral distance exceeds the greater of 10 metres and reported accuracy.
- Implausible backwards progress remains independent evidence.
- Recovery to a valid fix clears suspicion before confirmation.
- Confirmed episodes are deduplicated and automatically request an alternative from the raw GPS
  coordinate, preserving or safely replanning remaining CNG stops.
- Suspected or confirmed off-route state never presents the old route's speed limit as current.

## Duration notice contract

- Only a successful `OFF_ROUTE` replacement creates a notice.
- The notice records remaining trip duration immediately before replacement, new remaining trip
  duration, signed difference, replacement route ID and commit time.
- The accessible bottom card shows `Prima`, `Ora` and `Differenza`.
- Positive differences use the error color; zero or savings use the primary color.
- The card slides up, contributes its measured height to camera padding and disappears ten seconds
  after commit. Activity recreation does not restart the ten-second interval.
- Scheduled traffic refreshes and manual debug refreshes do not create the notice.

## Repository-local validation

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Tests cover nearby wrong-turn confirmation, recovery from temporary drift, one-request-per-episode,
use of raw GPS as reroute origin, off-route-only notice creation, positive/negative/zero formatting
and the non-restarting ten-second window.

## Connectivity prerequisite

The alternative is calculated by Compass/Valhalla, not on the phone. `adb reverse` works only while
ADB remains connected. Configure the runtime `Server` profile with an HTTPS endpoint reachable from
mobile data or Wi-Fi plus the dedicated Basic Auth username/password. The password persists under
Android Keystore protection. Server exposure, DNS, TLS and ingress remain operator-controlled; no
endpoint or credential is committed here. HTTP requires explicit acknowledgement in the app and is
only a fallback for a trusted network.

## Connected-device gate

With the existing backend reachable, start the deterministic route replay, open developer tools and
trigger `Simula fuori percorso`. Confirm:

1. the state becomes suspected and then confirmed off-route;
2. exactly one automatic route update starts for the episode;
3. the replacement route is committed from the raw simulated coordinate;
4. the bottom card appears with `Prima`, `Ora` and `Differenza`;
5. the card remains legible without covering the puck and disappears after ten seconds;
6. no card appears for a manual or scheduled traffic refresh.

## Independent road gate

With an API endpoint reachable without ADB, deliberately leave the route at a safe test location.
Confirm that the old route is not followed indefinitely, an alternative is calculated
automatically, guidance switches to it, and the ten-second duration notice reports the signed impact.

Return the operator's visual/behavioral confirmation and the bounded `CompassNavigation` and
`CompassNavigationUi` log lines if the expected result is not obtained. Screenshots and UIAutomator
captures are not required.

Do not close `0.19.3` until both live gates pass or the operator explicitly waives one.
