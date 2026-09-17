import asyncio
from bisect import bisect_right
from collections.abc import Iterator
from datetime import datetime

from sqlalchemy.orm import Session

from compass.candidates.domain import CorridorPolicy, SpatialCandidate
from compass.candidates.geometry import waypoint_route_leg_fraction_boundaries
from compass.candidates.service import find_corridor_candidates
from compass.detours.domain import (
    EligibleDetourCandidate,
    NetworkCostBasis,
    NetworkDetourPolicy,
    NetworkDetourRequest,
    NetworkDetourResult,
    NetworkEvaluationMetrics,
    calculate_detour_candidate,
)
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    MatrixCost,
    MatrixLocationError,
    MatrixRequest,
    MatrixResult,
    RoutingProvider,
    RoutingProviderError,
    WaypointRoute,
)


async def evaluate_cng_detours(
    session: Session,
    provider: RoutingProvider,
    request: NetworkDetourRequest,
    *,
    corridor_policy: CorridorPolicy,
    detour_policy: NetworkDetourPolicy,
    max_route_geometry_points: int,
    base_route: BaseRoute | None = None,
    cost_basis: NetworkCostBasis | None = None,
    waypoint_route: WaypointRoute | None = None,
    mandatory_waypoints: tuple[Coordinate, ...] = (),
) -> NetworkDetourResult:
    spatial = await find_corridor_candidates(
        session,
        provider,
        request.corridor_request,
        policy=corridor_policy,
        max_route_geometry_points=max_route_geometry_points,
        base_route=base_route,
    )
    route_request = request.corridor_request.route
    if waypoint_route is not None:
        if len(waypoint_route.legs) != len(mandatory_waypoints) + 1:
            raise RoutingProviderError("Waypoint route legs do not match mandatory waypoints")
        boundaries = waypoint_route_leg_fraction_boundaries(
            waypoint_route,
            max_points=max_route_geometry_points,
        )
        leg_indexes = {
            candidate.station_id: bisect_right(boundaries, candidate.route_fraction)
            for candidate in spatial.candidates
        }
    else:
        leg_indexes = {candidate.station_id: 0 for candidate in spatial.candidates}
    evaluated: list[EligibleDetourCandidate] = []
    unreachable = 0
    matrix_calls = 0
    fallback_splits = 0
    location_failures = 0

    batches = (
        batch
        for leg_index in range(len(waypoint_route.legs) if waypoint_route else 1)
        for batch in _batches(
            tuple(
                candidate
                for candidate in spatial.candidates
                if leg_indexes[candidate.station_id] == leg_index
            ),
            detour_policy.matrix_batch_size,
        )
    )
    fixed_points = (route_request.origin, *mandatory_waypoints, route_request.destination)
    for batch in batches:
        leg_index = leg_indexes[batch[0].station_id]
        leg_origin = fixed_points[leg_index] if waypoint_route else route_request.origin
        leg_destination = (
            fixed_points[leg_index + 1] if waypoint_route else route_request.destination
        )
        pairs, calls, splits, failures = await _matrix_cost_pairs(
            provider,
            batch,
            origin=leg_origin,
            destination=leg_destination,
            costing=route_request.costing,
            departure_at=request.departure_at,
        )
        matrix_calls += calls
        fallback_splits += splits
        location_failures += failures

        for station, (
            previous_to_station,
            station_to_destination,
            direct_leg_cost,
        ) in zip(
            batch, pairs, strict=True
        ):
            if (
                previous_to_station is None
                or station_to_destination is None
                or direct_leg_cost is None
            ):
                unreachable += 1
                continue
            prefix_distance = 0.0
            prefix_duration = 0.0
            suffix_distance = 0.0
            suffix_duration = 0.0
            if waypoint_route is not None:
                prefix_distance = sum(
                    leg.distance_meters for leg in waypoint_route.legs[:leg_index]
                )
                prefix_duration = sum(
                    leg.duration_seconds for leg in waypoint_route.legs[:leg_index]
                )
                suffix_distance = sum(
                    leg.distance_meters for leg in waypoint_route.legs[leg_index + 1 :]
                )
                suffix_duration = sum(
                    leg.duration_seconds for leg in waypoint_route.legs[leg_index + 1 :]
                )
                previous_to_station = MatrixCost(
                    distance_meters=(
                        prefix_distance + previous_to_station.distance_meters
                    ),
                    duration_seconds=(
                        prefix_duration + previous_to_station.duration_seconds
                    ),
                )
                station_to_destination = MatrixCost(
                    distance_meters=(
                        station_to_destination.distance_meters
                        + suffix_distance
                    ),
                    duration_seconds=(
                        station_to_destination.duration_seconds
                        + suffix_duration
                    ),
                )
            comparison_base_cost = MatrixCost(
                distance_meters=(
                    prefix_distance + direct_leg_cost.distance_meters + suffix_distance
                ),
                duration_seconds=(
                    prefix_duration + direct_leg_cost.duration_seconds + suffix_duration
                ),
            )
            evaluated.append(
                calculate_detour_candidate(
                    station=station,
                    base_route=spatial.base_route,
                    previous_to_station=previous_to_station,
                    station_to_destination=station_to_destination,
                    departure_at=request.departure_at,
                    itinerary_leg_index=leg_index,
                    comparison_base_cost=comparison_base_cost,
                )
            )

    range_eligible = tuple(
        candidate
        for candidate in evaluated
        if request.maximum_reachable_distance_meters is None
        or candidate.distance_from_previous_waypoint_meters
        <= request.maximum_reachable_distance_meters
    )
    eligible = tuple(
        sorted(
            (
                candidate
                for candidate in range_eligible
                if candidate.detour_duration_seconds <= request.maximum_detour_seconds
            ),
            key=lambda candidate: (
                candidate.detour_duration_seconds,
                candidate.extra_distance_meters,
                candidate.distance_from_previous_waypoint_meters,
                candidate.station.station_id,
            ),
        )
    )
    excluded_by_range = len(evaluated) - len(range_eligible)
    excluded_by_detour = len(range_eligible) - len(eligible)
    return NetworkDetourResult(
        spatial_result=spatial,
        maximum_detour_seconds=request.maximum_detour_seconds,
        departure_at=request.departure_at,
        cost_basis=cost_basis or NetworkCostBasis(),
        metrics=NetworkEvaluationMetrics(
            spatial_candidate_count=spatial.metrics.corridor_candidate_count,
            matrix_candidate_count=len(spatial.candidates),
            reachable_candidate_count=len(evaluated),
            unreachable_candidate_count=unreachable,
            eligible_candidate_count=len(eligible),
            excluded_by_detour_count=excluded_by_detour,
            matrix_batch_size=detour_policy.matrix_batch_size,
            matrix_calls=matrix_calls,
            matrix_fallback_splits=fallback_splits,
            matrix_location_failures=location_failures,
            excluded_by_range_count=(
                excluded_by_range
                if request.maximum_reachable_distance_meters is not None
                else None
            ),
        ),
        candidates=eligible,
    )


