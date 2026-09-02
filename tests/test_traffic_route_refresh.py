from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import httpx

from compass.routing.domain import BaseRoute, Coordinate, RouteRequest
from compass.traffic.route_refresh import (
    HttpTrafficRouteRefresher,
    JsonRouteRefreshLedgerStore,
    RouteRefreshLedger,
    TrafficRouteRefreshResult,
    recent_refresh,
    record_refresh_success,
    route_scope_key,
    sample_route_probe_points,
)
from compass.traffic.routing import refresh_base_route_traffic
from compass.traffic.updater_api import RouteTrafficRefreshRequest, refresh_route

TILESET = "valhalla-3.8.3:123"


def _encode_polyline6(coordinates: tuple[Coordinate, ...]) -> str:
    output: list[str] = []
    previous_latitude = 0
    previous_longitude = 0
    for coordinate in coordinates:
        latitude = round(coordinate.latitude * 1_000_000)
        longitude = round(coordinate.longitude * 1_000_000)
        for delta in (
            latitude - previous_latitude,
            longitude - previous_longitude,
        ):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                output.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            output.append(chr(value + 63))
        previous_latitude = latitude
        previous_longitude = longitude
    return "".join(output)


def test_route_scope_key_is_stable_and_changes_with_waypoints() -> None:
    origin = Coordinate(45.4642, 9.19)
    destination = Coordinate(44.4949, 11.3426)

    first = route_scope_key(
        origin=origin,
        destination=destination,
        costing="auto",
    )
    same = route_scope_key(
        origin=Coordinate(45.4642001, 9.1900001),
        destination=destination,
        costing="auto",
    )
    with_stop = route_scope_key(
        origin=origin,
        destination=destination,
        waypoints=(Coordinate(45.14197, 9.634009),),
        costing="auto",
    )

    assert first == same
    assert len(first) == 64
    assert with_stop != first


def test_route_geometry_is_sampled_with_spacing_and_hard_probe_cap() -> None:
    encoded = _encode_polyline6(
        (
            Coordinate(45.4642, 9.19),
            Coordinate(45.14197, 9.634009),
            Coordinate(44.961684, 9.905687),
            Coordinate(44.4949, 11.3426),
        )
    )

    probes = sample_route_probe_points(
        (encoded,),
        spacing_km=25,
        max_probes=8,
        max_geometry_points=100,
    )

    assert len(probes) == 8
    assert probes[0] == Coordinate(45.4642, 9.19)
    assert probes[-1] == Coordinate(44.4949, 11.3426)


def test_route_refresh_ledger_is_tileset_bound_and_skips_for_five_minutes(
    tmp_path,
) -> None:
    now = datetime(2026, 9, 1, 12, 0, tzinfo=UTC)
    store = JsonRouteRefreshLedgerStore(tmp_path / "route-refresh.json")
    ledger = record_refresh_success(
        RouteRefreshLedger(TILESET, {}),
        scope_key="route-a",
        completed_at=now,
        retention_seconds=86_400,
    )
    store.save(ledger)

    loaded = store.load(expected_tileset_identity=TILESET)
    skipped = recent_refresh(
        loaded,
        scope_key="route-a",
        evaluated_at=now + timedelta(seconds=299),
        minimum_interval_seconds=300,
    )
    allowed = recent_refresh(
        loaded,
        scope_key="route-a",
        evaluated_at=now + timedelta(seconds=300),
        minimum_interval_seconds=300,
    )

    assert skipped is not None
    assert skipped.state == "skipped_recent"
    assert allowed is None
    try:
        store.load(expected_tileset_identity="another-tileset")
    except ValueError as error:
        assert "invalid" in str(error)
    else:
        raise AssertionError("another tileset must invalidate the refresh ledger")


def test_http_route_refresher_falls_back_when_internal_service_is_unavailable() -> None:
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(503, json={"message": "down"})
        )
    )
    refresher = HttpTrafficRouteRefresher(
        base_url="http://traffic-updater:8003",
        timeout_seconds=1,
        user_agent="test",
        client=client,
    )
    try:
        result = asyncio.run(
            refresher.refresh(
                scope_key="a" * 64,
                trigger="route_calculation",
                encoded_polylines=("shape",),
            )
        )
    finally:
        asyncio.run(client.aclose())

    assert result.state == "unavailable"
    assert result.overlay_changed is False


