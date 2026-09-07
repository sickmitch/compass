import asyncio
import json

import httpx

from compass.api.main import app
from compass.config import Settings, get_api_settings
from compass.routing.domain import Coordinate
from compass.search.corroboration import (
    CorroboratedPlaceSearchProvider,
    extract_query_house_number,
    rank_results_for_query,
)
from compass.search.dependencies import get_place_search_provider
from compass.search.domain import (
    PlaceSearchProviderError,
    PlaceSearchRequest,
    PlaceSearchResult,
    PlaceSearchUnavailableError,
)
from compass.search.google_places import (
    GOOGLE_PLACES_TEXT_SEARCH_FIELD_MASK,
    GooglePlacesNewSearchProvider,
)
from compass.search.nominatim import NominatimPlaceSearchProvider
from compass.search.service import parse_coordinate_query, search_places


def test_coordinate_query_is_normalized_without_calling_provider() -> None:
    class UnexpectedProvider:
        async def search(self, _request):
            raise AssertionError("coordinate search must not call an external provider")

    results = asyncio.run(
        search_places(UnexpectedProvider(), PlaceSearchRequest("45.4642, 9.1900"))
    )
    assert len(results) == 1
    assert results[0].kind == "coordinate"
    assert results[0].coordinate == Coordinate(45.4642, 9.19)


def test_invalid_coordinate_like_query_falls_through_to_provider() -> None:
    assert parse_coordinate_query("95, 9") is None


