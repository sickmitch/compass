from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from compass.routing.domain import (
    BaseRoute,
    RouteRequest,
    RoutingError,
    RoutingProvider,
    WaypointRoute,
    WaypointRouteRequest,
)
from compass.traffic.route_refresh import TrafficRouteRefresher, route_scope_key


async def refresh_base_route_traffic(
    *,
    route: BaseRoute,
    request: RouteRequest,
    provider: RoutingProvider,
    refresher: TrafficRouteRefresher,
    current_departure_tolerance_seconds: float,
) -> BaseRoute:
    if not _is_current_departure(
        request.departure_at,
        tolerance_seconds=current_departure_tolerance_seconds,
    ):
        return route
    scope_key = route_scope_key(
        origin=request.origin,
        destination=request.destination,
        costing=request.costing,
    )
    result = await refresher.refresh(
        scope_key=scope_key,
        trigger="route_calculation",
        encoded_polylines=(route.encoded_polyline,),
    )
    if not result.overlay_changed:
        return route
    try:
        return await provider.route(request)
    except RoutingError:
        logging.getLogger("compass.traffic.routing").warning(
            "traffic overlay changed but route recomputation failed; returning initial route",
            extra={"traffic_scope_key": scope_key},
        )
        return route


async def refresh_waypoint_route_traffic(
    *,
    route: WaypointRoute,
    request: WaypointRouteRequest,
    provider: RoutingProvider,
    refresher: TrafficRouteRefresher,
    current_departure_tolerance_seconds: float,
) -> WaypointRoute:
    if not _is_current_departure(
        request.departure_at,
        tolerance_seconds=current_departure_tolerance_seconds,
    ):
        return route
    scope_key = route_scope_key(
        origin=request.origin,
        destination=request.destination,
        waypoints=request.waypoints,
        costing=request.costing,
    )
    result = await refresher.refresh(
        scope_key=scope_key,
        trigger="route_calculation",
        encoded_polylines=tuple(leg.encoded_polyline for leg in route.legs),
    )
    if not result.overlay_changed:
        return route
    try:
        return await provider.route_with_waypoints(request)
    except RoutingError:
        logging.getLogger("compass.traffic.routing").warning(
            "traffic overlay changed but waypoint route recomputation failed; "
            "returning initial route",
            extra={"traffic_scope_key": scope_key},
        )
        return route


def _is_current_departure(
    departure_at: datetime | None, *, tolerance_seconds: float
) -> bool:
    if departure_at is None:
        return True
    now = datetime.now(UTC)
    departure = departure_at.astimezone(UTC)
    return abs(departure - now) <= timedelta(seconds=tolerance_seconds)
