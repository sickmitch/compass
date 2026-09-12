# Android client development

## Android scope

The Android app is a native Kotlin/Jetpack Compose client in `android/`. It opens in route-free GPS
follow mode and does not call the routing API until the driver creates a trip. `Crea viaggio` opens
the endpoint selector; after both endpoints are chosen, the app calls `POST /api/v1/routes`, decodes
its polyline6 geometry and renders the route preview with MapLibre.

Phase 9 retains that fixed endpoint pair and adds the manual `Aggiungi tappa → Metano` workflow. It
collects maximum detour and effective range, displays arrival-aware ranked station markers/cards,
and recalculates the route through a selected official MIMIT station.

Phase 10 adds a separate predictive flow driven by a user-supplied remaining-range estimate and
safety reserve. It shows a complete ordered reserve-preserving refuelling itinerary or an explicit
no-refill/no-safe-itinerary state. Each planned stop assumes a full refill to the effective range.
Phase 11 adds editable coordinates. Navigation Stage 1 then introduces the server-backed
`NavigationRoute`, explicit navigation-session boundary and the `Avvia navigazione` route handoff.
Navigation Stage 2 adds foreground location, local route matching/progress and the MapLibre
navigation renderer. Navigation Stage 3 adds speed-aware Italian voice guidance, dynamic camera
follow/remaining-route overview, confirmed off-route rerouting through Compass, five-minute traffic
route refresh and in-place route replacement. After the physical-device gate passed on 2026-09-02,
the debug replay was slowed from eight geometry points per second to one point every 1.5 seconds;
the simulated fix still reports road speed independently for maneuver timing.
Debug API calls emit bounded `CompassApi` logcat events containing endpoint, outcome, duration and
exception classes. Payloads and response bodies are deliberately excluded from these diagnostics.
Ordinary calls retain the short global network limits. Predictive candidate evaluation alone uses a
240-second read/call limit because a bounded full-range itinerary may require multiple sequential
Valhalla matrix batches; its actual elapsed time is recorded in the same event stream.
Navigation Stage 4 adds an explicit next-stop skip/replacement confirmation. It replans from the
snapped position with the unavailable MIMIT ID excluded and commits only a complete range-safe
itinerary. Its physical-device gate passed on 2026-09-02.

Navigation Stage 5 adds local vehicle profiles and an explicit gasoline fallback. A profile stores
effective full range and reserve for both fuels and is selected across app restarts. Predictive CNG
planning still requires driver-entered remaining CNG range. Remaining gasoline is also entered by
the driver and is optional; when present, Compass may offer a direct gasoline fallback only after no
complete CNG itinerary exists and only if both reserves are preserved. The fallback remains labelled
in navigation preview and active guidance.

Phase 12 adds destination search and current-location origin selection. The search screen accepts
addresses, localities, named POIs/businesses and coordinates, then displays only normalized Compass
results. “Usa la posizione attuale” requests location permission when needed, acquires the first
available GPS/network fix and writes it into the visible origin fields; the driver confirms it with
“Calcola percorso”. Selecting a destination then reloads the base route and clears stale
route-dependent planning state. Navigation startup independently requires the Android notification
permission on API 33+, even when location was already granted. A debug off-route injection pauses
demo fixes only while rerouting and resumes replay on the committed replacement route. Zero-cost
routing responses are rejected as non-navigable. The
navigation preview separately shows driving time, traffic-delay availability, cumulative CNG dwell
and total trip duration.

Phase 13 adds durable, versioned private-device caching for the active route, geometry, maneuvers,
planned CNG waypoints and range policy. A process restart restores an explicitly cached preview;
`Termina navigazione` clears it. Exact recent place queries can fall back to one of the ten cached
result sets only after a network/server failure. The active screen distinguishes local cached-route
guidance, unavailable rerouting, unavailable traffic and cached CNG data. MapLibre's configurable
ambient cache retains resources already viewed but does not guarantee an arbitrary offline region.
Android version is `0.22.1` (`versionCode=28`).

Destination search uses Compass `POST /api/v1/destinations/suggest` after a configurable 300 ms
debounce and `POST /api/v1/destinations/resolve` only after selection. Results and full Google
addresses are displayed on a mapless screen with attribution. Entering any MapLibre surface replaces
that text with `Destinazione selezionata`; routing receives the resolved coordinate directly. Search
sessions survive recomposition/rotation with the ViewModel but are not persisted across process
death, and no Google content enters the legacy place-search cache.

The Google-only destination live gate was accepted on the live backend and a physical device on
2026-09-07. Eight applicable manual cases passed. The ninth proposed case—editing the destination
of an already active CNG/vehicle plan while preserving it—is not exposed by the current UI and was
explicitly waived by the operator; it is not recorded as a successful on-device test.

