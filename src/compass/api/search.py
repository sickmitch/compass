from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse
from pydantic import Field

from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.config import Settings, get_api_settings
from compass.search.dependencies import get_place_search_provider
from compass.search.domain import (
    PlaceSearchProvider,
    PlaceSearchProviderError,
    PlaceSearchRequest,
    PlaceSearchUnavailableError,
)
from compass.search.service import search_places

router = APIRouter(prefix="/api/v1", tags=["place-search"])


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
