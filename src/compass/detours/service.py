import asyncio
from collections.abc import Iterator

from sqlalchemy.orm import Session

from compass.candidates.domain import CorridorPolicy, SpatialCandidate
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
    evaluated: list[EligibleDetourCandidate] = []
    unreachable = 0
    matrix_calls = 0
    fallback_splits = 0
    location_failures = 0

    for batch in _batches(spatial.candidates, detour_policy.matrix_batch_size):
        pairs, calls, splits, failures = await _matrix_cost_pairs(
            provider,
            batch,
            origin=route_request.origin,
            destination=route_request.destination,
            costing=route_request.costing,
        )
        matrix_calls += calls
        fallback_splits += splits
        location_failures += failures

        for station, (previous_to_station, station_to_destination) in zip(
            batch, pairs, strict=True
        ):
            if previous_to_station is None or station_to_destination is None:
                unreachable += 1
                continue
            evaluated.append(
                calculate_detour_candidate(
                    station=station,
                    base_route=spatial.base_route,
                    previous_to_station=previous_to_station,
                    station_to_destination=station_to_destination,
                    departure_at=request.departure_at,
                )
            )

    eligible = tuple(
        sorted(
            (
                candidate
                for candidate in evaluated
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
    excluded_by_detour = len(evaluated) - len(eligible)
    return NetworkDetourResult(
        spatial_result=spatial,
        maximum_detour_seconds=request.maximum_detour_seconds,
        departure_at=request.departure_at,
        cost_basis=NetworkCostBasis(),
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
) -> tuple[
    tuple[tuple[MatrixCost | None, MatrixCost | None], ...],
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
            targets=station_coordinates,
            costing=costing,
        ),
        MatrixRequest(
            sources=station_coordinates,
            targets=(destination,),
            costing=costing,
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
            return (((None, None),), 2, 0, 1)
        split_at = len(candidates) // 2
        left = await _matrix_cost_pairs(
            provider,
            candidates[:split_at],
            origin=origin,
            destination=destination,
            costing=costing,
        )
        right = await _matrix_cost_pairs(
            provider,
            candidates[split_at:],
            origin=origin,
            destination=destination,
            costing=costing,
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
    _require_matrix_shape(outward, source_count=1, target_count=len(candidates))
    _require_matrix_shape(onward, source_count=len(candidates), target_count=1)
    return (
        tuple(
            (outward.costs[0][index], onward.costs[index][0])
            for index in range(len(candidates))
        ),
        2,
        0,
        0,
    )
