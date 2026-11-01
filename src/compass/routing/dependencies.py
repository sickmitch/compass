from fastapi import Request

from compass.config import get_settings
from compass.routing.domain import RoutingProvider
from compass.routing.valhalla import ValhallaRoutingAdapter


async def get_routing_provider(request: Request) -> RoutingProvider:
    settings = get_settings()
    return ValhallaRoutingAdapter(
        base_url=settings.valhalla_url,
        connect_timeout_seconds=settings.valhalla_connect_timeout_seconds,
        read_timeout_seconds=settings.valhalla_read_timeout_seconds,
        user_agent=settings.http_user_agent,
        speed_limits_enabled=settings.valhalla_speed_limits_enabled,
        client=getattr(request.app.state, "routing_http_client", None),
    )
