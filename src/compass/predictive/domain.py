from dataclasses import dataclass
from datetime import datetime
from math import isclose, isfinite
from typing import Literal

from compass.candidates.domain import SpatialCandidate
from compass.ranking.domain import (
    EvaluatedCngPrice,
    OpeningHoursEvaluation,
    RankedCandidate,
    RankedCandidatesRequest,
    RankedCandidatesResult,
)

PredictiveSuggestionState = Literal[
    "not_needed",
    "suggested",
    "no_reachable_station",
    "no_eligible_station",
    "no_complete_itinerary",
]

MAX_CNG_ITINERARY_STOPS = 32


@dataclass(frozen=True, slots=True)
class PredictiveCandidatesRequest:
    ranked_request: RankedCandidatesRequest
    estimated_remaining_cng_range_km: float
    reserve_cng_range_km: float

    def __post_init__(self) -> None:
        effective_range = (
            self.ranked_request.network_request.corridor_request.effective_cng_range_km
        )
        if (
            not isfinite(self.estimated_remaining_cng_range_km)
            or self.estimated_remaining_cng_range_km <= 0
        ):
            raise ValueError("estimated_remaining_cng_range_km must be greater than zero")
        if self.estimated_remaining_cng_range_km > effective_range:
            raise ValueError(
                "estimated_remaining_cng_range_km must not exceed effective_cng_range_km"
            )
        if not isfinite(self.reserve_cng_range_km) or self.reserve_cng_range_km < 0:
            raise ValueError("reserve_cng_range_km must not be negative")
        if self.reserve_cng_range_km >= self.estimated_remaining_cng_range_km:
            raise ValueError("reserve_cng_range_km must be lower than estimated remaining range")


@dataclass(frozen=True, slots=True)
class PredictiveRangeBasis:
    effective_cng_range_km: float
    estimated_remaining_cng_range_km: float
    reserve_cng_range_km: float
    usable_range_before_reserve_km: float
    remaining_route_distance_km: float
    range_shortfall_to_destination_km: float
    destination_reachable_with_reserve: bool
    remaining_route_origin: Literal["request_origin"] = "request_origin"
    consumption_model: Literal["caller_estimated_remaining_range"] = (
        "caller_estimated_remaining_range"
    )
    traffic_state: Literal["not_configured"] = "not_configured"
    traffic_adjusted: bool = False

    def __post_init__(self) -> None:
        if not isclose(
            self.usable_range_before_reserve_km,
            self.estimated_remaining_cng_range_km - self.reserve_cng_range_km,
            abs_tol=1e-9,
        ):
            raise ValueError("usable range must equal estimated range minus reserve")
        if self.remaining_route_distance_km < 0:
            raise ValueError("remaining route distance must not be negative")
        if self.range_shortfall_to_destination_km < 0:
            raise ValueError("range shortfall must not be negative")
        expected_reachable = self.remaining_route_distance_km <= self.usable_range_before_reserve_km
        if self.destination_reachable_with_reserve != expected_reachable:
            raise ValueError("destination reachability does not match range basis")


@dataclass(frozen=True, slots=True)
class PredictiveReachabilityMetrics:
    detour_eligible_candidate_count: int
    reachable_before_reserve_count: int
    excluded_unreachable_before_reserve_count: int
    ranked_reachable_candidate_count: int
    furthest_reachable_route_fraction: float | None
    evaluation_skipped_destination_reachable: bool
    pairwise_matrix_calls: int = 0
    pairwise_matrix_fallback_splits: int = 0
    pairwise_matrix_location_failures: int = 0
    itinerary_search_labels: int = 0

    def __post_init__(self) -> None:
        counts = (
            self.detour_eligible_candidate_count,
            self.reachable_before_reserve_count,
            self.excluded_unreachable_before_reserve_count,
            self.ranked_reachable_candidate_count,
            self.pairwise_matrix_calls,
            self.pairwise_matrix_fallback_splits,
            self.pairwise_matrix_location_failures,
            self.itinerary_search_labels,
        )
        if any(count < 0 for count in counts):
            raise ValueError("predictive reachability counts must not be negative")
        if not self.evaluation_skipped_destination_reachable and (
            self.reachable_before_reserve_count + self.excluded_unreachable_before_reserve_count
            != self.detour_eligible_candidate_count
        ):
            raise ValueError("reachable and excluded counts must reconcile")
        if self.ranked_reachable_candidate_count > self.reachable_before_reserve_count:
            raise ValueError("ranked candidates must be a subset of reachable candidates")


@dataclass(frozen=True, slots=True)
class PredictiveRankedCandidate:
    ranked: RankedCandidate
    estimated_remaining_range_at_arrival_km: float
    reserve_margin_at_arrival_km: float

    def __post_init__(self) -> None:
        if self.estimated_remaining_range_at_arrival_km < 0:
            raise ValueError("remaining range at station must not be negative")
        if self.reserve_margin_at_arrival_km < 0:
            raise ValueError("reachable station must preserve reserve")


