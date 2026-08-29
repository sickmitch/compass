# Phase 8 acceptance criteria

Phase 8 establishes the first native Android client without implementing the Phase 9 Add CNG Stop
workflow. It consumes the accepted Phase 7 base-route contract and displays a real route preview,
MapLibre geometry and basic maneuvers while preserving data/domain/UI boundaries.

## Implemented gate

- A standalone Gradle Android application uses Kotlin, Jetpack Compose and MapLibre Native OpenGL.
- AGP, Gradle distribution/checksum, compiler plugins, Compose BOM and application dependencies are
  version-pinned.
- API and map-style URLs are build properties rather than committed host endpoints or secrets.
- Debug cleartext is restricted to emulator/`adb reverse` loopback; other endpoints require HTTPS.
- The OkHttp client sends the strict Phase 7 base-route request and parses its public response rather
  than Valhalla JSON.
- Transport DTOs, domain models/repository and Compose/ViewModel state are separate.
- The domain decoder validates and converts polyline6 to latitude/longitude coordinates.
- MapLibre draws the road geometry and origin/destination layers and fits the route bounds.
- The Compose screen exposes loading, content, stable user-facing error/retry, route summary and a
  scrollable maneuver list.
- JVM tests cover known polyline6 geometry, malformed geometry, exact HTTP request/response mapping,
  machine errors and ViewModel success/error state.
- `scripts/run-phase8-live.sh` is an executable, single-command build/install/launch handoff.

## Local validation required

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Local validation result

Validated on 2026-08-29:

- Gradle 9.4.1 ran with Temurin JDK 17.0.20.1;
- Android API 37.0 and AGP-selected Build-Tools 36.0.0 were used;
- six JVM tests passed;
- `lintDebug` completed with zero errors;
- `assembleDebug` produced the installable debug APK;
- MapLibre 13.6.0 compiled through GeoJSON sources/style layers without deprecated annotation APIs;
- `bash -n scripts/run-phase8-live.sh` passed and its missing-environment guard failed clearly as
  designed;
- no live backend, emulator or physical-device rendering was exercised by repository-local tests.

Lint reports one informational tool-version warning because Gradle 9.4.1 is deliberately pinned to
the version supported by AGP 9.2.0 instead of adopting a newer Gradle independently.

## Live gate

Follow `docs/android.md` and run `bash scripts/run-phase8-live.sh` on the machine with an authorized
device. Acceptance requires the complete runner output, Android launch `Status: ok`, and operator
confirmation that the Milan-to-Bologna route, two endpoint markers, non-zero route summary and
maneuvers render correctly. Rotation/background/resume must not crash.

## Live validation result

Accepted from the operator's device run on 2026-08-29:

- `bash scripts/run-phase8-live.sh` reached `AUTOMATED PHASE 8 DEVICE CHECKS COMPLETED`;
- backend readiness returned HTTP success with database and routing `ready`, data explicitly
  `degraded`, and traffic explicitly `not_configured`;
- the Phase 8 Milan-to-Bologna base-route preflight succeeded and saved a real API response;
- `adb reverse` connected device port 8000 to the build host, so no public development endpoint was
  required;
- the Android gate completed all 53 Gradle tasks successfully, installed the debug APK with streamed
  install `Success`, and cold-launched `org.compass.cng.debug/org.compass.cng.MainActivity` with
  `Status: ok` in 1,298 ms;
- the returned device screenshot shows a rendered MapLibre route with distinct origin/destination
  markers, a non-zero 210.9 km / 1 h 52 min summary attributed to Valhalla, and the Italian maneuver
  list in the scrollable application layout;
- the operator returned the complete runner output and requested device screenshot after performing
  the runner's manual acceptance checklist, which includes rotation and background/resume stability.

The Phase 8 gate is complete. Phase 9 may begin only when explicitly requested.
