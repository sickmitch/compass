import asyncio

import httpx

from compass.api.main import app
from compass.config import get_api_settings, get_settings
from compass.routing.domain import Coordinate
from compass.search.dependencies import get_place_search_provider
from compass.search.domain import (
    PlaceSearchProviderError,
    PlaceSearchRequest,
    PlaceSearchResult,
    PlaceSearchUnavailableError,
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


def _get(path: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path)

    return asyncio.run(request())


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
    try:
        response = _get("/api/v1/places/search?q=Bologna")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
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


def test_search_api_maps_provider_failures_without_exposing_details() -> None:
    class Provider:
        async def search(self, _request):
            raise PlaceSearchUnavailableError("secret upstream detail")

    async def override_provider():
        return Provider()

    app.dependency_overrides[get_place_search_provider] = override_provider
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
        return get_settings().model_copy(update={"geocoding_provider": "none"})

    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = _get("/api/v1/places/search?q=45.4642%2C%209.19")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
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
