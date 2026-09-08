# Compass — Navigation UI & MapLibre Upgrade

## Role

You are a senior Android GIS/navigation engineer specialized in:

- MapLibre Native Android
- Kotlin
- Jetpack Compose
- turn-by-turn navigation systems
- Valhalla routing engine
- OpenStreetMap
- automotive navigation UX
- GPS/map matching
- offline-first navigation

You are working directly on the existing Compass repository.

The goal is NOT to rewrite Compass.

The goal is to evolve the current Android application from a route visualization application into a real turn-by-turn navigation application comparable in usability and visual quality to OsmAnd and CoMaps, while preserving Compass-specific CNG-aware routing.

---

# Existing architecture

Before making any changes, inspect the repository and verify the actual implementation.

Compass currently includes or is expected to include:

- native Android client
- Kotlin
- Jetpack Compose
- MapLibre map rendering
- Valhalla backend
- route calculation
- route geometry
- maneuver/instruction data
- GPS tracking
- CNG fuel-aware routing
- automatically selected CNG stops
- cached routes
- offline route availability
- rerouting/recalculation infrastructure
- traffic integration where available

Do not replace working infrastructure unnecessarily.

Prefer extending the existing architecture.

---

# Primary objective

Compass must stop feeling like:

- a GIS viewer
- a route-debugging application
- a polyline displayed over a map

and start behaving like:

- a proper automotive navigator
- a turn-by-turn navigation application
- an application that can realistically be mounted in a vehicle and followed without continuous interaction

The target UX should take inspiration from:

- CoMaps for simplicity
- OsmAnd for navigation capabilities
- Google Maps for automotive camera behavior

Do NOT clone any of them directly.

Compass should retain its own identity centered around fuel-aware CNG navigation.

---

# General implementation rules

For every phase:

1. Inspect the existing implementation first.
2. Reuse existing abstractions whenever reasonable.
3. Avoid large rewrites unless technically justified.
4. Do not duplicate navigation state in multiple components.
5. Keep business logic outside Compose UI code.
6. Keep MapLibre-specific rendering logic separated from routing/navigation state.
7. Preserve existing Valhalla compatibility.
8. Preserve CNG routing logic.
9. Preserve offline/cached route support.
10. Keep debug functionality available, but move it out of the primary navigation UI.
11. Add tests where the repository architecture allows them.
12. Do not introduce unnecessary proprietary services.
13. Prefer open-source components and self-hostable infrastructure.

At the end of every phase:

- summarize files changed;
- explain architectural decisions;
- list remaining limitations;
- provide any manual testing steps I need to execute;
- stop and wait for my validation before proceeding to the next phase if live/device validation is required.

---

# Phase 1 — Navigation screen redesign

## Goal

Transform the current navigation screen from a diagnostic interface into a real driving interface.

The map must become the dominant visual element.

## Layout

The primary navigation screen should use a fullscreen MapLibre map.

UI elements should be overlays rather than large permanent panels.

### Top maneuver card

Create a compact maneuver card containing:

- maneuver icon
- distance to maneuver
- primary instruction
- target street/road name
- optional following maneuver preview

Example concept:

    ↱
    300 m
    Svolta a destra
    Via Roma

The card must remain readable at a glance while driving.

### Bottom navigation bar

Create a compact bottom bar containing the most important trip information:

- remaining distance
- remaining driving time
- ETA

Example:

    215 km        3 h 03        23:25
    Rimanenti     Durata        Arrivo

### CNG information

Surface the next CNG stop without occupying excessive screen space.

Example:

    ⛽ S. ZENONE OVEST
    23 km · arrivo 20:45

The CNG information may be integrated into the bottom navigation area.

### Expandable information panel

Detailed information should live in an expandable bottom sheet containing items such as:

- all planned CNG stops
- route details
- traffic status
- connectivity/cache state
- skip CNG stop
- replace CNG stop
- route recalculation actions

### Debug controls

Remove actions such as:

- simulate deviation
- debug recalculation
- raw navigation diagnostics

from the main driving interface.

Move them into a dedicated developer/debug screen.

## Acceptance criteria