Navigation UI Phase 10 adds temporal confirmation and recovery hysteresis to the existing
accuracy-, heading-, speed- and progress-aware off-route detector. Explicit stationary drift is
suppressed unless the displacement is gross. While the position is doubtful, obsolete route
progress, maneuver, ETA and next-stop state are frozen; a confirmed reroute still originates from
the raw accepted fix and preserves the Compass CNG policy. A compact indeterminate spinner labelled
`Ricalcolo rotta` appears below the maneuver card and in accessibility semantics for every active
route update. Failed server updates retain downloaded guidance and expose degraded rerouting. The
operator accepted the complete live-backend and physical-device gate on 2026-09-08.

Navigation UI Phase 11 carries the CNG enrichment already selected by the driver into the
navigation route instead of dropping it at the routing boundary. The navigation state owns a
per-waypoint lifecycle and exposes the next stop independently of the optional trip panel. The
compact CNG card and details sheet show road distance, ETA, why the stop is required, and the
server-provided dwell duration. Valid opening evidence is displayed only near the ETA for which it
was evaluated; only fresh prices are displayed. Restored cache-only routes retain waypoint guidance
but suppress both dynamic claims. Replacement continues through Compass predictive routing, so a
failed safe-plan search leaves the existing route untouched. The operator accepted the complete
live-backend and physical-device gate on 2026-09-08, including local-time rendering and placement
of the recalculation pill below the persistent CNG card.

Navigation UI Phase 12 gives a routed CNG waypoint a real stop lifecycle. On arrival the engine
enters an explicit refuelling visit, freezes matched route/maneuver progress, counts down the
server-routed dwell and suppresses rerouting until the driver confirms completion. Final ETA stays
stable during the planned dwell, improves if the stop finishes early and slips if it runs late.
The action is available on the compact CNG card, in details and in the foreground notification;
demo replay pauses and resumes through the same production boundary. The refuelling gate was
accepted on a physical device on 2026-09-08. The `0.22.1` supplement orders selectable stations
strictly by deviation duration and uses pastel price tiers (cheapest green, second distinct price
yellow, later prices red). Its reduced physical-device gate was accepted on 2026-09-09, completing
Navigation UI Phase 12.

Navigation UI Phase 13 upgrades the private route cache to an active-session checkpoint. It restores
the last matched pose/progress, maneuver state, remaining values, voice preference and CNG stop or
refuelling lifecycle, then automatically resumes the foreground service after process recreation.
Normal progress writes are throttled to five seconds; critical transitions are immediate. Android
network loss is independent of GPS: downloaded route, local ETA, maneuver/voice and waypoint
guidance continue, while traffic and dynamic CNG evidence are explicitly unavailable. On network
return a `CONNECTIVITY_RECOVERY` update re-enters the existing CNG-aware route recalculator. Failed
recovery attempts keep the local route and retry after 5, 15 and then 60 seconds. An active debug
replay is persisted as the position source and resumes from its saved segment after process
recreation. The ambient MapLibre cache covers only already visited resources. Android `0.23.1`
(`versionCode=30`) passed the physical-device gate on 2026-09-09.

Navigation UI Phase 14 adds a compact, map-native interaction cluster. The direction control toggles
between heading-up tracking and north-up tracking without releasing the puck anchor. `Panoramica`
continues to fit only the remaining route, always at bearing and pitch zero; `Ricentra` returns from
overview or manual free mode to heading-up tracking. Automatic speed/maneuver zoom remains the
default and pinch zoom remains available, avoiding permanent +/- controls. Voice and trip controls
stay visible, while a dedicated stop control requires confirmation before it clears navigation and
returns to route-free GPS follow. Android `0.24.0` (`versionCode=31`) is ready for the device gate in
`scripts/run-navigation-ui-phase14-live.sh`.

The operator explicitly advanced to Phase 15 on 2026-09-09 without returning the Phase 14 device
runner output; Phase 14 therefore remains recorded as implemented locally rather than falsely
reported as device-accepted.

Navigation UI Phase 15 adds up to eight ordered ordinary waypoints between departure and
destination. The waypoint selector reuses all endpoint acquisition modes. Its original
route-rectangle search was superseded by the dedicated Search Along Route contract documented
below; selection remains mapless and resolves one provider result only. The ViewModel then asks
Valhalla for the direct route and the exact
origin→waypoint→destination route, accepting the waypoint only when the latter's added road distance
does not exceed the chosen kilometre limit. The default is 30% of direct-route length.

The waypoint enters navigation as a Compass-owned neutral `Tappa intermedia`, never as persisted
Google display text. A selected result first receives an exact-route MapLibre preview with a
highlighted neutral marker and is committed only by `Scegli`. On arrival, guidance and demo replay
pause without adding dwell seconds. The operator can resume explicitly; during real GPS guidance
two moving fixes beyond the departure radius complete the waypoint automatically. ETA is rebuilt
from the completion time and remaining driving/CNG dwell.

