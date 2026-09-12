from dataclasses import asdict
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import Field

from compass.api.auth import AuthenticatedApiUser, require_api_user
from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.routing.domain import Coordinate
from compass.search.along_route import (
    AlongRouteContext,
    AlongRouteLeg,
    AlongRouteSearchService,
    InvalidRouteContextError,
    RouteRequiredError,
    UnsupportedRouteGeometryError,
)
from compass.search.along_route import (
    AlongRouteSearchRequest as DomainAlongRouteSearchRequest,
)
from compass.search.dependencies import get_along_route_search_service
from compass.search.destination_domain import (
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchUnavailableError,
    StaleDestinationSelectionError,
)

router = APIRouter(prefix="/api/v1/places/search-along-route", tags=["place-search"])


class CoordinateRequest(StrictModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class RouteLegRequest(StrictModel):
    encoded_polyline: str = Field(min_length=2, max_length=1_000_000)
    precision: Literal[6]


class RouteContextRequest(StrictModel):
    route_id: str = Field(min_length=1, max_length=256)
    route_revision: int = Field(ge=0)
    origin: CoordinateRequest
    final_destination: CoordinateRequest
    remaining_waypoints: list[CoordinateRequest] = Field(default_factory=list, max_length=8)
    legs: list[RouteLegRequest] = Field(min_length=1, max_length=9)
    progress_shape_index: int | None = Field(default=None, ge=0)
    insertion_leg_index: int | None = Field(default=None, ge=0, le=8)


class AlongRouteSearchRequest(StrictModel):
    intent: Literal["ADD_STOP_ALONG_ROUTE"]
    query: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=36, max_length=36)
    revision: int = Field(ge=0)
    language: str = Field(default="it", pattern=r"^[a-z]{2}$")
    route: RouteContextRequest | None = None
    page_cursor: str | None = Field(default=None, min_length=1, max_length=128)


class SuggestionResponse(StrictModel):
    id: str
    provider: Literal["google_places_new"]
    provider_ref: str
    kind: Literal["business", "address", "locality", "unknown"]
    title: str
    subtitle: str | None
    address_preview: str | None
    distance_meters: int | None
    provider_rank: int
    requires_resolution: bool
    attribution: str


class AlongRouteSearchResponse(StrictModel):
    session_id: str
    revision: int
    route_id: str
    route_revision: int
    route_fingerprint: str
    mode: Literal["route_biased"]
    limitation: str
    next_page_cursor: str | None
    results: list[SuggestionResponse]


class AlongRouteResolveRequest(StrictModel):
    intent: Literal["ADD_STOP_ALONG_ROUTE"]
    session_id: str = Field(min_length=36, max_length=36)
    revision: int = Field(ge=0)
    route_id: str = Field(min_length=1, max_length=256)
    route_revision: int = Field(ge=0)
    route_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    provider_ref: str = Field(min_length=1, max_length=512)
    route: RouteContextRequest


class ResolvedSelectionResponse(StrictModel):
    provider: Literal["google_places_new"]
    provider_ref: str
    formatted_address: str | None
    location: CoordinateRequest
    kind: Literal["business", "address", "locality", "unknown"]
    attribution: list[str]


class NavigationTargetResponse(StrictModel):
    location: CoordinateRequest
    provider_ref: str | None
    map_label: Literal["Destinazione selezionata"]


class AlongRouteResolveResponse(StrictModel):
    session_id: str
    revision: int
    route_id: str
    route_revision: int
    selection: ResolvedSelectionResponse
    navigation_target: NavigationTargetResponse


class AlongRouteMetricsResponse(StrictModel):
    provider_calls: int
    latency_ms_total: int
    provider_errors: int
    rate_limited: int
    results_returned: int
    stale_responses_discarded: int
    resolutions_succeeded: int


def _error(error: Exception) -> JSONResponse:
    if isinstance(error, RouteRequiredError):
        return error_response(409, "route_required", "Calculate a route before adding a stop.")
    if isinstance(error, StaleDestinationSelectionError):
        return error_response(409, "route_context_stale", "The route search context is stale.")
    if isinstance(error, UnsupportedRouteGeometryError):
        return error_response(
            422, "unsupported_route_geometry", "This route geometry cannot be searched."
        )
    if isinstance(error, InvalidRouteContextError):
        return error_response(422, "invalid_route_context", "The route context is invalid.")
    if isinstance(error, DestinationRateLimitedError):
        response = error_response(429, "rate_limited", "Place search is rate limited.")
        if error.retry_after_seconds is not None:
            response.headers["Retry-After"] = str(error.retry_after_seconds)
        return response
    if isinstance(error, DestinationProviderError):
        return error_response(502, "search_provider_error", "Place provider rejected the request.")
    return error_response(503, "search_unavailable", "Place search is unavailable.")