def _batches(
    candidates: tuple[SpatialCandidate, ...], batch_size: int
) -> Iterator[tuple[SpatialCandidate, ...]]:
    for start in range(0, len(candidates), batch_size):
        yield candidates[start : start + batch_size]


def _require_matrix_shape(
    result: MatrixResult, *, source_count: int, target_count: int
) -> None:
    if len(result.costs) != source_count or any(
        len(row) != target_count for row in result.costs
    ):
        raise RoutingProviderError("Routing provider returned an invalid matrix shape")


async def _matrix_cost_pairs(
    provider: RoutingProvider,
    candidates: tuple[SpatialCandidate, ...],
    *,
    origin: Coordinate,
    destination: Coordinate,
    costing: str,
    departure_at: datetime | None,
) -> tuple[
    tuple[tuple[MatrixCost | None, MatrixCost | None, MatrixCost | None], ...],
    int,
    int,
    int,
]:
    station_coordinates = tuple(
        Coordinate(candidate.latitude, candidate.longitude) for candidate in candidates
    )
    requests = (
        MatrixRequest(
            sources=(origin,),
            targets=station_coordinates + (destination,),
            costing=costing,
            departure_at=departure_at,
        ),
        MatrixRequest(
            sources=station_coordinates,
            targets=(destination,),
            costing=costing,
            departure_at=departure_at,
        ),
    )
    results = await asyncio.gather(
        *(provider.matrix(request) for request in requests),
        return_exceptions=True,
    )
    for result in results:
        if isinstance(result, BaseException) and not isinstance(
            result, MatrixLocationError
        ):
            raise result

    if any(isinstance(result, MatrixLocationError) for result in results):
        if len(candidates) == 1:
            return (((None, None, None),), 2, 0, 1)
        split_at = len(candidates) // 2
        left = await _matrix_cost_pairs(
            provider,
            candidates[:split_at],
            origin=origin,
            destination=destination,
            costing=costing,
            departure_at=departure_at,
        )
        right = await _matrix_cost_pairs(
            provider,
            candidates[split_at:],
            origin=origin,
            destination=destination,
            costing=costing,
            departure_at=departure_at,
        )
        return (
            left[0] + right[0],
            2 + left[1] + right[1],
            1 + left[2] + right[2],
            left[3] + right[3],
        )

    outward, onward = results
    if not isinstance(outward, MatrixResult) or not isinstance(onward, MatrixResult):
        raise RoutingProviderError("Routing provider returned an invalid matrix result")
    _require_matrix_shape(outward, source_count=1, target_count=len(candidates) + 1)
    _require_matrix_shape(onward, source_count=len(candidates), target_count=1)
    direct_leg_cost = outward.costs[0][-1]
    if direct_leg_cost is None:
        raise RoutingProviderError("Routing matrix returned no direct comparison cost")
    return (
        tuple(
            (outward.costs[0][index], onward.costs[index][0], direct_leg_cost)
            for index in range(len(candidates))
        ),
        2,
        0,
        0,
    )