def test_nominatim_normalizes_address_locality_and_poi_metadata() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/search"
        assert request.url.params["countrycodes"] == "it"
        assert request.headers["User-Agent"] == "compass-test/contact"
        return httpx.Response(
            200,
            json=[
                {
                    "place_id": 123,
                    "osm_type": "node",
                    "osm_id": 456,
                    "lat": "45.4642",
                    "lon": "9.1900",
                    "display_name": "Duomo di Milano, Piazza del Duomo, Milano",
                    "category": "tourism",
                    "type": "attraction",
                    "namedetails": {"name": "Duomo di Milano"},
                    "address": {
                        "pedestrian": "Piazza del Duomo",
                        "city": "Milano",
                        "state": "Lombardia",
                    },
                }
            ],
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = NominatimPlaceSearchProvider(
                base_url="https://search.invalid",
                timeout_seconds=2,
                user_agent="compass-test/contact",
                client=client,
            )
            return await provider.search(PlaceSearchRequest("Duomo Milano"))

    results = asyncio.run(run())
    assert results == (
        PlaceSearchResult(
            result_id="nominatim:node:456",
            display_name="Duomo di Milano, Piazza del Duomo, Milano",
            address="Piazza del Duomo, Milano, Lombardia",
            coordinate=Coordinate(45.4642, 9.19),
            kind="poi",
            category="attraction",
            poi_name="Duomo di Milano",
            provider="nominatim",
            provider_place_id="node:456",
            street_name="Piazza del Duomo",
            locality="Milano",
        ),
    )


def test_nominatim_prefers_house_type_over_place_category_for_address_kind() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=[
                {
                    "place_id": 12,
                    "lat": "45.40",
                    "lon": "9.20",
                    "display_name": "Via Dante 1, Milano",
                    "category": "place",
                    "type": "house",
                    "address": {"road": "Via Dante", "house_number": "1", "city": "Milano"},
                }
            ],
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = NominatimPlaceSearchProvider(
                base_url="https://search.invalid",
                timeout_seconds=2,
                user_agent="compass-test/contact",
                client=client,
            )
            return await provider.search(PlaceSearchRequest("Via Dante 1, Milano"))

    result = asyncio.run(run())[0]
    assert result.kind == "address"
    assert result.address == "Via Dante 1, Milano"
    assert result.street_name == "Via Dante"
    assert result.house_number == "1"
    assert result.locality == "Milano"


def test_google_places_new_uses_post_field_mask_and_structured_civic() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/v1/places:searchText"
        assert request.headers["X-Goog-Api-Key"] == "test-key"
        assert request.headers["X-Goog-FieldMask"] == GOOGLE_PLACES_TEXT_SEARCH_FIELD_MASK
        assert json.loads(request.content) == {
            "textQuery": "Via Cappafredda, 12, Roverchiara",
            "languageCode": "it",
            "regionCode": "IT",
            "maxResultCount": 8,
        }
        return httpx.Response(
            200,
            json={
                "places": [
                    {
                        "id": "google-place-12",
                        "displayName": {"text": "Via Cappafredda, 12"},
                        "formattedAddress": "Via Cappafredda, 12, 37050 Roverchiara VR, Italia",
                        "location": {"latitude": 45.2581, "longitude": 11.2472},
                        "primaryType": "street_address",
                        "types": ["street_address"],
                        "addressComponents": [
                            {"longText": "12", "types": ["street_number"]},
                            {"longText": "Via Cappafredda", "types": ["route"]},
                            {"longText": "Roverchiara", "types": ["locality"]},
                        ],
                    }
                ]
            },
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewSearchProvider(
                base_url="https://places.invalid/v1",
                api_key="test-key",
                timeout_seconds=2,
                client=client,
            )
            return await provider.search(
                PlaceSearchRequest("Via Cappafredda, 12, Roverchiara")
            )

    result = asyncio.run(run())[0]
    assert result.kind == "address"
    assert result.street_name == "Via Cappafredda"
    assert result.house_number == "12"
    assert result.locality == "Roverchiara"
    assert result.coordinate == Coordinate(45.2581, 11.2472)


def test_civic_aware_ranking_drops_explicit_conflicts_when_exact_result_exists() -> None:
    wrong = PlaceSearchResult(
        result_id="nominatim:wrong",
        display_name="Via Cappafredda 21, Roverchiara",
        address="Via Cappafredda 21, Roverchiara",
        coordinate=Coordinate(45.2580, 11.2470),
        kind="address",
        provider="nominatim",
        street_name="Via Cappafredda",
        house_number="21",
        locality="Roverchiara",
    )
    exact = PlaceSearchResult(
        result_id="nominatim:exact",
        display_name="Via Cappafredda 12, Roverchiara",
        address="Via Cappafredda 12, Roverchiara",
        coordinate=Coordinate(45.2581, 11.2472),
        kind="address",
        provider="nominatim",
        street_name="Via Cappafredda",
        house_number="12",
        locality="Roverchiara",
    )
    google = PlaceSearchResult(
        result_id="google:exact",
        display_name="Via Cappafredda, 12",
        address="Via Cappafredda, 12, Roverchiara",
        coordinate=Coordinate(45.25811, 11.24721),
        kind="address",
        provider="google_places_new",
        street_name="Via Cappafredda",
        house_number="12",
        locality="Roverchiara",
    )

    ranked = rank_results_for_query(
        "Via Cappafredda, 12, Roverchiara",
        (wrong, exact),
        (google,),
        limit=8,
        corroboration_radius_meters=75,
    )

    assert extract_query_house_number("Via Cappafredda, 12/A, Roverchiara") == "12a"
    assert ranked == (exact,)


def test_civic_aware_ranking_drops_conflict_even_without_exact_primary_result() -> None:
    wrong = PlaceSearchResult(
        result_id="nominatim:wrong",
        display_name="Via Cappafredda 21, Roverchiara",
        address="Via Cappafredda 21, Roverchiara",
        coordinate=Coordinate(45.2580, 11.2470),
        kind="address",
        provider="nominatim",
        street_name="Via Cappafredda",
        house_number="21",
        locality="Roverchiara",
    )

    assert rank_results_for_query(
        "Via Cappafredda, 12, Roverchiara",
        (wrong,),
        limit=8,
        corroboration_radius_meters=75,
    ) == ()


def test_road_class_number_is_not_treated_as_a_house_number() -> None:
    assert extract_query_house_number("Strada Statale 434 Transpolesana") is None
    assert extract_query_house_number("SS 434 Transpolesana") is None
    assert extract_query_house_number("Via SS 434, 12, Verona") == "12"
    assert extract_query_house_number("Via Roma, 12 bis, Milano") == "12bis"
    assert extract_query_house_number("Via Cappafredda 12, Roverchiara") is None


def test_poi_results_are_ranked_by_query_and_cross_provider_proximity() -> None:
    unrelated = PlaceSearchResult(
        result_id="nominatim:unrelated",
        display_name="Duomo Bar, Milano",
        address="Via Torino, Milano",
        coordinate=Coordinate(45.4600, 9.1800),
        kind="poi",
        poi_name="Duomo Bar",
        provider="nominatim",
    )
    cathedral = PlaceSearchResult(
        result_id="nominatim:duomo",
        display_name="Duomo di Milano, Piazza del Duomo",
        address="Piazza del Duomo, Milano",
        coordinate=Coordinate(45.4642, 9.1916),
        kind="poi",
        poi_name="Duomo di Milano",
        provider="nominatim",
    )
    google = PlaceSearchResult(
        result_id="google:duomo",
        display_name="Duomo di Milano",
        address="Piazza del Duomo, Milano",
        coordinate=Coordinate(45.46421, 9.19161),
        kind="poi",
        poi_name="Duomo di Milano",
        provider="google_places_new",
    )

    ranked = rank_results_for_query(
        "Duomo di Milano",
        (unrelated, cathedral),
        (google,),
        limit=2,
        corroboration_radius_meters=75,
    )

    assert ranked[0] == cathedral


def test_google_failure_does_not_hide_valid_nominatim_results(caplog) -> None:
    result = PlaceSearchResult(
        result_id="nominatim:milano",
        display_name="Milano",
        address="Milano, Lombardia",
        coordinate=Coordinate(45.4642, 9.19),
        kind="locality",
        provider="nominatim",
    )

    class Primary:
        async def search(self, _request):
            return (result,)

    class UnavailableCorroborator:
        async def search(self, _request):
            raise PlaceSearchUnavailableError("temporary upstream secret")

    provider = CorroboratedPlaceSearchProvider(
        primary=Primary(),
        corroborator=UnavailableCorroborator(),
        corroboration_radius_meters=75,
    )

    with caplog.at_level("WARNING"):
        assert asyncio.run(provider.search(PlaceSearchRequest("Milano"))) == (result,)
    assert "Place corroborator unavailable" in caplog.text
    assert "temporary upstream secret" not in caplog.text


def _get(path: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path)

    return asyncio.run(request())


async def _disabled_auth_settings() -> Settings:
    return Settings(_env_file=None)


def test_search_api_exposes_normalized_results() -> None:
    class Provider:
        async def search(self, request: PlaceSearchRequest):
            assert request.query == "Bologna"
            return (
                PlaceSearchResult(
                    result_id="fixture:1",
                    display_name="Bologna, Emilia-Romagna",
                    address="Bologna, Emilia-Romagna",
                    coordinate=Coordinate(44.4949, 11.3426),
                    kind="locality",
                    category="city",
                    provider="fixture",
                    provider_place_id="1",
                ),
            )

    async def override_provider():
        return Provider()

    app.dependency_overrides[get_place_search_provider] = override_provider
    app.dependency_overrides[get_api_settings] = _disabled_auth_settings
    try:
        response = _get("/api/v1/places/search?q=Bologna")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["cacheable"] is True
    assert response.json()["results"][0] == {
        "result_id": "fixture:1",
        "display_name": "Bologna, Emilia-Romagna",
        "address": "Bologna, Emilia-Romagna",
        "location": {"latitude": 44.4949, "longitude": 11.3426},
        "kind": "locality",
        "category": "city",
        "poi_name": None,
        "provider": "fixture",
        "provider_place_id": "1",
    }


def test_search_api_marks_google_corroborated_ordering_as_non_cacheable() -> None:
    class Provider:
        cacheable = False

        async def search(self, _request):
            return (
                PlaceSearchResult(
                    result_id="nominatim:1",
                    display_name="Via Roma 1, Milano",
                    address="Via Roma 1, Milano",
                    coordinate=Coordinate(45.46, 9.19),
                    kind="address",
                    provider="nominatim",
                ),
            )

    async def override_provider():
        return Provider()

    app.dependency_overrides[get_place_search_provider] = override_provider
    app.dependency_overrides[get_api_settings] = _disabled_auth_settings
    try:
        response = _get("/api/v1/places/search?q=Via%20Roma%201%2C%20Milano")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["cacheable"] is False


def test_search_api_maps_provider_failures_without_exposing_details() -> None:
    class Provider:
        async def search(self, _request):
            raise PlaceSearchUnavailableError("secret upstream detail")

    async def override_provider():
        return Provider()

    app.dependency_overrides[get_place_search_provider] = override_provider
    app.dependency_overrides[get_api_settings] = _disabled_auth_settings
    try:
        response = _get("/api/v1/places/search?q=Milano")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 503
    assert response.json() == {
        "code": "search_unavailable",
        "message": "Place search is unavailable.",
    }


def test_coordinate_search_remains_available_when_external_geocoding_is_disabled() -> None:
    async def override_settings():
        return Settings(_env_file=None, geocoding_provider="none")

    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = _get("/api/v1/places/search?q=45.4642%2C%209.19")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["cacheable"] is True
    assert response.json()["results"] == [
        {
            "result_id": "coordinate:45.464200, 9.190000",
            "display_name": "45.464200, 9.190000",
            "address": None,
            "location": {"latitude": 45.4642, "longitude": 9.19},
            "kind": "coordinate",
            "category": None,
            "poi_name": None,
            "provider": "coordinate",
            "provider_place_id": None,
        }
    ]


def test_nominatim_rejects_invalid_contract() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"not": "a list"})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = NominatimPlaceSearchProvider(
                base_url="https://search.invalid",
                timeout_seconds=2,
                user_agent="test",
                client=client,
            )
            await provider.search(PlaceSearchRequest("Milano"))

    try:
        asyncio.run(run())
    except PlaceSearchProviderError:
        pass
    else:
        raise AssertionError("invalid provider response must fail")
