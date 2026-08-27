# ADR 0002: Source authority and reconciliation boundary

- Status: Accepted
- Date: 2026-08-26

## Context

MIMIT and OSM overlap but have different identifiers, quality characteristics and licensing. Silent
field replacement or speculative fuzzy joins would make provenance and errors hard to explain.

## Decision

Treat MIMIT as authoritative for active station identity and reported CNG unit price. Treat OSM as
complementary enrichment. Retain exact source snapshots, original records, source IDs and timestamps
in separate raw tables.

Phase 1 performs no cross-source match. Phase 2 will introduce normalized stations, spatial types and
explicit reconciliation records with method/version, confidence, manual override and unmatched or
ambiguous outcomes.

## Consequences

Raw imports can succeed independently. An Overpass outage cannot erase valid MIMIT state. Downstream
consumers can identify provenance and price freshness. Storage is larger because exact source payloads
are retained, but imports remain reproducible and auditable.

