from dataclasses import asdict
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from compass.config import Settings, get_api_settings
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
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
