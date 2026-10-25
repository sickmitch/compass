# Android 0.28.45 — Diagnostic traffic minutes

Preview displays the backend's traffic_delay_seconds as minutes only with fresh/mock
data and a successful measurement. Zero is “0 min”; positive values below a minute
are “<1 min”; other values round to the nearest minute. Stale/missing data and failed
probes show “Non live”. An older backend that sends only traffic_detected cannot
provide minutes and also shows “Non live”.

The backend preserves the diagnostic live-versus-baseline difference in the existing
traffic_delay_seconds field, including a measured zero. Missing measurements remain
null. traffic_aware remains false in production alert-only mode. Geometry, driving
duration, manoeuvre timings, dwell and arrival calculations remain baseline values.
The probe still compares independently routed Valhalla results; it is an estimate,
not a measured queue delay on every edge of the selected route.

The trip summary removes the driving-time column (keeps distance and total) and uses
the pastel-orange message “Traffico rilevato ma non conteggiabile nell'ETA”. Walking
and cycling keep their existing duration columns. The navigation badge reads “~ETA”.

## Operator handoff

After synchronizing the repository, on the server in ~/docker/compass:

```sh
docker compose build api
docker compose up -d --no-deps api
```

Existing .env settings and credentials must remain available. No database migration
or traffic-updater rebuild is required. Install dist/compass-0.28.45.apk on Android.
The build also updates dist/last.txt; publish both together for client updates.

Calculate a normal driving route from the app (normal on-demand traffic calls apply).
Check the preview minutes, orange warning only with detected traffic, absence of
the Guida column and the ~ETA label. A fresh zero must show 0 min; stale/unavailable
data must show Non live. ETA/duration must exclude the displayed traffic delay.

If results differ, return screenshots, the navigation response fields
traffic_delay_seconds, traffic_delay_state, traffic_detected, traffic_state,
traffic_aware and duration_seconds, and collect:

```sh
docker compose logs --since 10m --tail 200 api traffic-updater
```

Local tests use fixtures only; no paid provider requests or device tests were run.

Validation: 285 Android unit tests and assembleDebug passed; APK signature verified.
The Python non-integration suite passed (456 tests, 5 deselected), followed by the
expanded traffic probe tests (16 passed) and API/OpenAPI tests (9 passed).
Ruff for changed Python files and git diff --check passed.
