from __future__ import annotations

from dataclasses import asdict
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse
from pydantic import Field

from compass.api.auth import AuthenticatedApiUser, require_api_user
from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.config import Settings, get_api_settings
from compass.routing.domain import Coordinate
from compass.search.dependencies import get_destination_search_service, get_place_search_provider
from compass.search.destination_domain import (
    DestinationNotFoundError,
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchContext,
    DestinationSearchUnavailableError,
    DestinationUnresolvableError,
    StaleDestinationSelectionError,
)
from compass.search.destination_domain import (
    DestinationSuggestRequest as DomainDestinationSuggestRequest,
)
from compass.search.destination_service import DestinationSearchService
from compass.search.domain import (
    PlaceSearchProvider,
    PlaceSearchProviderError,
    PlaceSearchRequest,
    PlaceSearchUnavailableError,
)
from compass.search.service import parse_coordinate_query, search_places

router = APIRouter(prefix="/api/v1", tags=["place-search"])


class DestinationContextRequest(StrictModel):
    location: SearchCoordinateResponse | None = None
    bias_radius_meters: float | None = Field(default=None, ge=1, le=50_000)


class DestinationSuggestRequest(StrictModel):
    query: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=36, max_length=36)
    revision: int = Field(ge=0)
    language: str = Field(default="it", pattern=r"^[a-z]{2}$")
    context: DestinationContextRequest | None = None


class DestinationSuggestionResponse(StrictModel):
    id: str
    provider: Literal["google_places_new"]
    provider_ref: str
    kind: Literal["business", "address", "locality", "unknown"]
    title: str
    subtitle: str | None
    address_preview: str | None
    distance_meters: int | None = Field(default=None, ge=0)
    provider_rank: int = Field(ge=0)
    requires_resolution: bool
    attribution: str


class DestinationSuggestResponse(StrictModel):
    session_id: str
    revision: int
    provider: Literal["google_places_new"] = "google_places_new"
    maximum_results: int = 5
    results: list[DestinationSuggestionResponse]


class DestinationResolveRequest(StrictModel):
    session_id: str = Field(min_length=36, max_length=36)
    revision: int = Field(ge=0)
    provider: Literal["google_places_new"]
    provider_ref: str = Field(min_length=1, max_length=512)


class AddressComponentResponse(StrictModel):
    long_text: str
    short_text: str | None
    types: list[str]


class NormalizedAddressResponse(StrictModel):
    street: str | None
    street_number: str | None
    locality: str | None
    province: str | None
    region: str | None
    postal_code: str | None
    country: str | None


class ResolvedSelectionResponse(StrictModel):
    provider: Literal["google_places_new"]
    provider_ref: str
    formatted_address: str | None
    address_components: list[AddressComponentResponse]
    normalized_address: NormalizedAddressResponse
    location: SearchCoordinateResponse
    kind: Literal["business", "address", "locality", "unknown"]
    resolution_status: Literal["resolved"]
    attribution: list[str]
    field_sources: dict[str, Literal["google_places_new"]]


class NavigationTargetResponse(StrictModel):
    location: SearchCoordinateResponse
    provider_ref: str | None
    map_label: Literal["Destinazione selezionata"]


class DestinationResolveResponse(StrictModel):
    session_id: str
    revision: int
    selection: ResolvedSelectionResponse
    navigation_target: NavigationTargetResponse


class DestinationSearchMetricsResponse(StrictModel):
    sessions_started: int
    sessions_concluded: int
    sessions_abandoned: int
    suggest_requests: int
    resolve_requests: int
    suggest_latency_ms_total: int
    resolve_latency_ms_total: int
    resolutions_succeeded: int
    provider_errors: int
    rate_limited: int
    tomtom_destination_calls: int


def _destination_error(error: Exception) -> JSONResponse:
    if isinstance(error, DestinationRateLimitedError):
        headers = (
            {"Retry-After": str(error.retry_after_seconds)}
            if error.retry_after_seconds is not None
            else None
        )
        response = error_response(429, "rate_limited", "Destination search is rate limited.")
        if headers:
            response.headers.update(headers)
        return response
    if isinstance(error, DestinationNotFoundError):
        return error_response(404, "place_not_found", "The selected place no longer exists.")
    if isinstance(error, StaleDestinationSelectionError):
        return error_response(409, "stale_selection", "The destination selection is stale.")
    if isinstance(error, DestinationUnresolvableError):
        return error_response(
            422,
            "destination_unresolvable",
            "The selected destination has no usable coordinates.",
        )
    if isinstance(error, DestinationProviderError):
        return error_response(
            502, "search_provider_error", "Destination provider returned invalid data."
        )
    return error_response(503, "search_unavailable", "Destination search is unavailable.")


