from dataclasses import asdict
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import Field, model_validator
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.api.ranking import (
    CurrentCngPriceResponse,
    OpeningHoursEvaluationResponse,
    RankedCandidateResponse,
    RankedCandidatesApiRequest,
    RankingMetricsResponse,
    RankingPolicyResponse,
    _ranked_candidate_response,
    _ranking_policy,
    _ranking_policy_response,
)
from compass.api.routes import (
    BaseRouteResponse,
    CoordinateRequest,
    CorridorPolicyResponse,
    NetworkCostBasisResponse,
    NetworkEvaluationMetricsResponse,
    SpatialPruningMetricsResponse,
    _base_route_response,
)
from compass.candidates.domain import CorridorCandidateRequest, CorridorPolicy
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.detours.domain import NetworkDetourPolicy, NetworkDetourRequest
from compass.predictive.domain import (
    GasolineFallback,
    PredictiveCandidatesRequest,
    PredictiveItinerary,
    PredictiveItineraryStop,
    PredictiveRankedCandidate,
)
from compass.predictive.service import evaluate_predictive_cng_candidates
from compass.ranking.domain import RankedCandidatesRequest
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    Coordinate,
    NoRouteError,
    RouteRequest,
    RoutingProvider,
    RoutingProviderError,
    RoutingUnavailableError,
)
from compass.traffic.domain import TrafficHealthState
from compass.traffic.service import network_cost_basis_from_settings

router = APIRouter(prefix="/api/v1", tags=["predictive-refuelling"])


class PredictiveCandidatesApiRequest(RankedCandidatesApiRequest):
    estimated_remaining_cng_range_km: float = Field(
        gt=0,
        le=2000,
        description="Caller-estimated CNG range remaining at the request origin.",
    )
    reserve_cng_range_km: float = Field(
        ge=0,
        lt=2000,
        description="Safety range that must remain on arrival at a suggested station.",
    )
    estimated_remaining_gasoline_range_km: float | None = Field(
        default=None,
        gt=0,
        le=2000,
        description=(
            "Caller-estimated gasoline range remaining at the request origin. "
            "Supplying it enables an explicit direct-route fallback only when no complete "
            "CNG itinerary exists."
        ),
    )
    reserve_gasoline_range_km: float | None = Field(
        default=None,
        ge=0,
        lt=2000,
        description="Gasoline safety range that must remain at the destination.",
    )
    excluded_mimit_station_ids: list[str] = Field(
        default_factory=list,
        max_length=32,
        description=(
            "Official MIMIT station IDs that must not be considered for this replacement plan."
        ),
    )

    @model_validator(mode="after")
    def validate_predictive_ranges(self) -> "PredictiveCandidatesApiRequest":
        if self.estimated_remaining_cng_range_km > self.effective_cng_range_km:
            raise ValueError("estimated remaining range must not exceed effective vehicle range")
        if self.reserve_cng_range_km >= self.estimated_remaining_cng_range_km:
            raise ValueError("reserve range must be lower than estimated remaining range")
        if (self.estimated_remaining_gasoline_range_km is None) != (
            self.reserve_gasoline_range_km is None
        ):
            raise ValueError("gasoline remaining range and reserve must be supplied together")
        if (
            self.estimated_remaining_gasoline_range_km is not None
            and self.reserve_gasoline_range_km is not None
            and self.reserve_gasoline_range_km
            >= self.estimated_remaining_gasoline_range_km
        ):
            raise ValueError("gasoline reserve must be lower than estimated remaining range")
        if len(set(self.excluded_mimit_station_ids)) != len(self.excluded_mimit_station_ids):
            raise ValueError("excluded_mimit_station_ids must not contain duplicates")
        if any(
            not station_id.isascii() or not station_id.isdigit() or len(station_id) > 32
            for station_id in self.excluded_mimit_station_ids
        ):
            raise ValueError("excluded_mimit_station_ids must contain official numeric IDs")
        return self


