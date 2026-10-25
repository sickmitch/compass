"""Traffic detection must never replace graph-speed route or matrix costs."""

import asyncio
import json
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from compass.routing.domain import Coordinate, MatrixRequest, RouteRequest, WaypointRouteRequest
from compass.routing.valhalla import ValhallaRoutingAdapter


@pytest.mark.parametrize("waypoints", [False, True])
@pytest.mark.parametrize("probe_status", [200, 503])
def test_alert_probe_cannot_change_route_geometry_maneuvers_or_times(waypoints, probe_status):
    fixture = json.loads(Path("tests/fixtures/valhalla_route_response.json").read_text())
    if waypoints:
        fixture["trip"]["legs"].append(deepcopy(fixture["trip"]["legs"][0]))
    calls = []

    def handler(request):
        payload = json.loads(request.content)
        calls.append(payload)
        if len(calls) == 1:
            assert "date_time" not in payload
            assert payload["costing_options"]["auto"]["speed_types"] == [
                "constrained", "freeflow",
            ]
            return httpx.Response(200, json=fixture)
        assert "current" in payload["costing_options"]["auto"]["speed_types"]
        live = deepcopy(fixture)
        live["trip"]["summary"]["time"] += 1800
        for leg in live["trip"]["legs"]:
            leg["summary"]["time"] += 1800
            leg["shape"] = "different-live-route"
        return httpx.Response(probe_status, json=live)

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = ValhallaRoutingAdapter(
                base_url="http://valhalla.test", connect_timeout_seconds=1,
                read_timeout_seconds=1, user_agent="test", client=client,
                traffic_aware=True, traffic_alert_only=True,
            )
            origin, destination = Coordinate(45.4, 9.1), Coordinate(45.5, 9.3)
            if waypoints:
                return await adapter.route_with_waypoints(WaypointRouteRequest(
                    origin=origin, destination=destination, waypoints=(Coordinate(45.45, 9.2),),
                ))
            return await adapter.route(RouteRequest(origin=origin, destination=destination))

    result = asyncio.run(run())
    assert len(calls) == 2
    assert result.traffic_detected is (probe_status == 200)
    assert result.traffic_aware is False
    assert result.traffic_delay_seconds is None
    assert result.duration_seconds == fixture["trip"]["summary"]["time"]
    if waypoints:
        for leg, expected in zip(result.legs, fixture["trip"]["legs"], strict=True):
            assert leg.encoded_polyline == expected["shape"]
            assert leg.duration_seconds == expected["summary"]["time"]
    else:
        assert result.encoded_polyline == fixture["trip"]["legs"][0]["shape"]
        assert result.maneuvers[0].duration_seconds == (
            fixture["trip"]["legs"][0]["maneuvers"][0]["time"]
        )


def test_alert_mode_matrix_excludes_live_and_predicted_speeds():
    def handler(request):
        payload = json.loads(request.content)
        assert "date_time" not in payload
        assert payload["costing_options"]["auto"]["speed_types"] == ["constrained", "freeflow"]
        return httpx.Response(200, json={
            "units": "kilometers", "algorithm": "costmatrix",
            "sources_to_targets": [[{"from_index": 0, "to_index": 0, "distance": 2, "time": 120}]],
        })

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = ValhallaRoutingAdapter(
                base_url="http://valhalla.test", connect_timeout_seconds=1,
                read_timeout_seconds=1, user_agent="test", client=client,
                traffic_aware=True, traffic_alert_only=True,
            )
            return await adapter.matrix(MatrixRequest(
                sources=(Coordinate(45.4, 9.1),), targets=(Coordinate(45.5, 9.3),),
            ))

    result = asyncio.run(run())
    assert result.costs[0][0].duration_seconds == 120


@pytest.mark.parametrize(
    "state,expected", [("fresh", True), ("stale", False), ("unavailable", False)]
)
def test_alert_requires_current_provider_data(monkeypatch, state, expected):
    from datetime import UTC, datetime

    from compass.api.routes import _navigation_traffic
    from compass.config import Settings
    from compass.navigation.domain import build_navigation_timing
    from compass.routing.domain import BaseRoute

    observed = datetime(2026, 10, 25, tzinfo=UTC)
    monkeypatch.setattr(
        "compass.api.routes.traffic_health_from_settings",
        lambda _: SimpleNamespace(provider_status=state, last_success_at=observed),
    )
    route = BaseRoute(1000, 120, "shape", (), "valhalla", traffic_detected=True)
    metadata = _navigation_traffic(route, Settings(_env_file=None))
    timing = build_navigation_timing(
        encoded_polylines=("shape",), driving_duration_seconds=120,
        fuel_stop_ids=("123",), **metadata,
    )
    assert timing.traffic_detected is expected
    assert timing.traffic_aware is False
    assert timing.traffic_delay_seconds is None
    assert timing.driving_duration_seconds == 120
    assert timing.total_trip_duration_seconds == 1320
