# Android 0.28.39 — journey progress and puck motion

## Changes

The ETA ring is now pastel green. Its progress belongs to the navigation engine and is
checkpointed with the session, rather than being a UI-only maximum keyed by route ID.
Traffic refreshes replace the residual route: its local progress starts at zero, while
journey progress continues from the fraction already completed. Repeated refreshes and
on-route connectivity recovery preserve that fraction. Off-route, manual and fuel-stop
replacement recalculations start a new progress baseline. Reliable fixes advance the ring
monotonically; unreliable fixes do not advance it. Existing checkpoints default to their
saved route progress when the new fields are absent.

Device navigation requests GPS updates every 500 ms instead of 1,000 ms (the actual
delivery rate remains hardware/OS dependent). Puck and following camera continue using
frame-driven interpolation, now spanning the full observed update interval, up to two
seconds. Repeated delivery of the same fix by UI updates no longer cancels animation or
snaps to the target. New fixes interpolate from the currently displayed position. Large
discontinuities still snap; stationary jitter is still filtered. No GPS positions are
extrapolated beyond received fixes. More frequent GPS requests may increase battery use.

## Local verification

From `android/`, with JDK 17 and Android SDK configured:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

Regression coverage includes repeated traffic-refresh progress preservation, subsequent
advancement, off-route reset, checkpoint serialization, duplicate timestamp handling,
500 ms interpolation and shortest-path bearing interpolation across north.

Local result: 280 Android unit tests passed; debug APK assembled and signature verified.
Python non-integration regression suite: 446 passed, five PostGIS tests excluded.
`git diff --check` passed. No live-provider requests or device tests were performed.
APK SHA-256: `504ed103deeafb7de4d0e8358658c5f37567c2369476d94ce3403dc2199a2759`.

## Device gate

Install `dist/compass-0.28.39-debug.apk` directly, or from the repository root:

```bash
adb install -r dist/compass-0.28.39-debug.apk
adb shell am start -n org.compass.cng.debug/org.compass.cng.MainActivity
```

No new settings or secrets are required; retain the existing server configuration.
Test safely, with a passenger observing or using a replay:

1. Follow a route through several turns and for longer than five minutes. The green
   ring must not reset at turns or the periodic traffic refresh.
2. Open/close details and the action menu while moving. Puck motion should continue
   without jumping to the most recent fix when panels update.
3. Verify PiP/resume and restoration retain journey progress.
4. Intentionally take a different route safely: a confirmed off-route recalculation
   may reset the baseline. Verify normal ETA and stop completion remain unchanged.

If progress resets unexpectedly or motion stutters, return a short recording and the
time of the event, plus logs (remove private coordinates before sharing):

```bash
adb logcat -d -v threadtime CompassNavigation:I CompassNavigationUi:I AndroidRuntime:E '*:S'
```

Hardware smoothness and live navigation remain operator-validation requirements; local
unit tests do not certify either. Resolve any regression before advancing this gate.
