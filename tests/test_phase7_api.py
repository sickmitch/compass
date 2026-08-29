import asyncio
import json
from collections.abc import AsyncIterator
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import httpx
import pytest

from compass.api.main import app
from compass.db import get_session
from compass.freshness.domain import DataFreshnessReport, DataSourceFreshness
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    Maneuver,
    RouteLeg,
    WaypointRoute,
    WaypointRouteRequest,
)
from compass.stations.domain import (
    StationCurrentPriceRecord,
    StationDetail,
    StationOsmEnrichment,
)


def _station(*, active: bool = True, with_location: bool = True) -> StationDetail:
    return StationDetail(
        station_id=7,
        mimit_station_id="43690",
        name="S.ZENONE OVEST",
        address="A1 km 15",
        municipality="SAN ZENONE AL LAMBRO",
        province="MI",
        brand="Enilive",
        manager="Fixture operator",
        station_type="Autostradale",
        latitude=45.321004 if with_location else None,
        longitude=9.376063 if with_location else None,
        location_source="mimit" if with_location else None,
        is_active=active,
        source_observed_at=datetime.fromisoformat("2026-08-28T06:00:00+00:00"),
        updated_at=datetime.fromisoformat("2026-08-29T05:00:00+00:00"),
        current_cng_prices=(
            StationCurrentPriceRecord(
                unit_price=Decimal("1.599"),
                currency="EUR",
                unit="kg",
                service_mode="served",
                observed_at=datetime.fromisoformat("2026-08-29T04:00:00+00:00"),
                ingested_at=datetime.fromisoformat("2026-08-29T06:00:00+00:00"),
                source_name="mimit",
            ),
        ),
        osm=StationOsmEnrichment(
            osm_type="node",
            osm_id=123,
            name="San Zenone Ovest",
            opening_hours="24/7",
            phone="+39 02 123456",
            brand="Enilive",
            operator="Fixture operator",
            source_observed_at=datetime.fromisoformat("2026-08-29T05:00:00+00:00"),
            match_method="proximity_v1",
            confidence=0.95,
            distance_meters=4.2,
            is_manual=False,
        ),
    )


async def _override_session() -> AsyncIterator[object]:
    yield object()


def _request(method: str, path: str, **kwargs: object) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.request(method, path, **kwargs)

    return asyncio.run(request())


