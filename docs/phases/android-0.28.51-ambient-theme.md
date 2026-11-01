# Android 0.28.51 — Ambient-light theme

Options → Tema adds `Automatica`, persisted through the existing preference repository.
`Sistema`, `Scuro` and `Chiaro` keep their behavior. Automatic mode listens to the phone's
TYPE_LIGHT sensor at SENSOR_DELAY_NORMAL only while the activity is at least STARTED
(including visible PiP). Leaving automatic mode or disposing the theme unregisters the
listener; stopping the activity unregisters it and cancels pending changes.

At <=10 lux the target is dark; at >=50 lux it is light. The threshold must hold for
2 seconds before switching. Between thresholds the current theme is retained and pending
switches are canceled. Invalid lux values do not select a theme. An on-change sensor need
not emit repeated samples to complete the delay. Before the first settled reading, when
registration fails, or when the sensor is absent, the system theme is used. No additional
permission is required. The existing theme propagation updates application colors and maps.

## Local validation

From the repository root, with JDK 17 and the Android SDK configured:

```bash
cd android
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover dark/light thresholds, neutral light and invalid readings. Hardware sensor
behavior, lifecycle and visual transitions require the following device check.

Local result: 296 Android unit tests passed; lint, APK assembly and signature verification
passed. Physical-device validation remains pending.

## Operator acceptance

Install `dist/compass-0.28.51.apk` or use the automatic update prompt. No backend redeploy.

1. Select Options → Tema → Automatica. Cover the light sensor for more than 2 seconds:
   expect dark theme. Expose it to a well-lit environment: expect light theme after 2 seconds.
2. Briefly cover/uncover it: expect no flicker. Check navigation map and ETA colors change
   with the theme. Repeat in visible PiP.
3. Select Chiaro/Scuro/Sistema: light changes must stop overriding that selection.
4. Restart the app: Automatica must still be selected. Background and reopen it: light
   detection must resume. On a device/emulator without a light sensor, expect system theme.

If behavior differs, return phone/Android version, selected mode, the exact sequence and:

```bash
adb logcat -d -s AndroidRuntime
adb shell dumpsys sensorservice
```

The sensor dump can contain information about other apps; retain only the Compass/light
sensor entries. The next step is this device validation; no physical-device result is
claimed from local unit tests.
