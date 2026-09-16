import asyncio
import json
from dataclasses import replace
from uuid import uuid4

import httpx
import pytest

from compass.api.main import app
from compass.candidates.geometry import encode_polyline5
from compass.config import Settings, get_api_settings
from compass.routing.domain import Coordinate, WaypointRoute
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
    build_google_route_recovery_polylines,
    classify_waypoint_query,
    is_within_time_budget,
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
    encoded, _ = build_google_route_polyline(slotted, max_points=100, max_encoded_chars=10_000)
    assert encoded == encode_polyline5(
        (
            Coordinate(45.100003, 10.200004),
            Coordinate(45.2, 10.3),
            Coordinate(45.300005, 10.500006),
        )
    )


def test_ring_route_is_kept_and_split_into_bounded_recovery_segments() -> None:
    origin = Coordinate(45.0, 10.0)
    destination = Coordinate(45.00001, 10.00001)
    ring = AlongRouteContext(
        route_id="ring",
        route_revision=1,
        origin=origin,
        final_destination=destination,
        remaining_waypoints=(),
        legs=(
            AlongRouteLeg(
                _encode(
                    [
                        origin,
                        Coordinate(45.1, 10.1),
                        Coordinate(45.2, 10.0),
                        Coordinate(45.1, 9.9),
                        destination,
                    ]
                )
            ),
        ),
    )
    encoded, _ = build_google_route_polyline(ring, max_points=100, max_encoded_chars=10_000)
    segments = build_google_route_recovery_polylines(
        ring, max_points=100, max_encoded_chars=10_000, segment_count=2
    )
    assert encoded
    assert len(segments) == 2
    assert all(segment != encoded for segment in segments)


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
    assert payload["searchAlongRouteParameters"] == {"polyline": {"encodedPolyline": "abc"}}
    assert payload["pageSize"] == 10
    assert "routingSummaries" not in payload
    assert "rankPreference" not in payload


@pytest.mark.parametrize(
    ("query", "expected"),
    [
        ("farmacia", "generic"),
        ("pizzeria", "generic"),
        ("supermercato aperto", "generic"),
        ("Via Roma 12, Verona", "specific"),
        ("Lidl via X Verona", "specific"),
        ("ristoranti a Mantova", "specific"),
        ("Bar Centrale", "ambiguous"),
        ("Arena di Verona", "ambiguous"),
    ],
)
def test_waypoint_query_intent_is_deterministic(query: str, expected: str) -> None:
    assert classify_waypoint_query(query) == expected


@pytest.mark.parametrize(
    ("duration", "expected"),
    [(7_199.0, True), (7_200.0, True), (7_201.0, False)],
)
def test_one_third_time_budget_uses_unrounded_seconds(duration: float, expected: bool) -> None:
    assert (
        is_within_time_budget(
            candidate_duration_seconds=duration,
            baseline_duration_seconds=5_400.0,
            maximum_total_added_duration_seconds=1_800.0,
        )
        is expected
    )


def test_specific_query_uses_global_text_search_without_silent_global_fallback() -> None:
    captured: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"places": []})

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
            service = _service(provider)
            await service.search(
                "user",
                _request(_route(), query="Via Roma 12, Verona"),
            )

    asyncio.run(run())
    payload = json.loads(
        captured.single().content if hasattr(captured, "single") else captured[0].content
    )
    assert "searchAlongRouteParameters" not in payload


def test_generic_candidates_are_filtered_by_total_budget_and_sorted_by_marginal_time() -> None:
    route = replace(
        _route(),
        baseline_duration_seconds=100.0,
        current_duration_seconds=130.0,
        maximum_total_added_duration_seconds=50.0,
    )

    def result(ref: str, longitude: float, rank: int) -> AlongRouteProviderResult:
        suggestion = DestinationSuggestion(
            id=f"google_places_new:{ref}",
            provider="google_places_new",
            provider_ref=ref,
            kind="business",
            title=ref,
            subtitle=None,
            address_preview=None,
            distance_meters=None,
            provider_rank=rank,
            requires_resolution=False,
        )
        selection = ResolvedDestinationSelection(
            provider="google_places_new",
            provider_ref=ref,
            formatted_address=None,
            address_components=(),
            normalized_address=NormalizedAddress(),
            coordinate=Coordinate(45.2, longitude),
            kind="business",
            attribution=("Google Maps",),
            field_sources=(("location", "google_places_new"),),
        )
        return AlongRouteProviderResult(suggestion, selection)

    class Provider(_Provider):
        async def search(self, **kwargs):
            self.calls += 1
            assert kwargs["encoded_polyline5"] is not None
            return AlongRouteProviderResponse(
                (
                    result("within-slower", 10.81, 0),
                    result("outside", 10.82, 1),
                    result("within-faster", 10.83, 2),
                ),
                None,
            )

    class Routing:
        def __init__(self):
            self.requests = []

        async def route_with_waypoints(self, request):
            self.requests.append(request)
            duration = {10.81: 145.0, 10.82: 151.0, 10.83: 135.0}[request.waypoints[-1].longitude]
            return WaypointRoute(0.0, duration, (), "fixture")

    provider = Provider()
    routing = Routing()

    async def run():
        return await _service(provider).search(
            "user",
            _request(route, query="farmacia"),
            routing_provider=routing,
        )

    response = asyncio.run(run())
    assert response.mode == "route_time_filtered"
    assert [item.provider_ref for item in response.results] == [
        "within-faster",
        "within-slower",
    ]
    assert [item.marginal_added_duration_seconds for item in response.results] == [5.0, 15.0]
    assert all(item.insertion_leg_index == 1 for item in response.results)
    assert all(item.within_time_budget for item in response.results)
    assert all(request.waypoints[0] == route.remaining_waypoints[0] for request in routing.requests)


