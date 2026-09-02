from __future__ import annotations

import json
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from compass.traffic.domain import TrafficEdgeMatch, TrafficFlowSegment, TrafficQualityPolicy
from compass.traffic.valhalla.executor import (
    TrafficOverlayExecutionError,
    TrafficOverlayExecutor,
    clear_managed_plan,
)
from compass.traffic.valhalla.overlay import (
    NativeValhallaTrafficWriter,
    TrafficWriteReceipt,
    ValhallaTrafficWriterError,
)
from compass.traffic.valhalla.planner import (
    TrafficEdgeCandidate,
    TrafficOverlayPlanner,
    TrafficOverlayState,
    TrafficStateError,
)

NOW = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
TILESET = "valhalla-3.8.3:123"


class FakeWriter:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.calls: list[tuple[object, bool]] = []

    def apply(self, plan, *, require_unknown: bool = False) -> TrafficWriteReceipt:
        self.calls.append((plan, require_unknown))
        if self.fail:
            raise ValhallaTrafficWriterError("writer failed")
        return TrafficWriteReceipt(
            set_count=len(plan.set_updates),
            reset_count=len(plan.reset_graph_ids),
            operation_count=len(plan.set_updates) + len(plan.reset_graph_ids),
        )


class FakeStore:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.saved: list[TrafficOverlayState] = []

    def save(self, state: TrafficOverlayState) -> None:
        if self.fail:
            raise TrafficStateError("state failed")
        self.saved.append(state)


def _plan():
    state = TrafficOverlayState(tileset_identity=TILESET)
    planner = TrafficOverlayPlanner(
        state=state,
        quality_policy=TrafficQualityPolicy(),
    )
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id="flow-1",
        observed_at=NOW,
        expires_at=NOW + timedelta(minutes=1),
        current_speed_kph=35,
        confidence=0.9,
    )
    match = TrafficEdgeMatch(
        directed_edge_ids=("0/1/10",),
        match_method="geometry_trace",
        confidence=0.9,
        direction_match=True,
        valhalla_tileset_version=TILESET,
        mapping_version="valhalla-openlr-geometry-v1",
    )
    return state, planner.plan(
        (TrafficEdgeCandidate(segment=segment, match=match),),
        evaluated_at=NOW,
    )


def test_executor_writes_before_persisting_state() -> None:
    previous, plan = _plan()
    writer = FakeWriter()
    store = FakeStore()
    executor = TrafficOverlayExecutor(writer=writer, state_store=store)  # type: ignore[arg-type]

    receipt = executor.execute(previous_state=previous, plan=plan)

    assert receipt.state_persisted is True
    assert writer.calls[0][1] is True
    assert store.saved == [plan.resulting_state]


def test_executor_rolls_overlay_back_if_state_persistence_fails() -> None:
    previous, plan = _plan()
    writer = FakeWriter()
    store = FakeStore(fail=True)
    executor = TrafficOverlayExecutor(writer=writer, state_store=store)  # type: ignore[arg-type]

    with pytest.raises(TrafficOverlayExecutionError, match="rolled back"):
        executor.execute(previous_state=previous, plan=plan)

    assert len(writer.calls) == 2
    rollback = writer.calls[1][0]
    assert rollback.set_updates == ()
    assert rollback.reset_graph_ids == ("0/1/10",)


def test_executor_does_not_persist_state_when_writer_rejects_plan() -> None:
    previous, plan = _plan()
    writer = FakeWriter(fail=True)
    store = FakeStore()
    executor = TrafficOverlayExecutor(writer=writer, state_store=store)  # type: ignore[arg-type]

    with pytest.raises(TrafficOverlayExecutionError, match="not applied"):
        executor.execute(previous_state=previous, plan=plan)

    assert store.saved == []


def test_clear_managed_plan_resets_only_owned_edges() -> None:
    _previous, populated = _plan()

    clear = clear_managed_plan(populated.resulting_state)

    assert clear.set_updates == ()
    assert clear.reset_graph_ids == ("0/1/10",)
    assert clear.resulting_state.edges == ()


def test_native_writer_serializes_one_batch_and_requires_unknown_on_first_apply(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _previous, plan = _plan()
    archive = tmp_path / "traffic.tar"
    archive.write_bytes(b"fixture")
    captured: dict[str, object] = {}

    def fake_run(command, **_kwargs):
        plan_path = Path(command[command.index("--plan-file") + 1])
        captured.update(json.loads(plan_path.read_text(encoding="utf-8")))
        return subprocess.CompletedProcess(
            command,
            0,
            stdout=json.dumps(
                {"set_count": 1, "reset_count": 0, "operation_count": 1}
            ),
            stderr="",
        )

    monkeypatch.setattr(subprocess, "run", fake_run)
    writer = NativeValhallaTrafficWriter(
        executable_path="/native/tool",
        traffic_extract=archive,
    )

    receipt = writer.apply(plan, require_unknown=True)

    assert receipt.operation_count == 1
    assert captured["require_unknown"] is True
    assert captured["set_updates"][0]["graph_id"] == "0/1/10"  # type: ignore[index]