@dataclass(frozen=True, slots=True)
class PredictiveItineraryStop:
    sequence: int
    station: SpatialCandidate
    arrival_at: datetime
    leg_distance_meters: float
    leg_duration_seconds: float
    available_range_at_departure_km: float
    estimated_remaining_range_at_arrival_km: float
    reserve_margin_at_arrival_km: float
    opening: OpeningHoursEvaluation
    phone: str | None
    brand: str | None
    operator: str | None
    osm_match_confidence: float | None
    price: EvaluatedCngPrice | None

    def __post_init__(self) -> None:
        if self.sequence <= 0:
            raise ValueError("itinerary stop sequence must be positive")
        values = (
            self.leg_distance_meters,
            self.leg_duration_seconds,
            self.available_range_at_departure_km,
            self.estimated_remaining_range_at_arrival_km,
            self.reserve_margin_at_arrival_km,
        )
        if any(not isfinite(value) or value < 0 for value in values):
            raise ValueError("itinerary stop range and cost values must not be negative")
        if self.arrival_at.tzinfo is None or self.arrival_at.utcoffset() is None:
            raise ValueError("itinerary stop arrival must include a UTC offset")


@dataclass(frozen=True, slots=True)
class PredictiveDestinationLeg:
    distance_meters: float
    duration_seconds: float
    available_range_at_departure_km: float
    estimated_remaining_range_at_arrival_km: float
    reserve_margin_at_arrival_km: float
    destination_eta: datetime

    def __post_init__(self) -> None:
        values = (
            self.distance_meters,
            self.duration_seconds,
            self.available_range_at_departure_km,
            self.estimated_remaining_range_at_arrival_km,
            self.reserve_margin_at_arrival_km,
        )
        if any(not isfinite(value) or value < 0 for value in values):
            raise ValueError("destination leg range and cost values must not be negative")
        if self.destination_eta.tzinfo is None or self.destination_eta.utcoffset() is None:
            raise ValueError("destination ETA must include a UTC offset")


@dataclass(frozen=True, slots=True)
class PredictiveItinerary:
    stops: tuple[PredictiveItineraryStop, ...]
    destination_leg: PredictiveDestinationLeg
    total_distance_meters: float
    total_duration_seconds: float
    refuel_assumption: Literal["full_effective_range_after_each_stop"] = (
        "full_effective_range_after_each_stop"
    )
    distance_model: Literal["road_network"] = "road_network"

    def __post_init__(self) -> None:
        if not self.stops:
            raise ValueError("a predictive itinerary must contain at least one CNG stop")
        if len(self.stops) > MAX_CNG_ITINERARY_STOPS:
            raise ValueError("a predictive itinerary contains too many CNG stops")
        if tuple(stop.sequence for stop in self.stops) != tuple(
            range(1, len(self.stops) + 1)
        ):
            raise ValueError("itinerary stop sequence must be contiguous")
        if len({stop.station.station_id for stop in self.stops}) != len(self.stops):
            raise ValueError("an itinerary must not visit a station more than once")
        expected_distance = (
            sum(stop.leg_distance_meters for stop in self.stops)
            + self.destination_leg.distance_meters
        )
        expected_duration = (
            sum(stop.leg_duration_seconds for stop in self.stops)
            + self.destination_leg.duration_seconds
        )
        if not isclose(self.total_distance_meters, expected_distance, abs_tol=1e-6):
            raise ValueError("itinerary distance must equal its leg sum")
        if not isclose(self.total_duration_seconds, expected_duration, abs_tol=1e-6):
            raise ValueError("itinerary duration must equal its leg sum")


@dataclass(frozen=True, slots=True)
class PredictiveCandidatesResult:
    suggestion_state: PredictiveSuggestionState
    range_basis: PredictiveRangeBasis
    reachability: PredictiveReachabilityMetrics
    ranking_result: RankedCandidatesResult
    candidates: tuple[PredictiveRankedCandidate, ...]
    itinerary: PredictiveItinerary | None = None

    def __post_init__(self) -> None:
        has_candidates = bool(self.candidates)
        if (self.suggestion_state == "suggested") != has_candidates:
            raise ValueError("only a suggested result may contain candidates")
        if len(self.candidates) != self.reachability.ranked_reachable_candidate_count:
            raise ValueError("candidate count must match predictive metrics")
        if (self.suggestion_state == "suggested") != (self.itinerary is not None):
            raise ValueError("only a suggested result may contain a complete itinerary")
        if self.itinerary is not None:
            if len(self.candidates) != 1:
                raise ValueError("a complete itinerary must expose its selected first stop")
            selected_station_id = self.candidates[0].ranked.detour.station.station_id
            if self.itinerary.stops[0].station.station_id != selected_station_id:
                raise ValueError("the ranked first stop must start the itinerary")
