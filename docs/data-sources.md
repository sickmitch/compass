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

There is no universal cross-source identifier. Phase 1 defines only separate source identities and
provenance. Phase 2 will add deterministic matching where possible, confidence/method version,
manual overrides, and explicit unmatched/ambiguous states. Low-confidence fuzzy joins will never be
created silently.
