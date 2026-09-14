# Android 0.28.13 — navigation panels live gate

## Changes under test

- Structured green exit signs are separate from the maneuver card, below it and right-aligned.
- Maneuver and upcoming CNG-stop cards both use 70% surface opacity.
- The voice button keeps the same background and foreground colors in enabled and muted states;
  only its icon and accessibility description change.
- The trip summary omits the redundant aggregate CNG-stop line while retaining the detailed
  **Tappe CNG** section and each planned dwell duration.

## Automated gate

From `android/`:

```bash
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Device gate

1. Start a route containing a motorway exit with structured sign data.
2. Verify the green exit badge appears below the black maneuver card and against its right edge.
3. Continue toward a planned CNG stop and compare the two cards: both must expose the map through
   the same 70% surface opacity while keeping text readable.
4. Toggle voice guidance several times. Verify the button colors remain unchanged and only the
   volume/volume-off icon changes; confirm speech state still follows the icon.
5. Open the pre-navigation trip summary for a route with at least one CNG stop.
6. Verify the line such as `1 sosta CNG · 20 min di rifornimento` is absent from the summary card.
7. Verify **Tappe CNG**, station names and **Sosta prevista** values remain visible below it.

No repository-local test claims success for MapLibre compositing or physical-device speech state.

## Diagnostics

```bash
adb logcat -c
# Reproduce the problem, then:
adb logcat -d -s CompassNavigationUi CompassNavigation CompassPlanner AndroidRuntime
```

For layout or alpha issues, return screenshots for both a maneuver with a green exit sign and a
route with an upcoming CNG stop, together with device model and Android version.
