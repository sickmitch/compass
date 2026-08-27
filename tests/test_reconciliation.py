from dataclasses import replace
from decimal import Decimal

import pytest

from compass.normalization.values import clean_text, normalize_text, valid_italy_coordinates
from compass.reconciliation.domain import (
    CandidateInput,
    MatchDecision,
    ReconciliationError,
    ReconciliationPolicy,
    decide_match,
    name_similarity,
    resolve_osm_link_conflicts,
)


def test_text_normalization_is_deterministic_and_accent_insensitive() -> None:
    assert clean_text("  Caffè   Metano  ") == "Caffè Metano"
    assert normalize_text("  Caffè—Metano S.p.A. ") == "caffe metano s p a"
    assert name_similarity("Eni Metano", "ENI   Metano") == 1.0


@pytest.mark.parametrize(
    ("latitude", "longitude", "expected"),
    [
        (Decimal("45.4642"), Decimal("9.19"), True),
        (None, Decimal("9.19"), False),
        (Decimal("48"), Decimal("9.19"), False),
        (Decimal("45"), Decimal("20"), False),
    ],
)
def test_italian_coordinate_validation(
    latitude: Decimal | None, longitude: Decimal | None, expected: bool
) -> None:
    assert valid_italy_coordinates(latitude, longitude) is expected


def test_unique_close_candidate_is_matched_by_proximity() -> None:
    decision = decide_match(
        "Milano Metano",
        [CandidateInput(osm_feature_id=101, distance_meters=12, name="Different name")],
        ReconciliationPolicy(),
    )

    assert decision.status == "matched"
    assert decision.selected_osm_feature_id == 101
    assert decision.match_method == "proximity_v1"


def test_named_candidate_can_match_beyond_close_distance() -> None:
    decision = decide_match(
        "Bologna CNG",
        [CandidateInput(osm_feature_id=201, distance_meters=120, name="Bologna CNG")],
        ReconciliationPolicy(),
    )

    assert decision.status == "matched"
    assert decision.match_method == "proximity_name_v1"


def test_similar_top_candidates_are_ambiguous_and_ranked_stably() -> None:
    inputs = [
        CandidateInput(osm_feature_id=202, distance_meters=20, name="Bologna CNG"),
        CandidateInput(osm_feature_id=201, distance_meters=20, name="Bologna CNG"),
    ]

    decision = decide_match("Bologna CNG", inputs, ReconciliationPolicy())

    assert decision.status == "ambiguous"
    assert decision.selected_osm_feature_id is None
    assert [candidate.osm_feature_id for candidate in decision.candidates] == [201, 202]


def test_candidates_failing_thresholds_remain_explicitly_unmatched() -> None:
    decision = decide_match(
        "Firenze Centro",
        [CandidateInput(osm_feature_id=301, distance_meters=200, name="Unrelated")],
        ReconciliationPolicy(),
    )

    assert decision.status == "unmatched"
    assert decision.reason == "nearby_candidates_failed_auto_match_thresholds"
    assert len(decision.candidates) == 1


def _matched(feature_id: int, *, manual: bool = False) -> MatchDecision:
    return MatchDecision(
        status="matched",
        selected_osm_feature_id=feature_id,
        match_method="manual_override" if manual else "proximity_v1",
        confidence=1.0,
        distance_meters=0,
        name_similarity=1.0,
        reason="fixture",
        candidates=(),
    )


def test_osm_feature_conflict_never_creates_two_automatic_links() -> None:
    resolved = resolve_osm_link_conflicts({1: _matched(101), 2: _matched(101)})

    assert resolved[1].status == "ambiguous"
    assert resolved[2].status == "ambiguous"


def test_manual_link_wins_over_automatic_claim() -> None:
    resolved = resolve_osm_link_conflicts({1: _matched(101, manual=True), 2: _matched(101)})

    assert resolved[1].status == "matched"
    assert resolved[2] == replace(
        _matched(101),
        status="ambiguous",
        selected_osm_feature_id=None,
        reason="osm_feature_claimed_by_multiple_mimit_stations",
    )


def test_conflicting_manual_links_are_rejected() -> None:
    with pytest.raises(ReconciliationError, match="multiple manual overrides"):
        resolve_osm_link_conflicts({1: _matched(101, manual=True), 2: _matched(101, manual=True)})