- map occupies most of the screen;
- primary UI can be understood at a glance;
- debug information is no longer mixed with driving information;
- active navigation resembles an automotive navigation application rather than a debugging interface.

---

# Phase 2 — Navigation camera

## Goal

Implement true navigation camera behavior using MapLibre.

The current static/top-down map behavior is insufficient.

## Follow mode

Implement a navigation camera mode that follows:

- current matched vehicle position
- current direction of travel
- current route

The camera should use:

- heading-aligned bearing
- perspective pitch
- dynamic zoom
- forward-looking viewport

Initial target pitch should be approximately:

- 45–60 degrees during normal navigation

Do not hardcode these values throughout the application.

Expose them through a centralized navigation camera configuration.

## Vehicle positioning

During navigation, the vehicle should normally appear below the vertical center of the screen so that more map space is available ahead.

Conceptually:

             upcoming route


                  ↑


                 car


              route behind

## Camera transitions

Implement smooth camera interpolation.

Do NOT instantly reset camera position for every GPS event.

## Manual map interaction

When the user:

- pans
- rotates
- zooms

the navigation camera must temporarily leave follow mode.

Display a visible:

    Ricentra

control.

Pressing it restores navigation follow mode.

## Acceptance criteria

Driving with Compass should produce continuously moving navigation-camera behavior rather than a sequence of map jumps.

---

# Phase 3 — Vehicle puck and location pipeline

## Goal

Replace the basic/static position marker with a real navigation location indicator.

## Navigation puck

Implement a vehicle/location puck that supports:

- position
- heading
- smooth movement
- smooth rotation
- accuracy where appropriate
- matched route position

Do not directly render raw GPS coordinates as the authoritative vehicle location while actively navigating.

## Location pipeline

Implement or formalize the following pipeline:

    Android location provider
            |
            v
       raw GPS fix
            |
            v
      filtering / validation
            |
            v
       route matching
            |
            v
     navigation position
            |
            v
    interpolated map puck

Inspect existing functionality before adding another implementation.

## GPS noise

Handle:

- small stationary movements
- inaccurate fixes
- heading instability at low speed
- sudden GPS jumps
- delayed fixes

## Animation

Interpolate movement between location updates.

The puck should visually move instead of teleporting.

## Acceptance criteria

Under normal GPS conditions:

- vehicle motion appears smooth;
- heading is stable;
- position stays aligned with the route;
- small GPS errors do not cause obvious visual jitter.

---

# Phase 4 — Route rendering

## Goal

Upgrade the route from a simple polyline into a proper navigation route visualization.

## Main route

Render the active route using at least:

- route casing
- main route line
- high visual contrast

The route should remain clearly visible over both day and night map styles.

## Route progress

Distinguish between:

- already travelled route
- remaining route

The travelled portion should become visually secondary.

## Alternative routes

If Compass exposes alternative routes, support rendering them as secondary lines.

They must never visually compete with the active route.

## Route line behavior

Ensure route rendering behaves correctly during:

- zoom
- pitch
- bearing changes
- rerouting
- route replacement
- CNG stop replacement

Avoid destroying/recreating MapLibre sources unnecessarily.

Prefer updating existing sources/layers.

## Acceptance criteria

The active route should immediately be visually identifiable even in complex road networks.

---

# Phase 5 — Compass map style

## Goal

Develop a navigation-focused MapLibre style rather than relying on a generic OSM map appearance.

The map must prioritize driving information.

## Day style

Characteristics:

- low visual noise
- clear road hierarchy
- subdued land use
- reduced POI clutter
- readable labels
- strong route contrast
- important motorway and trunk roads clearly distinguishable

Do not attempt to display every available OSM feature.

## Night style

Create a dedicated night navigation appearance with:

- dark background
- subdued road surfaces
- reduced brightness
- readable labels
- highly visible active route
- minimal glare

## Automatic switching

Architect the style selection so it can support:

- system theme
- manual mode
- automatic day/night selection

Automatic switching may be implemented later if necessary.

## Performance

Avoid unnecessarily complex style expressions or excessive layers that degrade rendering performance on Android.

