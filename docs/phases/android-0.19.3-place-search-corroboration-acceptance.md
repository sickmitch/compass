# Android 0.19.3 place-search corroboration acceptance

## Scope

This increment adds optional Google Places API (New) corroboration to the existing server-side
Nominatim search. It changes neither the map provider nor routing. Google data is request-local and
Nominatim remains the only mobile-visible source.

Acceptance requires all of these invariants:

- `nominatim_google` cannot start without a non-empty server-side Google key;
- Text Search (New) uses POST, an explicit field mask and structured address components;
- the query UI explains `Via Cappafredda, 12, Roverchiara` and the keyboard Search action submits;
- only a comma-separated civic after `Via` is treated as the requested house number;
- exact civics, compatible street/locality and points within the configured radius corroborate an
  address; explicit conflicting civics are never returned;
- POIs are ordered by query coherence and compatible cross-provider name/proximity;
- Google records, identifiers and coordinates never cross the API boundary or enter Android cache;
- hybrid responses have `cacheable=false` and Android honors it;
- Google failure retains Nominatim search and logs no secret or upstream payload.

## Repository-local evidence

Run from the repository root:

```bash
.venv/bin/ruff check src tests
API_AUTH_ENABLED=false .venv/bin/pytest -q \
  tests/test_search.py tests/test_config.py tests/test_phase7_api.py
JAVA_HOME=/home/mike/toolchains/jdk17 ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
  android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug
PYTHONPATH=src .venv/bin/python scripts/export-openapi.py --check
```

## Operator live gate

Configure and recreate the API as documented in
[`deployment.md`](../deployment.md#optional-google-places-new-corroboration). Return both JSON
responses and the bounded API log. On Android, open `Crea viaggio`, choose `Ricerca`, verify the
address-format helper, search the same civic and confirm the selected point sits on the correct
part of the road. Then search a known POI and confirm the intended place ranks above similarly named
alternatives. Google Cloud screenshots and the key are never required.
