from dataclasses import asdict
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.candidates.domain import CorridorCandidateRequest, CorridorPolicy
from compass.candidates.service import find_corridor_candidates
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    NoRouteError,
    RouteRequest,
    RoutingProvider,
    RoutingProviderError,
    RoutingUnavailableError,
)

router = APIRouter(prefix="/api/v1", tags=["routing"])


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CoordinateRequest(StrictModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class BaseRouteRequest(StrictModel):
    origin: CoordinateRequest
    destination: CoordinateRequest
    costing: Literal["auto"] = "auto"
    language: str | None = Field(
        default=None,
        pattern=r"^[A-Za-z]{2}(-[A-Za-z]{2})?$",
        description="BCP 47 language tag used for maneuver instructions.",
    )


class CorridorCandidatesRequest(BaseRouteRequest):
    effective_cng_range_km: float = Field(
        gt=0,
        le=2000,
        description=(
            "Usable CNG range after the caller's reserve/safety allowance. "
            "It controls spatial corridor width only."
        ),
    )


class RouteGeometry(StrictModel):
    format: Literal["polyline6"] = "polyline6"
    encoded_polyline: str = Field(min_length=1)


class ManeuverResponse(StrictModel):
    type: int = Field(ge=0)
    instruction: str = Field(min_length=1)
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    begin_shape_index: int = Field(ge=0)
    end_shape_index: int = Field(ge=0)
    street_names: list[str]
    verbal_transition_alert_instruction: str | None
    verbal_pre_transition_instruction: str | None
    verbal_post_transition_instruction: str | None
    bearing_before: int | None
    bearing_after: int | None
    travel_mode: str | None
    travel_type: str | None


class BaseRouteResponse(StrictModel):
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    geometry: RouteGeometry
    maneuvers: list[ManeuverResponse]
    provider: Literal["valhalla"]


class ErrorResponse(StrictModel):
    code: str
    message: str


class CorridorPolicyResponse(StrictModel):
    effective_cng_range_km: float = Field(gt=0)
    range_fraction: float = Field(gt=0, le=1)
    uncapped_radius_km: float = Field(gt=0)
    radius_km: float = Field(gt=0)
    cap_applied: Literal["minimum", "maximum", "none"]


class SpatialPruningMetricsResponse(StrictModel):
    active_station_count: int = Field(ge=0)
    active_station_with_location_count: int = Field(ge=0)
    excluded_missing_location_count: int = Field(ge=0)
    corridor_candidate_count: int = Field(ge=0)
    returned_candidate_count: int = Field(ge=0)
    pruned_with_location_count: int = Field(ge=0)
    reduction_ratio: float = Field(ge=0, le=1)
    candidate_limit_applied: bool
    routing_calls: Literal[1]


class SpatialCandidateResponse(StrictModel):
    station_id: int = Field(gt=0)
    mimit_station_id: str = Field(min_length=1)
    name: str | None
    municipality: str | None
    province: str | None
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    straight_line_distance_to_route_meters: float = Field(
        ge=0,
        description=(
            "Cheap geographic prefilter distance. This is not road distance or detour distance."
        ),
    )
    route_fraction: float = Field(
        ge=0,
        le=1,
        description="Approximate projection position from route start (0) to end (1).",
    )


class CorridorCandidatesResponse(StrictModel):
    stage: Literal["spatial_pruning"] = "spatial_pruning"
    base_route: BaseRouteResponse
    corridor: CorridorPolicyResponse
    metrics: SpatialPruningMetricsResponse
    candidates: list[SpatialCandidateResponse]


@router.post(
    "/routes",
    response_model=BaseRouteResponse,
    responses={
        422: {"model": ErrorResponse, "description": "No route was found."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Routing provider unavailable."},
    },
)
async def base_route(
    request: BaseRouteRequest,
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> BaseRouteResponse | JSONResponse:
    domain_request = RouteRequest(
        origin=Coordinate(
            latitude=request.origin.latitude,
            longitude=request.origin.longitude,
        ),
        destination=Coordinate(
            latitude=request.destination.latitude,
            longitude=request.destination.longitude,
        ),
        costing=request.costing,
        language=request.language or settings.valhalla_route_language,
    )
    try:
        route = await provider.route(domain_request)
    except NoRouteError:
        return _error(422, "route_not_found", "No route was found between the locations.")
    except RoutingUnavailableError:
        return _error(503, "routing_unavailable", "The routing service is unavailable.")
    except RoutingProviderError:
        return _error(
            502,
            "routing_provider_error",
            "The routing service returned an invalid response.",
        )

    return _base_route_response(route)


@router.post(
    "/cng/corridor-candidates",
    response_model=CorridorCandidatesResponse,
    responses={
        422: {"model": ErrorResponse, "description": "Invalid request or no route."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Database or routing unavailable."},
    },
)
async def corridor_candidates(
    request: CorridorCandidatesRequest,
    session: Annotated[Session, Depends(get_session)],
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> CorridorCandidatesResponse | JSONResponse:
    domain_request = CorridorCandidateRequest(
        route=RouteRequest(
            origin=Coordinate(
                latitude=request.origin.latitude,
                longitude=request.origin.longitude,
            ),
            destination=Coordinate(
                latitude=request.destination.latitude,
                longitude=request.destination.longitude,
            ),
            costing=request.costing,
            language=request.language or settings.valhalla_route_language,
        ),
        effective_cng_range_km=request.effective_cng_range_km,
    )
    policy = CorridorPolicy(
        range_fraction=settings.cng_corridor_range_fraction,
        minimum_radius_km=settings.cng_corridor_minimum_radius_km,
        maximum_radius_km=settings.cng_corridor_maximum_radius_km,
        candidate_limit=settings.cng_corridor_candidate_limit,
    )
    try:
        result = await find_corridor_candidates(
            session,
            provider,
            domain_request,
            policy=policy,
            max_route_geometry_points=settings.route_geometry_max_points,
        )
    except NoRouteError:
        return _error(422, "route_not_found", "No route was found between the locations.")
    except RoutingUnavailableError:
        return _error(503, "routing_unavailable", "The routing service is unavailable.")
    except RoutingProviderError:
        return _error(
            502,
            "routing_provider_error",
            "The routing service returned an invalid response.",
        )
    except SQLAlchemyError:
        return _error(503, "database_unavailable", "The station database is unavailable.")

    return CorridorCandidatesResponse(
        base_route=_base_route_response(result.base_route),
        corridor=CorridorPolicyResponse.model_validate(asdict(result.corridor)),
        metrics=SpatialPruningMetricsResponse.model_validate(asdict(result.metrics)),
        candidates=[
            SpatialCandidateResponse.model_validate(asdict(candidate))
            for candidate in result.candidates
        ],
    )


def _base_route_response(route: BaseRoute) -> BaseRouteResponse:
    return BaseRouteResponse(
        distance_meters=route.distance_meters,
        duration_seconds=route.duration_seconds,
        geometry=RouteGeometry(encoded_polyline=route.encoded_polyline),
        maneuvers=[
            ManeuverResponse.model_validate(asdict(maneuver)) for maneuver in route.maneuvers
        ],
        provider="valhalla",
    )


def _error(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=ErrorResponse(code=code, message=message).model_dump(),
    )