## Acceptance criteria

The map should look intentionally designed for navigation rather than like a generic OSM map embedded in an application.

---

# Phase 6 — Maneuver presentation

## Goal

Turn Valhalla maneuver information into a complete navigation instruction system.

## Supported maneuver families

At minimum support appropriate visual representation for:

- straight
- left turn
- right turn
- slight left
- slight right
- sharp left
- sharp right
- U-turn
- keep left
- keep right
- merge
- motorway entrance
- motorway exit
- roundabout
- destination arrival

Reuse Valhalla maneuver semantics instead of inferring instructions from geometry whenever possible.

## Distance countdown

Distance to the next maneuver must update continuously.

Use human-readable thresholds.

Example progression:

    1.2 km
    800 m
    500 m
    300 m
    100 m
    50 m

Avoid rapidly changing noisy values.

## Following maneuver

Where useful, expose the following maneuver as secondary information.

Example:

    Tra 300 m
    svolta a destra su Via Roma

    Poi mantieni la sinistra

## Acceptance criteria

The driver should not need to inspect the route line to understand the next action.

---

# Phase 7 — Lane and junction guidance

## Goal

Use any suitable Valhalla maneuver/lane information already available to improve complex junction guidance.

Inspect the route response first.

Do not invent lane guidance data that Valhalla does not provide.

Where data is available, represent lane guidance clearly.

Example:

    ↑   ↑   ↗
        ✓   ✓

Support situations such as:

- motorway exits
- forks
- merges
- multi-lane turns

If current backend responses do not expose sufficient lane information:

- document exactly what is missing;
- prepare the Android data model;
- identify the minimum backend/Valhalla change required.

Do not fabricate lane recommendations.

---

# Phase 8 — Voice guidance

## Goal

Provide proper turn-by-turn voice instructions.

Initially use Android TextToSpeech unless the existing project already has a better abstraction.

## Architecture

Voice output should originate from navigation events, not directly from Compose components.

Preferred architecture:

    Navigation state
          |
          v
    instruction scheduler
          |
          v
      TTS abstraction
          |
          v
    Android TextToSpeech

## Instruction timing

Support staged instructions where appropriate.

For example:

    "Tra 800 metri svolta a destra su Via Roma."

then:

    "Tra 200 metri svolta a destra."

then:

    "Svolta a destra."

Avoid repeated instructions caused by GPS oscillation.

## Voice events

Support at least:

- maneuver approaching
- immediate maneuver
- route recalculated
- significant off-route event
- approaching CNG stop
- CNG stop arrival
- final destination reached

## Audio controls

Prepare support for:

- mute/unmute
- speech volume handling
- language selection where appropriate

## Acceptance criteria

A user should be able to follow an ordinary route without needing to look continuously at the screen.

---

# Phase 9 — Navigation state engine

## Goal

Centralize navigation behavior into a coherent state model.

Navigation logic must not be distributed among Compose UI callbacks.

## Navigation lifecycle

Define explicit states equivalent to:

    IDLE
    ROUTE_READY
    STARTING
    NAVIGATING
    OFF_ROUTE
    REROUTING
    PAUSED
    ARRIVED
    ERROR

Adapt names to the existing codebase where necessary.

Do not create redundant states if equivalent ones already exist.

## NavigationState

Expose a single observable navigation state containing appropriate data such as:

- raw position
- matched position
- heading
- speed
- route progress
- distance remaining
- drive time remaining
- total trip duration
- ETA
- current maneuver
- next maneuver
- maneuver distance
- current road
- next road
- route deviation state
- next CNG stop
- distance to CNG stop
- ETA at CNG stop
- traffic status
- connectivity/cache status
- camera/follow state

Compose UI should observe this state.

It should not independently calculate navigation business logic.

## Acceptance criteria

Navigation behavior remains consistent even when UI components are recreated.

---

# Phase 10 — Off-route detection and rerouting

## Goal

Implement production-quality deviation handling.

Do not recalculate the route because of minor GPS noise.

## Detection

Use multiple signals where appropriate:

- distance from route
- matched position confidence
- direction of travel
- duration off route
- movement speed

