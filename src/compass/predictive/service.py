import heapq
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from itertools import count

from sqlalchemy.orm import Session

from compass.candidates.domain import (
    CorridorCandidateResult,
    CorridorPolicy,
    SpatialCandidate,
    SpatialPruningMetrics,
)
from compass.detours.domain import (
    EligibleDetourCandidate,
    NetworkCostBasis,
    NetworkDetourPolicy,
    NetworkDetourResult,
    NetworkEvaluationMetrics,
)
from compass.detours.service import evaluate_cng_detours
from compass.predictive.domain import (
    MAX_CNG_ITINERARY_STOPS,
    PredictiveCandidatesRequest,
    PredictiveCandidatesResult,
    PredictiveDestinationLeg,
    PredictiveItinerary,
    PredictiveItineraryStop,
    PredictiveRangeBasis,
    PredictiveRankedCandidate,
    PredictiveReachabilityMetrics,
    PredictiveSuggestionState,
)
from compass.ranking.domain import CandidateEnrichment, RankingPolicy
from compass.ranking.opening_hours import evaluate_opening_hours
from compass.ranking.service import (
    evaluate_price,
    load_candidate_enrichments,
    rank_network_candidates,
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


@dataclass(frozen=True, slots=True)
class _MatrixStats:
    calls: int = 0
    fallback_splits: int = 0
    location_failures: int = 0

    def __add__(self, other: "_MatrixStats") -> "_MatrixStats":
        return _MatrixStats(
            calls=self.calls + other.calls,
            fallback_splits=self.fallback_splits + other.fallback_splits,
            location_failures=self.location_failures + other.location_failures,
        )


@dataclass(frozen=True, slots=True)
class _Path:
    candidate_indexes: tuple[int, ...]
    leg_costs: tuple[MatrixCost, ...]
    destination_cost: MatrixCost
    labels_explored: int


async def evaluate_predictive_cng_candidates(
    session: Session,
    provider: RoutingProvider,
    request: PredictiveCandidatesRequest,
    *,
    corridor_policy: CorridorPolicy,
    detour_policy: NetworkDetourPolicy,
    ranking_policy: RankingPolicy,
    max_route_geometry_points: int,
) -> PredictiveCandidatesResult:
    remaining_range_km = request.estimated_remaining_cng_range_km
    reserve_range_km = request.reserve_cng_range_km
    effective_range_km = (
        request.ranked_request.network_request.corridor_request.effective_cng_range_km
    )
    first_usable_range_km = remaining_range_km - reserve_range_km
    full_usable_range_km = effective_range_km - reserve_range_km
    network_request = request.ranked_request.network_request
    base_route = await provider.route(network_request.corridor_request.route)
    remaining_route_km = base_route.distance_meters / 1_000
    destination_reachable = remaining_route_km <= first_usable_range_km

    if destination_reachable:
        network = _skipped_network_result(
            base_route=base_route,
            request=request,
            corridor_policy=corridor_policy,
            detour_policy=detour_policy,
        )
        enrichments: dict[int, CandidateEnrichment] = {}
    else:
        network = await evaluate_cng_detours(
            session,
            provider,
            network_request,
            corridor_policy=corridor_policy,
            detour_policy=detour_policy,
            max_route_geometry_points=max_route_geometry_points,
            base_route=base_route,
        )
        enrichments = {}

    first_reachable = tuple(
        candidate
        for candidate in network.candidates
        if candidate.distance_from_previous_waypoint_meters
        <= first_usable_range_km * 1_000
    )
    if first_reachable:
        enrichments = load_candidate_enrichments(
            session,
            (candidate.station.station_id for candidate in network.candidates),
        )
    first_eligible = tuple(
        candidate
        for candidate in first_reachable
        if _station_is_allowed(
            candidate.station,
            candidate.station_eta,
            enrichments,
            ranking_policy,
            include_closed=request.ranked_request.include_closed,
        )
    )

    path: _Path | None = None
    matrix_stats = _MatrixStats()
    if not destination_reachable and first_eligible:
        path = _one_stop_path(first_eligible, network.candidates, full_usable_range_km)
        if path is None and len(network.candidates) > 1:
            pairwise_costs, matrix_stats = await _pairwise_candidate_costs(
                provider,
                network.candidates,
                costing=network_request.corridor_request.route.costing,
                batch_size=detour_policy.matrix_batch_size,
            )
            path = _search_complete_itinerary(
                candidates=network.candidates,
                first_eligible=first_eligible,
                pairwise_costs=pairwise_costs,
                departure_at=network_request.departure_at,
                full_usable_range_km=full_usable_range_km,
                enrichments=enrichments,
                ranking_policy=ranking_policy,
                include_closed=request.ranked_request.include_closed,
            )

    selected_first = (
        network.candidates[path.candidate_indexes[0]] if path is not None else None
    )
    ranking_candidates = (
        (selected_first,)
        if selected_first is not None
        else (first_reachable if first_reachable and not first_eligible else ())
    )
    exposed_network = replace(
        network,
        candidates=ranking_candidates,
    )
    ranking = rank_network_candidates(
        session,
        exposed_network,
        include_closed=request.ranked_request.include_closed,
        ranking_policy=ranking_policy,
        enrichments=enrichments,
        enrichment_queries=(1 if first_reachable else 0),
    )
    candidates = tuple(
        PredictiveRankedCandidate(
            ranked=candidate,
            estimated_remaining_range_at_arrival_km=(
                remaining_range_km
                - candidate.detour.distance_from_previous_waypoint_meters / 1_000
            ),
            reserve_margin_at_arrival_km=(
                first_usable_range_km
                - candidate.detour.distance_from_previous_waypoint_meters / 1_000
            ),
        )
        for candidate in ranking.candidates
    )
    itinerary = (
        _build_itinerary(
            path,
            network.candidates,
            request,
            enrichments,
            ranking_policy,
        )
        if path is not None
        else None
    )

    suggestion_state: PredictiveSuggestionState
    if destination_reachable:
        suggestion_state = "not_needed"
    elif not first_reachable:
        suggestion_state = "no_reachable_station"
    elif not first_eligible:
        suggestion_state = "no_eligible_station"
    elif itinerary is None:
        suggestion_state = "no_complete_itinerary"
    else:
        suggestion_state = "suggested"

    return PredictiveCandidatesResult(
        suggestion_state=suggestion_state,
        range_basis=PredictiveRangeBasis(
            effective_cng_range_km=effective_range_km,
            estimated_remaining_cng_range_km=remaining_range_km,
            reserve_cng_range_km=reserve_range_km,
            usable_range_before_reserve_km=first_usable_range_km,
            remaining_route_distance_km=remaining_route_km,
            range_shortfall_to_destination_km=max(
                0.0, remaining_route_km - first_usable_range_km
            ),
            destination_reachable_with_reserve=destination_reachable,
        ),
        reachability=PredictiveReachabilityMetrics(
            detour_eligible_candidate_count=len(network.candidates),
            reachable_before_reserve_count=len(first_reachable),
            excluded_unreachable_before_reserve_count=(
                0 if destination_reachable else len(network.candidates) - len(first_reachable)
            ),
            ranked_reachable_candidate_count=len(candidates),
            furthest_reachable_route_fraction=(
                max(candidate.station.route_fraction for candidate in first_reachable)
                if first_reachable
                else None
            ),
            evaluation_skipped_destination_reachable=destination_reachable,
            pairwise_matrix_calls=matrix_stats.calls,
            pairwise_matrix_fallback_splits=matrix_stats.fallback_splits,
            pairwise_matrix_location_failures=matrix_stats.location_failures,
            itinerary_search_labels=(path.labels_explored if path is not None else 0),
        ),
        ranking_result=ranking,
        candidates=candidates,
        itinerary=itinerary,
    )


def _station_is_allowed(
    station: SpatialCandidate,
    arrival_at: datetime,
    enrichments: dict[int, CandidateEnrichment],
    ranking_policy: RankingPolicy,
    *,
    include_closed: bool,
) -> bool:
    if include_closed:
        return True
    enrichment = enrichments.get(station.station_id, CandidateEnrichment())
    opening = evaluate_opening_hours(
        enrichment.opening_hours,
        eta=arrival_at,
        latitude=station.latitude,
        longitude=station.longitude,
        timezone_name=ranking_policy.opening_hours_timezone,
        country=ranking_policy.opening_hours_country,
        source_confidence=enrichment.osm_match_confidence,
    )
    return opening.state != "closed"


def _one_stop_path(
    first_eligible: tuple[EligibleDetourCandidate, ...],
    all_candidates: tuple[EligibleDetourCandidate, ...],
    full_usable_range_km: float,
) -> _Path | None:
    index_by_station = {
        candidate.station.station_id: index for index, candidate in enumerate(all_candidates)
    }
    viable = [
        candidate
        for candidate in first_eligible
        if candidate.station_to_destination_distance_meters
        <= full_usable_range_km * 1_000
    ]
    if not viable:
        return None
    selected = min(
        viable,
        key=lambda candidate: (
            candidate.route_via_station_duration_seconds,
            candidate.detour_duration_seconds,
            candidate.station.station_id,
        ),
    )
    return _Path(
        candidate_indexes=(index_by_station[selected.station.station_id],),
        leg_costs=(
            MatrixCost(
                selected.distance_from_previous_waypoint_meters,
                selected.duration_from_previous_waypoint_seconds,
            ),
        ),
        destination_cost=MatrixCost(
            selected.station_to_destination_distance_meters,
            selected.station_to_destination_duration_seconds,
        ),
        labels_explored=len(viable),
    )


def _search_complete_itinerary(
    *,
    candidates: tuple[EligibleDetourCandidate, ...],
    first_eligible: tuple[EligibleDetourCandidate, ...],
    pairwise_costs: tuple[tuple[MatrixCost | None, ...], ...],
    departure_at: datetime,
    full_usable_range_km: float,
    enrichments: dict[int, CandidateEnrichment],
    ranking_policy: RankingPolicy,
    include_closed: bool,
) -> _Path | None:
    index_by_station = {
        candidate.station.station_id: index for index, candidate in enumerate(candidates)
    }
    serial = count()
    queue: list[tuple[int, float, int, tuple[int, ...], tuple[MatrixCost, ...]]] = []
    for candidate in first_eligible:
        index = index_by_station[candidate.station.station_id]
        edge = MatrixCost(
            candidate.distance_from_previous_waypoint_meters,
            candidate.duration_from_previous_waypoint_seconds,
        )
        heapq.heappush(queue, (1, edge.duration_seconds, next(serial), (index,), (edge,)))

    labels_explored = 0
    full_usable_meters = full_usable_range_km * 1_000
    while queue:
        stop_count, elapsed_seconds, _, path, leg_costs = heapq.heappop(queue)
        labels_explored += 1
        current_index = path[-1]
        current = candidates[current_index]
        destination_cost = MatrixCost(
            current.station_to_destination_distance_meters,
            current.station_to_destination_duration_seconds,
        )
        if destination_cost.distance_meters <= full_usable_meters:
            return _Path(path, leg_costs, destination_cost, labels_explored)
        if stop_count >= MAX_CNG_ITINERARY_STOPS:
            continue

        current_remaining_distance = current.station_to_destination_distance_meters
        for next_index, edge in enumerate(pairwise_costs[current_index]):
            if edge is None or edge.distance_meters > full_usable_meters:
                continue
            if next_index in path:
                continue
            next_candidate = candidates[next_index]
            # A refuelling chain must make real road-network progress toward the
            # destination.  Route projection fractions are retained only as a
            # spatial diagnostic; they are not a reachability decision input.
            if (
                next_candidate.station_to_destination_distance_meters
                >= current_remaining_distance - 1.0
            ):
                continue
            next_elapsed = elapsed_seconds + edge.duration_seconds
            arrival_at = departure_at + timedelta(seconds=next_elapsed)
            if not _station_is_allowed(
                next_candidate.station,
                arrival_at,
                enrichments,
                ranking_policy,
                include_closed=include_closed,
            ):
                continue
            heapq.heappush(
                queue,
                (
                    stop_count + 1,
                    next_elapsed,
                    next(serial),
                    (*path, next_index),
                    (*leg_costs, edge),
                ),
            )
    return None


def _build_itinerary(
    path: _Path,
    candidates: tuple[EligibleDetourCandidate, ...],
    request: PredictiveCandidatesRequest,
    enrichments: dict[int, CandidateEnrichment],
    ranking_policy: RankingPolicy,
) -> PredictiveItinerary:
    departure_at = request.ranked_request.network_request.departure_at
    remaining_range_km = request.estimated_remaining_cng_range_km
    reserve_range_km = request.reserve_cng_range_km
    effective_range_km = (
        request.ranked_request.network_request.corridor_request.effective_cng_range_km
    )
    elapsed_seconds = 0.0
    stops: list[PredictiveItineraryStop] = []
    for position, (candidate_index, leg_cost) in enumerate(
        zip(path.candidate_indexes, path.leg_costs, strict=True),
        start=1,
    ):
        candidate = candidates[candidate_index]
        available_range_km = remaining_range_km if position == 1 else effective_range_km
        remaining_at_arrival = available_range_km - leg_cost.distance_meters / 1_000
        elapsed_seconds += leg_cost.duration_seconds
        arrival_at = departure_at + timedelta(seconds=elapsed_seconds)
        enrichment = enrichments.get(candidate.station.station_id, CandidateEnrichment())
        opening = evaluate_opening_hours(
            enrichment.opening_hours,
            eta=arrival_at,
            latitude=candidate.station.latitude,
            longitude=candidate.station.longitude,
            timezone_name=ranking_policy.opening_hours_timezone,
            country=ranking_policy.opening_hours_country,
            source_confidence=enrichment.osm_match_confidence,
        )
        stops.append(
            PredictiveItineraryStop(
                sequence=position,
                station=candidate.station,
                arrival_at=arrival_at,
                leg_distance_meters=leg_cost.distance_meters,
                leg_duration_seconds=leg_cost.duration_seconds,
                available_range_at_departure_km=available_range_km,
                estimated_remaining_range_at_arrival_km=remaining_at_arrival,
                reserve_margin_at_arrival_km=remaining_at_arrival - reserve_range_km,
                opening=opening,
                phone=enrichment.phone,
                brand=enrichment.brand,
                operator=enrichment.operator,
                osm_match_confidence=enrichment.osm_match_confidence,
                price=evaluate_price(
                    enrichment.current_price,
                    eta=arrival_at,
                    freshness_seconds=ranking_policy.price_freshness_seconds,
                ),
            )
        )

    destination_remaining = (
        effective_range_km - path.destination_cost.distance_meters / 1_000
    )
    total_duration = elapsed_seconds + path.destination_cost.duration_seconds
    destination_leg = PredictiveDestinationLeg(
        distance_meters=path.destination_cost.distance_meters,
        duration_seconds=path.destination_cost.duration_seconds,
        available_range_at_departure_km=effective_range_km,
        estimated_remaining_range_at_arrival_km=destination_remaining,
        reserve_margin_at_arrival_km=destination_remaining - reserve_range_km,
        destination_eta=departure_at + timedelta(seconds=total_duration),
    )
    return PredictiveItinerary(
        stops=tuple(stops),
        destination_leg=destination_leg,
        total_distance_meters=(
            sum(cost.distance_meters for cost in path.leg_costs)
            + path.destination_cost.distance_meters
        ),
        total_duration_seconds=total_duration,
    )


async def _pairwise_candidate_costs(
    provider: RoutingProvider,
    candidates: tuple[EligibleDetourCandidate, ...],
    *,
    costing: str,
    batch_size: int,
) -> tuple[tuple[tuple[MatrixCost | None, ...], ...], _MatrixStats]:
    coordinates = tuple(
        Coordinate(candidate.station.latitude, candidate.station.longitude)
        for candidate in candidates
    )
    rows: list[list[MatrixCost | None]] = [
        [None for _ in candidates] for _ in candidates
    ]
    stats = _MatrixStats()
    for source_start in range(0, len(candidates), batch_size):
        source_end = min(source_start + batch_size, len(candidates))
        for target_start in range(0, len(candidates), batch_size):
            target_end = min(target_start + batch_size, len(candidates))
            block, block_stats = await _matrix_block(
                provider,
                coordinates[source_start:source_end],
                coordinates[target_start:target_end],
                costing=costing,
            )
            stats += block_stats
            for relative_source, block_row in enumerate(block):
                rows[source_start + relative_source][target_start:target_end] = block_row
    return tuple(tuple(row) for row in rows), stats


async def _matrix_block(
    provider: RoutingProvider,
    sources: tuple[Coordinate, ...],
    targets: tuple[Coordinate, ...],
    *,
    costing: str,
) -> tuple[tuple[tuple[MatrixCost | None, ...], ...], _MatrixStats]:
    try:
        result = await provider.matrix(MatrixRequest(sources, targets, costing))
    except MatrixLocationError:
        if len(sources) == 1 and len(targets) == 1:
            return ((None,),), _MatrixStats(calls=1, location_failures=1)
        if len(sources) >= len(targets) and len(sources) > 1:
            split_at = len(sources) // 2
            left, left_stats = await _matrix_block(
                provider, sources[:split_at], targets, costing=costing
            )
            right, right_stats = await _matrix_block(
                provider, sources[split_at:], targets, costing=costing
            )
            return (
                left + right,
                _MatrixStats(calls=1, fallback_splits=1) + left_stats + right_stats,
            )
        split_at = len(targets) // 2
        left, left_stats = await _matrix_block(
            provider, sources, targets[:split_at], costing=costing
        )
        right, right_stats = await _matrix_block(
            provider, sources, targets[split_at:], costing=costing
        )
        return (
            tuple(left_row + right_row for left_row, right_row in zip(left, right, strict=True)),
            _MatrixStats(calls=1, fallback_splits=1) + left_stats + right_stats,
        )
    if not isinstance(result, MatrixResult):
        raise RoutingProviderError("Routing provider returned an invalid matrix result")
    if len(result.costs) != len(sources) or any(
        len(row) != len(targets) for row in result.costs
    ):
        raise RoutingProviderError("Routing provider returned an invalid matrix shape")
    return result.costs, _MatrixStats(calls=1)


def _skipped_network_result(
    *,
    base_route: BaseRoute,
    request: PredictiveCandidatesRequest,
    corridor_policy: CorridorPolicy,
    detour_policy: NetworkDetourPolicy,
) -> NetworkDetourResult:
    network_request = request.ranked_request.network_request
    corridor_request = network_request.corridor_request
    # The early not-needed decision deliberately avoids querying station inventory. Zero
    # counts below mean "not evaluated" and are paired with the explicit reachability flag.
    spatial = CorridorCandidateResult(
        base_route=base_route,
        corridor=corridor_policy.radius_for(corridor_request.effective_cng_range_km),
        metrics=SpatialPruningMetrics(
            active_station_count=0,
            active_station_with_location_count=0,
            excluded_missing_location_count=0,
            corridor_candidate_count=0,
            returned_candidate_count=0,
            pruned_with_location_count=0,
            reduction_ratio=0,
            candidate_limit_applied=False,
        ),
        candidates=(),
    )
    return NetworkDetourResult(
        spatial_result=spatial,
        maximum_detour_seconds=network_request.maximum_detour_seconds,
        departure_at=network_request.departure_at,
        cost_basis=NetworkCostBasis(),
        metrics=NetworkEvaluationMetrics(
            spatial_candidate_count=0,
            matrix_candidate_count=0,
            reachable_candidate_count=0,
            unreachable_candidate_count=0,
            eligible_candidate_count=0,
            excluded_by_detour_count=0,
            matrix_batch_size=detour_policy.matrix_batch_size,
            matrix_calls=0,
            matrix_fallback_splits=0,
            matrix_location_failures=0,
        ),
        candidates=(),
    )