Trip creation now follows one animated endpoint → personalization → common-summary flow. Ordinary
stops are reordered by long-press drag, vehicle list/form replace the lower CNG planning panel, and
the common summary edits or removes individual planned stops inline. Manual and predictive CNG
selections enter that overview directly; it presents distance,
driving/total duration, fuel-stop dwell, traffic and warnings without route IDs or internal cache
diagnostics. Puck icon size interpolates with MapLibre zoom from the established full size to a
lower bound of exactly 50%. Android `0.25.3` (`versionCode=35`) adds a uniform branded Material 3
presentation across every non-map phase and dedicated day/night Material puck artwork, without
changing the accepted route-creation or navigation behavior. The operator reported the
`scripts/run-navigation-ui-phase15-live.sh` physical-device gate green on 2026-09-11; Phase 15 is
complete.

Android `0.27.0` (`versionCode=42`) separates destination-search intent from ordinary-stop search.
Origin and destination keep Google Autocomplete/Details. **Aggiungi tappa → Ricerca** instead sends
the selected Valhalla route legs to Compass as E6, and Compass performs Google Text Search with
Search Along Route after converting the geometry to E5. Search results stay on the mapless screen;
the subsequent preview and itinerary use only the coordinate, provider reference and neutral label.
The existing Valhalla preview/explicit **Scegli** commit remains authoritative and generic stops keep
zero dwell time.

Android `0.27.1` (`versionCode=43`) removes the former Milan startup coordinate from production
state. Route-free follow listens to both enabled Android location providers and re-polls every five
seconds whenever no fix exists or the last fix is older than ten seconds. Generic origin and
destination searches use only a recent device fix as Google location bias; they never substitute a
route endpoint or hard-coded city. Dark Material surfaces use OLED black without elevation tint.
The personalization actions form two balanced rows: **Cambia percorso / Aggiungi tappe** and
**Sosta CNG / Piano CNG**.

Android `0.27.2` (`versionCode=44`) fixes cancellation of an uncommitted ordinary-stop draft.
Opening **Aggiungi tappe** no longer marks the itinerary as containing a stop; leaving map search,
place search or the empty stop editor preserves the original route and keeps every planning action
available. The four personalization actions also share the same neutral outlined-button treatment.

Navigation UI Phase 7, accepted on a physical Android device on 2026-09-06, preserves Valhalla
junction-sign groups and roundabout exit counts across the strict API, Android models and version-1
private route cache. Active guidance adds a compact sign panel only when provider data exists, and
places a numeric exit badge on roundabout icons. The UI does not parse localized maneuver prose to
manufacture missing guidance. Debug builds provide a separate, clearly labelled rendering gallery
under `Strumenti sviluppatore`.

Navigation UI Phase 8, accepted on the live backend and physical device on 2026-09-06, carries
ordered Valhalla graph speed limits with each routed shape. The
navigation engine resolves a limit only when the map-matched segment falls inside its half-open
shape-index range. Active guidance then shows a white/red regulatory badge above the MapLibre
attribution; unavailable, zero and unlimited values produce no badge. Profiles survive selected
CNG routes, predictive multi-stop routes, route replacement and the version-1 private cache.
Pinch zoom remains in follow mode and uses the puck's driving position at 75% of viewport height as
its focal point; its selected zoom survives later location frames, while only a deliberate
single-finger pan releases the camera into temporary free mode. Camera and puck consume the same
interpolated pose on every frame, preventing either from advancing first.
The optional trip panel contains `Nascondi ﹀` on the left and `Dettagli ︿` on the right; the details
actions use vertically centered vector chevrons. Opening the panel shortens the unobscured driving
viewport, so the puck remains at 75% of the visible map above it instead of sinking behind the
panel. The details sheet wraps its content instead of forcing a nearly full-screen height.

Navigation UI Phase 9, accepted on the live backend and physical device on 2026-09-06, derives a
three-state speed-compliance presentation from the filtered matched speed and the current Phase 8
limit. It enters `OVER_LIMIT` at five km/h above the limit and clears at two km/h above it, avoiding
boundary flicker without presenting those values as legal tolerances. The regulatory badge retains
its white face and red border; the warning adds a second bright-red ring, red number and an Italian
accessibility description. Missing speed or limit stays `UNAVAILABLE`. The phase deliberately adds
no sound or vibration.
Its live gate treats visual behavior as operator-owned evidence and does not use UIAutomator or
screenshot parsing.

