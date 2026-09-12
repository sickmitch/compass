import asyncio
import json
from uuid import uuid4

import httpx
import pytest

from compass.api.main import app
from compass.candidates.geometry import encode_polyline5
from compass.config import Settings, get_api_settings
from compass.routing.domain import Coordinate
from compass.search.along_route import (
    GOOGLE_SEARCH_ALONG_ROUTE_FIELD_MASK,
    AlongRouteContext,
    AlongRouteLeg,
    AlongRouteProviderResponse,
    AlongRouteProviderResult,
    AlongRouteSearchRequest,
    AlongRouteSearchService,
    GooglePlacesNewAlongRouteProvider,
    RouteRequiredError,
    StaleDestinationSelectionError,
    build_google_route_polyline,
)
from compass.search.dependencies import get_along_route_search_service
from compass.search.destination_domain import (
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchUnavailableError,
    DestinationSuggestion,
    NormalizedAddress,
    ResolvedDestinationSelection,
)


def _encode(points: list[Coordinate], precision: int = 6) -> str:
    factor = 10**precision
    result: list[str] = []
    previous_latitude = previous_longitude = 0
    for point in points:
        latitude = round(point.latitude * factor)
        longitude = round(point.longitude * factor)
        for delta in (latitude - previous_latitude, longitude - previous_longitude):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                result.append(chr((0x20 | value & 0x1F) + 63))
                value >>= 5
            result.append(chr(value + 63))
        previous_latitude, previous_longitude = latitude, longitude
    return "".join(result)


def _route(*, revision: int = 7, progress: int | None = None) -> AlongRouteContext:
    first = Coordinate(45.000001, 10.000002)
    joint = Coordinate(45.100003, 10.200004)
    last = Coordinate(45.300005, 10.500006)
    return AlongRouteContext(
        route_id="route-selected",
        route_revision=revision,
        origin=first,
        final_destination=last,
        remaining_waypoints=(joint,),
        legs=(
            AlongRouteLeg(_encode([first, Coordinate(45.05, 10.1), joint])),
            AlongRouteLeg(_encode([joint, Coordinate(45.2, 10.3), last])),
        ),
        progress_shape_index=progress,
    )


def _request(route: AlongRouteContext | None = None, **values) -> AlongRouteSearchRequest:
    return AlongRouteSearchRequest(
        query=values.get("query", "farmacia"),
        session_id=values.get("session_id", str(uuid4())),
        revision=values.get("revision", 1),
        route=route,
        page_cursor=values.get("page_cursor"),
    )


def test_e6_legs_are_concatenated_in_order_and_reencoded_as_e5() -> None:
    route = _route()
    encoded, fingerprint = build_google_route_polyline(
        route, max_points=100, max_encoded_chars=10_000
    )
    expected = encode_polyline5(
        (
            Coordinate(45.000001, 10.000002),
            Coordinate(45.05, 10.1),
            Coordinate(45.100003, 10.200004),
            Coordinate(45.2, 10.3),
            Coordinate(45.300005, 10.500006),
        )
    )
    assert encoded == expected
    assert len(fingerprint) == 64


def test_navigation_progress_uses_authoritative_shape_index() -> None:
    encoded, _ = build_google_route_polyline(
        _route(progress=2), max_points=100, max_encoded_chars=10_000
    )
    assert encoded == encode_polyline5(
        (
            Coordinate(45.100003, 10.200004),
            Coordinate(45.2, 10.3),
            Coordinate(45.300005, 10.500006),
        )
    )


def test_explicit_insertion_slot_uses_only_the_selected_leg() -> None:
    route = _route()
    slotted = AlongRouteContext(
        route_id=route.route_id,
        route_revision=route.route_revision,
        origin=route.origin,
        final_destination=route.final_destination,
        remaining_waypoints=route.remaining_waypoints,
        legs=route.legs,
        insertion_leg_index=1,
    )
    encoded, _ = build_google_route_polyline(
        slotted, max_points=100, max_encoded_chars=10_000
    )
    assert encoded == encode_polyline5(
        (
            Coordinate(45.100003, 10.200004),
            Coordinate(45.2, 10.3),
            Coordinate(45.300005, 10.500006),
        )
    )


