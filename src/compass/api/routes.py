from dataclasses import asdict
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import Field, field_validator
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.candidates.domain import CorridorCandidateRequest, CorridorPolicy
from compass.candidates.service import find_corridor_candidates
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.detours.domain import (
    EligibleDetourCandidate,
    NetworkDetourPolicy,
    NetworkDetourRequest,
)
from compass.detours.service import evaluate_cng_detours
from compass.navigation.domain import NavigationTiming, build_navigation_timing
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
from compass.traffic.dependencies import get_traffic_route_refresher
from compass.traffic.domain import TrafficHealthState
from compass.traffic.route_refresh import TrafficRouteRefresher
from compass.traffic.routing import refresh_base_route_traffic
from compass.traffic.service import network_cost_basis_from_settings

router = APIRouter(prefix="/api/v1", tags=["routing"])


class CoordinateRequest(StrictModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class BaseRouteRequest(StrictModel):
    origin: CoordinateRequest
    destination: CoordinateRequest
    costing: Literal["auto"] = "auto"
    departure_at: datetime | None = Field(
        default=None,
        description=(
            "Optional timezone-aware departure instant. When omitted, traffic-aware "
            "routing departs now."
        ),
    )
    language: str | None = Field(
        default=None,
        pattern=r"^[A-Za-z]{2}(-[A-Za-z]{2})?$",
        description="BCP 47 language tag used for maneuver instructions.",
    )

    @field_validator("departure_at")
    @classmethod
    def validate_optional_departure_offset(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("departure_at must include a UTC offset")
        return value


class CorridorCandidatesRequest(BaseRouteRequest):
    effective_cng_range_km: float = Field(
        gt=0,
        le=2000,
        description=(
            "Usable CNG range after the caller's reserve/safety allowance. "
            "It controls spatial corridor width only."
        ),
    )


class DetourCandidatesRequest(CorridorCandidatesRequest):
    maximum_detour_minutes: float = Field(
        ge=0,
        le=240,
        description="Inclusive maximum added road-network travel time.",
    )
    departure_at: datetime = Field(
        description=(
            "Timezone-aware departure instant used to calculate station and destination ETAs."
        )
    )

    @field_validator("departure_at")
    @classmethod
    def validate_departure_offset(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("departure_at must include a UTC offset")
        return value


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


class NavigationTimingResponse(StrictModel):
    route_id: str = Field(pattern=r"^route_[0-9a-f]{32}$")
    driving_duration_seconds: float = Field(ge=0)
    remaining_driving_duration_seconds: float = Field(ge=0)
    refueling_stop_count: int = Field(ge=0)
    dwell_seconds_per_refueling_stop: int = Field(ge=0)
    total_refueling_dwell_seconds: float = Field(ge=0)
    total_trip_duration_seconds: float = Field(ge=0)
    departure_at: datetime | None
    driving_arrival_at: datetime | None
    trip_arrival_at: datetime | None
    traffic_delay_seconds: float | None = Field(default=None, ge=0)
    traffic_delay_state: Literal["unavailable", "estimated"] = "unavailable"


class BaseRouteResponse(StrictModel):
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    geometry: RouteGeometry
    maneuvers: list[ManeuverResponse]
    provider: Literal["valhalla"]
    navigation: NavigationTimingResponse


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


class NetworkCostBasisResponse(StrictModel):
    provider: Literal["valhalla"]
    traffic_state: TrafficHealthState
    traffic_aware: bool
    duration_model: Literal[
        "valhalla_graph_speeds",
        "valhalla_time_dependent_traffic",
    ]
    distance_model: Literal["road_network"]


class NetworkEvaluationMetricsResponse(StrictModel):
    spatial_candidate_count: int = Field(ge=0)
    matrix_candidate_count: int = Field(ge=0)
    reachable_candidate_count: int = Field(ge=0)
    unreachable_candidate_count: int = Field(ge=0)
    eligible_candidate_count: int = Field(ge=0)
    excluded_by_detour_count: int = Field(ge=0)
    matrix_batch_size: int = Field(gt=0)
    matrix_calls: int = Field(ge=0)
    matrix_fallback_splits: int = Field(
        ge=0,
        description="Binary batch splits used to isolate uncorrelatable locations.",
    )
    matrix_location_failures: int = Field(
        ge=0,
        description="Isolated candidate locations with no suitable routing edge.",
    )
    base_route_calls: Literal[1]
    per_candidate_route_calls: Literal[0]


class EligibleDetourCandidateResponse(SpatialCandidateResponse):
    distance_from_previous_waypoint_meters: float = Field(
        ge=0,
        description="Road-network distance from the request origin/previous waypoint.",
    )
    duration_from_previous_waypoint_seconds: float = Field(ge=0)
    station_to_destination_distance_meters: float = Field(ge=0)
    station_to_destination_duration_seconds: float = Field(ge=0)
    route_via_station_distance_meters: float = Field(ge=0)
    route_via_station_duration_seconds: float = Field(ge=0)
    extra_distance_meters: float = Field(ge=0)
    detour_duration_seconds: float = Field(ge=0)
    detour_minutes: float = Field(ge=0)
    station_eta: datetime
    destination_eta: datetime


class DetourCandidatesResponse(StrictModel):
    stage: Literal["network_detour"] = "network_detour"
    departure_at: datetime
    maximum_detour_minutes: float = Field(ge=0)
    base_route: BaseRouteResponse
    corridor: CorridorPolicyResponse
    spatial_pruning: SpatialPruningMetricsResponse
    cost_basis: NetworkCostBasisResponse
    network_evaluation: NetworkEvaluationMetricsResponse
    candidates: list[EligibleDetourCandidateResponse]


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
    traffic_refresher: Annotated[
        TrafficRouteRefresher, Depends(get_traffic_route_refresher)
    ],
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
        departure_at=request.departure_at,
    )
    try:
        route = await provider.route(domain_request)
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

    route = await refresh_base_route_traffic(
        route=route,
        request=domain_request,
        provider=provider,
        refresher=traffic_refresher,
        current_departure_tolerance_seconds=(
            settings.traffic_route_refresh_min_interval_seconds
        ),
    )

    return _base_route_response(route, departure_at=request.departure_at)


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
            departure_at=request.departure_at,
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

    return CorridorCandidatesResponse(
        base_route=_base_route_response(result.base_route),
        corridor=CorridorPolicyResponse.model_validate(asdict(result.corridor)),
        metrics=SpatialPruningMetricsResponse.model_validate(asdict(result.metrics)),
        candidates=[
            SpatialCandidateResponse.model_validate(asdict(candidate))
            for candidate in result.candidates
        ],
    )


@router.post(
    "/cng/detour-candidates",
    response_model=DetourCandidatesResponse,
    responses={
        422: {"model": ErrorResponse, "description": "Invalid request or no route."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Database or routing unavailable."},
    },
)
async def detour_candidates(
    request: DetourCandidatesRequest,
    session: Annotated[Session, Depends(get_session)],
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> DetourCandidatesResponse | JSONResponse:
    route_request = RouteRequest(
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
        departure_at=request.departure_at,
    )
    domain_request = NetworkDetourRequest(
        corridor_request=CorridorCandidateRequest(
            route=route_request,
            effective_cng_range_km=request.effective_cng_range_km,
        ),
        maximum_detour_seconds=request.maximum_detour_minutes * 60,
        departure_at=request.departure_at,
    )
    corridor_policy = CorridorPolicy(
        range_fraction=settings.cng_corridor_range_fraction,
        minimum_radius_km=settings.cng_corridor_minimum_radius_km,
        maximum_radius_km=settings.cng_corridor_maximum_radius_km,
        candidate_limit=settings.cng_corridor_candidate_limit,
    )
    try:
        result = await evaluate_cng_detours(
            session,
            provider,
            domain_request,
            corridor_policy=corridor_policy,
            detour_policy=NetworkDetourPolicy(
                matrix_batch_size=settings.valhalla_matrix_batch_size
            ),
            max_route_geometry_points=settings.route_geometry_max_points,
            cost_basis=network_cost_basis_from_settings(settings),
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

    spatial = result.spatial_result
    return DetourCandidatesResponse(
        departure_at=result.departure_at,
        maximum_detour_minutes=result.maximum_detour_seconds / 60,
        base_route=_base_route_response(spatial.base_route),
        corridor=CorridorPolicyResponse.model_validate(asdict(spatial.corridor)),
        spatial_pruning=SpatialPruningMetricsResponse.model_validate(
            asdict(spatial.metrics)
        ),
        cost_basis=NetworkCostBasisResponse.model_validate(asdict(result.cost_basis)),
        network_evaluation=NetworkEvaluationMetricsResponse.model_validate(
            asdict(result.metrics)
        ),
        candidates=[_detour_candidate_response(candidate) for candidate in result.candidates],
    )


def _base_route_response(
    route: BaseRoute,
    *,
    departure_at: datetime | None = None,
) -> BaseRouteResponse:
    navigation = build_navigation_timing(
        encoded_polylines=(route.encoded_polyline,),
        driving_duration_seconds=route.duration_seconds,
        departure_at=departure_at,
    )
    return BaseRouteResponse(
        distance_meters=route.distance_meters,
        duration_seconds=route.duration_seconds,
        geometry=RouteGeometry(encoded_polyline=route.encoded_polyline),
        maneuvers=[
            ManeuverResponse.model_validate(asdict(maneuver)) for maneuver in route.maneuvers
        ],
        provider="valhalla",
        navigation=_navigation_timing_response(navigation),
    )


def _navigation_timing_response(timing: NavigationTiming) -> NavigationTimingResponse:
    return NavigationTimingResponse.model_validate(asdict(timing))


def _detour_candidate_response(
    candidate: EligibleDetourCandidate,
) -> EligibleDetourCandidateResponse:
    return EligibleDetourCandidateResponse.model_validate(
        {
            **asdict(candidate.station),
            "distance_from_previous_waypoint_meters": (
                candidate.distance_from_previous_waypoint_meters
            ),
            "duration_from_previous_waypoint_seconds": (
                candidate.duration_from_previous_waypoint_seconds
            ),
            "station_to_destination_distance_meters": (
                candidate.station_to_destination_distance_meters
            ),
            "station_to_destination_duration_seconds": (
                candidate.station_to_destination_duration_seconds
            ),
            "route_via_station_distance_meters": (
                candidate.route_via_station_distance_meters
            ),
            "route_via_station_duration_seconds": (
                candidate.route_via_station_duration_seconds
            ),
            "extra_distance_meters": candidate.extra_distance_meters,
            "detour_duration_seconds": candidate.detour_duration_seconds,
            "detour_minutes": candidate.detour_minutes,
            "station_eta": candidate.station_eta,
            "destination_eta": candidate.destination_eta,
        }
    )