## State transition

Typical behavior:

    NAVIGATING
        |
        v
    possible deviation
        |
        v
    deviation confirmed
        |
        v
    OFF_ROUTE
        |
        v
    REROUTING
        |
        v
    NAVIGATING

## Rerouting

A new route must preserve Compass routing constraints including:

- CNG autonomy
- required fuel stops
- user detour constraints
- destination
- relevant routing preferences

Do not downgrade rerouting to standard A→B navigation and lose CNG planning.

## Offline behavior

If the backend cannot be reached:

- retain the existing route;
- avoid terminating navigation;
- clearly expose degraded rerouting capability.

---

# Phase 11 — CNG navigation UX

## Goal

Make CNG-aware routing a native part of navigation rather than an attached information panel.

CNG stops must behave as first-class navigation waypoints.

## Next CNG stop

Display compact information such as:

    ⛽ S. ZENONE OVEST
    23 km
    ETA 20:45
    Aperto
    €1.49/kg

Only display:

- opening state
- price
- freshness indicators

when data confidence/freshness supports it.

Never present cached/stale information as live.

## Stop lifecycle

Represent a CNG waypoint through states such as:

    PLANNED
    APPROACHING
    ARRIVED
    REFUELING
    COMPLETED
    SKIPPED
    REPLACED

Adapt to existing architecture rather than duplicating concepts already present.

## Actions

Support:

- skip stop
- replace stop
- reroute
- inspect station details

## Refueling time

Compass route duration must continue accounting for the configured/planned refueling duration.

Current intended behavior:

    20 minutes per CNG stop

Keep this configuration centralized rather than scattering a literal 20-minute value through the Android application.

## Acceptance criteria

The driver should understand:

- where the next CNG stop is;
- when they will reach it;
- why it is part of the route;
- what happens after stopping.

---

# Phase 12 — Stop arrival and trip timing

## Goal

Handle CNG stop arrival realistically.

When approaching a planned station:

- provide visual indication;
- provide optional voice guidance;
- identify the stop as the current intermediate destination.

When the vehicle reaches the station:

- transition navigation into the appropriate stop/refueling state;
- avoid immediately navigating away as if it were a normal geometry point.

Account for planned refueling duration in:

- total duration
- ETA at final destination
- remaining trip time

After refueling completion:

- resume navigation toward the next waypoint/final destination.

The architecture should permit future automatic or user-confirmed refueling completion.

---

# Phase 13 — Offline resilience

## Goal

Navigation must remain useful after network loss.

## Persist locally

At minimum persist enough information to continue the current route:

- route geometry
- maneuver list
- CNG waypoint list
- route metadata
- destination
- routing parameters
- relevant navigation progress

## Connectivity loss

If connectivity disappears during active navigation:

Compass should continue providing:

- map display, where map data exists locally/cached;
- vehicle position;
- route line;
- maneuver guidance;
- voice guidance;
- remaining route calculations that can be derived locally;
- CNG waypoint guidance.

Features that truly require connectivity must enter an explicit degraded state rather than causing navigation to fail.

## Recovery

When connectivity returns:

- refresh information safely;
- do not abruptly replace the active route without reason;
- refresh traffic/CNG freshness according to existing backend behavior.

---

# Phase 14 — Navigation map interaction

## Goal

Provide the essential controls expected from a navigation application without clutter.

Implement appropriate controls for:

- recenter
- orientation mode
- zoom where useful
- overview route
- mute/unmute
- cancel/finish navigation

## Overview mode

Provide an action that displays the entire remaining route.

Leaving overview and pressing recenter should return to navigation follow mode.

## Orientation

Architect support for:

- heading-up navigation mode
- north-up overview mode

Avoid exposing unnecessary map controls while actively driving.

---

# Phase 15 — Search and route-start UX integration

## Goal

Ensure destination search flows naturally into navigation.

Compass already supports destination search using:

- addresses
- cities
- POIs
- coordinates

Redesign the transition:

    Search
      |
      v
    Search result
      |
      v
    Route preview
      |
      v
    CNG-aware route calculation
      |
      v
    Route overview
      |
      v
    Start navigation

