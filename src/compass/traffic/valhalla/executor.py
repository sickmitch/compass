from __future__ import annotations

from dataclasses import dataclass

from compass.traffic.valhalla.overlay import (
    NativeValhallaTrafficWriter,
    TrafficWriteReceipt,
    ValhallaTrafficWriterError,
)
from compass.traffic.valhalla.planner import (
    JsonTrafficStateStore,
    TrafficOverlayPlan,
    TrafficOverlayState,
    TrafficStateError,
)


class TrafficOverlayExecutionError(RuntimeError):
    """The overlay and its durable managed-edge state could not be committed."""


@dataclass(frozen=True, slots=True)
class TrafficOverlayExecutionReceipt:
    write: TrafficWriteReceipt
    state_persisted: bool
    rollback_performed: bool = False


class TrafficOverlayExecutor:
    def __init__(
        self,
        *,
        writer: NativeValhallaTrafficWriter,
        state_store: JsonTrafficStateStore,
    ) -> None:
        self._writer = writer
        self._state_store = state_store

    def execute(
        self,
        *,
        previous_state: TrafficOverlayState,
        plan: TrafficOverlayPlan,
    ) -> TrafficOverlayExecutionReceipt:
        if plan.resulting_state.tileset_identity != previous_state.tileset_identity:
            raise TrafficOverlayExecutionError(
                "traffic plan and previous state belong to different tilesets"
            )
        try:
            write_receipt = self._writer.apply(
                plan,
                require_unknown=not previous_state.edges and bool(plan.set_updates),
            )
        except ValhallaTrafficWriterError as error:
            raise TrafficOverlayExecutionError(
                "traffic overlay transaction was not applied"
            ) from error
        try:
            self._state_store.save(plan.resulting_state)
        except TrafficStateError as state_error:
            rollback = _rollback_plan(previous_state=previous_state, applied_plan=plan)
            try:
                self._writer.apply(rollback)
            except ValhallaTrafficWriterError as rollback_error:
                raise TrafficOverlayExecutionError(
                    "traffic state persistence failed and overlay rollback also failed"
                ) from rollback_error
            raise TrafficOverlayExecutionError(
                "traffic state persistence failed; overlay was rolled back"
            ) from state_error
        return TrafficOverlayExecutionReceipt(
            write=write_receipt,
            state_persisted=True,
        )


def clear_managed_plan(state: TrafficOverlayState) -> TrafficOverlayPlan:
    graph_ids = tuple(sorted(edge.graph_id for edge in state.edges))
    return TrafficOverlayPlan(
        set_updates=(),
        reset_graph_ids=graph_ids,
        rejected_segments=(),
        resulting_state=TrafficOverlayState(tileset_identity=state.tileset_identity),
    )


def _rollback_plan(
    *,
    previous_state: TrafficOverlayState,
    applied_plan: TrafficOverlayPlan,
) -> TrafficOverlayPlan:
    affected = {
        update.graph_id for update in applied_plan.set_updates
    } | set(applied_plan.reset_graph_ids)
    previous = {edge.graph_id: edge for edge in previous_state.edges}
    return TrafficOverlayPlan(
        set_updates=tuple(
            previous[graph_id].as_update()
            for graph_id in sorted(affected & previous.keys())
        ),
        reset_graph_ids=tuple(sorted(affected - previous.keys())),
        rejected_segments=(),
        resulting_state=previous_state,
    )
