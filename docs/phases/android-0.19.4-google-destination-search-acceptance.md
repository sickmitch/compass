# Android 0.19.4 Google destination-search acceptance

Status: accepted on the live backend and a physical Android device on 2026-09-07.

## Scope and gate

Android `0.19.4` and the Compass API replace the active destination-search path with Google Places
API (New) Autocomplete plus selected-place Details. Google is the only destination provider for
this gate. MapLibre, Valhalla, CNG stops, vehicle constraints, traffic and the preview/Start flow are
unchanged.

The gate is not ready until the operator verifies the Google Cloud billing-account address and sets
`GOOGLE_PLACES_CONTRACT_REGIME=eea`. This repository deliberately refuses to enable the integration
when the value is `unverified` or `non_eea`. That check records the applicable configuration; it is
not a general legal conclusion.

Acceptance requires all of these invariants:

- suggestions appear on a screen with no map, after 300 ms and at least three significant
  characters; IME composition does not submit provisional text;
- Google order is retained, exact repeated Place IDs are removed, and different branches/civics
  remain separate;
- no Details request occurs until a suggestion is selected; double taps converge on one resolution;
- the selected Place ID returns the displayed full address and WGS84 coordinates;
- the route request uses those coordinates directly and never invokes legacy text/reverse geocoding;
- Google name/address does not appear on MapLibre, map markers, guidance, route summary or
  navigation notification; the map label is `Destinazione selezionata`;
- no Google result payload is persisted or inserted in the CNG catalog;
- Google 403, 429, timeout and not-found failures do not fall back to TomTom/Nominatim;
- `/api/v1/destinations/metrics` reports `tomtom_destination_calls=0` while configured TomTom
  traffic remains independent.

## Local evidence

Run from the repository root:

```bash
.venv/bin/ruff check src tests
.venv/bin/pytest -q
PYTHONPATH=src .venv/bin/python scripts/export-openapi.py --check
JAVA_HOME=/home/mike/toolchains/jdk17 ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
  android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug
```

All Google tests use synthetic fixtures and simulated HTTP transports; they require no key and spend
no quota.

## Live preparation

On the server, from the synchronized repository directory, make sure `.env` contains:

```dotenv
DESTINATION_SEARCH_PROVIDERS=google_places_new
GOOGLE_PLACES_ENABLED=true
GOOGLE_PLACES_API_KEY=<server-side secret>
GOOGLE_PLACES_CONTRACT_REGIME=eea
TOMTOM_SEARCH_ENABLED=false
DESTINATION_SEARCH_FALLBACK_ENABLED=false
GOOGLE_PLACES_TEXT_SEARCH_ENABLED=false
GEOCODING_PROVIDER=none
```

Do not print the key. Places API (New), billing and compatible server-side key restrictions must be
active in Google Cloud. Then run:

```bash
cd ~/docker/compass
docker compose config --quiet
docker compose build api
docker compose up -d --force-recreate api
docker compose exec -T api python -c 'from compass.config import get_settings; s=get_settings(); print({"providers": s.destination_search_providers, "google_enabled": s.google_places_enabled, "contract_regime": s.google_places_contract_regime, "tomtom_search": s.tomtom_search_enabled, "destination_fallback": s.destination_search_fallback_enabled, "text_search": s.google_places_text_search_enabled, "traffic_provider": s.traffic_provider, "key_configured": bool(s.google_places_api_key.get_secret_value())})'
docker compose ps api
```

On the Android workstation:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
bash scripts/run-android-0.19.4-google-destination-live.sh
```

## Manual cases

For each query, inspect Google Cloud request metrics and Compass aggregate metrics; do not assume a
specific Google result in advance.

1. A known local activity: select it, verify the full address, calculate a preview, then confirm the
   map uses only the neutral destination label.
2. A chain with multiple branches: verify branch subtitles distinguish locations and selecting one
   resolves that same branch.
3. A slightly misspelled activity name: observe ranking without claiming a guaranteed correction.
4. A name plus a distant Italian city: verify it can rank despite the current-position bias.
5. `Via Cappafredda, 12, Roverchiara`: verify the selected Details address contains the intended
   civic and the routed point is plausible; do not infer entrance accuracy from the address alone.
6. A nonsense query: verify the empty state is distinct from an outage.
7. Disable location permission: verify search still works without a fabricated distance or origin.
8. Double-tap one result: verify one destination application and one Details count.
9. Preserve a CNG/vehicle plan while changing destination and verify normal preview/Start behavior.

Return the complete runner output, the two metrics snapshots, and a short pass/fail note for every
manual case. Screenshots are optional. Do not proceed to a multiprovider phase until this gate is
accepted.

## Accepted live evidence

The operator ran `scripts/run-android-0.19.4-google-destination-live.sh` against the synchronized
live backend and a physical device on 2026-09-07. The Android destination-search unit tests and
debug APK build completed successfully, installation succeeded, and Compass cold-launched in the
foreground. The operator confirmed all applicable manual cases (1 through 8).

Case 9 is not exposed by the current mobile interaction model: an active CNG/vehicle plan has no
affordance for changing its destination while preserving that plan. The operator explicitly waived
this live case on 2026-09-07. It is therefore recorded as **not applicable**, not as a successful
on-device test. Automated ViewModel coverage verifies that Google destination selection and the
normal route transition preserve the configured effective range, reserve and maximum-detour
inputs; adding an active-plan destination-editing flow is a separate future UX increment.

The final aggregate metrics were:

```json
{
  "sessions_started": 1,
  "sessions_concluded": 1,
  "sessions_abandoned": 0,
  "suggest_requests": 11,
  "resolve_requests": 1,
  "suggest_latency_ms_total": 996,
  "resolve_latency_ms_total": 92,
  "resolutions_succeeded": 1,
  "provider_errors": 0,
  "rate_limited": 0,
  "tomtom_destination_calls": 0
}
```

The runner reported `google_destination_gate_metrics=ok`. This accepts the Google-only destination
phase with the explicit case-9 waiver; it does not authorize the later multiprovider increment.

## If it fails

Run on the server and return the bounded output (never the key or response payloads):

```bash
cd ~/docker/compass
docker compose ps api
docker compose logs --since=15m --no-color api | tail -n 250
curl --fail --silent --show-error --user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD" \
  https://compass.sickmitch.cc/health/live
curl --fail --silent --show-error --user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD" \
  https://compass.sickmitch.cc/api/v1/destinations/metrics | jq
```
