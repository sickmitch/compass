from collections.abc import AsyncIterator
from typing import Annotated

import httpx
from fastapi import Depends

from compass.config import Settings, get_api_settings
from compass.search.along_route import (
    AlongRouteSearchMetrics,
    AlongRouteSearchService,
    GooglePlacesNewAlongRouteProvider,
)
from compass.search.corroboration import CorroboratedPlaceSearchProvider
from compass.search.destination_domain import DestinationSearchUnavailableError
from compass.search.destination_service import DestinationSearchMetrics, DestinationSearchService
from compass.search.domain import PlaceSearchProvider, PlaceSearchUnavailableError
from compass.search.google_places import (
    GooglePlacesNewDestinationProvider,
    GooglePlacesNewSearchProvider,
)
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
            corroboration_radius_meters=(settings.google_places_corroboration_radius_meters),
        )


class DisabledDestinationSearchService:
    metrics = DestinationSearchMetrics()

    async def suggest(self, *_args, **_kwargs):
        raise DestinationSearchUnavailableError("destination search is not configured")

    async def resolve(self, *_args, **_kwargs):
        raise DestinationSearchUnavailableError("destination search is not configured")


class DisabledAlongRouteSearchService:
    metrics = AlongRouteSearchMetrics()

    async def search(self, *_args, **_kwargs):
        raise DestinationSearchUnavailableError("along-route search is not configured")

    async def resolve(self, *_args, **_kwargs):
        raise DestinationSearchUnavailableError("along-route search is not configured")


class DestinationSearchRuntime:
    def __init__(self, settings: Settings) -> None:
        providers = tuple(
            item.strip()
            for item in settings.destination_search_providers.split(",")
            if item.strip() and item.strip() != "none"
        )
        if not providers:
            self.client = None
            self.service = DisabledDestinationSearchService()
            self.along_route_service = DisabledAlongRouteSearchService()
            return
        if providers != ("google_places_new",) or not settings.google_places_enabled:
            raise ValueError("only google_places_new may be active during Google testing")
        self.client = httpx.AsyncClient()
        google = GooglePlacesNewDestinationProvider(
            base_url=settings.google_places_url,
            api_key=settings.google_places_api_key.get_secret_value(),
            timeout_seconds=settings.destination_search_timeout_seconds,
            region_code=settings.destination_search_region,
            included_region_codes=tuple(
                item.strip()
                for item in settings.destination_search_included_regions.split(",")
                if item.strip()
            ),
            client=self.client,
        )
        self.service = DestinationSearchService(
            providers=(google,),
            session_ttl_seconds=settings.destination_search_session_ttl_seconds,
            max_concurrency=settings.destination_search_max_concurrency,
            requests_per_user_minute=settings.destination_search_rate_limit_per_minute,
            minimum_query_characters=settings.destination_search_min_chars,
        )
        if settings.destination_along_route_enabled:
            along_route_provider = GooglePlacesNewAlongRouteProvider(
                base_url=settings.google_places_url,
                api_key=settings.google_places_api_key.get_secret_value(),
                timeout_seconds=settings.destination_search_timeout_seconds,
                region_code=settings.google_places_region_code,
                page_size=settings.destination_along_route_page_size,
                client=self.client,
            )
            self.along_route_service = AlongRouteSearchService(
                provider=along_route_provider,
                max_geometry_points=settings.destination_along_route_max_geometry_points,
                max_encoded_polyline_chars=(settings.destination_along_route_max_encoded_chars),
                session_ttl_seconds=settings.destination_search_session_ttl_seconds,
                max_concurrency=settings.destination_search_max_concurrency,
                requests_per_user_minute=(settings.destination_search_rate_limit_per_minute),
                minimum_query_characters=settings.destination_search_min_chars,
            )
        else:
            self.along_route_service = DisabledAlongRouteSearchService()

    async def close(self) -> None:
        if self.client is not None:
            await self.client.aclose()


_destination_runtime: DestinationSearchRuntime | None = None


async def get_destination_search_service(
    settings: Annotated[Settings, Depends(get_api_settings)],
):
    global _destination_runtime
    if _destination_runtime is None:
        _destination_runtime = DestinationSearchRuntime(settings)
    return _destination_runtime.service


async def get_along_route_search_service(
    settings: Annotated[Settings, Depends(get_api_settings)],
):
    global _destination_runtime
    if _destination_runtime is None:
        _destination_runtime = DestinationSearchRuntime(settings)
    return _destination_runtime.along_route_service


async def close_destination_search_runtime() -> None:
    global _destination_runtime
    if _destination_runtime is not None:
        await _destination_runtime.close()
        _destination_runtime = None
