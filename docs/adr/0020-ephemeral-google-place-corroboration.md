# ADR 0020: ephemeral Google Places corroboration with Nominatim output

Status: proposed pending live API and Android acceptance.

## Context

Compass needs better POI coherence and especially reliable association between an Italian civic
number and a precise point along the requested street. A single geocoder can return plausible but
wrong-number candidates. The client renders MapLibre, and its device cache currently persists a
small set of recent place searches.

Google Places policy requires Places results displayed on a map to use a Google Map and restricts
caching of Places content. Returning Google coordinates to the current Android/MapLibre flow or
placing Google records in the existing cache is therefore outside this architecture.

## Decision

Nominatim remains the only result source visible through Compass. An optional server-side Google
Places API (New) Text Search adapter runs concurrently as an ephemeral corroborator. The API key is
read only from server configuration.

POIs are ranked using query-token/sequence coherence plus compatible Google name and geographic
proximity. Address ranking additionally uses structured street, locality and civic components. A
civic is parsed only from a complete comma-separated numeric segment following a `Via` segment,
for example `Via Cappafredda, 12/A, Roverchiara`. Explicit civic conflicts are removed. Google
corroboration requires exact normalized civic equality and a configurable proximity bound, 75 m by
default.

Google payloads and identifiers are discarded inside the provider. They are not returned, logged
or persisted. The public response contains `cacheable=false`, and Android honors it. Google failure
degrades to locally query-ranked Nominatim results and emits only an error class in structured logs.

## Consequences

- Civic intent is unambiguous and road identifiers such as `SS 434` are not treated as house
  numbers.
- A known wrong civic is omitted rather than presented as a precise address.
- Google cannot silently replace the coordinates or attribution shown by Compass.
- Hybrid requests incur Google Text Search billing; structured address fields may select a higher
  field tier.
- Google can improve confidence/order only when Nominatim has a compatible candidate. It cannot
  supply a missing MapLibre-visible address under this decision.
- Operators remain responsible for current Google terms, key restrictions, billing and quota.
