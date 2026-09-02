from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Literal

from compass.candidates.domain import (
    CorridorCandidateRequest,
    CorridorCandidateResult,
    SpatialCandidate,
)
from compass.routing.domain import BaseRoute, MatrixCost
from compass.traffic.domain import TrafficHealthState


@dataclass(frozen=True, slots=True)
class NetworkDetourRequest:
    corridor_request: CorridorCandidateRequest
    maximum_detour_seconds: float
    departure_at: datetime

    def __post_init__(self) -> None:
        if self.maximum_detour_seconds < 0:
            raise ValueError("maximum_detour_seconds must not be negative")
        if self.departure_at.tzinfo is None or self.departure_at.utcoffset() is None:
            raise ValueError("departure_at must include a UTC offset")


@dataclass(frozen=True, slots=True)
class NetworkDetourPolicy:
    matrix_batch_size: int = 40

    def __post_init__(self) -> None:
        if self.matrix_batch_size <= 0:
            raise ValueError("matrix_batch_size must be greater than zero")


@dataclass(frozen=True, slots=True)
class NetworkCostBasis:
    provider: Literal["valhalla"] = "valhalla"
    traffic_state: TrafficHealthState = "not_configured"
    traffic_aware: bool = False
    duration_model: Literal[
        "valhalla_graph_speeds",
        "valhalla_time_dependent_traffic",
    ] = "valhalla_graph_speeds"
    distance_model: Literal["road_network"] = "road_network"


@dataclass(frozen=True, slots=True)
class EligibleDetourCandidate:
    station: SpatialCandidate
    distance_from_previous_waypoint_meters: float
    duration_from_previous_waypoint_seconds: float
    station_to_destination_distance_meters: float
    station_to_destination_duration_seconds: float
    route_via_station_distance_meters: float
    route_via_station_duration_seconds: float
    extra_distance_meters: float
    detour_duration_seconds: float
    detour_minutes: float
    station_eta: datetime
    destination_eta: datetime


@dataclass(frozen=True, slots=True)
class NetworkEvaluationMetrics:
    spatial_candidate_count: int
    matrix_candidate_count: int
    reachable_candidate_count: int
    unreachable_candidate_count: int
    eligible_candidate_count: int
    excluded_by_detour_count: int
    matrix_batch_size: int
    matrix_calls: int
    matrix_fallback_splits: int
    matrix_location_failures: int
    base_route_calls: int = 1
    per_candidate_route_calls: int = 0


@dataclass(frozen=True, slots=True)
class NetworkDetourResult:
    spatial_result: CorridorCandidateResult
    maximum_detour_seconds: float
    departure_at: datetime
    cost_basis: NetworkCostBasis
    metrics: NetworkEvaluationMetrics
    candidates: tuple[EligibleDetourCandidate, ...]


def calculate_detour_candidate(
    *,
    station: SpatialCandidate,
    base_route: BaseRoute,
    previous_to_station: MatrixCost,
    station_to_destination: MatrixCost,
    departure_at: datetime,
) -> EligibleDetourCandidate:
    via_distance = (
        previous_to_station.distance_meters + station_to_destination.distance_meters
    )
    via_duration = (
        previous_to_station.duration_seconds + station_to_destination.duration_seconds
    )
    # Independent route/matrix snapping can produce tiny negative deltas for equivalent paths.
    extra_distance = max(0.0, via_distance - base_route.distance_meters)
    detour_duration = max(0.0, via_duration - base_route.duration_seconds)
    return EligibleDetourCandidate(
        station=station,
        distance_from_previous_waypoint_meters=previous_to_station.distance_meters,
        duration_from_previous_waypoint_seconds=previous_to_station.duration_seconds,
        station_to_destination_distance_meters=station_to_destination.distance_meters,
        station_to_destination_duration_seconds=station_to_destination.duration_seconds,
        route_via_station_distance_meters=via_distance,
        route_via_station_duration_seconds=via_duration,
        extra_distance_meters=extra_distance,
        detour_duration_seconds=detour_duration,
        detour_minutes=detour_duration / 60,
        station_eta=departure_at
        + timedelta(seconds=previous_to_station.duration_seconds),
        destination_eta=departure_at + timedelta(seconds=via_duration),
    )