The `0.19.1` road-test follow-up removes redundant road text when the structured junction sign
already contains the same normalized name. The instruction and structured sign remain authoritative;
ordinary maneuvers without that duplication retain their road subtitle. Junction signs are centered
within the maneuver card and size to their text plus horizontal padding, up to the card width.

Android `0.19.2`, accepted in the operator's physical-device road test on 2026-09-06, corrects the
maneuver timeline exposed by the first road test. Valhalla maneuver instructions describe the
transition at `begin_shape_index`; guidance therefore selects the first maneuver whose begin index
is still ahead of the matched route segment and measures distance to that same index. This keeps the
visible instruction, following instruction, voice timing, approach phase and maneuver-aware camera
on the same upcoming transition instead of retaining the completed one. Evidence is recorded in
`docs/phases/android-0.19.2-road-test-acceptance.md`.

Android `0.19.3` strengthens the existing `OFF_ROUTE` route-update path. The local engine still
requires three consecutive accepted fixes, while using a 20-metre accuracy-aware lateral threshold
and a separate 65-degree moving-heading conflict threshold beyond the GPS uncertainty band. Once
confirmed, the existing route recalculator requests a server route from the raw GPS coordinate and
preserves remaining CNG stops or replans them through the established predictive policy.

A successful off-route replacement records the previous remaining trip duration and the new route
duration. Compose presents `Prima`, `Ora` and their signed `Differenza` in an accessible bottom card,
slides it into the same layout family as the trip panel, includes its measured height in camera
padding and removes it ten seconds after the server commit. Traffic/manual refreshes do not create
this notice, and speed-limit presentation becomes unavailable while the position is suspected or
confirmed off-route.

The final `0.19.3` increment makes the no-route state explicit. On a fresh launch, the foreground
Activity subscribes directly to GPS updates and renders the raw device position on a dedicated
route-free MapLibre surface. It retains the same 75%-height follow anchor and temporary free-pan
recentring policy, but has no route overview, maneuver matching, speed limit or destination. This
foreground-only listener stops with the Activity and before the navigation foreground service
starts, so route guidance continues to have a single location owner.

The `Crea viaggio` control replaces `Viaggio` only on that route-free surface. Its selector offers
`Posizione attuale`, a disabled `Posizioni preferite` placeholder, `Ricerca` and `Coordinate` as
content-width buttons that wrap onto additional rows. Departure puts current position first;
destination puts it last. The existing normalized place search can now fill either endpoint and
routing begins only after both endpoint values validate. Current-position acquisition reports its
state inside the relevant pill: a spinner while waiting, a green check on success and a light-red X
on failure. It does not emit a separate error-styled success message or repeat the selected label.
The search field accepts POIs and addresses and explains the address grammar directly below the
input: the civic must occupy a comma-separated segment, for example
`Via Cappafredda, 12, Roverchiara`. The keyboard search action submits the same query as the button.
The selector begins directly with `Partenza`. After a successful calculation it keeps the endpoint
choices visible and enables the direct route, one-stop planning and extended planning; editing an
endpoint locks those actions again until recalculation. Extended planning offers saved vehicle
profiles and an explicit custom-values choice. A selected profile supplies full-range and reserve
defaults, while remaining CNG range and maximum detour always remain driver inputs.

`Calcola percorso` stays disabled until both endpoint coordinate pairs have been acquired or
entered; attempting an incomplete request programmatically is a no-op and does not show a red
empty-field error. Once routing succeeds, `Imposta una sosta` and `Pianificazione estesa` use equal
outlined actions on one row. `Usa percorso diretto` remains a centered content-width pill.

In the CNG candidate list, tapping a station card only changes its corresponding map point from the
standard CNG green to the selection amber. Only the nested `Scegli` action adds that station and
requests a new route. Internal ranking percentages are no longer shown. Arrival opening state uses
a high-contrast badge, while an available CNG price has its own prominent surface with observation
time immediately below on the card background; qualitative freshness copy is intentionally omitted.
The price surface wraps only its label
and value; the transparent `Chiama` action sits above `Scegli` in the adjacent action column.

While turn-by-turn navigation is visible, the Activity keeps the display awake. The vehicle puck is
rendered at twice its previous scale in both route-free follow and active guidance. Night navigation
uses an intense blue remaining-route line (`#009DFF`). The former speed-limit badge is not rendered;
its position now contains a `Voce ON/OFF` control backed by the foreground service. Disabling it
immediately stops queued/current TTS output while maneuver scheduling continues, so re-enabling does
not replay stale instructions.

