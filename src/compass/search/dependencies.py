from collections.abc import AsyncIterator
from typing import Annotated

import httpx
from fastapi import Depends

from compass.config import Settings, get_api_settings
from compass.search.domain import PlaceSearchProvider, PlaceSearchUnavailableError
from compass.search.nominatim import NominatimPlaceSearchProvider


class DisabledPlaceSearchProvider:
    async def search(self, _request):
        raise PlaceSearchUnavailableError("place search is not configured")


async def get_place_search_provider(
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> AsyncIterator[PlaceSearchProvider]:
    if settings.geocoding_provider == "none":
        yield DisabledPlaceSearchProvider()
        return
    async with httpx.AsyncClient() as client:
        yield NominatimPlaceSearchProvider(
            base_url=settings.nominatim_url,
            timeout_seconds=settings.geocoding_timeout_seconds,
            user_agent=settings.http_user_agent,
            country_codes=settings.geocoding_country_codes,
            client=client,
        )
