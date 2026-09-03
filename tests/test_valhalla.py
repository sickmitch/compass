import asyncio
import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
import pytest

from compass.routing.domain import (
    Coordinate,
    MatrixLocationError,
    MatrixRequest,
    NoRouteError,
    RouteRequest,
    RoutingProviderError,
    RoutingUnavailableError,
    WaypointRouteRequest,
)
from compass.routing.valhalla import ValhallaRoutingAdapter, _parse_matrix

FIXTURE = Path(__file__).parent / "fixtures" / "valhalla_route_response.json"


def _request() -> RouteRequest:
    return RouteRequest(
        origin=Coordinate(latitude=45.4642, longitude=9.1900),
        destination=Coordinate(latitude=45.4781, longitude=9.2271),
    )


def _adapter(handler: httpx.AsyncBaseTransport) -> tuple[ValhallaRoutingAdapter, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=handler)
    return (
        ValhallaRoutingAdapter(
            base_url="http://valhalla.test:8002/",
            connect_timeout_seconds=1,
            read_timeout_seconds=2,
            user_agent="compass-test/0.1.0",
            client=client,
        ),
        client,
    )


def _traffic_adapter(
    handler: httpx.AsyncBaseTransport,
) -> tuple[ValhallaRoutingAdapter, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=handler)
    return (
        ValhallaRoutingAdapter(
            base_url="http://valhalla.test:8002/",
            connect_timeout_seconds=1,
            read_timeout_seconds=2,
            user_agent="compass-test/0.1.0",
            traffic_aware=True,
            client=client,
        ),
        client,
    )


def test_route_translates_request_and_normalizes_response() -> None:
    fixture = json.loads(FIXTURE.read_text())

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url == "http://valhalla.test:8002/route"
        assert request.headers["user-agent"] == "compass-test/0.1.0"
        payload = json.loads(request.content)
        assert payload == {
            "locations": [
                {"lat": 45.4642, "lon": 9.19, "type": "break"},
                {"lat": 45.4781, "lon": 9.2271, "type": "break"},
            ],
            "costing": "auto",
            "units": "kilometers",
            "language": "it-IT",
            "directions_type": "instructions",
            "shape_format": "polyline6",
        }
        return httpx.Response(200, json=fixture)

    adapter, client = _adapter(httpx.MockTransport(handler))
    try:
        route = asyncio.run(adapter.route(_request()))
    finally:
        asyncio.run(client.aclose())

    assert route.provider == "valhalla"
    assert route.distance_meters == 2500
    assert route.duration_seconds == 320
    assert route.encoded_polyline == fixture["trip"]["legs"][0]["shape"]
    assert len(route.maneuvers) == 2
    assert route.maneuvers[0].distance_meters == 2100
    assert route.maneuvers[1].street_names == ("Via Milano",)


def test_route_rejects_zero_cost_provider_result_as_non_navigable() -> None:
    fixture = json.loads(FIXTURE.read_text())
    fixture["trip"]["summary"]["length"] = 0
    fixture["trip"]["summary"]["time"] = 0

    adapter, client = _adapter(
        httpx.MockTransport(lambda _request: httpx.Response(200, json=fixture))
    )
    try:
        with pytest.raises(RoutingProviderError, match="zero-cost route"):
            asyncio.run(adapter.route(_request()))
    finally:
        asyncio.run(client.aclose())


def test_traffic_aware_route_uses_current_time_when_departure_is_omitted() -> None:
    fixture = json.loads(FIXTURE.read_text())

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["date_time"] == {"type": 0}
        assert payload["costing_options"] == {
            "auto": {
                "speed_types": ["current", "predicted", "constrained", "freeflow"]
            }
        }
        return httpx.Response(200, json=fixture)

    adapter, client = _traffic_adapter(httpx.MockTransport(handler))
    try:
        route = asyncio.run(adapter.route(_request()))
    finally:
        asyncio.run(client.aclose())

    assert route.duration_seconds == 320


