from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from compass.routing.domain import Coordinate
from compass.traffic.domain import (
    OsmWayReference,
    TrafficFetchRequest,
    TrafficFlowSegment,
    TrafficHealth,
    TrafficProviderMetrics,
    TrafficProviderSnapshot,
)


class MockTrafficProvider:
    provider_name = "mock"

    def __init__(
        self,
        *,
        fixture_path: Path | None = None,
        now: datetime | None = None,
        freshness_seconds: float = 300,
    ) -> None:
        self._fixture_path = fixture_path
        self._now = now
        self._freshness_seconds = freshness_seconds
        self._last_snapshot: TrafficProviderSnapshot | None = None

    async def fetch_flow(
        self, request: TrafficFetchRequest | None = None
    ) -> TrafficProviderSnapshot:
        observed_at = _aware_utc(self._now or datetime.now(UTC))
        raw_segments = (
            json.loads(self._fixture_path.read_text(encoding="utf-8"))
            if self._fixture_path is not None
            else _default_fixture(observed_at)
        )
        if not isinstance(raw_segments, list):
            raise ValueError("mock traffic fixture must contain a JSON array")
        segments: list[TrafficFlowSegment] = []
        rejected = 0
        stale = 0
        for raw in raw_segments:
            try:
                segment = _segment_from_mapping(raw, observed_at=observed_at)
            except (TypeError, ValueError, KeyError):
                rejected += 1
                continue
            if segment.is_expired(observed_at):
                stale += 1
            segments.append(segment)
        snapshot = TrafficProviderSnapshot(
            provider=self.provider_name,
            observed_at=observed_at,
            segments=tuple(segments),
            metrics=TrafficProviderMetrics(
                provider_segments_received=len(raw_segments),
                segments_normalized=len(segments),
                rejected_segments=rejected,
                stale_segments=stale,
            ),
        )
        self._last_snapshot = snapshot
        return snapshot

    def status(self) -> TrafficHealth:
        if self._last_snapshot is None:
            return TrafficHealth(
                enabled=True,
                provider=self.provider_name,
                provider_status="mock",
                traffic_aware_routing=False,
                message="mock traffic provider configured; no fetch completed yet",
            )
        return TrafficHealth(
            enabled=True,
            provider=self.provider_name,
            provider_status="mock",
            traffic_aware_routing=False,
            last_fetch_completed_at=self._last_snapshot.observed_at,
            last_success_at=self._last_snapshot.observed_at,
            provider_segments_received=(
                self._last_snapshot.metrics.provider_segments_received
            ),
            segments_normalized=self._last_snapshot.metrics.segments_normalized,
            feed_age_seconds=0,
            message="mock traffic provider using deterministic fixture data",
        )


def _segment_from_mapping(
    value: dict[str, Any], *, observed_at: datetime
) -> TrafficFlowSegment:
    segment_observed_at = _parse_time(value.get("observed_at")) or observed_at
    expires_at = _parse_time(value.get("expires_at")) or (
        segment_observed_at + timedelta(seconds=float(value.get("ttl_seconds", 300)))
    )
    geometry_value = value.get("geometry")
    geometry = None
    if geometry_value is not None:
        if not isinstance(geometry_value, list):
            raise ValueError("geometry must be a coordinate array")
        geometry = tuple(
            Coordinate(latitude=float(point["latitude"]), longitude=float(point["longitude"]))
            for point in geometry_value
        )
    osm_refs = tuple(
        OsmWayReference(
            way_id=int(item["way_id"]),
            backwards=item.get("backwards"),
            start_offset_meters=(
                float(item["start_offset_meters"])
                if item.get("start_offset_meters") is not None
                else None
            ),
            end_offset_meters=(
                float(item["end_offset_meters"])
                if item.get("end_offset_meters") is not None
                else None
            ),
        )
        for item in value.get("osm_way_ids", [])
    )
    return TrafficFlowSegment(
        provider="mock",
        provider_segment_id=str(value["provider_segment_id"]),
        observed_at=segment_observed_at,
        expires_at=expires_at,
        openlr=value.get("openlr"),
        osm_way_ids=osm_refs,
        geometry=geometry,
        direction=value.get("direction", "unknown"),
        current_speed_kph=_optional_float(value.get("current_speed_kph")),
        free_flow_speed_kph=_optional_float(value.get("free_flow_speed_kph")),
        travel_time_seconds=_optional_float(value.get("travel_time_seconds")),
        free_flow_travel_time_seconds=_optional_float(
            value.get("free_flow_travel_time_seconds")
        ),
        confidence=_optional_float(value.get("confidence")),
        congestion=_optional_float(value.get("congestion")),
        road_closed=bool(value.get("road_closed", False)),
        prediction=bool(value.get("prediction", False)),
    )


def _default_fixture(observed_at: datetime) -> list[dict[str, Any]]:
    return [
        {
            "provider_segment_id": "mock-free-flow",
            "observed_at": observed_at.isoformat(),
            "ttl_seconds": 300,
            "openlr": "mock-openlr-free-flow",
            "osm_way_ids": [{"way_id": 1001, "backwards": False}],
            "direction": "forward",
            "current_speed_kph": 90,
            "free_flow_speed_kph": 92,
            "travel_time_seconds": 40,
            "free_flow_travel_time_seconds": 39,
            "confidence": 0.99,
            "congestion": 0.05,
        },
        {
            "provider_segment_id": "mock-heavy-congestion",
            "observed_at": observed_at.isoformat(),
            "ttl_seconds": 300,
            "openlr": "mock-openlr-heavy",
            "osm_way_ids": [{"way_id": 1002, "backwards": False}],
            "direction": "forward",
            "current_speed_kph": 15,
            "free_flow_speed_kph": 90,
            "travel_time_seconds": 240,
            "free_flow_travel_time_seconds": 40,
            "confidence": 0.95,
            "congestion": 0.84,
        },
    ]


def _optional_float(value: Any) -> float | None:
    return float(value) if value is not None else None


def _parse_time(value: Any) -> datetime | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError("timestamp must be an ISO 8601 string")
    return _aware_utc(datetime.fromisoformat(value.replace("Z", "+00:00")))


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("traffic timestamps must include a UTC offset")
    return value.astimezone(UTC)
