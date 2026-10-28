# Development history — condensed

This summary replaces the phase-by-phase narrative in the main guides. It describes
milestones, not a new acceptance claim. Detailed scenario checklists and evidence remain
in [historical gates](phases/) and Git; ADRs retain the rationale for architectural choices.

| Milestone | Result retained in the current system |
| --- | --- |
| Foundation and ingestion (0–2) | Docker/FastAPI/PostGIS, migrations, MIMIT/OSM acquisition, immutable provenance, idempotency and deterministic reconciliation. |
| Routing and station eligibility (3–7) | Valhalla adapter, spatial corridor pruning, road-network detours, ETA-aware opening hours, explainable ranking and strict versioned API. |
| Android and CNG planning (8–11) | Native map client, manual refuelling selection, reserve-aware predictive chains, vehicle profiles and editable endpoints. |
| Search and recovery (12–13) | Destination search, journey dwell, rerouting with remaining stops, saved-route/cache recovery and explicit degraded operation. |
| Navigation UI iterations | Guidance, road context, speed limits, stop lifecycle, camera/follow controls and route-free startup. |
| Android 0.27–0.28 | Ordinary waypoints, favourites, highway choices, walking/cycling costing, map callouts, smoother puck/progress, PiP and automatic updates. |
| Android 0.28.46 | Nominal Valhalla route timing, green ETA/progress and removal of the former dynamic-speed pipeline. |

The phase 12/13 records report operator acceptance on 2026-09-03. Navigation UI 14
was advanced without returned device evidence; UI 15 records a physical gate on
2026-09-11. These historical results do not certify later versions. The automatic-update
installer cycle and the current nominal-routing deployment/device checklist require
operator evidence; do not infer their success from local tests.

## How to use old material

- Use README, architecture, Android, deployment and API guides for current behavior.
- Use `docs/phases` only for a targeted regression scenario or historical evidence.
  Version numbers, test counts, commands and prerequisites there describe that iteration.
- Phase runners are optional operator diagnostics; they are not the release/update path.
- Retired standalone Android installation scripts are no longer shipped. Use the
  [current update workflow](client-updates.md).
- Recover removed historical text with `git log -- <path>` and `git show <commit>:<path>`.

The [current release gate](phases/android-0.28.46-nominal-routing-gate.md) remains separate
so pending live acceptance is not lost during documentation cleanup.
