from __future__ import annotations

import asyncio
import time
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from email.utils import parsedate_to_datetime
from typing import Any

import httpx

from compass.routing.domain import Coordinate
from compass.traffic.domain import (
    OsmWayReference,
    TrafficFetchRequest,
    TrafficFlowSegment,
    TrafficHealth,
    TrafficProviderContractError,
    TrafficProviderMetrics,
    TrafficProviderSnapshot,
    TrafficProviderUnavailableError,
)


class TomTomTrafficProvider:
    """HTTP boundary for TomTom traffic APIs.

    The currently implemented mode targets the TomTom Traffic Flow API base
    `flowSegmentData` endpoint. That API is point/probe based, not a nationwide
    feed, so it is suitable for live development and corridor probes but not a
    drop-in replacement for Intermediate/Orbis bulk ingestion.
    """

    provider_name = "tomtom"

    def __init__(
        self,
        *,
        endpoint_url: str = "",
        api_key: str,
        timeout_seconds: float,
        refresh_seconds: float,
        segment_ttl_seconds: float | None = None,
        api_mode: str = "flow_segment",
        flow_segment_points: tuple[Coordinate, ...] = (),
        flow_segment_style: str = "absolute",
        flow_segment_zoom: int = 10,
        flow_segment_unit: str = "kmph",
        flow_segment_openlr: bool = True,
        max_retries: int = 3,
        max_concurrency: int = 2,
        backoff_base_seconds: float = 0.5,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("TomTom API key must be configured")
        if timeout_seconds <= 0:
            raise ValueError("TomTom timeout must be positive")
        if refresh_seconds <= 0:
            raise ValueError("traffic refresh interval must be positive")
        if segment_ttl_seconds is not None and segment_ttl_seconds <= 0:
            raise ValueError("traffic segment TTL must be positive")
        if api_mode not in {"flow_segment", "intermediate_json"}:
            raise ValueError("unsupported TomTom traffic API mode")
        if api_mode == "intermediate_json" and not endpoint_url:
            raise ValueError("TomTom intermediate JSON endpoint URL must be configured")
        if flow_segment_zoom < 0 or flow_segment_zoom > 22:
            raise ValueError("TomTom Flow Segment zoom must be between 0 and 22")
        if flow_segment_unit not in {"kmph", "mph"}:
            raise ValueError("TomTom Flow Segment unit must be kmph or mph")
        if max_retries < 0:
            raise ValueError("TomTom retry count must not be negative")
        if max_concurrency <= 0:
            raise ValueError("TomTom concurrency must be positive")
        self._endpoint_url = endpoint_url
        self._api_key = api_key
        self._timeout = httpx.Timeout(timeout_seconds)
        self._segment_ttl_seconds = segment_ttl_seconds or refresh_seconds * 2
        self._api_mode = api_mode
        self._flow_segment_points = flow_segment_points
        self._flow_segment_style = flow_segment_style
        self._flow_segment_zoom = flow_segment_zoom
        self._flow_segment_unit = flow_segment_unit
        self._flow_segment_openlr = flow_segment_openlr
        self._max_retries = max_retries
        self._max_concurrency = max_concurrency
        self._backoff_base_seconds = backoff_base_seconds
        self._headers = {"api-key": self._api_key}
        self._client = client
        self._last_status = TrafficHealth(
            enabled=True,
            provider=self.provider_name,
            provider_status="configured",
            traffic_aware_routing=False,
            message="TomTom provider configured; no fetch completed yet",
        )

    async def fetch_flow(
        self, request: TrafficFetchRequest | None = None
    ) -> TrafficProviderSnapshot:
        started = datetime.now(UTC)
        start_time = time.perf_counter()
        if self._api_mode == "flow_segment":
            points = request.probe_points if request is not None else self._flow_segment_points
            if not points:
                raise ValueError(
                    "TomTom Flow Segment mode requires route probe points or "
                    "TOMTOM_FLOW_SEGMENT_POINTS"
                )
            return await self._fetch_flow_segments(started, start_time, points=points)
        return await self._fetch_intermediate_json(started, start_time)

    async def _fetch_intermediate_json(
        self, started: datetime, start_time: float
    ) -> TrafficProviderSnapshot:
        response: httpx.Response | None = None
        for attempt in range(self._max_retries + 1):
            response = await self._get_intermediate_json()
            if response.status_code < 400:
                break
            if response.status_code == 429 or response.status_code >= 500:
                if attempt < self._max_retries:
                    await asyncio.sleep(self._backoff_base_seconds * (2**attempt))
                    continue
                self._last_status = TrafficHealth(
                    enabled=True,
                    provider=self.provider_name,
                    provider_status="unavailable",
                    traffic_aware_routing=False,
                    last_fetch_started_at=started,
                    last_fetch_completed_at=datetime.now(UTC),
                    message=f"TomTom traffic fetch failed with HTTP {response.status_code}",
                )
                raise TrafficProviderUnavailableError(
                    f"TomTom traffic fetch failed with HTTP {response.status_code}"
                )
            raise TrafficProviderContractError(
                f"TomTom traffic fetch was rejected with HTTP {response.status_code}"
            )

        assert response is not None
        latency = time.perf_counter() - start_time
        observed_at, segments, rejected = _normalise_payload(
            response,
            segment_ttl_seconds=self._segment_ttl_seconds,
        )
        snapshot = TrafficProviderSnapshot(
            provider=self.provider_name,
            observed_at=observed_at,
            segments=segments,
            metrics=TrafficProviderMetrics(
                provider_segments_received=len(segments) + rejected,
                segments_normalized=len(segments),
                rejected_segments=rejected,
                stale_segments=sum(
                    1 for segment in segments if segment.is_expired(datetime.now(UTC))
                ),
                provider_latency_seconds=latency,
            ),
        )
        self._mark_success(started, observed_at, snapshot, "TomTom traffic fetch completed")
        return snapshot

    async def _fetch_flow_segments(
        self,
        started: datetime,
        start_time: float,
        *,
        points: tuple[Coordinate, ...],
    ) -> TrafficProviderSnapshot:
        segments: list[TrafficFlowSegment] = []
        rejected = 0
        errors = 0
        latest_observed_at: datetime | None = None
        owns_client = self._client is None
        client = self._client or httpx.AsyncClient()
        semaphore = asyncio.Semaphore(self._max_concurrency)

        async def fetch(index: int, point: Coordinate):
            async with semaphore:
                try:
                    response = await self._get_flow_segment_with_retries(
                        point, client=client
                    )
                except TrafficProviderUnavailableError as error:
                    return index, point, error
                return index, point, response

        try:
            responses = await asyncio.gather(
                *(fetch(index, point) for index, point in enumerate(points))
            )
        finally:
            if owns_client:
                await client.aclose()

        for index, point, response in responses:
            if isinstance(response, TrafficProviderUnavailableError):
                errors += 1
                continue
            if response.status_code >= 400:
                if response.status_code == 429 or response.status_code >= 500:
                    errors += 1
                    continue
                rejected += 1
                continue
            try:
                observed_at, segment = _normalise_flow_segment_payload(
                    response,
                    point=point,
                    point_index=index,
                    segment_ttl_seconds=self._segment_ttl_seconds,
                )
            except (KeyError, TypeError, ValueError, TrafficProviderContractError):
                rejected += 1
                continue
            latest_observed_at = (
                observed_at
                if latest_observed_at is None
                else max(latest_observed_at, observed_at)
            )
            if all(
                existing.provider_segment_id != segment.provider_segment_id
                for existing in segments
            ):
                segments.append(segment)
        if errors and not segments:
            now = datetime.now(UTC)
            self._last_status = TrafficHealth(
                enabled=True,
                provider=self.provider_name,
                provider_status="unavailable",
                traffic_aware_routing=False,
                last_fetch_started_at=started,
                last_fetch_completed_at=now,
                message="TomTom Flow Segment probes failed",
            )
            raise TrafficProviderUnavailableError("TomTom Flow Segment probes failed")
        if rejected and not segments:
            raise TrafficProviderContractError(
                "TomTom Flow Segment probes returned no usable records"
            )

        latency = time.perf_counter() - start_time
        snapshot = TrafficProviderSnapshot(
            provider=self.provider_name,
            observed_at=latest_observed_at or started,
            segments=tuple(segments),
            metrics=TrafficProviderMetrics(
                provider_segments_received=len(points),
                segments_normalized=len(segments),
                rejected_segments=rejected,
                stale_segments=sum(
                    1 for segment in segments if segment.is_expired(datetime.now(UTC))
                ),
                api_errors=errors,
                provider_latency_seconds=latency,
            ),
        )
        self._mark_success(
            started,
            latest_observed_at or started,
            snapshot,
            "TomTom Flow Segment probes completed",
        )
        return snapshot

    def _mark_success(
        self,
        started: datetime,
        observed_at: datetime,
        snapshot: TrafficProviderSnapshot,
        message: str,
    ) -> None:
        now = datetime.now(UTC)
        self._last_status = TrafficHealth(
            enabled=True,
            provider=self.provider_name,
            provider_status="fresh",
            traffic_aware_routing=False,
            last_fetch_started_at=started,
            last_fetch_completed_at=now,
            last_success_at=now,
            provider_segments_received=snapshot.metrics.provider_segments_received,
            segments_normalized=snapshot.metrics.segments_normalized,
            feed_age_seconds=max(0.0, (now - observed_at).total_seconds()),
            message=message,
        )

    def status(self) -> TrafficHealth:
        return self._last_status

    async def _get_intermediate_json(self) -> httpx.Response:
        url = self._endpoint_url.replace("{api_key}", self._api_key)
        try:
            if self._client is not None:
                return await self._client.get(
                    url, timeout=self._timeout, headers=self._headers
                )
            async with httpx.AsyncClient() as client:
                return await client.get(url, timeout=self._timeout, headers=self._headers)
        except httpx.TransportError as error:
            raise TrafficProviderUnavailableError("TomTom traffic is unavailable") from error

    async def _get_flow_segment(
        self, point: Coordinate, *, client: httpx.AsyncClient
    ) -> httpx.Response:
        url = (
            self._endpoint_url
            or "https://api.tomtom.com/traffic/services/4/flowSegmentData/"
            f"{self._flow_segment_style}/{self._flow_segment_zoom}/json"
        )
        url = url.replace("{api_key}", self._api_key).replace(
            "{point}", _format_point(point)
        )
        params = {
            "key": self._api_key,
            "point": _format_point(point),
            "unit": self._flow_segment_unit,
            "openLr": str(self._flow_segment_openlr).lower(),
        }
        try:
            return await client.get(url, timeout=self._timeout, params=params)
        except httpx.TransportError as error:
            raise TrafficProviderUnavailableError("TomTom traffic is unavailable") from error

    async def _get_flow_segment_with_retries(
        self, point: Coordinate, *, client: httpx.AsyncClient
    ) -> httpx.Response:
        response: httpx.Response | None = None
        for attempt in range(self._max_retries + 1):
            response = await self._get_flow_segment(point, client=client)
            if response.status_code < 400:
                return response
            if response.status_code != 429 and response.status_code < 500:
                return response
            if attempt < self._max_retries:
                await asyncio.sleep(self._backoff_base_seconds * (2**attempt))
        assert response is not None
        return response


def _normalise_payload(
    response: httpx.Response, *, segment_ttl_seconds: float
) -> tuple[datetime, tuple[TrafficFlowSegment, ...], int]:
    content_type = response.headers.get("content-type", "")
    if "json" not in content_type:
        raise TrafficProviderContractError(
            "TomTom protobuf decoding is not wired in this process; provide a JSON fixture "
            "or run the future generated-schema decoder in the traffic updater."
        )
    payload = response.json()
    if not isinstance(payload, Mapping):
        raise TrafficProviderContractError("TomTom traffic payload must be an object")
    observed_at = _tomtom_observed_at(payload)
    records = payload.get("trafficFlow", payload.get("traffic_flow", payload.get("segments")))
    if not isinstance(records, list):
        raise TrafficProviderContractError("TomTom traffic payload has no segment list")
    segments: list[TrafficFlowSegment] = []
    rejected = 0
    for index, record in enumerate(records):
        if not isinstance(record, Mapping):
            rejected += 1
            continue
        try:
            segments.append(
                _normalise_record(
                    record,
                    observed_at=observed_at,
                    expires_at=observed_at + timedelta(seconds=segment_ttl_seconds),
                    fallback_id=f"tomtom:{index}",
                )
            )
        except (KeyError, TypeError, ValueError):
            rejected += 1
    return observed_at, tuple(segments), rejected


def _normalise_flow_segment_payload(
    response: httpx.Response,
    *,
    point: Coordinate,
    point_index: int,
    segment_ttl_seconds: float,
) -> tuple[datetime, TrafficFlowSegment]:
    payload = response.json()
    if not isinstance(payload, Mapping):
        raise TrafficProviderContractError("TomTom Flow Segment payload must be an object")
    record = payload.get("flowSegmentData")
    if not isinstance(record, Mapping):
        raise TrafficProviderContractError("TomTom Flow Segment payload is missing data")
    observed_at = _response_observed_at(response)
    expires_at = observed_at + timedelta(seconds=segment_ttl_seconds)
    geometry = _geometry(record.get("coordinates"))
    current_speed = _optional_float(record.get("currentSpeed"))
    free_flow_speed = _optional_float(record.get("freeFlowSpeed"))
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id=str(
            record.get(
                "id",
                record.get(
                    "openlr",
                    f"tomtom-flow:{point_index}:{point.latitude:.6f},{point.longitude:.6f}",
                ),
            )
        ),
        observed_at=observed_at,
        expires_at=expires_at,
        openlr=record.get("openlr") if isinstance(record.get("openlr"), str) else None,
        osm_way_ids=(),
        geometry=geometry,
        direction="unknown",
        current_speed_kph=current_speed,
        free_flow_speed_kph=free_flow_speed,
        travel_time_seconds=_optional_float(record.get("currentTravelTime")),
        free_flow_travel_time_seconds=_optional_float(record.get("freeFlowTravelTime")),
        confidence=_optional_float(record.get("confidence")),
        congestion=_congestion_from_speeds(current_speed, free_flow_speed),
        road_closed=bool(record.get("roadClosure", False)),
        prediction=False,
    )
    return observed_at, segment


