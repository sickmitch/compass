import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import httpx
import pytest

from compass.config import Settings
from compass.routing.domain import Coordinate
from compass.traffic.cli import _matching_source_reference
from compass.traffic.domain import (
    TrafficEdgeUpdate,
    TrafficFlowSegment,
    TrafficProviderUnavailableError,
    TrafficQualityPolicy,
)
from compass.traffic.matching.openlr import (
    DecodedOpenLrLine,
    parse_decoded_openlr_line,
)
from compass.traffic.matching.valhalla import ValhallaTraceTrafficEdgeMatcher
from compass.traffic.providers.mock import MockTrafficProvider
from compass.traffic.providers.tomtom import TomTomTrafficProvider
from compass.traffic.service import (
    build_traffic_provider,
    network_cost_basis_from_settings,
    traffic_health_from_settings,
)
from compass.traffic.valhalla.graph_id import graph_id_to_string
from compass.traffic.valhalla.overlay import (
    ValhallaTrafficOverlayConfig,
    build_extract_command,
)

FIXTURE = Path(__file__).parent / "fixtures" / "traffic" / "mock_flow_segments.json"
REPOSITORY_ROOT = Path(__file__).parents[1]


class _StaticOpenLrDecoder:
    def __init__(self, *lrps: Coordinate) -> None:
        self._lrps = lrps

    async def decode_line(self, reference: str) -> DecodedOpenLrLine:
        return DecodedOpenLrLine(reference=reference, lrps=self._lrps)


def test_mock_provider_loads_required_fixture_scenarios() -> None:
    provider = MockTrafficProvider(fixture_path=FIXTURE)

    snapshot = asyncio.run(provider.fetch_flow())
    ids = {segment.provider_segment_id for segment in snapshot.segments}

    assert snapshot.provider == "mock"
    assert "free-flow-road" in ids
    assert "heavy-congestion" in ids
    assert "stationary-traffic" in ids
    assert "closed-road" in ids
    assert "bidirectional-one-way-congested" in ids
    assert "same-osm-way-segment-a" in ids
    assert "same-osm-way-segment-b" in ids
    assert "openlr-only" in ids
    assert "low-confidence" in ids
    assert "stale-record" in ids
    assert snapshot.metrics.provider_segments_received == len(snapshot.segments)


def test_quality_policy_rejects_low_confidence_stale_and_impossible_speed() -> None:
    now = datetime(2026, 8, 31, 10, 0, tzinfo=UTC)
    policy = TrafficQualityPolicy(
        min_confidence=0.5,
        max_age_seconds=300,
        max_speed_kph=180,
        min_match_confidence=0.75,
    )
    accepted = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="accepted",
        observed_at=now - timedelta(seconds=60),
        expires_at=now + timedelta(seconds=60),
        confidence=0.9,
        current_speed_kph=80,
    )
    low_confidence = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="low-confidence",
        observed_at=now - timedelta(seconds=60),
        expires_at=now + timedelta(seconds=60),
        confidence=0.2,
        current_speed_kph=80,
    )
    stale = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="stale",
        observed_at=now - timedelta(seconds=301),
        expires_at=now + timedelta(seconds=60),
        confidence=0.9,
        current_speed_kph=80,
    )
    too_fast = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="too-fast",
        observed_at=now - timedelta(seconds=60),
        expires_at=now + timedelta(seconds=60),
        confidence=0.9,
        current_speed_kph=181,
    )

    assert policy.accepts(accepted, evaluated_at=now) is True
    assert policy.accepts(low_confidence, evaluated_at=now) is False
    assert policy.accepts(stale, evaluated_at=now) is False
    assert policy.accepts(too_fast, evaluated_at=now) is False


def test_zero_speed_requires_explicit_closure() -> None:
    with pytest.raises(ValueError, match="explicit closures"):
        TrafficEdgeUpdate(graph_id="1/2/3", speed_kph=0, closed=False)

    closed = TrafficEdgeUpdate(graph_id="1/2/3", speed_kph=0, closed=True)

    assert closed.closed is True