Route recalculation is server-backed. An APK using loopback HTTP plus `adb reverse` loses the API as
soon as USB/ADB is removed. Compass opens server configuration automatically when credentials are
missing or a routing/search request encounters a connection or authentication failure. There is no
manual server shortcut in the planner; it will move to the future settings surface. The connection
profile persists like vehicle profiles; its
password is encrypted with an app-private Android Keystore key and Android backup is disabled.
Every subsequent API request reads the current profile, so saving does not require an app restart or
rebuild. HTTPS is accepted directly. HTTP requires the user to acknowledge the cleartext-credential
warning explicitly and is intended only as a fallback on a trusted private network.

Navigation UI Phase 1 makes the active MapLibre view a full-screen driving surface. The primary
overlays contain only the current/following maneuver, remaining trip values and next CNG stop.
Traffic, GPS, cache/connectivity state, planned stops and trip actions are available in an
expandable bottom sheet. Debug-build simulation controls and raw state have a separate
`Strumenti sviluppatore` screen and are not composed into the normal driving interface. The UI
derives its display model from the existing authoritative `NavigationState`; routing and navigation
logic remain outside Compose. The operator accepted the Phase 1 device gate on 2026-09-03 after
validating the driving surface, expandable details, developer-tool isolation, foreground-service
continuity and clean notification teardown.

Navigation UI Phase 2 centralizes driving-camera parameters in `NavigationCameraConfig`. Follow
mode eases toward a speed- and maneuver-density-aware, pitched and heading-aligned camera target
projected ahead on the local route-heading centreline. Heading follows a short matched-route tangent so a lagging GPS bearing cannot
leave the road diagonal after recentering. Centralized top padding places the directional vehicle
lower in the viewport. The vehicle stays vertically aligned in follow while retaining route-bearing
rotation in free/overview mode. Nearby consecutive maneuvers increase zoom; sparse maneuvers widen
the view within centralized bounds. The current gesture policy keeps pinch zoom in follow and
releases only a deliberate single-finger pan, exposing a contrasting `Ricentra`; ten seconds
without a pan restore follow automatically. Overview remains
north-up and uses only untravelled geometry. Name-bearing style layers prefer Italian labels, map
waypoints and the optional trip summary use CNG-specific markers, and the summary is hidden until
the driver taps `Viaggio`. During active navigation, generic basemap POIs are filtered out while
fuel/charging stations, toll booths, border control and available traffic signals are retained;
Compass CNG waypoints are unaffected. This reuses the existing matched position and MapLibre APIs without
adding a navigation SDK. The device gate is `bash scripts/run-navigation-ui-phase2-live.sh`.
The operator accepted this gate on 2026-09-04, including the final navigation-only POI filter.

Navigation UI Phase 3 formalizes the location-to-puck pipeline without adding another navigation
engine. `NavigationLocation` remains the raw fix. `LocationFilter` rejects inaccurate,
out-of-order, delayed and implausible updates, applies a stationary deadband and ignores unstable
low-speed GPS bearing. The existing `RouteMatcher` projects accepted fixes onto the downloaded
route. `NavigationEngine` exposes the result as one `NavigationPosition`, including matched
coordinate/segment, filtered speed, stabilized route heading, source accuracy and accepted-fix
timestamp; legacy read accessors derive from that object rather than storing duplicate state.

`MapPuckAnimator` drives MapLibre source updates between accepted positions using the pure
`NavigationPuckMotion` planner. Normal updates interpolate coordinate and shortest-path angular
rotation for most of the fix interval. Initial placement and large discontinuities snap, while
stationary sub-three-metre changes hold the displayed pose. Camera state and route matching remain
separate: releasing the camera does not stop puck interpolation. Pipeline values are visible only
inside the existing developer screen. The device gate is
`bash scripts/run-navigation-ui-phase3-live.sh`.

The first Phase 3 device run exposed excessive altitude and lateral puck displacement during urban
turn sequences. The camera target now follows the local heading centreline instead of a distant
curved-route point, top padding is increased, and maneuver-aware zoom has a stronger continuous
approach range before the close-turn boost. These remain centralized camera policy values.

The same gate later exposed a server-side Valhalla time-dependent no-route before the Android route
preview could load. The routing adapter performs one graph-speed retry only for a traffic-aware
error 442. Independent Valhalla reproduction showed that the old Bologna city-centre fixture is
temporally restricted; the startup preview and traffic gate now terminate at drive-reachable
Bologna Centrale.

Navigation UI Phase 4 makes the existing live-traffic route lifecycle visible and testable. The
backend exposes provider state, whether the returned duration actually used traffic, the provider
observation timestamp and a delay calculated against a second Valhalla graph-speed route. Android
shows `Traffico live incluso` only when all of that evidence is present. A fresh feed combined with
a route fallback is shown separately as standard-speed routing, never as zero traffic delay.

