# ADR 0004: Deterministic MIMIT–OSM reconciliation

- Status: Accepted
- Date: 2026-08-27

## Context

MIMIT is authoritative for Italian station identity and reported prices. OSM supplies useful
geospatial enrichment but has no shared identifier with MIMIT, and both sources can contain missing,
inaccurate or duplicate-looking records. Silently merging fields would lose provenance and allow
uncertain matches to appear authoritative.

## Decision

Normalized `stations` remain anchored to MIMIT `idImpianto`. OSM features retain stable element type
and ID in a separate model. Both use nullable PostGIS `geography(Point, 4326)` locations with GiST
indexes; invalid or missing Italian coordinates stay null while raw values remain retained.

The versioned reconciliation algorithm first uses `ST_DWithin` to find a bounded candidate set, then
ranks candidates deterministically by geography distance and normalized name similarity. Policy
thresholds are configuration, not hidden constants. A result is always `matched`, `ambiguous`, or
`unmatched`, with candidate evidence and a reason. The accepted current link has uniqueness on both
station and OSM feature identity.

Manual link/unmatch decisions store operator and reason. Effective overrides and policy values are
hashed with the two source-run identities. Therefore identical work is reused, while a policy or
override change produces a new auditable reconciliation run.

Price history uses semantic observation identity rather than raw-snapshot identity. A separate table
points to the latest price for each active station/fuel/service mode.

## Consequences

- OSM enrichment cannot overwrite official station fields.
- Close conflicts and weak candidates require review instead of becoming silent joins.
- Matching outcomes are explainable, reproducible and fixture-testable.
- Candidate search uses spatial indexes and remains bounded as source volumes grow.
- Address geocoding and more advanced matching are deferred until measured evidence justifies a new
  versioned algorithm.
