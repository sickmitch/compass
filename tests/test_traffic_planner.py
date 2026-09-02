from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from compass.traffic.domain import (
    TrafficEdgeMatch,
    TrafficFlowSegment,
    TrafficQualityPolicy,
)
from compass.traffic.valhalla.planner import (
    JsonTrafficStateStore,
    TrafficEdgeCandidate,
    TrafficOverlayPlanner,
    TrafficOverlayState,
    TrafficStateError,
)

NOW = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
TILESET = "valhalla-3.8.3:123"


def _segment(
    segment_id: str,
    *,
    speed_kph: float | None = 35,
    road_closed: bool = False,
    observed_at: datetime = NOW,
    expires_at: datetime = NOW + timedelta(seconds=60),
) -> TrafficFlowSegment:
    return TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id=segment_id,
        observed_at=observed_at,
        expires_at=expires_at,
        current_speed_kph=speed_kph,
        confidence=0.9,
        congestion=0.7,
        road_closed=road_closed,
    )


def _match(
    *graph_ids: str,
    direction_match: bool | None = True,
    tileset: str = TILESET,
    confidence: float = 0.9,
) -> TrafficEdgeMatch:
    return TrafficEdgeMatch(
        directed_edge_ids=graph_ids,
        match_method="geometry_trace",
        confidence=confidence,
        direction_match=direction_match,
        valhalla_tileset_version=tileset,
        mapping_version="valhalla-openlr-geometry-v1",
    )


def _planner() -> TrafficOverlayPlanner:
    return TrafficOverlayPlanner(
        state=TrafficOverlayState(tileset_identity=TILESET),
        quality_policy=TrafficQualityPolicy(),
    )


def test_planner_retains_omitted_edges_until_expiry_then_resets_unknown() -> None:
    planner = _planner()
    candidate = TrafficEdgeCandidate(
        segment=_segment("flow-1"),
        match=_match("0/1/10", "0/1/11"),
    )

    first = planner.plan((candidate,), evaluated_at=NOW + timedelta(seconds=1))
    assert [update.graph_id for update in first.set_updates] == ["0/1/10", "0/1/11"]
    assert all(update.speed_kph == 35 for update in first.set_updates)
    assert first.reset_graph_ids == ()
    planner.commit(first)

    omitted = planner.plan((), evaluated_at=NOW + timedelta(seconds=30))
    assert omitted.set_updates == ()
    assert omitted.reset_graph_ids == ()
    assert len(omitted.resulting_state.edges) == 2
    planner.commit(omitted)

    expired = planner.plan((), evaluated_at=NOW + timedelta(seconds=61))
    assert expired.set_updates == ()
    assert expired.reset_graph_ids == ("0/1/10", "0/1/11")
    assert expired.resulting_state.edges == ()


def test_explicit_closure_wins_overlap_and_low_speed_is_not_a_closure() -> None:
    planner = _planner()
    congestion = TrafficEdgeCandidate(
        segment=_segment("slow", speed_kph=1),
        match=_match("0/1/10"),
    )
    closure = TrafficEdgeCandidate(
        segment=_segment(
            "closure",
            speed_kph=None,
            road_closed=True,
            observed_at=NOW - timedelta(seconds=10),
        ),
        match=_match("0/1/10"),
    )

    plan = planner.plan((congestion, closure), evaluated_at=NOW)

    assert len(plan.set_updates) == 1
    update = plan.set_updates[0]
    assert update.speed_kph == 0
    assert update.closed is True
    assert update.has_incidents is True

    slow_only = planner.plan((congestion,), evaluated_at=NOW)
    assert slow_only.set_updates[0].speed_kph == 1
    assert slow_only.set_updates[0].closed is False


@pytest.mark.parametrize(
    ("segment", "match", "reason"),
    [
        (_segment("zero", speed_kph=0), _match("0/1/10"), "current_speed_missing_or_zero"),
        (
            _segment("wrong-direction"),
            _match("0/1/10", direction_match=False),
            "direction_unverified",
        ),
        (
            _segment("wrong-tileset"),
            _match("0/1/10", tileset="valhalla-3.8.3:456"),
            "tileset_identity_mismatch",
        ),
    ],
)
def test_planner_rejects_unsafe_candidate(
    segment: TrafficFlowSegment, match: TrafficEdgeMatch, reason: str
) -> None:
    plan = _planner().plan(
        (TrafficEdgeCandidate(segment=segment, match=match),),
        evaluated_at=NOW,
    )

    assert plan.set_updates == ()
    assert plan.rejected_segments[0].reason == reason


def test_json_state_store_round_trips_and_refuses_another_tileset(tmp_path) -> None:
    planner = _planner()
    plan = planner.plan(
        (
            TrafficEdgeCandidate(
                segment=_segment("flow-1"),
                match=_match("0/1/10"),
            ),
        ),
        evaluated_at=NOW,
    )
    store = JsonTrafficStateStore(tmp_path / "traffic-state" / "state.json")

    store.save(plan.resulting_state)
    loaded = store.load(expected_tileset_identity=TILESET)

    assert loaded == plan.resulting_state
    assert store.load_existing() == plan.resulting_state
    with pytest.raises(TrafficStateError, match="differs"):
        store.load(expected_tileset_identity="valhalla-3.8.3:456")