## Route preview screen

Show useful information before starting:

- destination
- distance
- driving duration
- total duration
- number of CNG stops
- cumulative refueling duration
- major warnings
- traffic availability

Avoid showing internal route IDs or cache diagnostics in the primary UX.

Such information belongs in developer/debug details.

---

# Phase 16 — Visual polish

## Goal

Perform a final pass focused purely on automotive usability.

Review:

- typography hierarchy
- icon consistency
- spacing
- card sizes
- map contrast
- dark theme
- transitions
- animations
- touch target sizes
- landscape orientation
- different Android screen sizes
- system bars
- display cutouts

Avoid decorative complexity.

The navigation screen must remain easy to parse in under one second.

Use CoMaps as a reference for simplicity and OsmAnd as a reference for functional completeness.

---

# Phase 17 — Performance and lifecycle hardening

## Goal

Ensure long-running navigation sessions are stable.

Review:

- Android foreground service
- location lifecycle
- process recreation
- Compose recomposition behavior
- MapLibre resource lifecycle
- memory allocation
- map source/layer updates
- GPS frequency
- animation frequency
- battery consumption
- TTS lifecycle
- screen rotation
- application background/foreground transitions

Navigation must survive normal Android lifecycle events.

Avoid rebuilding the complete map/style/route on every state change.

Profile where appropriate before optimizing.

---

# Phase 18 — Final navigation validation

## Goal

Validate Compass as an actual navigator rather than only through unit tests.

Prepare a manual test plan covering:

### Normal route

- start route
- follow instructions
- multiple turns
- destination arrival

### Camera

- heading changes
- map pan
- recenter
- route overview
- return to follow mode

### GPS

- stationary GPS
- slow movement
- normal driving
- GPS temporary loss
- GPS jump

### Rerouting

- intentional wrong turn
- parallel road
- missed turn
- reconnect to original route

### CNG

- approach planned stop
- arrival
- skip stop
- replace stop
- route recalculation
- multiple CNG stops

### Connectivity

- connection available
- backend unavailable
- mobile data lost during navigation
- connection restored

### Android lifecycle

- screen off/on
- app backgrounded
- app reopened
- notification active
- process/lifecycle recovery where practical

Document failures and fix regressions before considering the navigation redesign complete.

---

# Final acceptance criteria

The upgrade is complete when a user can:

1. Search for an address or POI.
2. Calculate a CNG-aware route.
3. Inspect the route and planned fuel stops.
4. Start navigation.
5. Mount the phone in a vehicle.
6. Follow a heading-oriented MapLibre navigation map.
7. Receive smooth visual position updates.
8. Receive clear maneuver instructions.
9. Receive voice guidance.
10. Understand upcoming CNG stops.
11. Reach and complete CNG waypoints.
12. Automatically continue toward the destination.
13. Deviate from the route and receive a correct CNG-aware reroute.
14. Continue following the existing route during temporary connectivity loss.
15. Reach the destination without interacting with debug/developer controls.

Compass should no longer look or behave like a route visualization application.

It should behave like a dedicated modern navigation application.

The intended identity is:

    CoMaps-like simplicity
    +
    OsmAnd-like navigation capability
    +
    Compass CNG-aware routing intelligence

---

# Important architectural constraint

Do NOT introduce a complete external navigation SDK merely to obtain its default UI.

MapLibre should remain the map rendering foundation.

First implement the navigation experience using the existing Compass navigation state, Valhalla data and MapLibre capabilities.

If during implementation an external open-source navigation component such as Ferrostar or a MapLibre-compatible navigation library would materially improve:

- map matching
- navigation progress
- camera behavior
- instruction scheduling

do NOT immediately integrate it.

Instead:

1. identify the specific missing capability;
2. explain why the existing implementation is insufficient;
3. compare the external component against implementing the missing capability locally;
4. evaluate compatibility with Valhalla and Compass CNG routing;
5. present the proposed architectural change;
6. wait for approval before introducing the dependency.

Compass business logic and CNG-aware routing must remain authoritative.