class PredictiveRangeBasisResponse(StrictModel):
    effective_cng_range_km: float = Field(gt=0)
    estimated_remaining_cng_range_km: float = Field(gt=0)
    reserve_cng_range_km: float = Field(ge=0)
    usable_range_before_reserve_km: float = Field(gt=0)
    remaining_route_distance_km: float = Field(ge=0)
    range_shortfall_to_destination_km: float = Field(ge=0)
    destination_reachable_with_reserve: bool
    remaining_route_origin: Literal["request_origin"]
    consumption_model: Literal["caller_estimated_remaining_range"]
    traffic_state: TrafficHealthState
    traffic_adjusted: bool


class PredictiveReachabilityMetricsResponse(StrictModel):
    detour_eligible_candidate_count: int = Field(ge=0)
    reachable_before_reserve_count: int = Field(ge=0)
    excluded_unreachable_before_reserve_count: int = Field(ge=0)
    ranked_reachable_candidate_count: int = Field(ge=0)
    furthest_reachable_route_fraction: float | None = Field(default=None, ge=0, le=1)
    evaluation_skipped_destination_reachable: bool
    pairwise_matrix_calls: int = Field(ge=0)
    pairwise_matrix_fallback_splits: int = Field(ge=0)
    pairwise_matrix_location_failures: int = Field(ge=0)
    itinerary_search_labels: int = Field(ge=0)


class PredictiveRankedCandidateResponse(StrictModel):
    candidate: RankedCandidateResponse
    estimated_remaining_range_at_arrival_km: float = Field(ge=0)
    reserve_margin_at_arrival_km: float = Field(ge=0)


class PredictiveItineraryStopResponse(StrictModel):
    sequence: int = Field(gt=0)
    station_id: int = Field(gt=0)
    mimit_station_id: str
    name: str | None
    municipality: str | None
    province: str | None
    location: CoordinateRequest
    arrival_at: datetime
    leg_distance_meters: float = Field(ge=0)
    leg_duration_seconds: float = Field(ge=0)
    available_range_at_departure_km: float = Field(gt=0)
    estimated_remaining_range_at_arrival_km: float = Field(ge=0)
    reserve_margin_at_arrival_km: float = Field(ge=0)
    opening: OpeningHoursEvaluationResponse
    phone: str | None
    brand: str | None
    operator: str | None
    osm_match_confidence: float | None = Field(default=None, ge=0, le=1)
    price: CurrentCngPriceResponse | None
    dwell_time_seconds: int = Field(ge=0)


class PredictiveDestinationLegResponse(StrictModel):
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    available_range_at_departure_km: float = Field(gt=0)
    estimated_remaining_range_at_arrival_km: float = Field(ge=0)
    reserve_margin_at_arrival_km: float = Field(ge=0)
    destination_eta: datetime


class PredictiveItineraryResponse(StrictModel):
    stops: list[PredictiveItineraryStopResponse] = Field(min_length=1)
    destination_leg: PredictiveDestinationLegResponse
    total_distance_meters: float = Field(ge=0)
    total_duration_seconds: float = Field(ge=0)
    total_refueling_dwell_seconds: float = Field(ge=0)
    total_trip_duration_seconds: float = Field(ge=0)
    refuel_assumption: Literal["full_effective_range_after_each_stop"]
    distance_model: Literal["road_network"]


class GasolineFallbackResponse(StrictModel):
    estimated_remaining_gasoline_range_km: float = Field(gt=0)
    reserve_gasoline_range_km: float = Field(ge=0)
    usable_gasoline_range_km: float = Field(gt=0)
    cng_range_used_before_switch_km: float = Field(ge=0)
    required_gasoline_range_km: float = Field(ge=0)
    gasoline_margin_at_destination_km: float = Field(ge=0)
    strategy: Literal["direct_after_cng_reserve"]