def test_traffic_aware_route_preserves_scheduled_departure_time() -> None:
    fixture = json.loads(FIXTURE.read_text())
    departure_at = datetime(2026, 8, 30, 10, 0, tzinfo=ZoneInfo("Europe/Rome"))

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["date_time"] == {
            "type": 1,
            "value": "2026-08-30T10:00",
        }
        return httpx.Response(200, json=fixture)

    adapter, client = _traffic_adapter(httpx.MockTransport(handler))
    try:
        route = asyncio.run(
            adapter.route(
                RouteRequest(
                    origin=Coordinate(45.4642, 9.19),
                    destination=Coordinate(44.4949, 11.3426),
                    departure_at=departure_at,
                )
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert route.provider == "valhalla"


def test_scheduled_departure_is_converted_to_valhalla_local_time() -> None:
    fixture = json.loads(FIXTURE.read_text())
    departure_at = datetime(2026, 8, 30, 8, 0, tzinfo=ZoneInfo("UTC"))

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["date_time"] == {
            "type": 1,
            "value": "2026-08-30T10:00",
        }
        return httpx.Response(200, json=fixture)

    adapter, client = _traffic_adapter(httpx.MockTransport(handler))
    try:
        route = asyncio.run(
            adapter.route(
                RouteRequest(
                    origin=Coordinate(45.4642, 9.19),
                    destination=Coordinate(44.4949, 11.3426),
                    departure_at=departure_at,
                )
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert route.provider == "valhalla"


def test_traffic_aware_matrix_is_time_dependent() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["date_time"] == {"type": 0}
        assert payload["costing_options"]["auto"]["speed_types"][0] == "current"
        return httpx.Response(
            200,
            json={
                "algorithm": "costmatrix",
                "units": "kilometers",
                "sources_to_targets": [
                    [{"from_index": 0, "to_index": 0, "distance": 1.0, "time": 90}]
                ],
            },
        )

    adapter, client = _traffic_adapter(httpx.MockTransport(handler))
    try:
        result = asyncio.run(
            adapter.matrix(
                MatrixRequest(
                    sources=(Coordinate(45.4642, 9.19),),
                    targets=(Coordinate(45.4, 9.3),),
                )
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert result.costs[0][0] is not None
    assert result.costs[0][0].duration_seconds == 90


def test_waypoint_route_preserves_leg_boundaries() -> None:
    fixture = json.loads(FIXTURE.read_text())
    first_leg = fixture["trip"]["legs"][0]
    second_leg = json.loads(json.dumps(first_leg))
    second_leg["summary"] = {"length": 3.5, "time": 400}
    second_leg["shape"] = "second-leg-polyline"
    fixture["trip"]["summary"] = {"length": 6.0, "time": 720}
    fixture["trip"]["legs"] = [first_leg, second_leg]

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["locations"] == [
            {"lat": 45.4642, "lon": 9.19, "type": "break"},
            {"lat": 45.2, "lon": 9.7, "type": "break"},
            {"lat": 44.4949, "lon": 11.3426, "type": "break"},
        ]
        return httpx.Response(200, json=fixture)

    adapter, client = _adapter(httpx.MockTransport(handler))
    try:
        route = asyncio.run(
            adapter.route_with_waypoints(
                WaypointRouteRequest(
                    origin=Coordinate(45.4642, 9.19),
                    destination=Coordinate(44.4949, 11.3426),
                    waypoints=(Coordinate(45.2, 9.7),),
                )
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert route.distance_meters == 6000
    assert route.duration_seconds == 720
    assert len(route.legs) == 2
    assert route.legs[0].distance_meters == 2500
    assert route.legs[1].distance_meters == 3500
    assert route.legs[1].encoded_polyline == "second-leg-polyline"


def test_waypoint_route_rejects_wrong_leg_count() -> None:
    fixture = json.loads(FIXTURE.read_text())
    adapter, client = _adapter(
        httpx.MockTransport(lambda _request: httpx.Response(200, json=fixture))
    )
    try:
        with pytest.raises(RoutingProviderError, match="number of legs"):
            asyncio.run(
                adapter.route_with_waypoints(
                    WaypointRouteRequest(
                        origin=Coordinate(45.4642, 9.19),
                        destination=Coordinate(44.4949, 11.3426),
                        waypoints=(Coordinate(45.2, 9.7),),
                    )
                )
            )
    finally:
        asyncio.run(client.aclose())


@pytest.mark.parametrize(
    ("status_code", "payload", "error_type"),
    [
        (400, {"error_code": 442, "error": "No path"}, NoRouteError),
        (400, {"error_code": 154, "error": "Invalid input"}, RoutingProviderError),
        (503, {"error": "Unavailable"}, RoutingUnavailableError),
    ],
)
def test_route_maps_provider_failures(
    status_code: int, payload: dict[str, object], error_type: type[Exception]
) -> None:
    adapter, client = _adapter(
        httpx.MockTransport(lambda _request: httpx.Response(status_code, json=payload))
    )
    try:
        with pytest.raises(error_type):
            asyncio.run(adapter.route(_request()))
    finally:
        asyncio.run(client.aclose())


def test_route_rejects_malformed_success_response() -> None:
    adapter, client = _adapter(
        httpx.MockTransport(lambda _request: httpx.Response(200, json={"trip": {}}))
    )
    try:
        with pytest.raises(RoutingProviderError, match="missing status"):
            asyncio.run(adapter.route(_request()))
    finally:
        asyncio.run(client.aclose())


def test_status_requires_successful_json_object() -> None:
    adapter, client = _adapter(
        httpx.MockTransport(lambda _request: httpx.Response(200, json={"version": "3.8.3"}))
    )
    try:
        assert asyncio.run(adapter.is_ready()) is True
    finally:
        asyncio.run(client.aclose())


def test_network_failure_is_reported_as_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    adapter, client = _adapter(httpx.MockTransport(handler))
    try:
        with pytest.raises(RoutingUnavailableError):
            asyncio.run(adapter.route(_request()))
    finally:
        asyncio.run(client.aclose())


def test_matrix_translates_request_and_preserves_unreachable_pairs() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url == "http://valhalla.test:8002/sources_to_targets"
        assert json.loads(request.content) == {
            "sources": [{"lat": 45.4642, "lon": 9.19}],
            "targets": [
                {"lat": 45.4, "lon": 9.3},
                {"lat": 44.9, "lon": 10.1},
            ],
            "costing": "auto",
            "units": "kilometers",
            "verbose": True,
            "shape_format": "no_shape",
        }
        return httpx.Response(
            200,
            json={
                "algorithm": "costmatrix",
                "units": "kilometers",
                "sources_to_targets": [
                    [
                        {
                            "from_index": 0,
                            "to_index": 0,
                            "distance": 12.345,
                            "time": 678,
                        },
                        {
                            "from_index": 0,
                            "to_index": 1,
                            "distance": None,
                            "time": None,
                        },
                    ]
                ],
            },
        )

    adapter, client = _adapter(httpx.MockTransport(handler))
    try:
        result = asyncio.run(
            adapter.matrix(
                MatrixRequest(
                    sources=(Coordinate(45.4642, 9.19),),
                    targets=(Coordinate(45.4, 9.3), Coordinate(44.9, 10.1)),
                )
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert result.provider == "valhalla"
    assert result.algorithm == "costmatrix"
    assert result.costs[0][0] is not None
    assert result.costs[0][0].distance_meters == 12_345
    assert result.costs[0][0].duration_seconds == 678
    assert result.costs[0][1] is None


def test_matrix_maps_no_path_response_to_unreachable_cells() -> None:
    adapter, client = _adapter(
        httpx.MockTransport(
            lambda _request: httpx.Response(400, json={"error_code": 442, "error": "No path"})
        )
    )
    try:
        result = asyncio.run(
            adapter.matrix(
                MatrixRequest(
                    sources=(Coordinate(45, 9), Coordinate(44, 10)),
                    targets=(Coordinate(43, 11),),
                )
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert result.algorithm == "no_route"
    assert result.costs == ((None,), (None,))


def test_matrix_reports_uncorrelatable_locations_separately() -> None:
    adapter, client = _adapter(
        httpx.MockTransport(
            lambda _request: httpx.Response(
                400, json={"error_code": 171, "error": "No suitable edges"}
            )
        )
    )
    try:
        with pytest.raises(MatrixLocationError):
            asyncio.run(
                adapter.matrix(
                    MatrixRequest(
                        sources=(Coordinate(45, 9),),
                        targets=(Coordinate(44, 10),),
                    )
                )
            )
    finally:
        asyncio.run(client.aclose())


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (
            {
                "algorithm": "costmatrix",
                "units": "miles",
                "sources_to_targets": [[]],
            },
            "units",
        ),
        (
            {
                "algorithm": "costmatrix",
                "units": "kilometers",
                "sources_to_targets": [
                    [{"from_index": 1, "to_index": 0, "distance": 1, "time": 2}]
                ],
            },
            "from_index",
        ),
        (
            {
                "algorithm": "costmatrix",
                "units": "kilometers",
                "sources_to_targets": [
                    [{"from_index": 0, "to_index": 0, "distance": 1, "time": None}]
                ],
            },
            "both distance and time",
        ),
    ],
)
def test_matrix_rejects_malformed_success_response(
    payload: dict[str, object], message: str
) -> None:
    adapter, client = _adapter(
        httpx.MockTransport(lambda _request: httpx.Response(200, json=payload))
    )
    try:
        with pytest.raises(RoutingProviderError, match=message):
            asyncio.run(
                adapter.matrix(
                    MatrixRequest(
                        sources=(Coordinate(45, 9),),
                        targets=(Coordinate(44, 10),),
                    )
                )
            )
    finally:
        asyncio.run(client.aclose())


@pytest.mark.parametrize("value", [float("nan"), float("inf")])
def test_matrix_rejects_non_finite_costs(value: float) -> None:
    with pytest.raises(RoutingProviderError, match="finite"):
        _parse_matrix(
            {
                "algorithm": "costmatrix",
                "units": "kilometers",
                "sources_to_targets": [
                    [
                        {
                            "from_index": 0,
                            "to_index": 0,
                            "distance": value,
                            "time": 2,
                        }
                    ]
                ],
            },
            source_count=1,
            target_count=1,
        )
