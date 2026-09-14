# Android 0.28.12 — active-navigation visual gate

## Changes under test

- The status bar is hidden only on the active-navigation surface and restored after leaving it.
- The maneuver panel background uses 80% opacity.
- With the trip-information panel expanded, the voice and options controls share the same vertical
  center as the orientation, overview and stop controls.
- The remaining route is a 9 px-equivalent bright light-green line with repeated dark-blue gradient
  chevrons oriented toward the destination.
- Preview and planning maps retain their existing route style.

## Automated gate

From `android/`:

```bash
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Device gate

1. Calculate a route and start navigation.
2. Verify the Android status bar is absent, while the bottom system navigation area remains usable.
3. Swipe from the top and verify the status bar can appear transiently; let it hide again.
4. Verify the maneuver panel lets the map remain visible through its 80% background.
5. Expand the bottom trip-information panel.
6. Verify voice, options, orientation/overview and stop controls are aligned on one center line.
7. Verify the remaining route is bright light green, wider than before, and contains dark-blue
   arrowhead chevrons pointing toward the destination, including through bends.
8. Open Options or end navigation and verify the system status bar returns.
9. Return to route planning and verify its route line was not changed by this navigation-only style.

No repository-local test claims success for physical display insets or MapLibre rendering on a
device.

## Diagnostics

```bash
adb logcat -c
# Reproduce the problem, then:
adb logcat -d -s CompassNavigationUi CompassPlanner AndroidRuntime
adb shell dumpsys window | grep -Ei 'statusBar|InsetsSource|mCurrentFocus'
```

For a rendering issue, return a screenshot together with the log output and the device model,
Android version, display resolution and navigation mode (gestures or buttons).