@router.post(
    "/destinations/suggest",
    response_model=DestinationSuggestResponse,
    responses={
        409: {"model": ErrorResponse},
        429: {"model": ErrorResponse},
        502: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
async def destination_suggest(
    payload: DestinationSuggestRequest,
    service: Annotated[DestinationSearchService, Depends(get_destination_search_service)],
    user: Annotated[AuthenticatedApiUser, Depends(require_api_user)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> DestinationSuggestResponse | JSONResponse:
    context = payload.context
    try:
        results = await service.suggest(
            user.username or "anonymous",
            DomainDestinationSuggestRequest(
                query=payload.query,
                session_id=payload.session_id,
                revision=payload.revision,
                language=payload.language,
                context=DestinationSearchContext(
                    location=(
                        None
                        if context is None or context.location is None
                        else Coordinate(
                            context.location.latitude,
                            context.location.longitude,
                        )
                    ),
                    bias_radius_meters=(
                        None
                        if context is None or context.location is None
                        else context.bias_radius_meters
                        or settings.destination_search_bias_radius_meters
                    ),
                ),
            ),
        )
    except (
        DestinationSearchUnavailableError,
        DestinationRateLimitedError,
        DestinationProviderError,
        StaleDestinationSelectionError,
    ) as error:
        return _destination_error(error)
    except ValueError:
        return error_response(422, "invalid_request", "Destination search request is invalid.")
    return DestinationSuggestResponse(
        session_id=payload.session_id,
        revision=payload.revision,
        results=[DestinationSuggestionResponse(**asdict(item)) for item in results],
    )


@router.post(
    "/destinations/resolve",
    response_model=DestinationResolveResponse,
    responses={
        404: {"model": ErrorResponse},
        409: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        429: {"model": ErrorResponse},
        502: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
async def destination_resolve(
    payload: DestinationResolveRequest,
    service: Annotated[DestinationSearchService, Depends(get_destination_search_service)],
    user: Annotated[AuthenticatedApiUser, Depends(require_api_user)],
) -> DestinationResolveResponse | JSONResponse:
    try:
        selection, target = await service.resolve(
            owner=user.username or "anonymous",
            session_id=payload.session_id,
            revision=payload.revision,
            provider=payload.provider,
            provider_ref=payload.provider_ref,
        )
    except (
        DestinationSearchUnavailableError,
        DestinationRateLimitedError,
        DestinationProviderError,
        DestinationNotFoundError,
        DestinationUnresolvableError,
        StaleDestinationSelectionError,
    ) as error:
        return _destination_error(error)
    except ValueError:
        return error_response(422, "invalid_request", "Destination selection is invalid.")
    coordinate = SearchCoordinateResponse(
        latitude=selection.coordinate.latitude,
        longitude=selection.coordinate.longitude,
    )
    return DestinationResolveResponse(
        session_id=payload.session_id,
        revision=payload.revision,
        selection=ResolvedSelectionResponse(
            provider=selection.provider,
            provider_ref=selection.provider_ref,
            formatted_address=selection.formatted_address,
            address_components=[
                AddressComponentResponse(**asdict(item)) for item in selection.address_components
            ],
            normalized_address=NormalizedAddressResponse(**asdict(selection.normalized_address)),
            location=coordinate,
            kind=selection.kind,
            resolution_status=selection.resolution_status,
            attribution=list(selection.attribution),
            field_sources=dict(selection.field_sources),
        ),
        navigation_target=NavigationTargetResponse(
            location=coordinate,
            provider_ref=target.provider_ref,
            map_label=target.map_label,
        ),
    )


@router.get(
    "/destinations/metrics",
    response_model=DestinationSearchMetricsResponse,
)
async def destination_metrics(
    service: Annotated[DestinationSearchService, Depends(get_destination_search_service)],
) -> DestinationSearchMetricsResponse:
    """Aggregate operational counters; queries and Place IDs are never metric labels."""
    return DestinationSearchMetricsResponse(**service.metrics.snapshot())


class SearchCoordinateResponse(StrictModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class PlaceSearchResultResponse(StrictModel):
    result_id: str = Field(min_length=1)
    display_name: str = Field(min_length=1)
    address: str | None
    location: SearchCoordinateResponse
    kind: Literal["address", "locality", "poi", "coordinate", "unknown"]
    category: str | None
    poi_name: str | None
    provider: str = Field(min_length=1)
    provider_place_id: str | None


class PlaceSearchResponse(StrictModel):
    query: str
    cacheable: bool
    results: list[PlaceSearchResultResponse]


@router.get(
    "/places/search",
    response_model=PlaceSearchResponse,
    responses={
        502: {"model": ErrorResponse, "description": "Invalid search-provider response."},
        503: {"model": ErrorResponse, "description": "Search provider unavailable."},
    },
)
async def place_search(
    provider: Annotated[PlaceSearchProvider, Depends(get_place_search_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
    q: Annotated[str, Query(min_length=1, max_length=200)],
    limit: Annotated[int | None, Query(ge=1, le=20)] = None,
    language: Annotated[str, Query(pattern=r"^[A-Za-z]{2}(-[A-Za-z]{2})?$")] = "it",
) -> PlaceSearchResponse | JSONResponse:
    request = PlaceSearchRequest(
        query=q,
        limit=limit or settings.geocoding_result_limit,
        language=language,
    )
    try:
        results = await search_places(provider, request)
    except PlaceSearchUnavailableError:
        return error_response(503, "search_unavailable", "Place search is unavailable.")
    except PlaceSearchProviderError:
        return error_response(502, "search_provider_error", "Place search returned invalid data.")
    return PlaceSearchResponse(
        query=q,
        cacheable=(
            parse_coordinate_query(q) is not None or bool(getattr(provider, "cacheable", True))
        ),
        results=[
            PlaceSearchResultResponse(
                result_id=result.result_id,
                display_name=result.display_name,
                address=result.address,
                location=SearchCoordinateResponse(
                    latitude=result.coordinate.latitude,
                    longitude=result.coordinate.longitude,
                ),
                kind=result.kind,
                category=result.category,
                poi_name=result.poi_name,
                provider=result.provider,
                provider_place_id=result.provider_place_id,
            )
            for result in results
        ],
    )