The same metadata is decoded and retained in the versioned route cache, preview, selected CNG route,
multi-stop itinerary and active navigation updates. Debug route-commit logs contain only bounded
state/delay metadata and no provider payload or credential. The Phase 4 gate is
`bash scripts/run-navigation-ui-phase4-live.sh`; it refuses to install Android until the server
reports fresh TomTom data, managed overlay edges and a non-fallback traffic route.

Navigation UI Phase 5 replaces the Liberty development baseline with two Style Specification v8
documents included in the APK. Both use flat buildings and a restrained OpenMapTiles hierarchy;
the night palette is designed independently rather than dimming a day render. Compose selects the
map style from system dark mode, so Activity recreation changes cartography without changing the
navigation session or foreground service. Map-owned routes, endpoints and CNG markers use matching
day/night contrast palettes. Logs report the selected theme and a bounded source class without the
configured style URL. The operator accepted the
`bash scripts/run-navigation-ui-phase5-live.sh` device gate on 2026-09-05.

Navigation UI Phase 6 replaces the temporary character-based maneuver symbols with a typed,
density-independent Compose vector system for all published Valhalla maneuver IDs 0–36. It keeps
current and following icons separate, exposes Italian semantic descriptions and includes a
debug-only full catalog for device inspection of rare ferry/transit families. The normal surface
never displays provider IDs. Validate with `bash scripts/run-navigation-ui-phase6-live.sh`; see
`docs/phases/navigation-ui-phase-6-acceptance.md`.

## Navigation Stage 1 device gate

If the backend is remote, first keep this tunnel open in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

Then run from the repository root on the machine connected to the Android device:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage1-live.sh
```

The runner prints the two exact manual scenarios and screenshots required for acceptance.

## Navigation Stage 2 device gate

Stage 2 shares the navigation session between Compose and a foreground location service. During an
active session the app filters fixes, projects them onto a local window of the downloaded route,
updates progress/ETA/manoeuvres locally and renders only the snapped vehicle puck. Ordinary GPS
updates do not call Compass or Valhalla.

If the backend is remote, keep the same SSH tunnel shown above open. Then run:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage2-live.sh
```

The runner pauses once. On the device open the navigation-ready screen, press the debug-only
`Riproduci percorso demo`, grant location/notification permission and return to the terminal. The
replay traverses the downloaded geometry through the real foreground service and navigation engine;
it sends no simulated GPS point to the backend. See
`docs/phases/navigation-stage-2-acceptance.md` for thresholds and the three requested screenshots.

## Navigation Stage 3 device gate

Stage 3 keeps ordinary GPS updates entirely on the device. Android contacts Compass only after a
confirmed deviation, at the five-minute active-navigation refresh boundary, or through a
debug-only manual test action. A route update always goes through Compass and preserves the last
downloaded route if the network request fails. The foreground service owns TextToSpeech, so spoken
guidance is independent from Activity recreation.

If the backend is remote, keep the SSH tunnel shown above open in a separate terminal. Then run:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage3-live.sh
```

The runner pauses for explicit device actions, then verifies the foreground service, the automatic
off-route route replacement and background/resume continuity. The debug replay still sends no GPS
point to Compass; the debug deviation instead passes three controlled fixes through the production
filter, matcher and off-route state machine. See
`docs/phases/navigation-stage-3-acceptance.md` for the accepted evidence and thresholds.

## Navigation Stage 4 device gate

Stage 4 requires a predictive itinerary because the replacement decision must retain the driver's
explicit effective range, remaining-range estimate, reserve and maximum detour. Android derives the
current remaining range from local route progress, calls Compass with the unavailable official ID
excluded, and keeps the downloaded route for every non-complete result. A manual selected-stop
route has no caller-supplied tank state and is therefore guarded instead of being silently changed.

Run from the repository root with the same toolchain and backend tunnel used for Stage 3:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-stage4-live.sh
```

The runner verifies the deployed OpenAPI exclusion field, builds/installs the app, checks the
replacement start/commit events, confirms foreground-service continuity, exercises the manual-route
range-plan guard, and checks final service/notification teardown. See
`docs/phases/navigation-stage-4-acceptance.md` for the three required screenshots and diagnostics.

## Phase 12 device gate

After rebuilding/restarting the synchronized API, run from the repository root with the same JDK,
SDK and optional SSH tunnel used by prior navigation gates:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase12-live.sh
```

The preflight performs real address/locality/POI/coordinate searches, requests a final A-to-B route
and validates one/multiple-stop journey chronology. It then builds, installs and pauses for the
current-location, destination-search, maneuver-progress, CNG-preserving reroute, invalid-stop
replacement and lifecycle checks. Filtered API/navigation logs are captured continuously during
the operator steps so early search evidence cannot be evicted from logcat. Return the complete
output and eight requested screenshots. See
`docs/phases/phase-12-acceptance.md`; the operator accepted this gate on 2026-09-03.

## Phase 13 degraded/offline device gate

Run from the repository root with the same toolchain and loopback/SSH setup:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase13-live.sh
```

