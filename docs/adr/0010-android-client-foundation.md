# ADR 0010: Android client foundation and route-preview boundary

- Status: accepted
- Date: 2026-08-29

## Context

The accepted Phase 7 API exposes a provider-independent base route with polyline6 geometry and
maneuvers. Phase 8 must make that contract visible on Android without moving routing decisions into
the client or prematurely implementing the Phase 9 CNG stop-selection workflow. The device client
also needs a structure that can grow without binding Compose screens directly to HTTP DTOs or
MapLibre lifecycle details.

## Decision

Compass uses a standalone `android/` Gradle project with one native application module. The pinned
toolchain is Android Gradle Plugin 9.2.0, Gradle 9.4.1, JDK 17, compile/target API 37, Kotlin 2.3.10
compiler plugins and the Compose 2026.08 BOM. Minimum API 26 keeps the initial compatibility policy
explicit. MapLibre Native's OpenGL artifact is used because it has the widest device compatibility;
the map style URL remains build-configurable.

The client has three boundaries:

- `data` owns OkHttp, strict kotlinx-serialization DTOs and HTTP/error translation;
- `domain` owns coordinates, route preview, maneuvers, polyline6 decoding and the repository
  interface without Android UI types;
- `ui` owns lifecycle-aware state, Compose presentation and the MapLibre `MapView` bridge.

Manual dependency construction in `AppContainer` is sufficient for this single-screen foundation.
It keeps dependencies replaceable in tests without adding a DI framework before one is justified.
The initial screen requests the accepted Milan-to-Bologna fixture route, draws the decoded road
geometry and endpoints, and displays distance, duration, provider and maneuvers. Routing and
maneuver content continue to come from `POST /api/v1/routes`.

`COMPASS_API_BASE_URL` and `COMPASS_MAP_STYLE_URL` are Gradle properties compiled into the app; the
API URL must end in `/`. Debug cleartext is allowed only for Android emulator host alias
`10.0.2.2` and loopback `127.0.0.1`, supporting emulator development and `adb reverse`. Other
deployments must use HTTPS. Release builds do not add a cleartext exception.

## Consequences

- API DTO changes cannot silently leak into Compose state; repository mapping is explicit.
- Polyline6 decoding and ViewModel behavior are deterministic JVM-unit-test targets.
- MapLibre lifecycle ownership is isolated from the rest of the screen.
- Backend configuration contains no committed credentials or operator-specific endpoint.
- Device rendering and map-style retrieval remain human-gated because repository tests have no
  physical device or public map service dependency.
- Destination entry, Add CNG Stop, ranked station display, selected-stop routing, navigation
  sessions and rerouting remain later phases.

## References

- [ADR 0003: mobile client and operator validation](0003-mobile-client-and-operator-validation.md)
- [ADR 0009: stable public API identity, selected-stop routing and freshness](0009-stable-public-api-and-freshness.md)
- [Android client development and device gate](../android.md)
