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
