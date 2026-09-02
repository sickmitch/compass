from __future__ import annotations

from collections.abc import AsyncIterator

import httpx

from compass.config import get_settings
from compass.traffic.route_refresh import (
    DisabledTrafficRouteRefresher,
    HttpTrafficRouteRefresher,
    TrafficRouteRefresher,
)


async def get_traffic_route_refresher() -> AsyncIterator[TrafficRouteRefresher]:
    settings = get_settings()
    if (
        not settings.traffic_enabled
        or not settings.traffic_valhalla_overlay_enabled
        or settings.traffic_refresh_mode != "on_demand"
    ):
        yield DisabledTrafficRouteRefresher()
        return
    async with httpx.AsyncClient() as client:
        yield HttpTrafficRouteRefresher(
            base_url=settings.traffic_updater_url,
            timeout_seconds=settings.traffic_route_refresh_timeout_seconds,
            user_agent=settings.http_user_agent,
            client=client,
        )
