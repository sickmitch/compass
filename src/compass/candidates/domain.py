from dataclasses import dataclass
from typing import Literal

from compass.routing.domain import BaseRoute, RouteRequest


@dataclass(frozen=True, slots=True)
class CorridorRadius:
    effective_cng_range_km: float
    range_fraction: float
    uncapped_radius_km: float
    radius_km: float
    cap_applied: Literal["minimum", "maximum", "none"]


@dataclass(frozen=True, slots=True)
class CorridorPolicy:
    range_fraction: float = 0.20
    minimum_radius_km: float = 5.0
    maximum_radius_km: float = 50.0
    candidate_limit: int = 200

    def __post_init__(self) -> None:
        if not 0 < self.range_fraction <= 1:
            raise ValueError("range_fraction must be greater than zero and at most one")
        if self.minimum_radius_km <= 0:
            raise ValueError("minimum_radius_km must be greater than zero")
        if self.maximum_radius_km < self.minimum_radius_km:
            raise ValueError("maximum_radius_km must be at least minimum_radius_km")
        if self.candidate_limit <= 0:
            raise ValueError("candidate_limit must be greater than zero")

    def radius_for(self, effective_cng_range_km: float) -> CorridorRadius:
        if effective_cng_range_km <= 0:
            raise ValueError("effective_cng_range_km must be greater than zero")
        uncapped = effective_cng_range_km * self.range_fraction
        if uncapped < self.minimum_radius_km:
            radius = self.minimum_radius_km
            cap_applied: Literal["minimum", "maximum", "none"] = "minimum"
        elif uncapped > self.maximum_radius_km:
            radius = self.maximum_radius_km
            cap_applied = "maximum"
        else:
            radius = uncapped
            cap_applied = "none"
        return CorridorRadius(
            effective_cng_range_km=effective_cng_range_km,
            range_fraction=self.range_fraction,
            uncapped_radius_km=uncapped,
            radius_km=radius,
            cap_applied=cap_applied,
        )


@dataclass(frozen=True, slots=True)
class CorridorCandidateRequest:
    route: RouteRequest
    effective_cng_range_km: float


@dataclass(frozen=True, slots=True)
class SpatialCandidate:
    station_id: int
    mimit_station_id: str
    name: str | None
    municipality: str | None
    province: str | None
    latitude: float
    longitude: float
    straight_line_distance_to_route_meters: float
    route_fraction: float


@dataclass(frozen=True, slots=True)
class CandidateQueryResult:
    active_station_count: int
    active_station_with_location_count: int
    corridor_candidate_count: int
    candidates: tuple[SpatialCandidate, ...]


@dataclass(frozen=True, slots=True)
class SpatialPruningMetrics:
    active_station_count: int
    active_station_with_location_count: int
    excluded_missing_location_count: int
    corridor_candidate_count: int
    returned_candidate_count: int
    pruned_with_location_count: int
    reduction_ratio: float
    candidate_limit_applied: bool
    routing_calls: int = 1


@dataclass(frozen=True, slots=True)
class CorridorCandidateResult:
    base_route: BaseRoute
    corridor: CorridorRadius
    metrics: SpatialPruningMetrics
    candidates: tuple[SpatialCandidate, ...]
