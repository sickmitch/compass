import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from compass.candidates.domain import (
    CandidateQueryResult,
    CorridorCandidateRequest,
    CorridorPolicy,
    SpatialCandidate,
)
from compass.candidates.geometry import decode_polyline6, route_linestring_wkt
from compass.candidates.service import find_corridor_candidates
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    RouteRequest,
    RoutingProviderError,
)


def _encode_polyline6(coordinates: list[tuple[float, float]]) -> str:
    encoded: list[str] = []
    previous_latitude = 0
    previous_longitude = 0
    for latitude, longitude in coordinates:
        latitude_value = round(latitude * 1_000_000)
        longitude_value = round(longitude * 1_000_000)
        for delta in (
            latitude_value - previous_latitude,
            longitude_value - previous_longitude,
        ):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                encoded.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            encoded.append(chr(value + 63))
        previous_latitude = latitude_value
        previous_longitude = longitude_value
    return "".join(encoded)


@pytest.mark.parametrize(
    ("effective_range", "uncapped", "radius", "cap"),
    [
        (10, 2, 5, "minimum"),
        (150, 30, 30, "none"),
        (400, 80, 50, "maximum"),
    ],
)
def test_corridor_policy_applies_range_fraction_and_caps(
    effective_range: float,
    uncapped: float,
    radius: float,
    cap: str,
) -> None:
    result = CorridorPolicy().radius_for(effective_range)

    assert result.range_fraction == 0.20
    assert result.uncapped_radius_km == uncapped
    assert result.radius_km == radius
    assert result.cap_applied == cap


@pytest.mark.parametrize("effective_range", [0, -1])
def test_corridor_policy_rejects_non_positive_range(effective_range: float) -> None:
    with pytest.raises(ValueError, match="effective_cng_range_km"):
        CorridorPolicy().radius_for(effective_range)


def test_corridor_policy_uses_configured_factor_and_caps() -> None:
    policy = CorridorPolicy(
        range_fraction=0.10,
        minimum_radius_km=2,
        maximum_radius_km=20,
        candidate_limit=50,
    )

    uncapped = policy.radius_for(150)
    capped = policy.radius_for(500)

    assert uncapped.radius_km == 15
    assert uncapped.cap_applied == "none"
    assert capped.uncapped_radius_km == 50
    assert capped.radius_km == 20
    assert capped.cap_applied == "maximum"


def test_polyline6_decodes_to_wgs84_linestring() -> None:
    encoded = _encode_polyline6([(45.4642, 9.19), (44.4949, 11.3426)])

    decoded = decode_polyline6(encoded, max_points=10)

    assert decoded == (
        Coordinate(latitude=45.4642, longitude=9.19),
        Coordinate(latitude=44.4949, longitude=11.3426),
    )
    assert route_linestring_wkt(decoded) == (
        "LINESTRING(9.190000 45.464200,11.342600 44.494900)"
    )


def test_polyline6_decodes_checked_in_valhalla_shape() -> None:
    payload = json.loads(
        (Path(__file__).parent / "fixtures" / "valhalla_route_response.json").read_text()
    )

    decoded = decode_polyline6(payload["trip"]["legs"][0]["shape"], max_points=100)

    assert decoded == (
        Coordinate(latitude=38.5, longitude=-120.2),
        Coordinate(latitude=40.7, longitude=-120.95),
        Coordinate(latitude=43.252, longitude=-126.453),
    )


@pytest.mark.parametrize("encoded", ["", "_", "\n??"])
def test_polyline6_rejects_invalid_geometry(encoded: str) -> None:
    with pytest.raises(RoutingProviderError):
        decode_polyline6(encoded, max_points=10)


def test_polyline6_enforces_provider_point_limit() -> None:
    encoded = _encode_polyline6([(45.0, 9.0), (45.1, 9.1), (45.2, 9.2)])

    with pytest.raises(RoutingProviderError, match="point limit"):
        decode_polyline6(encoded, max_points=2)


class _FakeProvider:
    def __init__(self, route: BaseRoute) -> None:
        self.route_result = route
        self.calls: list[RouteRequest] = []

    async def route(self, request: RouteRequest) -> BaseRoute:
        self.calls.append(request)
        return self.route_result

    async def is_ready(self) -> bool:
        return True


def test_spatial_service_routes_once_and_exposes_pruning_metrics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = CorridorCandidateRequest(
        route=RouteRequest(
            origin=Coordinate(45.4642, 9.19),
            destination=Coordinate(44.4949, 11.3426),
        ),
        effective_cng_range_km=300,
    )
    provider = _FakeProvider(
        BaseRoute(
            distance_meters=220_000,
            duration_seconds=8_000,
            encoded_polyline=_encode_polyline6(
                [(45.4642, 9.19), (44.4949, 11.3426)]
            ),
            maneuvers=(
                Maneuver(1, "Procedi.", 220_000, 8_000, 0, 1),
            ),
            provider="valhalla",
        )
    )
    candidate = SpatialCandidate(
        station_id=7,
        mimit_station_id="1001",
        name="Milano Metano",
        municipality="Milano",
        province="MI",
        latitude=45.4642,
        longitude=9.19,
        straight_line_distance_to_route_meters=0,
        route_fraction=0,
    )
    captured: dict[str, object] = {}

    class FakeRepository:
        def __init__(self, session: object) -> None:
            captured["session"] = session

        def within_corridor(self, **kwargs: object) -> CandidateQueryResult:
            captured.update(kwargs)
            return CandidateQueryResult(1512, 1505, 83, (candidate,))

    monkeypatch.setattr(
        "compass.candidates.service.PostgisCandidateRepository",
        FakeRepository,
    )
    session = SimpleNamespace()

    result = asyncio.run(
        find_corridor_candidates(
            session,  # type: ignore[arg-type]
            provider,
            request,
            policy=CorridorPolicy(candidate_limit=1),
            max_route_geometry_points=100,
        )
    )

    assert provider.calls == [request.route]
    assert captured["radius_meters"] == 50_000
    assert captured["limit"] == 1
    assert str(captured["route_wkt"]).startswith("LINESTRING(9.190000 45.464200")
    assert result.candidates == (candidate,)
    assert result.metrics.active_station_count == 1512
    assert result.metrics.active_station_with_location_count == 1505
    assert result.metrics.excluded_missing_location_count == 7
    assert result.metrics.corridor_candidate_count == 83
    assert result.metrics.returned_candidate_count == 1
    assert result.metrics.pruned_with_location_count == 1422
    assert result.metrics.reduction_ratio == pytest.approx(1 - 83 / 1505)
    assert result.metrics.candidate_limit_applied is True
    assert result.metrics.routing_calls == 1

    provider.calls.clear()
    precomputed = asyncio.run(
        find_corridor_candidates(
            session,  # type: ignore[arg-type]
            provider,
            request,
            policy=CorridorPolicy(candidate_limit=1),
            max_route_geometry_points=100,
            base_route=provider.route_result,
        )
    )

    assert provider.calls == []
    assert precomputed.base_route is provider.route_result