def test_candidate_uses_nearest_leg_and_existing_stop_is_not_suggested_again() -> None:
    route = replace(
        _route(),
        baseline_duration_seconds=100.0,
        current_duration_seconds=130.0,
        maximum_total_added_duration_seconds=50.0,
    )

    def result(
        ref: str,
        coordinate: Coordinate,
        rank: int,
    ) -> AlongRouteProviderResult:
        return AlongRouteProviderResult(
            DestinationSuggestion(
                id=f"google_places_new:{ref}",
                provider="google_places_new",
                provider_ref=ref,
                kind="business",
                title=ref,
                subtitle=None,
                address_preview=None,
                distance_meters=None,
                provider_rank=rank,
                requires_resolution=False,
            ),
            ResolvedDestinationSelection(
                provider="google_places_new",
                provider_ref=ref,
                formatted_address=None,
                address_components=(),
                normalized_address=NormalizedAddress(),
                coordinate=coordinate,
                kind="business",
                attribution=("Google Maps",),
                field_sources=(("location", "google_places_new"),),
            ),
        )

    candidate = Coordinate(45.04, 10.08)

    class Provider(_Provider):
        async def search(self, **_kwargs):
            self.calls += 1
            return AlongRouteProviderResponse(
                (
                    result("already-selected", route.remaining_waypoints[0], 0),
                    result("first-leg", candidate, 1),
                ),
                None,
            )

    class Routing:
        def __init__(self):
            self.requests = []

        async def route_with_waypoints(self, request):
            self.requests.append(request)
            return WaypointRoute(0.0, 135.0, (), "fixture")

    provider = Provider()
    routing = Routing()
    response = asyncio.run(
        _service(provider, minimum_eligible_results=1).search(
            "user",
            _request(route, query="farmacia"),
            routing_provider=routing,
        )
    )

    assert [item.provider_ref for item in response.results] == ["first-leg"]
    assert response.results[0].insertion_leg_index == 0
    assert len(routing.requests) == 1
    assert routing.requests[0].waypoints == (candidate, route.remaining_waypoints[0])


def test_initial_search_consumes_next_provider_page_after_duplicate_filtering() -> None:
    route = replace(
        _route(),
        baseline_duration_seconds=100.0,
        current_duration_seconds=100.0,
        maximum_total_added_duration_seconds=50.0,
    )

    def result(ref: str, coordinate: Coordinate) -> AlongRouteProviderResult:
        return AlongRouteProviderResult(
            DestinationSuggestion(
                id=f"google_places_new:{ref}",
                provider="google_places_new",
                provider_ref=ref,
                kind="business",
                title=ref,
                subtitle=None,
                address_preview=None,
                distance_meters=None,
                provider_rank=0,
                requires_resolution=False,
            ),
            ResolvedDestinationSelection(
                provider="google_places_new",
                provider_ref=ref,
                formatted_address=None,
                address_components=(),
                normalized_address=NormalizedAddress(),
                coordinate=coordinate,
                kind="business",
                attribution=("Google Maps",),
                field_sources=(("location", "google_places_new"),),
            ),
        )

    class Provider(_Provider):
        def __init__(self):
            self.calls = 0
            self.page_tokens = []

        async def search(self, **kwargs):
            self.calls += 1
            self.page_tokens.append(kwargs["page_token"])
            if kwargs["page_token"] is None:
                return AlongRouteProviderResponse(
                    (result("already-selected", route.remaining_waypoints[0]),),
                    "page-2",
                )
            return AlongRouteProviderResponse(
                (result("next-result", Coordinate(45.2, 10.3)),),
                None,
            )

    class Routing:
        async def route_with_waypoints(self, _request):
            return WaypointRoute(0.0, 110.0, (), "fixture")

    provider = Provider()
    response = asyncio.run(
        _service(provider, minimum_eligible_results=1).search(
            "user",
            _request(route, query="farmacia"),
            routing_provider=Routing(),
        )
    )

    assert [item.provider_ref for item in response.results] == ["next-result"]
    assert provider.page_tokens == [None, "page-2"]
    assert response.next_page_cursor is None


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


def _service(
    provider: _Provider,
    *,
    minimum_eligible_results: int = 3,
) -> AlongRouteSearchService:
    return AlongRouteSearchService(
        provider=provider,
        max_geometry_points=100,
        max_encoded_polyline_chars=10_000,
        session_ttl_seconds=900,
        max_concurrency=2,
        requests_per_user_minute=20,
        minimum_query_characters=3,
        minimum_eligible_results=minimum_eligible_results,
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
        first = await service.search("user", _request(_route(), session_id=session_id, revision=1))
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


def test_identical_inflight_search_is_coalesced() -> None:
    class Provider(_Provider):
        def __init__(self):
            self.calls = 0
            self.release = asyncio.Event()

        async def search(self, **_kwargs):
            self.calls += 1
            await self.release.wait()
            return AlongRouteProviderResponse((), None)

    async def run():
        provider = Provider()
        service = _service(provider)
        request = _request(_route())
        first = asyncio.create_task(service.search("user", request))
        await asyncio.sleep(0)
        second = asyncio.create_task(service.search("user", request))
        await asyncio.sleep(0)
        provider.release.set()
        responses = await asyncio.gather(first, second)
        return provider, responses

    provider, responses = asyncio.run(run())
    assert provider.calls == 1
    assert responses[0] == responses[1]


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
        found = await service.search("user", _request(route, session_id=session_id, revision=1))
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
