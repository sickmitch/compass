from sqlalchemy.orm import Session

from compass.candidates.domain import (
    CorridorCandidateRequest,
    CorridorCandidateResult,
    CorridorPolicy,
    SpatialPruningMetrics,
)
from compass.candidates.geometry import decode_polyline6, route_linestring_wkt
from compass.candidates.repository import PostgisCandidateRepository
from compass.routing.domain import BaseRoute, RoutingProvider


async def find_corridor_candidates(
    session: Session,
    provider: RoutingProvider,
    request: CorridorCandidateRequest,
    *,
    policy: CorridorPolicy,
    max_route_geometry_points: int,
    base_route: BaseRoute | None = None,
) -> CorridorCandidateResult:
    """Route once, then perform only cheap PostGIS spatial candidate pruning."""
    corridor = policy.radius_for(request.effective_cng_range_km)
    if base_route is None:
        base_route = await provider.route(request.route)
    route_coordinates = decode_polyline6(
        base_route.encoded_polyline,
        max_points=max_route_geometry_points,
    )
    query = PostgisCandidateRepository(session).within_corridor(
        route_wkt=route_linestring_wkt(route_coordinates),
        radius_meters=corridor.radius_km * 1000,
        limit=policy.candidate_limit,
        excluded_mimit_station_ids=request.excluded_mimit_station_ids,
    )

    with_location = query.active_station_with_location_count
    corridor_count = query.corridor_candidate_count
    reduction_ratio = 0.0 if with_location == 0 else 1 - (corridor_count / with_location)
    metrics = SpatialPruningMetrics(
        active_station_count=query.active_station_count,
        active_station_with_location_count=with_location,
        excluded_missing_location_count=query.active_station_count - with_location,
        corridor_candidate_count=corridor_count,
        returned_candidate_count=len(query.candidates),
        pruned_with_location_count=with_location - corridor_count,
        reduction_ratio=reduction_ratio,
        candidate_limit_applied=corridor_count > len(query.candidates),
    )
    return CorridorCandidateResult(
        base_route=base_route,
        corridor=corridor,
        metrics=metrics,
        candidates=query.candidates,
    )