def test_current_route_is_recomputed_only_after_overlay_update() -> None:
    initial = BaseRoute(1000, 100, "first", (), "valhalla")
    updated = BaseRoute(1000, 140, "second", (), "valhalla")

    class Provider:
        async def route(self, _request: RouteRequest) -> BaseRoute:
            return updated

    class Refresher:
        async def refresh(self, **_kwargs) -> TrafficRouteRefreshResult:
            return TrafficRouteRefreshResult(state="updated", scope_key="scope")

    request = RouteRequest(
        origin=Coordinate(45.4642, 9.19),
        destination=Coordinate(44.4949, 11.3426),
    )
    result = asyncio.run(
        refresh_base_route_traffic(
            route=initial,
            request=request,
            provider=Provider(),  # type: ignore[arg-type]
            refresher=Refresher(),  # type: ignore[arg-type]
            current_departure_tolerance_seconds=300,
        )
    )

    assert result == updated


def test_future_scheduled_route_does_not_fetch_current_traffic() -> None:
    initial = BaseRoute(1000, 100, "first", (), "valhalla")

    class Provider:
        async def route(self, _request: RouteRequest) -> BaseRoute:
            raise AssertionError("future route must not be recalculated")

    class Refresher:
        async def refresh(self, **_kwargs) -> TrafficRouteRefreshResult:
            raise AssertionError("future route must not refresh current traffic")

    request = RouteRequest(
        origin=Coordinate(45.4642, 9.19),
        destination=Coordinate(44.4949, 11.3426),
        departure_at=datetime.now(UTC) + timedelta(hours=3),
    )
    result = asyncio.run(
        refresh_base_route_traffic(
            route=initial,
            request=request,
            provider=Provider(),  # type: ignore[arg-type]
            refresher=Refresher(),  # type: ignore[arg-type]
            current_departure_tolerance_seconds=300,
        )
    )

    assert result == initial


def test_internal_updater_refreshes_once_then_deduplicates(
    monkeypatch,
    tmp_path,
) -> None:
    encoded = _encode_polyline6(
        (Coordinate(45.4642, 9.19), Coordinate(44.4949, 11.3426))
    )
    settings = SimpleNamespace(
        traffic_enabled=True,
        traffic_valhalla_overlay_enabled=True,
        traffic_refresh_mode="on_demand",
        traffic_refresh_ledger_path=str(tmp_path / "route-refresh.json"),
        traffic_valhalla_tileset_version=TILESET,
        traffic_route_refresh_min_interval_seconds=300,
        traffic_route_probe_spacing_km=25,
        traffic_route_max_probes=8,
        route_geometry_max_points=100,
    )
    fetch_requests = []

    async def fake_cycle(_settings, *, provider, fetch_request):
        fetch_requests.append(fetch_request)
        evaluated_at = datetime.now(UTC)
        return SimpleNamespace(
            provider="tomtom",
            observed_at=evaluated_at,
            segments_received=2,
            segments_normalized=2,
            segments_considered=2,
            segments_accepted=2,
            segments_unmatched=0,
            provider_rejected_segments=0,
            provider_stale_segments=0,
            provider_api_errors=0,
            edges_set=4,
            edges_reset=0,
            managed_edge_count=4,
            mapping_version="valhalla-openlr-geometry-v1",
        )

    monkeypatch.setattr("compass.traffic.updater_api.get_settings", lambda: settings)
    monkeypatch.setattr(
        "compass.traffic.updater_api.build_traffic_provider", lambda _settings: object()
    )
    monkeypatch.setattr("compass.traffic.updater_api._run_updater_cycle", fake_cycle)
    monkeypatch.setattr(
        "compass.traffic.updater_api._successful_runtime_health",
        lambda *args, **kwargs: object(),
    )
    monkeypatch.setattr(
        "compass.traffic.updater_api._persist_runtime_health_safely",
        lambda *args, **kwargs: None,
    )
    request = RouteTrafficRefreshRequest(
        scope_key="b" * 64,
        trigger="route_calculation",
        encoded_polylines=[encoded],
    )

    async def exercise():
        return await refresh_route(request), await refresh_route(request)

    first, second = asyncio.run(exercise())

    assert first.state == "updated"
    assert first.probe_count == 8
    assert second.state == "skipped_recent"
    assert len(fetch_requests) == 1
    assert fetch_requests[0].scope_key == "b" * 64
    assert len(fetch_requests[0].probe_points) == 8
