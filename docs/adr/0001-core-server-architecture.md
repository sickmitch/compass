# ADR 0001: Core server architecture

- Status: Accepted
- Date: 2026-08-26

## Context

Compass needs self-hosted, time-dependent routing with maneuvers and future traffic inputs, indexed
spatial candidate pruning, and a strict mobile-facing API.

## Decision

Use Valhalla for routing, PostgreSQL 16 with PostGIS 3.5 for spatial/source persistence, Python 3.12
with FastAPI for the API and domain services, Alembic for migrations, and Docker Compose as the
reference server deployment.

Valhalla traffic support consumes external traffic data; it is not treated as a traffic provider.
Traffic integration will use a provider boundary with no-traffic and deterministic fixture modes.

## Consequences

Route-network distances, detours, ETA and maneuvers will come from Valhalla. PostGIS will spatially
prune before expensive routing calls. Server workloads remain containerized and one-shot jobs remain
separate from persistent services. Phase 3, not Phase 0/1, owns Valhalla tiles and runtime integration.

