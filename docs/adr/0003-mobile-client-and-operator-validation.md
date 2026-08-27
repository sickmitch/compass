# ADR 0003: Mobile client and operator validation

- Status: Accepted
- Date: 2026-08-26

## Context

Compass is a navigation product whose primary interface is Android. Repository agents cannot claim
test-server, public-network or physical-device success without operator evidence.

## Decision

Use native Android with Kotlin, Jetpack Compose and MapLibre Native when the mobile phase begins. The
backend owns routing, spatial selection and ranking; the client owns interaction and presentation.
Server components use Docker; the Android device application does not.

Repository-local validation uses fixtures and automated tests. The human operator synchronizes to the
test server, executes documented live checks, and returns output before a live-gated phase advances.

## Consequences

API models will not mirror database tables or contain UI styling instructions. Mobile is a first-class
later phase rather than a demo. Each server phase ends with exact test-server commands and clearly
distinguishes local evidence from live evidence.