def _tomtom_observed_at(payload: Mapping[str, Any]) -> datetime:
    meta = payload.get("metaInformation", payload.get("meta_information", {}))
    if not isinstance(meta, Mapping):
        raise TrafficProviderContractError("TomTom metaInformation must be an object")
    epoch = meta.get("createTimeUTCSeconds", meta.get("create_time_utc_seconds"))
    if isinstance(epoch, bool) or not isinstance(epoch, int | float):
        raise TrafficProviderContractError("TomTom createTimeUTCSeconds is missing")
    return datetime.fromtimestamp(float(epoch), tz=UTC)


def _response_observed_at(response: httpx.Response) -> datetime:
    value = response.headers.get("date")
    if value:
        try:
            parsed = parsedate_to_datetime(value)
        except (TypeError, ValueError):
            parsed = None
        if parsed is not None and parsed.tzinfo is not None:
            return parsed.astimezone(UTC)
    return datetime.now(UTC)


def _normalise_record(
    record: Mapping[str, Any],
    *,
    observed_at: datetime,
    expires_at: datetime,
    fallback_id: str,
) -> TrafficFlowSegment:
    location = record.get("location", {})
    if not isinstance(location, Mapping):
        raise ValueError("TomTom location must be an object")
    speed = record.get("speed", {})
    if isinstance(speed, list):
        speed = speed[0] if speed else {}
    if not isinstance(speed, Mapping):
        raise ValueError("TomTom speed must be an object")
    condition = str(speed.get("trafficCondition", speed.get("traffic_condition", ""))).upper()
    confidence = speed.get("confidence")
    if isinstance(confidence, int | float) and confidence > 1:
        confidence = float(confidence) / 100
    geometry = _geometry(location.get("geometry", record.get("geometry")))
    return TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id=str(record.get("id", record.get("provider_segment_id", fallback_id))),
        observed_at=observed_at,
        expires_at=expires_at,
        openlr=location.get("openlr") if isinstance(location.get("openlr"), str) else None,
        osm_way_ids=_osm_way_references(location),
        geometry=geometry,
        direction=str(record.get("direction", "unknown")).lower(),  # type: ignore[arg-type]
        current_speed_kph=_optional_float(
            speed.get("averageSpeedKmph", speed.get("currentSpeed"))
        ),
        free_flow_speed_kph=_optional_float(
            speed.get("freeFlowSpeedKmph", speed.get("freeFlowSpeed"))
        ),
        travel_time_seconds=_optional_float(
            speed.get("travelTimeSeconds", speed.get("currentTravelTime"))
        ),
        free_flow_travel_time_seconds=_optional_float(
            speed.get("freeFlowTravelTimeSeconds", speed.get("freeFlowTravelTime"))
        ),
        confidence=_optional_float(confidence),
        congestion=_congestion(condition, speed.get("relativeSpeed")),
        road_closed=condition == "CLOSED" or bool(record.get("roadClosure", False)),
        prediction=bool(record.get("prediction", False)),
    )


