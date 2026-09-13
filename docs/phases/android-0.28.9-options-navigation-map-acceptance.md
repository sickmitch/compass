# Android 0.28.9 — options, navigation state and map contrast live gate

## Included

- Higher road/building contrast in the bundled day and night MapLibre styles.
- Back from **Personalizza viaggio** returns to **Crea viaggio** without closing the Activity.
- A fresh map-picker draft can be opened after every committed intermediate stop.
- Shared title-only headers; the explicit arrow follows Android's navigation mode.
- A global Material settings control on planner, follow and active-navigation surfaces.
- Persistent theme and voice defaults, favourites management, encrypted server connection and a
  read-only diagnostics panel containing app/backend versions, traffic, tileset, route provider,
  map source and current GPS coordinate.

## Automated acceptance

From `android/`:

```bash
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The tests must report no failures, lint must complete successfully, and the APK must be written to
`android/app/build/outputs/apk/debug/app-debug.apk`.

## Device acceptance

1. Open Compass with Android gesture navigation: no app back arrow is shown; swipe Back works.
2. Switch Android to button navigation: reopen a secondary screen and verify the arrow is shown.
3. Calculate a route, reach **Personalizza viaggio**, press system Back and verify **Crea viaggio**
   remains visible with both endpoints intact.
4. Add a map-selected stop, confirm it, expand **Aggiungi tappa**, add a second map-selected stop and
   verify both numbered stops and both route markers remain present.
5. Open the gear from create-trip, route preview, GPS follow and active navigation.
6. In Options switch light/dark/system and verify both Material UI and MapLibre change together.
7. Disable voice, close/reopen Compass, start navigation and verify guidance remains disabled.
8. Open favourites and connection from Options; Back returns to Options without changing the trip.
9. In Info verify app/backend version, server, traffic state, Valhalla tileset and current coordinate.
10. Compare bundled day/night maps around buildings: minor, secondary and primary roads must remain
    visually distinct without obscuring the blue route overlay.

Live server and physical-device results remain operator-owned and are not asserted by local tests.
