# Android 0.28.11 — voice default and manual map-stop policy live gate

## Changes under test

- Starting each new navigation reloads and applies the persisted **Voce** default.
- A generic intermediate stop chosen manually on the map is not rejected by the maximum added-time
  limit. Compass still calculates its complete route with Valhalla and shows a warning in the
  insertion preview when the limit is exceeded.
- The exception does not alter the search/POI time-budget policy.
- Cancelling the picker or its calculation retains the committed route and stops.

## Automated gate

From `android/`:

```bash
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Device gate

1. In **Opzioni**, set **Voce** to off.
2. Calculate a route, open its summary, and press **Avvia navigazione**.
3. Verify the voice button starts muted and no instruction is spoken.
4. End navigation, set **Voce** to on, start a new navigation, and verify voice guidance starts on.
5. Calculate another route and open **Aggiungi tappe**.
6. Set a very small maximum added time, such as `0,1 min`.
7. Choose **Selezione dalla mappa**, select a deliberately distant point, and press **Scegli**.
8. Verify **Ricalcolo percorso…** appears and the app reaches the stop preview rather than rejecting
   the selection. The preview must warn that the time limit is exceeded.
9. Cancel once and verify the original route and final destination are unchanged.
10. Repeat, confirm the preview, and verify the intermediate stop is inserted while the final
    destination remains unchanged.
11. Run an ordinary along-route POI search and verify its existing eligibility policy has not become
    a global/manual-map bypass.

No repository-local test claims success for GPS, speech output, Valhalla, or Android device behavior.

## Diagnostics

Before reproducing, clear the Android log:

```bash
adb logcat -c
```

After reproducing, collect:

```bash
adb logcat -d -s CompassPlanner CompassApi CompassNavigation AndroidRuntime
docker compose logs --tail=150 --no-color api
```

The Android log should include `navigation voice default applied: enabled=false` for the muted case
and `intermediate map selection confirmed` for the manual map stop.