def _osm_way_references(location: Mapping[str, Any]) -> tuple[OsmWayReference, ...]:
    references: list[OsmWayReference] = []
    segment_groups = location.get("segmentIds", location.get("segment_ids", []))
    if isinstance(segment_groups, Mapping):
        segment_groups = [segment_groups]
    if not isinstance(segment_groups, list):
        return ()
    for group in segment_groups:
        if not isinstance(group, Mapping):
            continue
        if group.get("type") not in {"OSM_WAY_ID", "osm_way_id", "OSM"}:
            continue
        entries = group.get("segmentId", group.get("segment_id", []))
        if not entries and group.get("id") is not None:
            entries = [group]
        if isinstance(entries, Mapping):
            entries = [entries]
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if not isinstance(entry, Mapping) or entry.get("id") is None:
                continue
            references.append(
                OsmWayReference(
                    way_id=int(entry["id"]),
                    backwards=(
                        bool(entry["backwards"])
                        if entry.get("backwards") is not None
                        else None
                    ),
                    start_offset_meters=_optional_float(
                        entry.get("startOffsetInMeters")
                    ),
                    end_offset_meters=_optional_float(entry.get("endOffsetInMeters")),
                )
            )
    return tuple(references)


def _geometry(value: Any) -> tuple[Coordinate, ...] | None:
    if value is None:
        return None
    if isinstance(value, Mapping):
        value = value.get("coordinates", value.get("coordinate", value.get("points")))
    if not isinstance(value, list):
        return None
    points = []
    for item in value:
        if isinstance(item, Mapping):
            points.append(
                Coordinate(
                    latitude=float(item.get("latitude", item.get("lat"))),
                    longitude=float(item.get("longitude", item.get("lon"))),
                )
            )
        elif isinstance(item, list | tuple) and len(item) >= 2:
            points.append(Coordinate(latitude=float(item[1]), longitude=float(item[0])))
    return tuple(points) if len(points) >= 2 else None


def _congestion(condition: str, relative_speed: Any) -> float | None:
    if isinstance(relative_speed, int | float):
        return max(0.0, min(1.0, 1.0 - float(relative_speed)))
    return {
        "FREE_TRAFFIC": 0.0,
        "HEAVY_TRAFFIC": 0.25,
        "SLOW_TRAFFIC": 0.55,
        "QUEUING_TRAFFIC": 0.75,
        "STATIONARY_TRAFFIC": 0.95,
        "CLOSED": 1.0,
        "UNKNOWN": None,
        "": None,
    }.get(condition)


def _congestion_from_speeds(
    current_speed_kph: float | None, free_flow_speed_kph: float | None
) -> float | None:
    if current_speed_kph is None or free_flow_speed_kph is None:
        return None
    if free_flow_speed_kph <= 0:
        return None
    return max(0.0, min(1.0, 1.0 - (current_speed_kph / free_flow_speed_kph)))


def _optional_float(value: Any) -> float | None:
    return float(value) if value is not None else None


def _format_point(point: Coordinate) -> str:
    return f"{point.latitude:.6f},{point.longitude:.6f}"
