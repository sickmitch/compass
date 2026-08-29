from dataclasses import asdict
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import Field
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.api.routes import (
    BaseRouteResponse,
    CorridorPolicyResponse,
    DetourCandidatesRequest,
    EligibleDetourCandidateResponse,
    NetworkCostBasisResponse,
    NetworkEvaluationMetricsResponse,
    SpatialPruningMetricsResponse,
    _base_route_response,
    _detour_candidate_response,
)
from compass.candidates.domain import CorridorCandidateRequest, CorridorPolicy
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.detours.domain import NetworkDetourPolicy, NetworkDetourRequest
from compass.ranking.domain import RankedCandidate, RankedCandidatesRequest, RankingPolicy
from compass.ranking.service import rank_cng_candidates
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    Coordinate,
    NoRouteError,
    RouteRequest,
    RoutingProvider,
    RoutingProviderError,
    RoutingUnavailableError,
)

router = APIRouter(prefix="/api/v1", tags=["ranking"])


class RankedCandidatesApiRequest(DetourCandidatesRequest):
    include_closed: bool = Field(
        default=False,
        description=(
            "Include stations evaluated as closed at ETA with a zero opening score. "
            "Unknown opening state remains included regardless of this option."
        ),
    )


class OpeningHoursEvaluationResponse(StrictModel):
    state: Literal["open", "closed", "unknown"]
    validation: Literal["valid", "missing", "invalid"]
    opening_hours: str | None
    source: Literal["osm"] | None
    source_confidence: float | None = Field(default=None, ge=0, le=1)
    evaluated_at: datetime
    timezone: str
    next_change_at: datetime | None
    comment: str | None
    warnings: list[str]


class CurrentCngPriceResponse(StrictModel):
    unit_price: float = Field(gt=0)
    currency: str = Field(min_length=3, max_length=3)
    unit: str = Field(min_length=1)
    service_mode: Literal["self", "served"]
    observed_at: datetime
    ingested_at: datetime
    source_name: str = Field(min_length=1)
    age_seconds: float | None
    freshness_state: Literal["fresh", "stale", "future_observation", "unknown"]


class RankingBreakdownResponse(StrictModel):
    rank: int = Field(gt=0)
    total_score: float = Field(ge=0, le=1)
    detour_score: float = Field(ge=0, le=1)
    opening_score: float = Field(ge=0, le=1)
    price_score: float = Field(ge=0, le=1)
    price_freshness_score: float = Field(ge=0, le=1)
    detour_contribution: float = Field(ge=0, le=1)
    opening_contribution: float = Field(ge=0, le=1)
    price_contribution: float = Field(ge=0, le=1)
    price_freshness_contribution: float = Field(ge=0, le=1)
    availability_multiplier: float = Field(ge=0, le=1)


class RankedCandidateResponse(EligibleDetourCandidateResponse):
    opening: OpeningHoursEvaluationResponse
    phone: str | None
    brand: str | None
    operator: str | None
    osm_match_confidence: float | None = Field(default=None, ge=0, le=1)
    price: CurrentCngPriceResponse | None
    ranking: RankingBreakdownResponse


class RankingPolicyResponse(StrictModel):
    detour_weight: float = Field(ge=0, le=1)
    opening_weight: float = Field(ge=0, le=1)
    price_weight: float = Field(ge=0, le=1)
    price_freshness_weight: float = Field(ge=0, le=1)
    unknown_opening_score: float = Field(ge=0, le=1)
    closed_score_multiplier: float = Field(ge=0, le=1)
    price_freshness_hours: float = Field(gt=0)
    opening_hours_timezone: str
    opening_hours_country: str
    price_selection: Literal["lowest_current_cng_unit_price"]
    closed_candidate_policy: Literal["exclude", "include_with_zero_opening_score"]


class RankingMetricsResponse(StrictModel):
    detour_eligible_candidate_count: int = Field(ge=0)
    opening_open_count: int = Field(ge=0)
    opening_closed_count: int = Field(ge=0)
    opening_unknown_count: int = Field(ge=0)
    opening_valid_count: int = Field(ge=0)
    opening_missing_count: int = Field(ge=0)
    opening_invalid_count: int = Field(ge=0)
    excluded_closed_count: int = Field(ge=0)
    price_available_count: int = Field(ge=0)
    price_missing_count: int = Field(ge=0)
    ranked_candidate_count: int = Field(ge=0)
    enrichment_queries: int = Field(ge=0, le=1)


class RankedCandidatesResponse(StrictModel):
    stage: Literal["ranking"] = "ranking"
    departure_at: datetime
    maximum_detour_minutes: float = Field(ge=0)
    base_route: BaseRouteResponse
    corridor: CorridorPolicyResponse
    spatial_pruning: SpatialPruningMetricsResponse
    cost_basis: NetworkCostBasisResponse
    network_evaluation: NetworkEvaluationMetricsResponse
    ranking_policy: RankingPolicyResponse
    ranking_evaluation: RankingMetricsResponse
    candidates: list[RankedCandidateResponse]