The runner populates search and active-route caches, removes only `adb reverse`, validates degraded
navigation, force-stops/reopens Compass while offline, and then restores connectivity. It captures
bounded client/navigation/map-cache logs and asks for screenshots A–G. See
`docs/phases/phase-13-acceptance.md`. The operator accepted this gate on 2026-09-03.

## Required toolchain

- JDK 17;
- Android SDK Platform 37.0;
- Android SDK Build-Tools 36.0.0 or the AGP-selected compatible version;
- Android Platform-Tools for `adb`;
- an Android API 26+ emulator or physical device for the live gate.

Gradle 9.4.1 is supplied by the checked-in wrapper and its distribution SHA-256 is pinned. On the
current development workstation the isolated toolchain is installed under `/home/mike/toolchains`.
Set paths explicitly so no system Java selection is assumed:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
```

## Repository-local validation

Run these commands from the repository root:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug assembleDebug
cd ..
```

The generated APK is:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

To build, install and cold-launch Android `0.22.1` on the single authorized device while preserving
the saved vehicle and server profiles:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
bash scripts/install-android-0.22.1.sh
```

Set `COMPASS_ADB_SERIAL` only if more than one device is connected. This installer intentionally
does not use UIAutomator, inspect screenshots or clear application data.

## Backend and map configuration

The debug build defaults to the emulator host alias:

```text
COMPASS_API_BASE_URL=http://10.0.2.2:8000/
```

This value is only the first-launch default. If no authenticated profile exists, Compass opens the
server form automatically. Enter the externally reachable endpoint and the backend's
`API_AUTH_USERNAME`/`API_AUTH_PASSWORD`, then choose `Salva e connetti`. The same form opens after a
connection or authentication error. The endpoint may include a reverse-proxy path and its trailing
slash is added automatically. Credentials embedded in the URL, query strings and fragments are
rejected.

Override it at build time; the trailing slash is mandatory:

```bash
cd android
./gradlew --no-daemon \
-PCOMPASS_API_BASE_URL=https://compass.example.test/ \
assembleDebug
cd ..
```

The app defaults to the bundled `asset://compass-day.json` and `asset://compass-night.json` styles.
They reference keyless OpenFreeMap/OpenMapTiles data and fonts. A deployment can replace either
complete style independently:

```bash
cd android
./gradlew --no-daemon \
-PCOMPASS_MAP_DAY_STYLE_URL=https://maps.example.test/day.json \
-PCOMPASS_MAP_NIGHT_STYLE_URL=https://maps.example.test/night.json \
assembleDebug
cd ..
```

For backwards compatibility, `COMPASS_MAP_STYLE_URL` still overrides both modes when neither
mode-specific property is present. Use it only when one external style deliberately serves both
themes. An operator self-hosting OpenFreeMap must ensure the custom style's tile, font and sprite
URLs also point at the intended host; Compass treats the supplied document as authoritative.

MapLibre caches resources visited during normal use. Its size defaults to 100 MiB and can be changed
at build time (16–1,024 MiB):

```bash
cd android
./gradlew --no-daemon -PCOMPASS_MAP_AMBIENT_CACHE_MB=200 assembleDebug
cd ..
```

No token is committed. If a chosen style requires credentials, inject a protected style URL using
the operator's normal secret/configuration mechanism and do not add it to Git.

Use HTTPS for any LAN/public hostname or IP. Android permits dynamic HTTP endpoints only because the
runtime `Server` form blocks saving them until the user accepts the visible cleartext-credential
warning. A physical-device gate can retain the backend's safe loopback-only bind by using `adb
reverse`, which the checked-in runner configures automatically.

## Phase 9 physical-device live gate

The runner must execute on the machine that has the repository, Android SDK, one authorized Android
device and access to the backend. The simplest setup is a device attached to the test server. If the
device is attached to another workstation, first open a local SSH tunnel from that workstation to
the server's loopback API in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

On the device/build machine, export the toolchain and run the single handoff command:

```bash
cd /path/to/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase9-live.sh
```

When multiple devices are connected, select one before starting:

```bash
export COMPASS_ADB_SERIAL=DEVICE_SERIAL
```

The script performs eight separate gates:

1. backend readiness;
2. a real full-Italy ranked-candidate request with an offset-aware departure instant;
3. top-ranked official-ID selection and independent two-leg route validation;
4. device authorization and `adb reverse` when using loopback;
5. unit tests, lint and APK assembly with the selected API URL;
6. APK installation;
7. application launch with Android's `Status: ok` invariant;
8. an immediate fatal-exception check.

Automated completion is not by itself a live acceptance result. On the device follow the five short
items printed by the runner: open the Metano form, run the ranked search, inspect the required card
fields, select a station and exercise change/remove plus lifecycle behavior. Return the complete
script output and screenshots of both the candidate list and selected-stop route. The initial gate
was accepted on 2026-08-29; this procedure remains the reproducible regression check.

The accepted historical Phase 8 preview-only gate remains reproducible with
`bash scripts/run-phase8-live.sh`; the accepted Phase 9 regression uses
`bash scripts/run-phase9-live.sh`. New acceptance runs should use the Phase 10 procedure below.

## Phase 10 predictive physical-device live gate

Run this on the workstation attached to the Android device. If the backend is on a different test
server, first open the tunnel in its own terminal and leave that terminal running:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

Then open a second terminal and run these commands from the repository root:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
```

