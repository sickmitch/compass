# Data sources, provenance and attribution

## MIMIT / Osservaprezzi Carburanti

MIMIT is authoritative in Compass for active station identity (`idImpianto`) and reported fuel
prices. The official dataset page states that the two downloads are refreshed daily and are licensed
under IODL 2.0:

- Dataset page: <https://www.mimit.gov.it/it/open-data/elenco-dataset/carburanti-prezzi-praticati-e-anagrafica-degli-impianti>
- Active stations: <https://www.mimit.gov.it/images/exportCSV/anagrafica_impianti_attivi.csv>
- Prices at 08:00: <https://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv>
- IODL 2.0: <https://www.dati.gov.it/content/italian-open-data-license-v20>

MIMIT changed the current files to pipe (`|`) delimiters on 10 February 2026. The parser supports
that format and retains semicolon support for historical fixtures. The exact payload, extraction time,
download time and URL are retained. CNG/metano prices are modeled as EUR/kg. LNG/GNL is explicitly
excluded from the CNG filter.

The active-station and price extraction dates must match when both headers supply a date. This avoids
silently combining two daily snapshots during a publication rollover; the operator can safely retry.

Coordinates are voluntarily supplied to MIMIT and may be missing or inaccurate; later validation
must not silently improve them with OSM values without provenance.

## OpenStreetMap / Overpass

OSM is a complementary enrichment source for `opening_hours`, phone, brand/operator and related map
attributes. Phase 1 requests `amenity=fuel` plus `fuel:cng=yes` and stores node, way or relation
identity separately from MIMIT IDs.

- OpenStreetMap copyright and attribution: <https://www.openstreetmap.org/copyright>
- ODbL 1.0: <https://opendatacommons.org/licenses/odbl/1-0/>
- Overpass API: <https://wiki.openstreetmap.org/wiki/Overpass_API>

The default public Overpass instance is configurable. Operators must respect instance resource and
usage policies, use an identifying `HTTP_USER_AGENT`, and may point `OVERPASS_URL` to a self-hosted or
alternate compliant instance. A failed OSM import does not delete or invalidate MIMIT data.

## Google Places API (New) search corroboration

ADR 0021 supersedes this corroboration design for the active destination selector. The old Text
Search adapter remains packaged but is disabled with `GEOCODING_PROVIDER=none`. The dedicated
Search Along Route Text Search operation can be enabled independently with
`DESTINATION_ALONG_ROUTE_ENABLED=true`; it is reachable only from ordinary-stop insertion and does
not reactivate legacy generic search.

## Google Places API (New) destination selection

Compass uses Autocomplete (New) as transient input assistance and Place Details (New) only for the
Place ID explicitly selected by the user. No Google business catalog is stored, no response is
written to the CNG tables, and Android does not persist predictions, names or addresses. Exact
provider/Place-ID duplicates retain the first Google rank; different IDs remain distinct.

The server key is restricted to environment/secrets. The EEA profile separates mapless Google text
selection from MapLibre: only coordinates, Place ID and a neutral Compass label cross into map and
navigation models. The operator must verify the Cloud billing-account address and set
`GOOGLE_PLACES_CONTRACT_REGIME=eea`; an unverified or non-EEA profile cannot enable this boundary.

- Autocomplete (New): <https://developers.google.com/maps/documentation/places/web-service/place-autocomplete>
- Place Details (New): <https://developers.google.com/maps/documentation/places/web-service/place-details>
- Places policy: <https://developers.google.com/maps/documentation/places/web-service/policies>

### Historical corroboration design

Google Places is an optional, runtime-only corroboration source for place search. It is disabled by
default and configured with `GEOCODING_PROVIDER=nominatim_google`. Compass requests Text Search
(New) structured address components, name and location, then compares them with Nominatim
candidates. It never publishes, persists or logs Google content, coordinates, identifiers or the
API key. Mobile-visible search results remain attributable Nominatim records and hybrid ordering is
explicitly non-cacheable.

This boundary exists because Compass renders MapLibre, while Google requires Places results shown
on a map to be shown on a Google Map and restricts caching of Places content. Operators must enable
and bill the Places API (New), restrict the server key to the API and the required Google service,
and review current Google terms before deployment. Requesting `addressComponents` affects the Text
Search field tier and therefore cost.