def test_google_contract_uses_search_along_route_without_routing_summaries() -> None:
    captured: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "places": [
                    {
                        "id": "place-a",
                        "displayName": {"text": "Farmacia Uno"},
                        "formattedAddress": "Via Roma 1, Verona",
                        "location": {"latitude": 45.1, "longitude": 10.9},
                        "types": ["establishment", "point_of_interest"],
                    },
                    {
                        "id": "place-a",
                        "displayName": {"text": "duplicato"},
                        "location": {"latitude": 45.1, "longitude": 10.9},
                    },
                ],
                "nextPageToken": "google-next",
            },
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewAlongRouteProvider(
                base_url="https://places.googleapis.com/v1",
                api_key="secret",
                timeout_seconds=2,
                region_code="IT",
                page_size=10,
                client=client,
            )
            return await provider.search(
                query="farmacia",
                language="it",
                encoded_polyline5="abc",
                page_token=None,
            )

    response = asyncio.run(run())
    assert [item.suggestion.provider_ref for item in response.results] == ["place-a"]
    assert response.results[0].selection.coordinate == Coordinate(45.1, 10.9)
    assert response.next_page_token == "google-next"
    request = captured[0]
    assert request.headers["X-Goog-FieldMask"] == GOOGLE_SEARCH_ALONG_ROUTE_FIELD_MASK
    payload = json.loads(request.content)
    assert payload["searchAlongRouteParameters"] == {
        "polyline": {"encodedPolyline": "abc"}
    }
    assert payload["pageSize"] == 10
    assert "routingSummaries" not in payload
    assert "rankPreference" not in payload


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (403, DestinationProviderError),
        (429, DestinationRateLimitedError),
        (503, DestinationSearchUnavailableError),
    ],
)
def test_google_errors_do_not_fall_back_to_global_search(status: int, expected: type) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status)

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewAlongRouteProvider(
                base_url="https://places.googleapis.com/v1",
                api_key="secret",
                timeout_seconds=2,
                region_code="IT",
                page_size=10,
                client=client,
            )
            await provider.search(
                query="farmacia", language="it", encoded_polyline5="abc", page_token=None
            )

    with pytest.raises(expected):
        asyncio.run(run())


class _Provider:
    provider_name = "google_places_new"
    calls = 0

    async def search(self, **_kwargs):
        self.calls += 1
        from compass.search.along_route import AlongRouteProviderResponse

        return AlongRouteProviderResponse((), None)


def _service(provider: _Provider) -> AlongRouteSearchService:
    return AlongRouteSearchService(
        provider=provider,
        max_geometry_points=100,
        max_encoded_polyline_chars=10_000,
        session_ttl_seconds=900,
        max_concurrency=2,
        requests_per_user_minute=20,
        minimum_query_characters=3,
    )


def test_add_stop_without_route_never_calls_provider() -> None:
    provider = _Provider()
    with pytest.raises(RouteRequiredError):
        asyncio.run(_service(provider).search("user", _request()))
    assert provider.calls == 0


def test_route_change_invalidates_pagination_cursor() -> None:
    from compass.search.along_route import AlongRouteProviderResponse

    class Provider(_Provider):
        async def search(self, **_kwargs):
            self.calls += 1
            return AlongRouteProviderResponse((), "next")

    provider = Provider()
    service = _service(provider)
    session_id = str(uuid4())

    async def run():
        first = await service.search(
            "user", _request(_route(), session_id=session_id, revision=1)
        )
        with pytest.raises(StaleDestinationSelectionError):
            await service.search(
                "user",
                _request(
                    _route(revision=8),
                    session_id=session_id,
                    revision=1,
                    page_cursor=first.next_page_cursor,
                ),
            )

    asyncio.run(run())
    assert provider.calls == 1


def test_api_requires_route_without_calling_provider() -> None:
    provider = _Provider()
    service = _service(provider)

    async def override_service():
        return service

    async def override_settings():
        return Settings(_env_file=None, geocoding_provider="none")

    async def run():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            return await client.post(
                "/api/v1/places/search-along-route",
                json={
                    "intent": "ADD_STOP_ALONG_ROUTE",
                    "query": "farmacia",
                    "session_id": str(uuid4()),
                    "revision": 1,
                    "route": None,
                },
            )

    app.dependency_overrides[get_along_route_search_service] = override_service
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = asyncio.run(run())
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 409
    assert response.json()["code"] == "route_required"
    assert provider.calls == 0


def test_selection_is_bound_to_current_route_and_double_resolve_is_idempotent() -> None:
    suggestion = DestinationSuggestion(
        id="google_places_new:place-a",
        provider="google_places_new",
        provider_ref="place-a",
        kind="business",
        title="Farmacia Uno",
        subtitle="Via Roma 1, Verona",
        address_preview="Via Roma 1, Verona",
        distance_meters=None,
        provider_rank=0,
        requires_resolution=False,
    )
    selection = ResolvedDestinationSelection(
        provider="google_places_new",
        provider_ref="place-a",
        formatted_address="Via Roma 1, Verona",
        address_components=(),
        normalized_address=NormalizedAddress(),
        coordinate=Coordinate(45.1, 10.9),
        kind="business",
        attribution=("Google Maps",),
        field_sources=(("location", "google_places_new"),),
    )

    class Provider(_Provider):
        async def search(self, **_kwargs):
            self.calls += 1
            return AlongRouteProviderResponse(
                (AlongRouteProviderResult(suggestion, selection),), None
            )

    provider = Provider()
    service = _service(provider)
    route = _route()
    session_id = str(uuid4())

    async def run():
        found = await service.search(
            "user", _request(route, session_id=session_id, revision=1)
        )
        arguments = dict(
            owner="user",
            session_id=session_id,
            revision=1,
            route_id=found.route_id,
            route_revision=found.route_revision,
            route_fingerprint=found.route_fingerprint,
            provider_ref="place-a",
        )
        first = await service.resolve(**arguments, route=route)
        second = await service.resolve(**arguments, route=route)
        with pytest.raises(StaleDestinationSelectionError):
            await service.resolve(**arguments, route=_route(revision=8))
        return first, second

    first, second = asyncio.run(run())
    assert first == second
    assert service.metrics.resolutions_succeeded == 1
    assert provider.calls == 1
