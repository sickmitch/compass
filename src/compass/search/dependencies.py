from collections.abc import AsyncIterator
from typing import Annotated

import httpx
from fastapi import Depends

from compass.config import Settings, get_api_settings
from compass.search.corroboration import CorroboratedPlaceSearchProvider
from compass.search.domain import PlaceSearchProvider, PlaceSearchUnavailableError
from compass.search.google_places import GooglePlacesNewSearchProvider
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
        nominatim = NominatimPlaceSearchProvider(
            base_url=settings.nominatim_url,
            timeout_seconds=settings.geocoding_timeout_seconds,
            user_agent=settings.http_user_agent,
            country_codes=settings.geocoding_country_codes,
            client=client,
        )
        if settings.geocoding_provider == "nominatim":
            yield nominatim
            return
        google_places = GooglePlacesNewSearchProvider(
            base_url=settings.google_places_url,
            api_key=settings.google_places_api_key.get_secret_value(),
            timeout_seconds=settings.geocoding_timeout_seconds,
            region_code=settings.google_places_region_code,
            client=client,
        )
        yield CorroboratedPlaceSearchProvider(
            primary=nominatim,
            corroborator=google_places,
            corroboration_radius_meters=(
                settings.google_places_corroboration_radius_meters
            ),
        )