@router.post(
    "",
    response_model=AlongRouteSearchResponse,
    responses={
        409: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        429: {"model": ErrorResponse},
        502: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
async def search_along_route(
    payload: AlongRouteSearchRequest,
    service: Annotated[AlongRouteSearchService, Depends(get_along_route_search_service)],
    user: Annotated[AuthenticatedApiUser, Depends(require_api_user)],
) -> AlongRouteSearchResponse | JSONResponse:
    route = payload.route
    try:
        result = await service.search(
            user.username or "anonymous",
            DomainAlongRouteSearchRequest(
                query=payload.query,
                session_id=payload.session_id,
                revision=payload.revision,
                language=payload.language,
                page_cursor=payload.page_cursor,
                route=None if route is None else _domain_route(route),
            ),
        )
    except (
        RouteRequiredError,
        InvalidRouteContextError,
        UnsupportedRouteGeometryError,
        DestinationRateLimitedError,
        DestinationProviderError,
        DestinationSearchUnavailableError,
        StaleDestinationSelectionError,
    ) as error:
        return _error(error)
    except ValueError:
        return error_response(422, "invalid_request", "Along-route search request is invalid.")
    return AlongRouteSearchResponse(
        session_id=result.session_id,
        revision=result.revision,
        route_id=result.route_id,
        route_revision=result.route_revision,
        route_fingerprint=result.route_fingerprint,
        mode="route_biased",
        limitation=result.limitation,
        next_page_cursor=result.next_page_cursor,
        results=[SuggestionResponse(**asdict(item)) for item in result.results],
    )


@router.post("/resolve", response_model=AlongRouteResolveResponse)
async def resolve_along_route(
    payload: AlongRouteResolveRequest,
    service: Annotated[AlongRouteSearchService, Depends(get_along_route_search_service)],
    user: Annotated[AuthenticatedApiUser, Depends(require_api_user)],
) -> AlongRouteResolveResponse | JSONResponse:
    try:
        selection, target = await service.resolve(
            owner=user.username or "anonymous",
            session_id=payload.session_id,
            revision=payload.revision,
            route_id=payload.route_id,
            route_revision=payload.route_revision,
            route_fingerprint=payload.route_fingerprint,
            provider_ref=payload.provider_ref,
            route=_domain_route(payload.route),
        )
    except (
        DestinationSearchUnavailableError,
        StaleDestinationSelectionError,
        InvalidRouteContextError,
        UnsupportedRouteGeometryError,
    ) as error:
        return _error(error)
    coordinate = CoordinateRequest(
        latitude=selection.coordinate.latitude, longitude=selection.coordinate.longitude
    )
    return AlongRouteResolveResponse(
        session_id=payload.session_id,
        revision=payload.revision,
        route_id=payload.route_id,
        route_revision=payload.route_revision,
        selection=ResolvedSelectionResponse(
            provider="google_places_new",
            provider_ref=selection.provider_ref,
            formatted_address=selection.formatted_address,
            location=coordinate,
            kind=selection.kind,
            attribution=list(selection.attribution),
        ),
        navigation_target=NavigationTargetResponse(
            location=coordinate,
            provider_ref=target.provider_ref,
            map_label="Destinazione selezionata",
        ),
    )


@router.get("/metrics", response_model=AlongRouteMetricsResponse)
async def along_route_metrics(
    service: Annotated[AlongRouteSearchService, Depends(get_along_route_search_service)],
) -> AlongRouteMetricsResponse:
    return AlongRouteMetricsResponse(**service.metrics.snapshot())


def _domain_route(route: RouteContextRequest) -> AlongRouteContext:
    return AlongRouteContext(
        route_id=route.route_id,
        route_revision=route.route_revision,
        origin=Coordinate(route.origin.latitude, route.origin.longitude),
        final_destination=Coordinate(
            route.final_destination.latitude, route.final_destination.longitude
        ),
        remaining_waypoints=tuple(
            Coordinate(point.latitude, point.longitude) for point in route.remaining_waypoints
        ),
        legs=tuple(AlongRouteLeg(leg.encoded_polyline) for leg in route.legs),
        progress_shape_index=route.progress_shape_index,
        insertion_leg_index=route.insertion_leg_index,
    )