def test_traffic_health_and_network_cost_basis_defaults() -> None:
    settings = Settings(_env_file=None)

    health = traffic_health_from_settings(settings)
    cost_basis = network_cost_basis_from_settings(settings)

    assert health.provider_status == "not_configured"
    assert health.traffic_aware_routing is False
    assert cost_basis.traffic_state == "not_configured"
    assert cost_basis.traffic_aware is False
    assert cost_basis.duration_model == "valhalla_graph_speeds"


def test_traffic_health_and_network_cost_basis_when_overlay_enabled() -> None:
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="mock",
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version="tileset-test",
    )

    health = traffic_health_from_settings(settings)
    cost_basis = network_cost_basis_from_settings(settings)

    assert health.provider_status == "mock"
    assert health.traffic_aware_routing is True
    assert cost_basis.traffic_state == "mock"
    assert cost_basis.traffic_aware is True
    assert cost_basis.duration_model == "valhalla_time_dependent_traffic"


def test_provider_is_not_built_when_traffic_is_disabled() -> None:
    settings = Settings(_env_file=None, traffic_provider="mock")

    assert build_traffic_provider(settings) is None


def test_build_tomtom_flow_segment_provider_from_settings() -> None:
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="tomtom",
        tomtom_api_key="test-key",
        tomtom_flow_segment_points="45.4642,9.19;44.4949,11.3426",
    )

    provider = build_traffic_provider(settings)

    assert isinstance(provider, TomTomTrafficProvider)


def test_tomtom_feed_configuration_is_validated_when_provider_is_built() -> None:
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="tomtom",
    )

    with pytest.raises(ValueError, match="API key"):
        build_traffic_provider(settings)

    settings = settings.model_copy(update={"tomtom_api_key": "test-key"})
    provider = build_traffic_provider(settings)
    assert isinstance(provider, TomTomTrafficProvider)
    with pytest.raises(ValueError, match="route probe points"):
        asyncio.run(provider.fetch_flow())


def test_tomtom_json_adapter_normalizes_flow_records_and_retries_429() -> None:
    payload = {
        "metaInformation": {"createTimeUTCSeconds": 1788151200},
        "trafficFlow": [
            {
                "id": "tomtom-1",
                "location": {
                    "openlr": "CwRbWyNG9RpsCQCb/9UYBQ==",
                    "segmentIds": [
                        {"type": "OSM_WAY_ID", "id": 123456, "backwards": False}
                    ],
                    "geometry": [
                        {"latitude": 45.0, "longitude": 9.0},
                        {"latitude": 45.1, "longitude": 9.1},
                    ],
                },
                "speed": {
                    "averageSpeedKmph": 35,
                    "freeFlowSpeedKmph": 90,
                    "travelTimeSeconds": 180,
                    "freeFlowTravelTimeSeconds": 70,
                    "confidence": 0.84,
                    "relativeSpeed": 0.39,
                    "trafficCondition": "CONGESTION",
                },
            }
        ],
    }
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        assert request.headers["api-key"] == "test-key"
        if calls == 1:
            return httpx.Response(429, json={"error": "rate limited"})
        return httpx.Response(200, json=payload)

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    provider = TomTomTrafficProvider(
        endpoint_url="https://traffic.example.test/feed",
        api_key="test-key",
        timeout_seconds=1,
        refresh_seconds=60,
        api_mode="intermediate_json",
        max_retries=1,
        backoff_base_seconds=0.001,
        client=client,
    )
    try:
        snapshot = asyncio.run(provider.fetch_flow())
    finally:
        asyncio.run(client.aclose())

    assert calls == 2
    assert len(snapshot.segments) == 1
    segment = snapshot.segments[0]
    assert segment.provider == "tomtom"
    assert segment.provider_segment_id == "tomtom-1"
    assert segment.openlr == "CwRbWyNG9RpsCQCb/9UYBQ=="
    assert segment.osm_way_ids[0].way_id == 123456
    assert segment.current_speed_kph == 35
    assert segment.congestion == 0.61


