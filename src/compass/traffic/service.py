from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

from compass.config import Settings
from compass.detours.domain import NetworkCostBasis
from compass.routing.domain import Coordinate
from compass.traffic.domain import TrafficHealth, TrafficQualityPolicy
from compass.traffic.health import (
    JsonTrafficRuntimeHealthStore,
    TrafficRuntimeHealthError,
)
from compass.traffic.matching.openlr import NativeValhallaOpenLrDecoder
from compass.traffic.matching.valhalla import ValhallaTraceTrafficEdgeMatcher
from compass.traffic.providers.mock import MockTrafficProvider
from compass.traffic.providers.tomtom import TomTomTrafficProvider


def traffic_quality_policy(settings: Settings) -> TrafficQualityPolicy:
    return TrafficQualityPolicy(
        min_confidence=settings.traffic_min_confidence,
        max_age_seconds=settings.traffic_max_age_seconds,
        max_speed_kph=settings.traffic_max_speed_kph,
        min_match_confidence=settings.traffic_min_match_confidence,
    )


def build_traffic_edge_matcher(settings: Settings) -> ValhallaTraceTrafficEdgeMatcher:
    openlr_decoder = (
        NativeValhallaOpenLrDecoder(
            executable_path=settings.traffic_openlr_decoder_path,
            timeout_seconds=settings.traffic_openlr_decoder_timeout_seconds,
        )
        if settings.traffic_openlr_decoder_path
        else None
    )
    return ValhallaTraceTrafficEdgeMatcher(
        base_url=settings.valhalla_url,
        connect_timeout_seconds=settings.valhalla_connect_timeout_seconds,
        read_timeout_seconds=settings.valhalla_read_timeout_seconds,
        user_agent=settings.http_user_agent,
        search_radius_meters=settings.traffic_match_search_radius_meters,
        gps_accuracy_meters=settings.traffic_match_gps_accuracy_meters,
        openlr_endpoint_tolerance_meters=(
            settings.traffic_openlr_endpoint_tolerance_meters
        ),
        expected_tileset_identity=settings.traffic_valhalla_tileset_version,
        openlr_decoder=openlr_decoder,
    )


def build_traffic_provider(settings: Settings):
    if not settings.traffic_enabled:
        return None
    if settings.traffic_provider == "mock":
        fixture = (
            Path(settings.traffic_mock_fixture_path)
            if settings.traffic_mock_fixture_path
            else None
        )
        return MockTrafficProvider(
            fixture_path=fixture,
            freshness_seconds=settings.traffic_refresh_seconds,
        )
    if settings.traffic_provider == "tomtom":
        return TomTomTrafficProvider(
            endpoint_url=settings.tomtom_traffic_url,
            api_key=settings.tomtom_api_key,
            timeout_seconds=settings.tomtom_timeout_seconds,
            refresh_seconds=settings.traffic_refresh_seconds,
            segment_ttl_seconds=settings.traffic_max_age_seconds,
            api_mode=settings.tomtom_traffic_api_mode,
            flow_segment_points=_parse_probe_points(
                settings.tomtom_flow_segment_points
            ),
            flow_segment_style=settings.tomtom_flow_segment_style,
            flow_segment_zoom=settings.tomtom_flow_segment_zoom,
            flow_segment_unit=settings.tomtom_flow_segment_unit,
            flow_segment_openlr=settings.tomtom_flow_segment_openlr,
            max_retries=settings.tomtom_max_retries,
            max_concurrency=settings.tomtom_max_concurrency,
            backoff_base_seconds=settings.tomtom_backoff_base_seconds,
        )
    raise ValueError(f"unsupported traffic provider: {settings.traffic_provider}")


def traffic_health_from_settings(settings: Settings) -> TrafficHealth:
    if not settings.traffic_enabled:
        return TrafficHealth(
            enabled=False,
            provider=settings.traffic_provider,
            provider_status="not_configured",
            traffic_aware_routing=False,
            traffic_extract_path=settings.valhalla_traffic_extract,
            message="traffic is disabled",
        )
    state = "mock" if settings.traffic_provider == "mock" else "configured"
    if (
        settings.traffic_valhalla_overlay_enabled
        and settings.traffic_valhalla_tileset_version
    ):
        try:
            runtime = JsonTrafficRuntimeHealthStore(
                Path(settings.traffic_health_path)
            ).load(
                expected_provider=settings.traffic_provider,
                expected_tileset_identity=settings.traffic_valhalla_tileset_version,
            )
        except TrafficRuntimeHealthError:
            return TrafficHealth(
                enabled=True,
                provider=settings.traffic_provider,
                provider_status="unavailable",
                traffic_aware_routing=True,
                mapping_version=settings.traffic_mapping_version,
                valhalla_tileset_version=settings.traffic_valhalla_tileset_version,
                traffic_extract_path=settings.valhalla_traffic_extract,
                message=(
                    "traffic runtime health is invalid or belongs to another "
                    "provider/Valhalla tileset"
                ),
            )
        if runtime is not None:
            return runtime.as_health(
                evaluated_at=datetime.now(UTC),
                max_age_seconds=settings.traffic_max_age_seconds,
                traffic_extract_path=settings.valhalla_traffic_extract,
            )
    return TrafficHealth(
        enabled=True,
        provider=settings.traffic_provider,
        provider_status=state,
        traffic_aware_routing=settings.traffic_valhalla_overlay_enabled,
        mapping_version=settings.traffic_mapping_version,
        valhalla_tileset_version=settings.traffic_valhalla_tileset_version or None,
        traffic_extract_path=settings.valhalla_traffic_extract,
        message=(
            "traffic provider configured; Valhalla overlay enabled"
            if settings.traffic_valhalla_overlay_enabled
            else "traffic provider configured; Valhalla overlay not enabled"
        ),
    )


def _parse_probe_points(value: str) -> tuple[Coordinate, ...]:
    points: list[Coordinate] = []
    for raw_item in value.replace("\n", ";").split(";"):
        item = raw_item.strip()
        if not item:
            continue
        raw_latitude, separator, raw_longitude = item.partition(",")
        if not separator:
            raise ValueError("TomTom probe points must use lat,lon pairs")
        points.append(
            Coordinate(
                latitude=float(raw_latitude.strip()),
                longitude=float(raw_longitude.strip()),
            )
        )
    return tuple(points)


def network_cost_basis_from_settings(settings: Settings) -> NetworkCostBasis:
    health = traffic_health_from_settings(settings)
    traffic_aware = settings.traffic_enabled and settings.traffic_valhalla_overlay_enabled
    return NetworkCostBasis(
        traffic_state=health.provider_status,
        traffic_aware=traffic_aware,
        duration_model=(
            "valhalla_time_dependent_traffic"
            if traffic_aware
            else "valhalla_graph_speeds"
        ),
    )
