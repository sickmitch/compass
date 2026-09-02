from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from math import isfinite
from typing import Literal, Protocol

from compass.routing.domain import Coordinate

TrafficHealthState = Literal[
    "not_configured",
    "configured",
    "mock",
    "fresh",
    "stale",
    "unavailable",
]
TrafficDirection = Literal["forward", "backward", "both", "unknown"]
TrafficMatchMethod = Literal[
    "openlr",
    "geometry_trace",
    "osm_way_hint",
    "manual",
    "unmatched",
]
TrafficRefreshTrigger = Literal["route_calculation", "active_navigation"]


class TrafficProviderError(Exception):
    """Base traffic provider failure."""


class TrafficProviderUnavailableError(TrafficProviderError):
    """Provider is temporarily unavailable or rate-limited."""


class TrafficProviderContractError(TrafficProviderError):
    """Provider returned malformed or unsupported traffic records."""


@dataclass(frozen=True, slots=True)
class OsmWayReference:
    way_id: int
    backwards: bool | None = None
    start_offset_meters: float | None = None
    end_offset_meters: float | None = None

    def __post_init__(self) -> None:
        if self.way_id <= 0:
            raise ValueError("OSM way ID must be positive")
        for field_name in ("start_offset_meters", "end_offset_meters"):
            value = getattr(self, field_name)
            if value is not None and (not isfinite(value) or value < 0):
                raise ValueError(f"{field_name} must be a non-negative finite value")


@dataclass(frozen=True, slots=True)
class TrafficFlowSegment:
    provider: str
    provider_segment_id: str
    observed_at: datetime
    expires_at: datetime
    openlr: str | None = None
    osm_way_ids: tuple[OsmWayReference, ...] = ()
    geometry: tuple[Coordinate, ...] | None = None
    direction: TrafficDirection = "unknown"
    current_speed_kph: float | None = None
    free_flow_speed_kph: float | None = None
    travel_time_seconds: float | None = None
    free_flow_travel_time_seconds: float | None = None
    confidence: float | None = None
    congestion: float | None = None
    road_closed: bool = False
    prediction: bool = False

    def __post_init__(self) -> None:
        if not self.provider:
            raise ValueError("traffic provider must be non-empty")
        if not self.provider_segment_id:
            raise ValueError("provider segment ID must be non-empty")
        if self.direction not in {"forward", "backward", "both", "unknown"}:
            raise ValueError("traffic direction must be forward, backward, both or unknown")
        observed_at = _aware_utc(self.observed_at)
        expires_at = _aware_utc(self.expires_at)
        if expires_at <= observed_at:
            raise ValueError("traffic segment expiry must be after observation time")
        object.__setattr__(self, "observed_at", observed_at)
        object.__setattr__(self, "expires_at", expires_at)
        if self.geometry is not None and len(self.geometry) < 2:
            raise ValueError("traffic segment geometry must contain at least two points")
        for field_name in (
            "current_speed_kph",
            "free_flow_speed_kph",
            "travel_time_seconds",
            "free_flow_travel_time_seconds",
        ):
            value = getattr(self, field_name)
            if value is not None and (not isfinite(value) or value < 0):
                raise ValueError(f"{field_name} must be a non-negative finite value")
        for field_name in ("confidence", "congestion"):
            value = getattr(self, field_name)
            if value is not None and (not isfinite(value) or value < 0 or value > 1):
                raise ValueError(f"{field_name} must be between zero and one")

    def is_expired(self, evaluated_at: datetime | None = None) -> bool:
        return _aware_utc(evaluated_at or datetime.now(UTC)) >= self.expires_at


@dataclass(frozen=True, slots=True)
class TrafficQualityPolicy:
    min_confidence: float = 0.5
    max_age_seconds: float = 300
    max_speed_kph: float = 180
    min_match_confidence: float = 0.75

    def __post_init__(self) -> None:
        if not isfinite(self.min_confidence) or not 0 <= self.min_confidence <= 1:
            raise ValueError("minimum traffic confidence must be between zero and one")
        if not isfinite(self.max_age_seconds) or self.max_age_seconds <= 0:
            raise ValueError("maximum traffic age must be positive")
        if not isfinite(self.max_speed_kph) or self.max_speed_kph <= 0:
            raise ValueError("maximum traffic speed must be positive")
        if (
            not isfinite(self.min_match_confidence)
            or not 0 <= self.min_match_confidence <= 1
        ):
            raise ValueError("minimum match confidence must be between zero and one")

    def accepts(self, segment: TrafficFlowSegment, *, evaluated_at: datetime) -> bool:
        if segment.is_expired(evaluated_at):
            return False
        age_seconds = (_aware_utc(evaluated_at) - segment.observed_at).total_seconds()
        if age_seconds < 0 or age_seconds > self.max_age_seconds:
            return False
        if segment.confidence is not None and segment.confidence < self.min_confidence:
            return False
        for speed in (segment.current_speed_kph, segment.free_flow_speed_kph):
            if speed is not None and speed > self.max_speed_kph:
                return False
        return True


@dataclass(frozen=True, slots=True)
class TrafficProviderMetrics:
    provider_segments_received: int = 0
    segments_normalized: int = 0
    rejected_segments: int = 0
    stale_segments: int = 0
    api_errors: int = 0
    provider_latency_seconds: float | None = None

    def __post_init__(self) -> None:
        counts = (
            self.provider_segments_received,
            self.segments_normalized,
            self.rejected_segments,
            self.stale_segments,
            self.api_errors,
        )
        if any(count < 0 for count in counts):
            raise ValueError("traffic metrics must not be negative")
        if self.provider_latency_seconds is not None and (
            not isfinite(self.provider_latency_seconds)
            or self.provider_latency_seconds < 0
        ):
            raise ValueError("provider latency must be non-negative")