@router.post(
    "/cng/ranked-candidates",
    response_model=RankedCandidatesResponse,
    responses={
        422: {"model": ErrorResponse, "description": "Invalid request or no route."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Database or routing unavailable."},
    },
)
async def ranked_candidates(
    request: RankedCandidatesApiRequest,
    session: Annotated[Session, Depends(get_session)],
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> RankedCandidatesResponse | JSONResponse:
    route_request = RouteRequest(
        origin=Coordinate(request.origin.latitude, request.origin.longitude),
        destination=Coordinate(
            request.destination.latitude, request.destination.longitude
        ),
        costing=request.costing,
        language=request.language or settings.valhalla_route_language,
    )
    domain_request = RankedCandidatesRequest(
        network_request=NetworkDetourRequest(
            corridor_request=CorridorCandidateRequest(
                route=route_request,
                effective_cng_range_km=request.effective_cng_range_km,
            ),
            maximum_detour_seconds=request.maximum_detour_minutes * 60,
            departure_at=request.departure_at,
        ),
        include_closed=request.include_closed,
    )
    corridor_policy = CorridorPolicy(
        range_fraction=settings.cng_corridor_range_fraction,
        minimum_radius_km=settings.cng_corridor_minimum_radius_km,
        maximum_radius_km=settings.cng_corridor_maximum_radius_km,
        candidate_limit=settings.cng_corridor_candidate_limit,
    )
    ranking_policy = RankingPolicy(
        detour_weight=settings.cng_ranking_detour_weight,
        opening_weight=settings.cng_ranking_opening_weight,
        price_weight=settings.cng_ranking_price_weight,
        price_freshness_weight=settings.cng_ranking_price_freshness_weight,
        unknown_opening_score=settings.cng_ranking_unknown_opening_score,
        closed_score_multiplier=settings.cng_ranking_closed_score_multiplier,
        price_freshness_seconds=settings.cng_price_freshness_hours * 60 * 60,
        opening_hours_timezone=settings.opening_hours_timezone,
    )
    try:
        result = await rank_cng_candidates(
            session,
            provider,
            domain_request,
            corridor_policy=corridor_policy,
            detour_policy=NetworkDetourPolicy(
                matrix_batch_size=settings.valhalla_matrix_batch_size
            ),
            ranking_policy=ranking_policy,
            max_route_geometry_points=settings.route_geometry_max_points,
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

    network = result.network_result
    spatial = network.spatial_result
    return RankedCandidatesResponse(
        departure_at=network.departure_at,
        maximum_detour_minutes=network.maximum_detour_seconds / 60,
        base_route=_base_route_response(spatial.base_route),
        corridor=CorridorPolicyResponse.model_validate(asdict(spatial.corridor)),
        spatial_pruning=SpatialPruningMetricsResponse.model_validate(
            asdict(spatial.metrics)
        ),
        cost_basis=NetworkCostBasisResponse.model_validate(asdict(network.cost_basis)),
        network_evaluation=NetworkEvaluationMetricsResponse.model_validate(
            asdict(network.metrics)
        ),
        ranking_policy=RankingPolicyResponse(
            detour_weight=result.policy.detour_weight,
            opening_weight=result.policy.opening_weight,
            price_weight=result.policy.price_weight,
            price_freshness_weight=result.policy.price_freshness_weight,
            unknown_opening_score=result.policy.unknown_opening_score,
            closed_score_multiplier=result.policy.closed_score_multiplier,
            price_freshness_hours=result.policy.price_freshness_seconds / 3600,
            opening_hours_timezone=result.policy.opening_hours_timezone,
            opening_hours_country=result.policy.opening_hours_country,
            price_selection=result.policy.price_selection,
            closed_candidate_policy=(
                "include_with_zero_opening_score"
                if result.include_closed
                else "exclude"
            ),
        ),
        ranking_evaluation=RankingMetricsResponse.model_validate(asdict(result.metrics)),
        candidates=[_ranked_candidate_response(candidate) for candidate in result.candidates],
    )


def _ranked_candidate_response(candidate: RankedCandidate) -> RankedCandidateResponse:
    price = candidate.price
    return RankedCandidateResponse.model_validate(
        {
            **_detour_candidate_response(candidate.detour).model_dump(),
            "opening": asdict(candidate.opening),
            "phone": candidate.phone,
            "brand": candidate.brand,
            "operator": candidate.operator,
            "osm_match_confidence": candidate.osm_match_confidence,
            "price": (
                {
                    **asdict(price),
                    "unit_price": float(price.unit_price),
                }
                if price is not None
                else None
            ),
            "ranking": asdict(candidate.ranking),
        }
    )
