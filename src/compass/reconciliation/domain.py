from dataclasses import dataclass, replace
from difflib import SequenceMatcher

from compass.normalization.values import normalize_text

ALGORITHM_VERSION = "mimit-osm-distance-name-v1"


class ReconciliationError(ValueError):
    pass


@dataclass(frozen=True)
class ReconciliationPolicy:
    max_distance_meters: float = 250.0
    auto_match_distance_meters: float = 50.0
    named_match_distance_meters: float = 150.0
    name_similarity_threshold: float = 0.75
    ambiguity_score_margin: float = 0.08

    def __post_init__(self) -> None:
        if not (
            0
            < self.auto_match_distance_meters
            <= self.named_match_distance_meters
            <= self.max_distance_meters
        ):
            raise ValueError("reconciliation distance thresholds are inconsistent")
        if not 0 <= self.name_similarity_threshold <= 1:
            raise ValueError("name similarity threshold must be between zero and one")
        if not 0 <= self.ambiguity_score_margin <= 1:
            raise ValueError("ambiguity score margin must be between zero and one")


@dataclass(frozen=True)
class CandidateInput:
    osm_feature_id: int
    distance_meters: float
    name: str | None


@dataclass(frozen=True)
class ScoredCandidate:
    osm_feature_id: int
    distance_meters: float
    name_similarity: float
    score: float
    eligible: bool


@dataclass(frozen=True)
class MatchDecision:
    status: str
    selected_osm_feature_id: int | None
    match_method: str
    confidence: float | None
    distance_meters: float | None
    name_similarity: float | None
    reason: str
    candidates: tuple[ScoredCandidate, ...]


def name_similarity(left: str | None, right: str | None) -> float:
    normalized_left = normalize_text(left)
    normalized_right = normalize_text(right)
    if not normalized_left or not normalized_right:
        return 0.0
    if normalized_left == normalized_right:
        return 1.0
    sequence_score = SequenceMatcher(None, normalized_left, normalized_right).ratio()
    left_tokens = set(normalized_left.split())
    right_tokens = set(normalized_right.split())
    token_score = len(left_tokens & right_tokens) / len(left_tokens | right_tokens)
    return round(max(sequence_score, token_score), 6)


def _score_candidate(
    station_name: str | None,
    candidate: CandidateInput,
    policy: ReconciliationPolicy,
) -> ScoredCandidate:
    similarity = name_similarity(station_name, candidate.name)
    distance_score = max(0.0, 1.0 - candidate.distance_meters / policy.max_distance_meters)
    has_both_names = bool(normalize_text(station_name) and normalize_text(candidate.name))
    score = 0.7 * distance_score + 0.3 * similarity if has_both_names else 0.85 * distance_score
    eligible = candidate.distance_meters <= policy.auto_match_distance_meters or (
        candidate.distance_meters <= policy.named_match_distance_meters
        and similarity >= policy.name_similarity_threshold
    )
    return ScoredCandidate(
        osm_feature_id=candidate.osm_feature_id,
        distance_meters=round(candidate.distance_meters, 2),
        name_similarity=similarity,
        score=round(min(1.0, max(0.0, score)), 6),
        eligible=eligible,
    )


def decide_match(
    station_name: str | None,
    candidates: list[CandidateInput],
    policy: ReconciliationPolicy,
) -> MatchDecision:
    ranked = tuple(
        sorted(
            (_score_candidate(station_name, candidate, policy) for candidate in candidates),
            key=lambda item: (-item.score, item.distance_meters, item.osm_feature_id),
        )
    )
    if not ranked:
        return MatchDecision(
            status="unmatched",
            selected_osm_feature_id=None,
            match_method=ALGORITHM_VERSION,
            confidence=None,
            distance_meters=None,
            name_similarity=None,
            reason="no_osm_candidate_within_maximum_distance",
            candidates=ranked,
        )

    eligible = [candidate for candidate in ranked if candidate.eligible]
    if not eligible:
        return MatchDecision(
            status="unmatched",
            selected_osm_feature_id=None,
            match_method=ALGORITHM_VERSION,
            confidence=None,
            distance_meters=None,
            name_similarity=None,
            reason="nearby_candidates_failed_auto_match_thresholds",
            candidates=ranked,
        )

    best = eligible[0]
    if len(eligible) > 1 and best.score - eligible[1].score < policy.ambiguity_score_margin:
        return MatchDecision(
            status="ambiguous",
            selected_osm_feature_id=None,
            match_method=ALGORITHM_VERSION,
            confidence=best.score,
            distance_meters=best.distance_meters,
            name_similarity=best.name_similarity,
            reason="top_candidates_within_ambiguity_margin",
            candidates=ranked,
        )

    method = (
        "proximity_v1"
        if best.distance_meters <= policy.auto_match_distance_meters
        else "proximity_name_v1"
    )
    return MatchDecision(
        status="matched",
        selected_osm_feature_id=best.osm_feature_id,
        match_method=method,
        confidence=best.score,
        distance_meters=best.distance_meters,
        name_similarity=best.name_similarity,
        reason="unique_candidate_passed_auto_match_thresholds",
        candidates=ranked,
    )


def resolve_osm_link_conflicts(
    decisions: dict[int, MatchDecision],
) -> dict[int, MatchDecision]:
    claims: dict[int, list[int]] = {}
    for station_id, decision in decisions.items():
        if decision.status == "matched" and decision.selected_osm_feature_id is not None:
            claims.setdefault(decision.selected_osm_feature_id, []).append(station_id)

    resolved = dict(decisions)
    for osm_feature_id, station_ids in claims.items():
        if len(station_ids) < 2:
            continue
        manual_station_ids = [
            station_id
            for station_id in station_ids
            if decisions[station_id].match_method == "manual_override"
        ]
        if len(manual_station_ids) > 1:
            raise ReconciliationError(
                f"multiple manual overrides claim OSM feature {osm_feature_id}"
            )
        preserved_station_id = manual_station_ids[0] if manual_station_ids else None
        for station_id in station_ids:
            if station_id == preserved_station_id:
                continue
            decision = decisions[station_id]
            resolved[station_id] = replace(
                decision,
                status="ambiguous",
                selected_osm_feature_id=None,
                reason="osm_feature_claimed_by_multiple_mimit_stations",
            )
    return resolved