@dataclass(frozen=True, slots=True)
class TrafficProviderSnapshot:
    provider: str
    observed_at: datetime
    segments: tuple[TrafficFlowSegment, ...]
    metrics: TrafficProviderMetrics

    def __post_init__(self) -> None:
        observed_at = _aware_utc(self.observed_at)
        object.__setattr__(self, "observed_at", observed_at)
        if any(segment.provider != self.provider for segment in self.segments):
            raise ValueError("snapshot contains segments from another provider")


@dataclass(frozen=True, slots=True)
class TrafficFetchRequest:
    """Provider-independent scope for one traffic refresh.

    Base TomTom traffic is point based, while a future bulk provider may ignore
    ``probe_points`` and use a provider-native corridor reference instead.  The
    rest of Compass therefore passes route intent without exposing TomTom
    request objects.
    """

    scope_key: str
    trigger: TrafficRefreshTrigger
    probe_points: tuple[Coordinate, ...]

    def __post_init__(self) -> None:
        if not self.scope_key:
            raise ValueError("traffic fetch scope key must be non-empty")
        if self.trigger not in {"route_calculation", "active_navigation"}:
            raise ValueError("unsupported traffic refresh trigger")
        if not self.probe_points:
            raise ValueError("traffic fetch request requires at least one probe point")


@dataclass(frozen=True, slots=True)
class TrafficHealth:
    enabled: bool
    provider: str
    provider_status: TrafficHealthState
    traffic_aware_routing: bool
    last_fetch_started_at: datetime | None = None
    last_fetch_completed_at: datetime | None = None
    last_success_at: datetime | None = None
    provider_segments_received: int = 0
    segments_normalized: int = 0
    segments_matched: int = 0
    segments_unmatched: int = 0
    edges_updated: int = 0
    edges_expired: int = 0
    provider_api_errors: int = 0
    updater_consecutive_failures: int = 0
    managed_edge_count: int = 0
    feed_age_seconds: float | None = None
    mapping_version: str | None = None
    valhalla_tileset_version: str | None = None
    traffic_extract_path: str | None = None
    message: str | None = None

    def __post_init__(self) -> None:
        counts = (
            self.provider_segments_received,
            self.segments_normalized,
            self.segments_matched,
            self.segments_unmatched,
            self.edges_updated,
            self.edges_expired,
            self.provider_api_errors,
            self.updater_consecutive_failures,
            self.managed_edge_count,
        )
        if any(count < 0 for count in counts):
            raise ValueError("traffic health counters must not be negative")
        if self.feed_age_seconds is not None and (
            not isfinite(self.feed_age_seconds) or self.feed_age_seconds < 0
        ):
            raise ValueError("traffic feed age must be non-negative")


@dataclass(frozen=True, slots=True)
class TrafficEdgeMatch:
    directed_edge_ids: tuple[str, ...]
    match_method: TrafficMatchMethod
    confidence: float
    matched_length_meters: float | None = None
    source_length_meters: float | None = None
    direction_match: bool | None = None
    valhalla_tileset_version: str | None = None
    mapping_version: str | None = None
    warnings: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not isfinite(self.confidence) or not 0 <= self.confidence <= 1:
            raise ValueError("traffic match confidence must be between zero and one")
        if self.directed_edge_ids and self.match_method == "unmatched":
            raise ValueError("an unmatched result must not contain directed edges")
        if not self.directed_edge_ids and self.match_method != "unmatched":
            raise ValueError("a matched result must contain directed edges")
        if self.directed_edge_ids and (
            not self.valhalla_tileset_version or not self.mapping_version
        ):
            raise ValueError("matched edges must carry tileset and mapping identities")
        for field_name in ("matched_length_meters", "source_length_meters"):
            value = getattr(self, field_name)
            if value is not None and (not isfinite(value) or value < 0):
                raise ValueError(f"{field_name} must be non-negative")


@dataclass(frozen=True, slots=True)
class TrafficEdgeUpdate:
    graph_id: str
    speed_kph: float | None
    congestion: float | None = None
    closed: bool = False
    has_incidents: bool = False
    expires_at: datetime | None = None

    def __post_init__(self) -> None:
        if not self.graph_id:
            raise ValueError("graph_id must be non-empty")
        if self.speed_kph is not None and (
            not isfinite(self.speed_kph) or self.speed_kph < 0
        ):
            raise ValueError("traffic speed must be non-negative")
        if self.speed_kph == 0 and not self.closed:
            raise ValueError("zero traffic speed is reserved for explicit closures")
        if self.congestion is not None and (
            not isfinite(self.congestion) or not 0 <= self.congestion <= 1
        ):
            raise ValueError("traffic congestion must be between zero and one")
        if self.expires_at is not None:
            object.__setattr__(self, "expires_at", _aware_utc(self.expires_at))


class TrafficProvider(Protocol):
    provider_name: str

    async def fetch_flow(
        self, request: TrafficFetchRequest | None = None
    ) -> TrafficProviderSnapshot: ...

    def status(self) -> TrafficHealth: ...


class TrafficEdgeMatcher(Protocol):
    async def match(self, segment: TrafficFlowSegment) -> TrafficEdgeMatch: ...


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("traffic timestamps must include a UTC offset")
    return value.astimezone(UTC)
