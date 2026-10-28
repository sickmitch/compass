# Architecture

## Responsibilities

| Component | Owns |
| --- | --- |
| Source adapters and ETL | MIMIT/OSM acquisition, immutable payloads, parsing, timestamps and restartable imports |
| PostgreSQL/PostGIS | Station geography, spatial indexes, price observations, reconciliation and provenance |
| Routing adapter | Valhalla HTTP requests, costing options, units, geometry validation and error translation |
| CNG domain services | Corridor pruning, road-network detours, reserve reachability, opening status and explainable ranking |
| FastAPI | Strict versioned request/response contracts, authentication and dependency health |
| Android | Interaction, saved profiles/routes, GPS guidance, presentation and update consent |

The backend returns domain data rather than widget placement or colors. Provider-specific
responses do not cross the mobile API boundary. See [API](api.md), [ADRs](adr/) and
[data-source rules](data-sources.md) for contracts and rationale.

## Data ingestion

MIMIT supplies authoritative station identity and official price observations. OSM
supplies complementary metadata such as opening hours and contact information. Imports
preserve original payloads and separate source timestamps from ingestion time.
Normalization uses deterministic, versioned reconciliation and explicit matched,
ambiguous and unmatched outcomes. Manual overrides are auditable; OSM enrichment does
not silently overwrite MIMIT source fields. Missing geography remains explicit.

Price observations are deduplicated independently of current-price pointers. Repeated
inputs reuse ingestion/reconciliation results where the policy and inputs are unchanged.
Source acquisition failures must not erase previously valid data from another source.

## Route and CNG pipeline

```mermaid
flowchart TD
    Android --> API[FastAPI versioned API]
    API --> Router[Valhalla adapter]
    Router --> Base[Nominal route geometry and duration]
    Base --> Corridor[Autonomy-based spatial corridor]
    DB[PostGIS station inventory] --> Corridor
    Corridor --> Prune[Bounded candidate pruning]
    Prune --> Costs[Road-network matrix and detour costs]
    Router --> Costs
    Costs --> Rank[Arrival opening hours and explainable ranking]
    Rank --> Select[Selected stops or predictive chain]
    Select --> Router
    Base --> Android
    Rank --> Android
```

The corridor defaults to 20% of effective CNG range, with configurable caps. Straight-line
metrics only prune candidates. Eligibility and displayed station distance use road costs;
detour is candidate travel time minus base travel time. Predictive planning additionally
validates remaining fuel/reserve and the complete refuelling chain. A full-refill
assumption and any gasoline fallback remain explicit domain policy.

Valhalla supplies nominal automotive durations using constrained/freeflow graph speeds;
pedestrian and bicycle requests use dedicated costing. Highway exclusions are enforced
by the routing adapter/runtime configuration. Departure timestamps anchor arrival and
opening-hours calculations. Planned dwell is separate from driving duration and advances
subsequent arrivals. Unknown opening hours are never treated as known open.

## Navigation and recovery

Android converts the server route into guidance state, filters GPS fixes, matches progress
to the route and updates remaining duration locally. Ordinary and fuel stops retain
explicit lifecycle state. Manual recalculation, deviations and connectivity recovery may
request a new route while preserving relevant remaining stops and destination.

The foreground session supports voice and Picture in Picture. Cached route geometry and
maneuvers can support an existing journey during an outage; new routing requires the
backend. MapLibre ambient caching only covers previously visited resources. Saved-route
format validation is independent of optional retired metadata.

Destination search is server-side and provider-configured. Search credentials never
belong in the mobile app. Provider attribution and storage constraints remain distinct
from routing geometry. Android's updater uses a separate download client without API
authentication; see [client updates](client-updates.md).

## Runtime and verification

Compose separates the API, PostgreSQL/PostGIS, Valhalla and one-shot migration/import/
graph-building jobs. Graph and database storage persist across service recreation.
The API is the public boundary; the operator owns HTTPS ingress and secrets. Liveness,
readiness and source freshness serve different purposes.

Fixture/unit tests validate local contracts. Database integration, external-provider
behavior and physical navigation require their own environments and evidence. Current
operational instructions are in [deployment](deployment.md) and [Android](android.md);
[history](history.md) summarizes older milestones without treating them as current proof.