```bash
bash scripts/run-phase10-live.sh
```

The runner prints the tunnel reminder before its first request. It first checks live OpenAPI so an
old API image is rejected before the longer route scenarios. It then validates standard, not-needed,
no-reachable and mandatory 65/30/100 multi-stop profiles. It recalculates one route through all
ordered official MIMIT stops and rejects any actual Valhalla leg that consumes reserve. Finally it
runs Android tests/lint/assembly, installs and cold-launches the APK, and checks for an immediate
fatal exception. All request bodies are generated as named JSON artifacts; no inline JSON needs to
be copied into the shell.

The automated gate is not the device acceptance. The runner prints complete scenario instructions,
including the exact meaning of each input and expected screen. Return the complete output plus its
four requested screenshots: 65/30/100 multi-stop plan, selected multi-stop route, not-needed state
and no-reachable safety state.

## Phase 11 editable-route physical-device live gate

Run this on the workstation attached to the Android device. If the backend is on a different test
server, open the tunnel in a separate terminal and leave it running:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

Then run from the repository root:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-phase11-live.sh
```

The runner avoids inline JSON. It writes a non-default Rome-to-Florence route request to
`/tmp/compass-phase11-custom-route-request.json`, validates the live route response, builds and
installs Android `0.4.0`, cold-launches the app and checks for an immediate fatal exception.

Manual acceptance must prove that endpoint editing drives the rest of the planner:

1. default Milan-to-Bologna preview still renders;
2. `Modifica percorso` accepts Rome `41.9028, 12.4964` and Florence `43.7696, 11.2558`;
3. manual Metano search and selected-stop routing stay on the edited route;
4. predictive CNG evaluation stays on the edited route and uses generic destination labels, not
   fixed Milan/Bologna copy;
5. destination longitude `200` is rejected on the coordinate form without crash.

Return the complete runner output and the four screenshots requested by the runner.

## Diagnostics

If no authorized device is found:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" devices -l
```

If the app reports that Compass is unreachable, confirm the host API and reverse mapping:

```bash
curl --fail --silent --show-error http://127.0.0.1:8000/health/ready
"$ANDROID_SDK_ROOT/platform-tools/adb" reverse --list
```

If the API preflight fails, inspect the unambiguous saved artifacts before rerunning anything:

```bash
python3 -m json.tool /tmp/compass-phase9-ranked-request.json
python3 -m json.tool /tmp/compass-phase9-ranked-response.json
python3 -m json.tool /tmp/compass-phase9-selected-request.json
python3 -m json.tool /tmp/compass-phase9-selected-response.json
```

For Phase 10 inspect each request and response independently:

```bash
python3 -m json.tool /tmp/compass-phase10-standard-request.json
python3 -m json.tool /tmp/compass-phase10-standard-response.json
```

```bash
python3 -m json.tool /tmp/compass-phase10-not-needed-response.json
python3 -m json.tool /tmp/compass-phase10-unreachable-response.json
```

```bash
python3 -m json.tool /tmp/compass-phase10-multi-stop-response.json
python3 -m json.tool /tmp/compass-phase10-itinerary-route-response.json
```

For Phase 11 inspect the generated request and response:

```bash
python3 -m json.tool /tmp/compass-phase11-custom-route-request.json
python3 -m json.tool /tmp/compass-phase11-custom-route-response.json
```

Return the failing artifact plus bounded service logs:

```bash
docker compose --profile routing logs --no-color --tail=200 api valhalla db
```

If install or launch fails, collect package/activity state and recent logs:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" shell pm list packages | grep org.compass.cng
"$ANDROID_SDK_ROOT/platform-tools/adb" shell dumpsys activity activities | grep org.compass.cng
"$ANDROID_SDK_ROOT/platform-tools/adb" logcat -d -t 300
```

If the route data appears but the basemap does not, verify that the device can reach the configured
map-style URL and return the last diagnostic command's output. Map-style availability is independent
from the Compass API and is not treated as backend route failure.
