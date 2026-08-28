import asyncio
import json
from pathlib import Path

import httpx
import pytest

from compass.routing.domain import (
    Coordinate,
    NoRouteError,
    RouteRequest,
    RoutingProviderError,
    RoutingUnavailableError,
)
from compass.routing.valhalla import ValhallaRoutingAdapter

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
