from collections.abc import Iterable
from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from compass.candidates.domain import CorridorPolicy
from compass.detours.domain import NetworkDetourPolicy
from compass.detours.service import evaluate_cng_detours
from compass.models import (
    OsmFeature,
    Station,
    StationCurrentPrice,
    StationOsmLink,
    StationPrice,
)
from compass.ranking.domain import (
    CandidateEnrichment,
    CurrentCngPrice,
    EvaluatedCngPrice,
    RankedCandidate,
    RankedCandidatesRequest,
    RankedCandidatesResult,
    RankingBreakdown,
    RankingMetrics,
    RankingPolicy,
)
from compass.ranking.opening_hours import evaluate_opening_hours
from compass.routing.domain import RoutingProvider


async def rank_cng_candidates(
    session: Session,
    provider: RoutingProvider,
    request: RankedCandidatesRequest,
    *,
    corridor_policy: CorridorPolicy,
    detour_policy: NetworkDetourPolicy,
    ranking_policy: RankingPolicy,
    max_route_geometry_points: int,
) -> RankedCandidatesResult:
    network_result = await evaluate_cng_detours(
        session,
        provider,
        request.network_request,
        corridor_policy=corridor_policy,
        detour_policy=detour_policy,
        max_route_geometry_points=max_route_geometry_points,
    )
    enrichments = load_candidate_enrichments(
        session,
        (candidate.station.station_id for candidate in network_result.candidates),
    )
    prepared: list[RankedCandidate] = []
    open_count = 0
    closed_count = 0
    unknown_count = 0
    valid_count = 0
    missing_count = 0
    invalid_count = 0
    price_available_count = 0

    for candidate in network_result.candidates:
        enrichment = enrichments.get(candidate.station.station_id, CandidateEnrichment())
        opening = evaluate_opening_hours(
            enrichment.opening_hours,
            eta=candidate.station_eta,
            latitude=candidate.station.latitude,
            longitude=candidate.station.longitude,
            timezone_name=ranking_policy.opening_hours_timezone,
            country=ranking_policy.opening_hours_country,
            source_confidence=enrichment.osm_match_confidence,
        )
        if opening.state == "open":
            open_count += 1
        elif opening.state == "closed":
            closed_count += 1
        else:
            unknown_count += 1
        if opening.validation == "valid":
            valid_count += 1
        elif opening.validation == "missing":
            missing_count += 1
        else:
            invalid_count += 1

        price = evaluate_price(
            enrichment.current_price,
            eta=candidate.station_eta,
            freshness_seconds=ranking_policy.price_freshness_seconds,
        )
        if price is not None:
            price_available_count += 1
        if opening.state == "closed" and not request.include_closed:
            continue
        prepared.append(
            RankedCandidate(
                detour=candidate,
                opening=opening,
                phone=enrichment.phone,
                brand=enrichment.brand,
                operator=enrichment.operator,
                osm_match_confidence=enrichment.osm_match_confidence,
                price=price,
                ranking=_empty_breakdown(),
            )
        )

    ranked = _score_and_sort(
        prepared,
        maximum_detour_seconds=request.network_request.maximum_detour_seconds,
        policy=ranking_policy,
    )
    detour_eligible_count = len(network_result.candidates)
    return RankedCandidatesResult(
        network_result=network_result,
        policy=ranking_policy,
        include_closed=request.include_closed,
        metrics=RankingMetrics(
            detour_eligible_candidate_count=detour_eligible_count,
            opening_open_count=open_count,
            opening_closed_count=closed_count,
            opening_unknown_count=unknown_count,
            opening_valid_count=valid_count,
            opening_missing_count=missing_count,
            opening_invalid_count=invalid_count,
            excluded_closed_count=(0 if request.include_closed else closed_count),
            price_available_count=price_available_count,
            price_missing_count=detour_eligible_count - price_available_count,
            ranked_candidate_count=len(ranked),
            enrichment_queries=1 if detour_eligible_count else 0,
        ),
        candidates=ranked,
    )


def load_candidate_enrichments(
    session: Session, station_ids: Iterable[int]
) -> dict[int, CandidateEnrichment]:
    unique_station_ids = tuple(dict.fromkeys(station_ids))
    if not unique_station_ids:
        return {}
    rows = session.execute(
        select(
            Station.id.label("station_id"),
            OsmFeature.opening_hours,
            OsmFeature.phone,
            OsmFeature.brand,
            OsmFeature.operator,
            StationOsmLink.confidence.label("osm_match_confidence"),
            StationPrice.id.label("price_id"),
            StationPrice.unit_price,
            StationPrice.currency,
            StationPrice.unit,
            StationPrice.service_mode,
            StationPrice.observed_at,
            StationPrice.ingested_at,
            StationPrice.source_name,
        )
        .select_from(Station)
        .outerjoin(StationOsmLink, StationOsmLink.station_id == Station.id)
        .outerjoin(
            OsmFeature,
            and_(
                OsmFeature.id == StationOsmLink.osm_feature_id,
                OsmFeature.is_active.is_(True),
            ),
        )
        .outerjoin(
            StationCurrentPrice,
            and_(
                StationCurrentPrice.station_id == Station.id,
                StationCurrentPrice.fuel_type == "cng",
            ),
        )
        .outerjoin(StationPrice, StationPrice.id == StationCurrentPrice.station_price_id)
        .where(Station.id.in_(unique_station_ids))
        .order_by(Station.id, StationPrice.id)
    ).all()

    enrichments: dict[int, CandidateEnrichment] = {}
    prices: dict[int, list[CurrentCngPrice]] = {}
    for row in rows:
        station_id = int(row.station_id)
        enrichments.setdefault(
            station_id,
            CandidateEnrichment(
                opening_hours=row.opening_hours,
                phone=row.phone,
                brand=row.brand,
                operator=row.operator,
                osm_match_confidence=(
                    float(row.osm_match_confidence)
                    if row.osm_match_confidence is not None
                    else None
                ),
            ),
        )
        if row.price_id is not None:
            prices.setdefault(station_id, []).append(
                CurrentCngPrice(
                    unit_price=Decimal(row.unit_price),
                    currency=row.currency,
                    unit=row.unit,
                    service_mode=row.service_mode,
                    observed_at=row.observed_at,
                    ingested_at=row.ingested_at,
                    source_name=row.source_name,
                )
            )

    for station_id in unique_station_ids:
        base = enrichments.get(station_id, CandidateEnrichment())
        candidates = prices.get(station_id, [])
        selected = min(candidates, key=_price_selection_key) if candidates else None
        enrichments[station_id] = replace(base, current_price=selected)
    return enrichments


