from collections.abc import AsyncIterator

import httpx

from compass.config import get_settings
from compass.routing.domain import RoutingProvider
from compass.routing.valhalla import ValhallaRoutingAdapter


async def get_routing_provider() -> AsyncIterator[RoutingProvider]:
    settings = get_settings()
    async with httpx.AsyncClient() as client:
        yield ValhallaRoutingAdapter(
            base_url=settings.valhalla_url,
            connect_timeout_seconds=settings.valhalla_connect_timeout_seconds,
            read_timeout_seconds=settings.valhalla_read_timeout_seconds,
            user_agent=settings.http_user_agent,
            client=client,
        )