class PredictiveCandidatesResponse(StrictModel):
    stage: Literal["predictive_ranking"] = "predictive_ranking"
    suggestion_state: Literal[
        "not_needed",
        "suggested",
        "gasoline_fallback",
        "no_reachable_station",
        "no_eligible_station",
        "no_complete_itinerary",
    ]
    departure_at: datetime
    maximum_detour_minutes: float = Field(ge=0)
    excluded_mimit_station_ids: list[str]
    base_route: BaseRouteResponse
    corridor: CorridorPolicyResponse
    spatial_pruning: SpatialPruningMetricsResponse
    cost_basis: NetworkCostBasisResponse
    network_evaluation: NetworkEvaluationMetricsResponse
    range_basis: PredictiveRangeBasisResponse
    reachability_evaluation: PredictiveReachabilityMetricsResponse
    ranking_policy: RankingPolicyResponse
    ranking_evaluation: RankingMetricsResponse
    candidates: list[PredictiveRankedCandidateResponse]
    itinerary: PredictiveItineraryResponse | None
    gasoline_fallback: GasolineFallbackResponse | None


@router.post(
    "/cng/predictive-candidates",
    response_model=PredictiveCandidatesResponse,
    responses={
        422: {"model": ErrorResponse, "description": "Invalid range request or no route."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Database or routing unavailable."},
    },
)
async def predictive_candidates(
    request: PredictiveCandidatesApiRequest,
    session: Annotated[Session, Depends(get_session)],
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> PredictiveCandidatesResponse | JSONResponse:
    route_request = RouteRequest(
        origin=Coordinate(request.origin.latitude, request.origin.longitude),
        destination=Coordinate(request.destination.latitude, request.destination.longitude),
        costing=request.costing,
        language=request.language or settings.valhalla_route_language,
        departure_at=request.departure_at,
    )
    predictive_request = PredictiveCandidatesRequest(
        ranked_request=RankedCandidatesRequest(
            network_request=NetworkDetourRequest(
                corridor_request=CorridorCandidateRequest(
                    route=route_request,
                    effective_cng_range_km=request.effective_cng_range_km,
                    excluded_mimit_station_ids=tuple(request.excluded_mimit_station_ids),
                ),
                maximum_detour_seconds=request.maximum_detour_minutes * 60,
                departure_at=request.departure_at,
            ),
            include_closed=request.include_closed,
        ),
        estimated_remaining_cng_range_km=request.estimated_remaining_cng_range_km,
        reserve_cng_range_km=request.reserve_cng_range_km,
        estimated_remaining_gasoline_range_km=(
            request.estimated_remaining_gasoline_range_km
        ),
        reserve_gasoline_range_km=request.reserve_gasoline_range_km,
    )
    corridor_policy = CorridorPolicy(
        range_fraction=settings.cng_corridor_range_fraction,
        minimum_radius_km=settings.cng_corridor_minimum_radius_km,
        maximum_radius_km=settings.cng_corridor_maximum_radius_km,
        candidate_limit=settings.cng_corridor_candidate_limit,
    )
    ranking_policy = _ranking_policy(settings)
    try:
        result = await evaluate_predictive_cng_candidates(
            session,
            provider,
            predictive_request,
            corridor_policy=corridor_policy,
            detour_policy=NetworkDetourPolicy(
                matrix_batch_size=settings.valhalla_matrix_batch_size
            ),
            ranking_policy=ranking_policy,
            max_route_geometry_points=settings.route_geometry_max_points,
            cost_basis=network_cost_basis_from_settings(settings),
            dwell_seconds_per_refueling_stop=settings.cng_refuel_dwell_seconds,
        )
    except NoRouteError:
        return error_response(422, "route_not_found", "No route was found between the locations.")
    except RoutingUnavailableError:
        return error_response(503, "routing_unavailable", "The routing service is unavailable.")
    except RoutingProviderError:
        return error_response(
            502,
            "routing_provider_error",
            "The routing service returned an invalid response.",
        )
    except SQLAlchemyError:
        return error_response(503, "database_unavailable", "The station database is unavailable.")

    ranking = result.ranking_result
    network = ranking.network_result
    spatial = network.spatial_result
    return PredictiveCandidatesResponse(
        suggestion_state=result.suggestion_state,
        departure_at=network.departure_at,
        maximum_detour_minutes=network.maximum_detour_seconds / 60,
        excluded_mimit_station_ids=request.excluded_mimit_station_ids,
        base_route=_base_route_response(
            spatial.base_route,
            departure_at=network.departure_at,
        ),
        corridor=CorridorPolicyResponse.model_validate(asdict(spatial.corridor)),
        spatial_pruning=SpatialPruningMetricsResponse.model_validate(asdict(spatial.metrics)),
        cost_basis=NetworkCostBasisResponse.model_validate(asdict(network.cost_basis)),
        network_evaluation=NetworkEvaluationMetricsResponse.model_validate(
            asdict(result.ranking_result.network_result.metrics)
        ),
        range_basis=PredictiveRangeBasisResponse.model_validate(asdict(result.range_basis)),
        reachability_evaluation=PredictiveReachabilityMetricsResponse.model_validate(
            asdict(result.reachability)
        ),
        ranking_policy=_ranking_policy_response(ranking.policy, ranking.include_closed),
        ranking_evaluation=RankingMetricsResponse.model_validate(asdict(ranking.metrics)),
        candidates=[_predictive_candidate_response(candidate) for candidate in result.candidates],
        itinerary=(
            _predictive_itinerary_response(result.itinerary)
            if result.itinerary is not None
            else None
        ),
        gasoline_fallback=(
            _gasoline_fallback_response(result.gasoline_fallback)
            if result.gasoline_fallback is not None
            else None
        ),
    )


def _gasoline_fallback_response(
    fallback: GasolineFallback,
) -> GasolineFallbackResponse:
    return GasolineFallbackResponse.model_validate(asdict(fallback))


def _predictive_candidate_response(
    candidate: PredictiveRankedCandidate,
) -> PredictiveRankedCandidateResponse:
    return PredictiveRankedCandidateResponse.model_validate(
        {
            "candidate": _ranked_candidate_response(candidate.ranked),
            "estimated_remaining_range_at_arrival_km": (
                candidate.estimated_remaining_range_at_arrival_km
            ),
            "reserve_margin_at_arrival_km": candidate.reserve_margin_at_arrival_km,
        }
    )


def _predictive_itinerary_response(
    itinerary: PredictiveItinerary,
) -> PredictiveItineraryResponse:
    return PredictiveItineraryResponse(
        stops=[_predictive_itinerary_stop_response(stop) for stop in itinerary.stops],
        destination_leg=PredictiveDestinationLegResponse.model_validate(
            asdict(itinerary.destination_leg)
        ),
        total_distance_meters=itinerary.total_distance_meters,
        total_duration_seconds=itinerary.total_duration_seconds,
        total_refueling_dwell_seconds=itinerary.total_refueling_dwell_seconds,
        total_trip_duration_seconds=itinerary.total_trip_duration_seconds,
        refuel_assumption=itinerary.refuel_assumption,
        distance_model=itinerary.distance_model,
    )


def _predictive_itinerary_stop_response(
    stop: PredictiveItineraryStop,
) -> PredictiveItineraryStopResponse:
    price = stop.price
    return PredictiveItineraryStopResponse.model_validate(
        {
            "sequence": stop.sequence,
            "station_id": stop.station.station_id,
            "mimit_station_id": stop.station.mimit_station_id,
            "name": stop.station.name,
            "municipality": stop.station.municipality,
            "province": stop.station.province,
            "location": {
                "latitude": stop.station.latitude,
                "longitude": stop.station.longitude,
            },
            "arrival_at": stop.arrival_at,
            "leg_distance_meters": stop.leg_distance_meters,
            "leg_duration_seconds": stop.leg_duration_seconds,
            "available_range_at_departure_km": stop.available_range_at_departure_km,
            "estimated_remaining_range_at_arrival_km": (
                stop.estimated_remaining_range_at_arrival_km
            ),
            "reserve_margin_at_arrival_km": stop.reserve_margin_at_arrival_km,
            "opening": asdict(stop.opening),
            "phone": stop.phone,
            "brand": stop.brand,
            "operator": stop.operator,
            "osm_match_confidence": stop.osm_match_confidence,
            "price": (
                {**asdict(price), "unit_price": float(price.unit_price)}
                if price is not None
                else None
            ),
            "dwell_time_seconds": stop.dwell_time_seconds,
        }
    )