def _price_selection_key(price: CurrentCngPrice) -> tuple[Decimal, float, str]:
    observed_at = _aware_utc(price.observed_at)
    newest_first = -observed_at.timestamp() if observed_at is not None else 0.0
    return (price.unit_price, newest_first, price.service_mode)


def evaluate_price(
    price: CurrentCngPrice | None, *, eta: datetime, freshness_seconds: float
) -> EvaluatedCngPrice | None:
    if price is None:
        return None
    observed_at = _aware_utc(price.observed_at)
    eta_utc = _aware_utc(eta)
    if observed_at is None or eta_utc is None:
        age_seconds = None
        freshness_state = "unknown"
    else:
        age_seconds = (eta_utc - observed_at).total_seconds()
        if age_seconds < 0:
            freshness_state = "future_observation"
        elif age_seconds <= freshness_seconds:
            freshness_state = "fresh"
        else:
            freshness_state = "stale"
    return EvaluatedCngPrice(
        unit_price=price.unit_price,
        currency=price.currency,
        unit=price.unit,
        service_mode=price.service_mode,
        observed_at=price.observed_at,
        ingested_at=price.ingested_at,
        source_name=price.source_name,
        age_seconds=age_seconds,
        freshness_state=freshness_state,
    )


def _score_and_sort(
    candidates: list[RankedCandidate],
    *,
    maximum_detour_seconds: float,
    policy: RankingPolicy,
) -> tuple[RankedCandidate, ...]:
    comparable_prices = [
        float(candidate.price.unit_price)
        for candidate in candidates
        if candidate.price is not None
        and candidate.price.freshness_state != "future_observation"
    ]
    minimum_price = min(comparable_prices) if comparable_prices else None
    maximum_price = max(comparable_prices) if comparable_prices else None
    scored: list[RankedCandidate] = []
    for candidate in candidates:
        detour_score = (
            1.0
            if maximum_detour_seconds == 0
            else max(
                0.0,
                1.0
                - candidate.detour.detour_duration_seconds / maximum_detour_seconds,
            )
        )
        opening_score = {
            "open": 1.0,
            "closed": 0.0,
            "unknown": policy.unknown_opening_score,
        }[candidate.opening.state]
        price_score = _price_score(candidate.price, minimum_price, maximum_price)
        price_freshness_score = _price_freshness_score(
            candidate.price, policy.price_freshness_seconds
        )
        contributions = (
            detour_score * policy.detour_weight,
            opening_score * policy.opening_weight,
            price_score * policy.price_weight,
            price_freshness_score * policy.price_freshness_weight,
        )
        availability_multiplier = (
            policy.closed_score_multiplier if candidate.opening.state == "closed" else 1.0
        )
        scored.append(
            replace(
                candidate,
                ranking=RankingBreakdown(
                    rank=0,
                    total_score=_rounded(sum(contributions) * availability_multiplier),
                    detour_score=_rounded(detour_score),
                    opening_score=_rounded(opening_score),
                    price_score=_rounded(price_score),
                    price_freshness_score=_rounded(price_freshness_score),
                    detour_contribution=_rounded(contributions[0]),
                    opening_contribution=_rounded(contributions[1]),
                    price_contribution=_rounded(contributions[2]),
                    price_freshness_contribution=_rounded(contributions[3]),
                    availability_multiplier=_rounded(availability_multiplier),
                ),
            )
        )
    scored.sort(
        key=lambda candidate: (
            -candidate.ranking.total_score,
            candidate.detour.detour_duration_seconds,
            candidate.price is None,
            candidate.price.unit_price if candidate.price is not None else Decimal("Infinity"),
            candidate.detour.station.station_id,
        )
    )
    return tuple(
        replace(candidate, ranking=replace(candidate.ranking, rank=index))
        for index, candidate in enumerate(scored, start=1)
    )


def _price_score(
    price: EvaluatedCngPrice | None,
    minimum_price: float | None,
    maximum_price: float | None,
) -> float:
    if (
        price is None
        or price.freshness_state == "future_observation"
        or minimum_price is None
        or maximum_price is None
    ):
        return 0.0
    if minimum_price == maximum_price:
        return 1.0
    return (maximum_price - float(price.unit_price)) / (maximum_price - minimum_price)


def _price_freshness_score(
    price: EvaluatedCngPrice | None, freshness_seconds: float
) -> float:
    if (
        price is None
        or price.age_seconds is None
        or price.age_seconds < 0
        or price.age_seconds > freshness_seconds
    ):
        return 0.0
    return 1.0 - price.age_seconds / freshness_seconds


def _aware_utc(value: datetime) -> datetime | None:
    if value.tzinfo is None or value.utcoffset() is None:
        return None
    return value.astimezone(UTC)


def _empty_breakdown() -> RankingBreakdown:
    return RankingBreakdown(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)


def _rounded(value: float) -> float:
    return round(value, 6)