def test_station_detail_exposes_provenance_price_freshness_and_opening_at_eta(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "compass.api.stations.load_station_detail",
        lambda _session, station_id: _station() if station_id == "43690" else None,
    )
    app.dependency_overrides[get_session] = _override_session
    try:
        response = _request(
            "GET",
            "/api/v1/cng/stations/43690",
            params={"arrival_at": "2026-08-30T10:19:11+02:00"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["mimit_station_id"] == "43690"
    assert body["location"] == {
        "latitude": 45.321004,
        "longitude": 9.376063,
        "source": "mimit",
    }
    assert body["current_cng_prices"][0]["unit_price"] == 1.599
    assert body["current_cng_prices"][0]["fuel_type"] == "cng"
    assert body["current_cng_prices"][0]["freshness_state"] == "fresh"
    assert body["osm"]["opening_hours"] == "24/7"
    assert body["osm"]["confidence"] == 0.95
    assert body["opening_at_arrival"]["status"] == "evaluated"
    assert body["opening_at_arrival"]["evaluation"]["state"] == "open"


def test_station_detail_uses_stable_not_found_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "compass.api.stations.load_station_detail", lambda _session, _station_id: None
    )
    app.dependency_overrides[get_session] = _override_session
    try:
        response = _request("GET", "/api/v1/cng/stations/999999")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 404
    assert response.json() == {
        "code": "station_not_found",
        "message": "The CNG station was not found.",
    }


def test_station_detail_rejects_naive_arrival_time(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "compass.api.stations.load_station_detail", lambda _session, _station_id: _station()
    )
    app.dependency_overrides[get_session] = _override_session
    try:
        response = _request(
            "GET",
            "/api/v1/cng/stations/43690",
            params={"arrival_at": "2026-08-30T10:19:11"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 422
    assert response.json() == {
        "code": "invalid_request",
        "message": "arrival_at must include a UTC offset.",
    }


class _WaypointProvider:
    def __init__(self) -> None:
        self.request: WaypointRouteRequest | None = None

    async def route_with_waypoints(self, request: WaypointRouteRequest) -> WaypointRoute:
        self.request = request
        return WaypointRoute(
            distance_meters=210_930,
            duration_seconds=6_839,
            legs=(
                RouteLeg(
                    23_106,
                    1_151,
                    "origin-to-stop",
                    (Maneuver(1, "Procedi verso la stazione.", 23_106, 1_151, 0, 10),),
                ),
                RouteLeg(
                    187_824,
                    5_688,
                    "stop-to-destination",
                    (Maneuver(1, "Procedi verso la destinazione.", 187_824, 5_688, 0, 20),),
                ),
            ),
            provider="valhalla",
        )


def test_route_with_selected_cng_stop_returns_exactly_two_legs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = _WaypointProvider()

    async def override_provider() -> _WaypointProvider:
        return provider

    monkeypatch.setattr(
        "compass.api.stations.load_station_detail", lambda _session, _station_id: _station()
    )
    app.dependency_overrides[get_session] = _override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _request(
            "POST",
            "/api/v1/routes/with-cng-stop",
            json={
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 44.4949, "longitude": 11.3426},
                "mimit_station_id": "43690",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["selected_stop"]["mimit_station_id"] == "43690"
    assert body["distance_meters"] == 210_930
    assert [leg["kind"] for leg in body["legs"]] == [
        "origin_to_cng_station",
        "cng_station_to_destination",
    ]
    assert body["legs"][0]["geometry"]["encoded_polyline"] == "origin-to-stop"
    assert body["legs"][1]["geometry"]["encoded_polyline"] == "stop-to-destination"
    assert provider.request is not None
    assert provider.request.waypoints[0].latitude == 45.321004


@pytest.mark.parametrize(
    ("station", "code"),
    [
        (_station(active=False), "station_inactive"),
        (_station(with_location=False), "station_location_unavailable"),
    ],
)
def test_route_with_selected_stop_rejects_unusable_station(
    monkeypatch: pytest.MonkeyPatch, station: StationDetail, code: str
) -> None:
    monkeypatch.setattr(
        "compass.api.stations.load_station_detail",
        lambda _session, _station_id: station,
    )
    app.dependency_overrides[get_session] = _override_session
    try:
        response = _request(
            "POST",
            "/api/v1/routes/with-cng-stop",
            json={
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 44.4949, "longitude": 11.3426},
                "mimit_station_id": "43690",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 409
    assert response.json()["code"] == code


def test_data_freshness_contract_is_explicit(monkeypatch: pytest.MonkeyPatch) -> None:
    evaluated_at = datetime.fromisoformat("2026-08-29T06:00:00+00:00")
    fresh = DataSourceFreshness("mimit_cng", "fresh", evaluated_at, evaluated_at, 0, 172_800)
    stale = DataSourceFreshness("osm_cng", "stale", evaluated_at, evaluated_at, 700_000, 604_800)
    reconciliation = DataSourceFreshness(
        "reconciliation", "fresh", evaluated_at, evaluated_at, 0, 172_800
    )
    report = DataFreshnessReport(
        evaluated_at=evaluated_at,
        overall_state="degraded",
        mimit=fresh,
        osm=stale,
        reconciliation=reconciliation,
    )
    monkeypatch.setattr("compass.api.system.load_data_freshness", lambda *args, **kwargs: report)
    app.dependency_overrides[get_session] = _override_session
    try:
        response = _request("GET", "/api/v1/data-freshness")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["overall_state"] == "degraded"
    assert [source["source_name"] for source in response.json()["sources"]] == [
        "mimit_cng",
        "osm_cng",
        "reconciliation",
    ]
    assert response.json()["traffic_state"] == "not_configured"


def test_phase7_openapi_contains_public_paths_and_shared_error_schema() -> None:
    schema = app.openapi()
    paths = schema["paths"]
    for path in (
        "/api/v1/routes",
        "/api/v1/cng/ranked-candidates",
        "/api/v1/cng/stations/{mimit_station_id}",
        "/api/v1/routes/with-cng-stop",
        "/api/v1/data-freshness",
        "/health/live",
        "/health/ready",
    ):
        assert path in paths
    assert "ErrorResponse" in schema["components"]["schemas"]
    route_schema = schema["components"]["schemas"]["RouteWithCngStopResponse"]
    assert route_schema["properties"]["legs"]["minItems"] == 2
    assert route_schema["properties"]["legs"]["maxItems"] == 2


def test_checked_openapi_artifact_matches_runtime_contract() -> None:
    checked = json.loads(Path("docs/openapi.json").read_text())
    assert checked == app.openapi()