- Places API policies: <https://developers.google.com/maps/documentation/places/web-service/policies>
- Text Search (New): <https://developers.google.com/maps/documentation/places/web-service/text-search>
- Place data fields: <https://developers.google.com/maps/documentation/places/web-service/data-fields>

## Reconciliation boundary

There is no universal cross-source identifier. Compass keeps MIMIT and OSM identities and fields
separate, then evaluates candidates using PostGIS geography distance and normalized name similarity.
The versioned `mimit-osm-distance-name-v1` policy defaults to:

- candidate search within 250 metres;
- automatic proximity eligibility within 50 metres;
- eligibility from 50–150 metres only when name similarity is at least 0.75;
- ambiguity when the two best eligible scores differ by less than 0.08.

All values are environment-configurable, validated for consistency, stored with each run and covered
by deterministic fixtures. A candidate outside the eligibility rules stays unmatched even when it is
inside the audit/search radius. Similar top candidates stay ambiguous. Candidate rank, distance,
name similarity, score and eligibility remain inspectable; low-confidence joins are never silently
created.

Manual `link` and `unmatch` overrides include an operator, reason and stable source identities. They
participate in the reconciliation configuration hash, so applying an override creates a new result
run. A manual target absent from the latest OSM snapshot becomes explicitly unmatched rather than
silently falling back to an automatic link.

## Valhalla routing graph input

Phase 3 uses OpenStreetMap extracts from Geofabrik as the default Valhalla graph input:

- Italy extract: <https://download.geofabrik.de/europe/italy.html>
- Geofabrik download server: <https://download.geofabrik.de/>

`VALHALLA_TILE_URLS` is configurable for regional extracts or an operator-hosted PBF. A URL ending in
`latest` changes as OSM is updated; reproducibility of an exact historical graph requires retaining
the downloaded PBF/hash or using an immutable operator-controlled URL. The named Valhalla volume
retains build inputs and metadata separately from application images.

Routing graph data derives from OSM and remains subject to ODbL attribution obligations. The graph
is not treated as a source of live traffic. Phase 3 exposes provider identity but makes no traffic
freshness or traffic-aware routing claim.

## Opening-hours interpretation data and software

Phase 6 interprets only the OSM `opening_hours` expression retained on an accepted OSM enrichment
link. It does not invent hours for missing data. Evaluation uses the open-source
[`opening-hours-py`](https://pypi.org/project/opening-hours-py/) parser, pinned to 2.1.4, with the
station ETA converted to the `Europe/Rome` IANA timezone and Italy supplied as the country context.
The parser is licensed under MIT or Apache-2.0. Timezone rules are supplied by the Python runtime or
the pinned `tzdata` 2026.3 fallback package (Apache-2.0).

Parser warnings and the original expression remain visible in API results. Missing syntax,
unparseable syntax and a valid expression whose state is unknown are separate cases. OSM remains the
source and retains its ODbL attribution requirements; parser output does not change source ownership
or authoritative MIMIT station/price fields.

## Public freshness semantics

`GET /api/v1/data-freshness` reports freshness separately for `mimit_cng`, `osm_cng` and the latest
completed reconciliation. MIMIT and OSM age use the source observation timestamp when present;
reconciliation age uses its completion timestamp. The API also exposes the evaluation instant and
configured threshold, so `fresh`, `stale`, `future_observation` and `missing` are reproducible rather
than opaque labels.

The defaults are 48 hours for MIMIT, 168 hours for OSM and 48 hours for reconciliation. They are
operational policy and can be changed with `MIMIT_DATA_FRESHNESS_HOURS`,
`OSM_DATA_FRESHNESS_HOURS` and `RECONCILIATION_DATA_FRESHNESS_HOURS`. A stale source degrades the
reported data state but does not conceal or delete the last successfully normalized records. Missing
required MIMIT or reconciliation data prevents readiness. Missing/stale OSM affects optional
enrichment and does not invalidate authoritative MIMIT station identity or prices.

Traffic is a separate provider domain. Its default state remains explicitly `not_configured`, and
Valhalla graph speeds are not presented as live traffic. The live-traffic architecture is documented
in `docs/traffic.md`; production traffic must enter through the provider-independent traffic
subsystem and Valhalla's native traffic overlay.
