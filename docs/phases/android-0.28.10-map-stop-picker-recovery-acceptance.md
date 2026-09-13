# Android 0.28.10 — map stop picker recovery live gate

## Regression fixed

After choosing an intermediate point on the map, the UI previously entered a busy state without
showing progress or failures. That busy state disabled Compose's BackHandler, allowing Android to
close the Activity and discard the in-memory planner state.

The picker now:

- displays **Ricalcolo percorso…** and a spinner while Valhalla evaluates the stop;
- prevents duplicate confirmation taps;
- displays time-budget, routing and connection errors in the picker;
- intercepts Back throughout the request;
- cancels the pending calculation before returning to **Organizza le tappe**;
- retains the base route, endpoints and already committed stops.

## Automated gate

From `android/`:

```bash
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Device gate

1. Calculate a route and open **Aggiungi tappe**.
2. Select **Selezione dalla mappa**, move the point and press **Scegli**.
3. Verify the button immediately becomes **Ricalcolo percorso…** with a spinner.
4. On success, verify **Verifica tappa** appears and confirmation retains the final destination.
5. Repeat with a second map-selected stop and verify both stops remain present.
6. Repeat once more, but press system Back while the spinner is active.
7. Verify Compass returns to **Organizza le tappe**, does not close, and retains the route and all
   previously confirmed stops.
8. Force a backend error or choose a point beyond the time limit and verify a readable error appears
   below the coordinate instead of an apparently unresponsive button.

If confirmation remains pending, capture:

```bash
adb logcat -d -s CompassPlanner CompassApi AndroidRuntime
docker compose logs --tail=150 --no-color api
```

The Android log must contain `intermediate map selection confirmed` followed by a Compass API
request event. No live/device success is asserted by the repository-local test suite.