def test_tomtom_provider_reports_unavailable_after_retry_budget() -> None:
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(503, json={"error": "down"})
        )
    )
    provider = TomTomTrafficProvider(
        endpoint_url="https://traffic.example.test/feed",
        api_key="test-key",
        timeout_seconds=1,
        refresh_seconds=60,
        api_mode="intermediate_json",
        max_retries=0,
        backoff_base_seconds=0.001,
        client=client,
    )
    try:
        with pytest.raises(TrafficProviderUnavailableError):
            asyncio.run(provider.fetch_flow())
    finally:
        asyncio.run(client.aclose())


def test_tomtom_flow_segment_adapter_normalizes_base_traffic_api_response() -> None:
    payload = {
        "flowSegmentData": {
            "frc": "FRC2",
            "currentSpeed": 41,
            "freeFlowSpeed": 70,
            "currentTravelTime": 153,
            "freeFlowTravelTime": 90,
            "confidence": 0.59,
            "roadClosure": False,
            "openlr": "CwRbWyNG9RpsCQCb/9UYBQ==",
            "coordinates": {
                "coordinate": [
                    {"latitude": 45.0, "longitude": 9.0},
                    {"latitude": 45.1, "longitude": 9.1},
                ]
            },
        }
    }
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        assert request.url.params["key"] == "test-key"
        assert request.url.params["point"] == "45.464200,9.190000"
        assert request.url.params["unit"] == "kmph"
        assert request.url.params["openLr"] == "true"
        if calls == 1:
            return httpx.Response(429, json={"error": "rate limited"})
        return httpx.Response(
            200,
            json=payload,
            headers={"Date": "Mon, 31 Aug 2026 08:00:00 GMT"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    provider = TomTomTrafficProvider(
        api_key="test-key",
        timeout_seconds=1,
        refresh_seconds=60,
        api_mode="flow_segment",
        flow_segment_points=(Coordinate(latitude=45.4642, longitude=9.19),),
        max_retries=1,
        backoff_base_seconds=0.001,
        client=client,
    )
    try:
        snapshot = asyncio.run(provider.fetch_flow())
    finally:
        asyncio.run(client.aclose())

    assert calls == 2
    assert len(snapshot.segments) == 1
    segment = snapshot.segments[0]
    assert segment.provider_segment_id == "CwRbWyNG9RpsCQCb/9UYBQ=="
    assert segment.openlr == "CwRbWyNG9RpsCQCb/9UYBQ=="
    assert segment.osm_way_ids == ()
    assert segment.current_speed_kph == 41
    assert segment.free_flow_speed_kph == 70
    assert segment.travel_time_seconds == 153
    assert segment.congestion == pytest.approx(1 - 41 / 70)
    assert snapshot.observed_at == datetime(2026, 8, 31, 8, 0, tzinfo=UTC)


def test_tomtom_route_probes_use_bounded_concurrency_and_deduplicate_segments() -> None:
    payload = {
        "flowSegmentData": {
            "id": "same-road-segment",
            "currentSpeed": 41,
            "freeFlowSpeed": 70,
            "confidence": 0.9,
            "roadClosure": False,
            "openlr": "CwRbWyNG9RpsCQCb/9UYBQ==",
            "coordinates": {
                "coordinate": [
                    {"latitude": 45.0, "longitude": 9.0},
                    {"latitude": 45.1, "longitude": 9.1},
                ]
            },
        }
    }
    active = 0
    maximum_active = 0

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal active, maximum_active
        active += 1
        maximum_active = max(maximum_active, active)
        await asyncio.sleep(0.01)
        active -= 1
        return httpx.Response(
            200,
            json=payload,
            headers={"Date": "Mon, 31 Aug 2026 08:00:00 GMT"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    provider = TomTomTrafficProvider(
        api_key="test-key",
        timeout_seconds=1,
        refresh_seconds=60,
        api_mode="flow_segment",
        flow_segment_points=(
            Coordinate(45.0, 9.0),
            Coordinate(45.1, 9.1),
            Coordinate(45.2, 9.2),
        ),
        max_concurrency=2,
        client=client,
    )
    try:
        snapshot = asyncio.run(provider.fetch_flow())
    finally:
        asyncio.run(client.aclose())

    assert maximum_active == 2
    assert snapshot.metrics.provider_segments_received == 3
    assert snapshot.metrics.segments_normalized == 1
    assert len(snapshot.segments) == 1


def test_valhalla_traffic_overlay_extract_command_uses_native_tool() -> None:
    config = ValhallaTrafficOverlayConfig(
        valhalla_config=Path("/custom_files/valhalla.json"),
        tile_dir=Path("/custom_files/valhalla_tiles"),
        tile_extract=Path("/custom_files/valhalla_tiles.tar"),
        traffic_extract=Path("/custom_files/traffic.tar"),
    )

    assert build_extract_command(config) == (
        "valhalla_build_extract",
        "--config",
        "/custom_files/valhalla.json",
        "--with-traffic",
        "--overwrite",
    )


def test_graph_id_decoder_accepts_valhalla_integer_and_string_forms() -> None:
    assert graph_id_to_string(7990054695074) == "2/779796/238122"
    assert graph_id_to_string("2/779796/238122") == "2/779796/238122"
    assert graph_id_to_string("bad") is None
    assert graph_id_to_string(True) is None


def test_valhalla_geometry_matcher_returns_ordered_directed_edges() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path == "/status":
            return httpx.Response(
                200,
                json={"version": "3.8.3", "tileset_last_modified": 123},
            )
        payload = json.loads(request.content)
        assert payload["shape_match"] == "map_snap"
        assert payload["shape"][0] == {
            "lat": 45.0,
            "lon": 9.0,
            "type": "via",
        }
        return httpx.Response(
            200,
            json={
                "confidence_score": 0.98,
                "edges": [
                    {
                        "id": 7990054695074,
                        "length": 0.055,
                        "way_id": 123456,
                    },
                    {
                        "id": "2/779796/238123",
                        "length": 0.056,
                        "way_id": 123456,
                    },
                ],
                "matched_points": [
                    {"type": "matched", "distance_from_trace_point": 1.0},
                    {"type": "matched", "distance_from_trace_point": 2.0},
                ],
            },
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    matcher = ValhallaTraceTrafficEdgeMatcher(
        base_url="http://valhalla.test",
        connect_timeout_seconds=1,
        read_timeout_seconds=1,
        user_agent="test",
        expected_tileset_identity="valhalla-3.8.3:123",
        client=client,
    )
    segment = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="segment-1",
        observed_at=datetime(2026, 8, 31, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 8, 31, 10, 5, tzinfo=UTC),
        openlr="CwRbWyNG9RpsCQCb/9UYBQ==",
        osm_way_ids=(),
        geometry=(
            Coordinate(latitude=45.0, longitude=9.0),
            Coordinate(latitude=45.0, longitude=9.00141),
        ),
        direction="forward",
        current_speed_kph=40,
    )
    try:
        match = asyncio.run(matcher.match(segment))
    finally:
        asyncio.run(client.aclose())

    assert calls == ["/status", "/trace_attributes"]
    assert match.directed_edge_ids == (
        "2/779796/238122",
        "2/779796/238123",
    )
    assert match.match_method == "geometry_trace"
    assert match.confidence > 0.9
    assert match.direction_match is True
    assert match.valhalla_tileset_version == "valhalla-3.8.3:123"
    assert match.mapping_version == "valhalla-trace-v1"
    assert any("OpenLR was not decoded" in warning for warning in match.warnings)


def test_valhalla_matcher_verifies_unknown_direction_with_native_openlr() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/status":
            return httpx.Response(
                200, json={"version": "3.8.3", "tileset_last_modified": 123}
            )
        return httpx.Response(
            200,
            json={
                "confidence_score": 0.98,
                "edges": [{"id": "2/779796/238123", "length": 0.111}],
                "matched_points": [
                    {"type": "matched", "distance_from_trace_point": 1.0},
                    {"type": "matched", "distance_from_trace_point": 1.0},
                ],
            },
        )

    start = Coordinate(latitude=45.0, longitude=9.0)
    end = Coordinate(latitude=45.0, longitude=9.00141)
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    matcher = ValhallaTraceTrafficEdgeMatcher(
        base_url="http://valhalla.test",
        connect_timeout_seconds=1,
        read_timeout_seconds=1,
        user_agent="test",
        expected_tileset_identity="valhalla-3.8.3:123",
        openlr_decoder=_StaticOpenLrDecoder(start, end),
        client=client,
    )
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id="segment-1",
        observed_at=datetime(2026, 8, 31, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 8, 31, 10, 5, tzinfo=UTC),
        openlr="CwajFyA9fAEJRxG5+OgBGw==",
        geometry=(start, end),
        direction="unknown",
        current_speed_kph=40,
    )
    try:
        match = asyncio.run(matcher.match(segment))
    finally:
        asyncio.run(client.aclose())

    assert match.confidence > 0.9
    assert match.direction_match is True
    assert match.mapping_version == "valhalla-openlr-geometry-v1"
    assert any("OpenLR direction verified" in warning for warning in match.warnings)


def test_valhalla_matcher_rejects_geometry_opposite_to_openlr() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/status":
            return httpx.Response(
                200, json={"version": "3.8.3", "tileset_last_modified": 123}
            )
        return httpx.Response(
            200,
            json={
                "confidence_score": 0.99,
                "edges": [{"id": "2/779796/238123", "length": 0.111}],
            },
        )

    start = Coordinate(latitude=45.0, longitude=9.0)
    end = Coordinate(latitude=45.0, longitude=9.00141)
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    matcher = ValhallaTraceTrafficEdgeMatcher(
        base_url="http://valhalla.test",
        connect_timeout_seconds=1,
        read_timeout_seconds=1,
        user_agent="test",
        expected_tileset_identity="valhalla-3.8.3:123",
        openlr_decoder=_StaticOpenLrDecoder(end, start),
        client=client,
    )
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id="segment-1",
        observed_at=datetime(2026, 8, 31, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 8, 31, 10, 5, tzinfo=UTC),
        openlr="CwajFyA9fAEJRxG5+OgBGw==",
        geometry=(start, end),
        current_speed_kph=40,
    )
    try:
        match = asyncio.run(matcher.match(segment))
    finally:
        asyncio.run(client.aclose())

    assert match.confidence == 0
    assert match.direction_match is False
    assert any("does not align" in warning for warning in match.warnings)


def test_valhalla_geometry_matcher_rejects_tileset_identity_mismatch() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return httpx.Response(
            200,
            json={"version": "3.8.3", "tileset_last_modified": 456},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    matcher = ValhallaTraceTrafficEdgeMatcher(
        base_url="http://valhalla.test",
        connect_timeout_seconds=1,
        read_timeout_seconds=1,
        user_agent="test",
        expected_tileset_identity="valhalla-3.8.3:123",
        client=client,
    )
    segment = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="segment-1",
        observed_at=datetime(2026, 8, 31, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 8, 31, 10, 5, tzinfo=UTC),
        geometry=(
            Coordinate(latitude=45.0, longitude=9.0),
            Coordinate(latitude=45.0, longitude=9.001),
        ),
    )
    try:
        match = asyncio.run(matcher.match(segment))
    finally:
        asyncio.run(client.aclose())

    assert calls == ["/status"]
    assert match.directed_edge_ids == ()
    assert match.confidence == 0
    assert "does not match" in match.warnings[0]


def test_valhalla_geometry_matcher_does_not_guess_without_geometry() -> None:
    matcher = ValhallaTraceTrafficEdgeMatcher(
        base_url="http://valhalla.test",
        connect_timeout_seconds=1,
        read_timeout_seconds=1,
        user_agent="test",
    )
    segment = TrafficFlowSegment(
        provider="mock",
        provider_segment_id="openlr-only",
        observed_at=datetime(2026, 8, 31, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 8, 31, 10, 5, tzinfo=UTC),
        openlr="CwRbWyNG9RpsCQCb/9UYBQ==",
    )

    match = asyncio.run(matcher.match(segment))

    assert match.directed_edge_ids == ()
    assert match.match_method == "unmatched"
    assert "no safe graph path can be traced" in match.warnings[0]


def test_native_openlr_helper_compiles_as_cpp20() -> None:
    source = (
        REPOSITORY_ROOT / "tools" / "valhalla-traffic" / "traffic_tar_tool.cc"
    ).read_text(encoding="utf-8")
    dockerfile = (
        REPOSITORY_ROOT / "tools" / "valhalla-traffic" / "Dockerfile"
    ).read_text(encoding="utf-8")

    assert "#include <valhalla/baldr/openlr.h>" in source
    assert "g++ -std=c++20" in dockerfile
    assert "-L/usr/local/lib -lvalhalla" in dockerfile


def test_native_openlr_output_parser_preserves_ordered_endpoints() -> None:
    reference = "CwajFyA9fAEJRxG5+OgBGw=="
    decoded = parse_decoded_openlr_line(
        {
            "reference": reference,
            "canonical_reference": reference,
            "location_type": "line",
            "line_direction": "first_lrp_to_last_lrp",
            "lrps": [
                {"index": 0, "latitude": 45.1, "longitude": 9.1},
                {"index": 1, "latitude": 45.2, "longitude": 9.2},
            ],
        },
        expected_reference=reference,
    )

    assert decoded.reference == reference
    assert decoded.lrps == (
        Coordinate(latitude=45.1, longitude=9.1),
        Coordinate(latitude=45.2, longitude=9.2),
    )


def test_traffic_updater_image_is_pinned_to_valhalla_native_runtime() -> None:
    dockerfile = (REPOSITORY_ROOT / "Dockerfile.traffic").read_text(encoding="utf-8")
    compose = (REPOSITORY_ROOT / "compose.yaml").read_text(encoding="utf-8")

    assert "FROM ${VALHALLA_IMAGE}" in dockerfile
    assert "g++ -std=c++20" in dockerfile
    assert "-L/usr/local/lib -lvalhalla" in dockerfile
    assert "dockerfile: Dockerfile.traffic" in compose
    assert "TRAFFIC_OPENLR_DECODER_PATH" in compose
    assert 'user: "0:0"' in compose
    assert 'cap_drop: ["ALL"]' in compose
    assert 'security_opt: ["no-new-privileges:true"]' in compose
    assert "read_only: true" in compose
    assert "/custom_files/compass_traffic_state/state.json" in compose
    assert "/custom_files/compass_traffic_state/health.json" in compose
    assert "valhalla_data:/custom_files:ro" in compose
    assert "traffic_state:/var/lib/compass-traffic" not in compose


def test_matching_diagnostic_exposes_provider_reference_without_speed() -> None:
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id="segment-1",
        observed_at=datetime(2026, 8, 31, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 8, 31, 10, 5, tzinfo=UTC),
        openlr="CwajFyA9fAEJRxG5+OgBGw==",
        geometry=(
            Coordinate(latitude=45.1, longitude=9.1),
            Coordinate(latitude=45.2, longitude=9.2),
        ),
        current_speed_kph=35,
    )

    assert _matching_source_reference(segment) == {
        "openlr": "CwajFyA9fAEJRxG5+OgBGw==",
        "direction": "unknown",
        "geometry": [
            {"latitude": 45.1, "longitude": 9.1},
            {"latitude": 45.2, "longitude": 9.2},
        ],
    }
