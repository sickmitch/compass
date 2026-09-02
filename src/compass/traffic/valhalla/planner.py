from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from tempfile import NamedTemporaryFile

from compass.traffic.domain import (
    TrafficEdgeMatch,
    TrafficEdgeUpdate,
    TrafficFlowSegment,
    TrafficQualityPolicy,
)

STATE_SCHEMA_VERSION = 1


class TrafficStateError(RuntimeError):
    """The managed overlay state is malformed or belongs to another tileset."""


@dataclass(frozen=True, slots=True)
class TrafficEdgeCandidate:
    segment: TrafficFlowSegment
    match: TrafficEdgeMatch


@dataclass(frozen=True, slots=True)
class ManagedTrafficEdge:
    graph_id: str
    provider_segment_id: str
    observed_at: datetime
    expires_at: datetime
    speed_kph: float
    congestion: float | None
    closed: bool
    has_incidents: bool
    match_confidence: float
    mapping_version: str

    def __post_init__(self) -> None:
        update = self.as_update()
        object.__setattr__(self, "observed_at", _aware_utc(self.observed_at))
        object.__setattr__(self, "expires_at", _aware_utc(self.expires_at))
        if self.expires_at <= self.observed_at:
            raise ValueError("managed traffic edge expiry must follow observation")
        if not 0 <= self.match_confidence <= 1:
            raise ValueError("managed traffic match confidence must be between zero and one")
        if not self.provider_segment_id or not self.mapping_version:
            raise ValueError("managed traffic edge provenance must not be empty")
        if update.expires_at is None:
            raise AssertionError("managed edge update must carry expiry")

    def as_update(self) -> TrafficEdgeUpdate:
        return TrafficEdgeUpdate(
            graph_id=self.graph_id,
            speed_kph=self.speed_kph,
            congestion=self.congestion,
            closed=self.closed,
            has_incidents=self.has_incidents,
            expires_at=self.expires_at,
        )


@dataclass(frozen=True, slots=True)
class TrafficOverlayState:
    tileset_identity: str
    edges: tuple[ManagedTrafficEdge, ...] = ()

    def __post_init__(self) -> None:
        if not self.tileset_identity:
            raise ValueError("traffic state tileset identity must not be empty")
        graph_ids = [edge.graph_id for edge in self.edges]
        if len(graph_ids) != len(set(graph_ids)):
            raise ValueError("traffic state contains duplicate GraphIds")


@dataclass(frozen=True, slots=True)
class RejectedTrafficSegment:
    provider_segment_id: str
    reason: str


@dataclass(frozen=True, slots=True)
class TrafficOverlayPlan:
    set_updates: tuple[TrafficEdgeUpdate, ...]
    reset_graph_ids: tuple[str, ...]
    rejected_segments: tuple[RejectedTrafficSegment, ...]
    resulting_state: TrafficOverlayState


class TrafficOverlayPlanner:
    """Create deterministic whole-edge operations without touching traffic.tar."""

    def __init__(
        self,
        *,
        state: TrafficOverlayState,
        quality_policy: TrafficQualityPolicy,
    ) -> None:
        self._state = state
        self._quality_policy = quality_policy

    @property
    def state(self) -> TrafficOverlayState:
        return self._state

    def plan(
        self,
        candidates: tuple[TrafficEdgeCandidate, ...],
        *,
        evaluated_at: datetime,
    ) -> TrafficOverlayPlan:
        evaluated_at = _aware_utc(evaluated_at)
        selected: dict[str, ManagedTrafficEdge] = {}
        rejected: list[RejectedTrafficSegment] = []
        for candidate in candidates:
            reason = self._rejection_reason(candidate, evaluated_at=evaluated_at)
            if reason is not None:
                rejected.append(
                    RejectedTrafficSegment(
                        provider_segment_id=candidate.segment.provider_segment_id,
                        reason=reason,
                    )
                )
                continue
            for edge in _managed_edges(candidate):
                previous = selected.get(edge.graph_id)
                if previous is None or _selection_key(edge) > _selection_key(previous):
                    selected[edge.graph_id] = edge

        previous = {edge.graph_id: edge for edge in self._state.edges}
        expired = {
            graph_id
            for graph_id, edge in previous.items()
            if edge.expires_at <= evaluated_at and graph_id not in selected
        }
        retained = {
            graph_id: edge
            for graph_id, edge in previous.items()
            if edge.expires_at > evaluated_at and graph_id not in selected
        }
        resulting = {**retained, **selected}
        resulting_state = TrafficOverlayState(
            tileset_identity=self._state.tileset_identity,
            edges=tuple(resulting[key] for key in sorted(resulting)),
        )
        return TrafficOverlayPlan(
            set_updates=tuple(selected[key].as_update() for key in sorted(selected)),
            reset_graph_ids=tuple(sorted(expired)),
            rejected_segments=tuple(rejected),
            resulting_state=resulting_state,
        )

    def commit(self, plan: TrafficOverlayPlan) -> None:
        if plan.resulting_state.tileset_identity != self._state.tileset_identity:
            raise TrafficStateError("cannot commit traffic state for another tileset")
        self._state = plan.resulting_state

    def _rejection_reason(
        self, candidate: TrafficEdgeCandidate, *, evaluated_at: datetime
    ) -> str | None:
        segment = candidate.segment
        match = candidate.match
        if not self._quality_policy.accepts(segment, evaluated_at=evaluated_at):
            return "provider_quality_rejected"
        if not match.directed_edge_ids:
            return "unmatched"
        if match.confidence < self._quality_policy.min_match_confidence:
            return "match_confidence_rejected"
        if match.direction_match is not True:
            return "direction_unverified"
        if match.valhalla_tileset_version != self._state.tileset_identity:
            return "tileset_identity_mismatch"
        if not match.mapping_version:
            return "mapping_version_missing"
        if not segment.road_closed and (
            segment.current_speed_kph is None or segment.current_speed_kph <= 0
        ):
            return "current_speed_missing_or_zero"
        return None


class JsonTrafficStateStore:
    def __init__(self, path: Path) -> None:
        self._path = path

    def load(self, *, expected_tileset_identity: str) -> TrafficOverlayState:
        if not self._path.exists():
            return TrafficOverlayState(tileset_identity=expected_tileset_identity)
        state = self.load_existing()
        if state.tileset_identity != expected_tileset_identity:
            raise TrafficStateError(
                "traffic state tileset identity differs from the active Valhalla graph"
            )
        return state

    def load_existing(self) -> TrafficOverlayState:
        """Read persisted state without accepting it for an active graph.

        This is intentionally limited to migration diagnostics. Callers must still use
        :meth:`load` before planning or writing traffic for a configured tileset.
        """
        if not self._path.exists():
            raise TrafficStateError("traffic state file does not exist")
        try:
            payload = json.loads(self._path.read_text(encoding="utf-8"))
            state = _state_from_json(payload)
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise TrafficStateError("traffic state file is malformed") from error
        return state

    def save(self, state: TrafficOverlayState) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(_state_to_json(state), indent=2, sort_keys=True) + "\n"
        try:
            with NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=self._path.parent,
                prefix=f".{self._path.name}.",
                delete=False,
            ) as handle:
                temporary_path = Path(handle.name)
                handle.write(payload)
                handle.flush()
            temporary_path.replace(self._path)
        except OSError as error:
            raise TrafficStateError("traffic state file could not be saved") from error


def _managed_edges(candidate: TrafficEdgeCandidate) -> tuple[ManagedTrafficEdge, ...]:
    segment = candidate.segment
    match = candidate.match
    speed_kph = 0.0 if segment.road_closed else float(segment.current_speed_kph or 0)
    return tuple(
        ManagedTrafficEdge(
            graph_id=graph_id,
            provider_segment_id=segment.provider_segment_id,
            observed_at=segment.observed_at,
            expires_at=segment.expires_at,
            speed_kph=speed_kph,
            congestion=segment.congestion,
            closed=segment.road_closed,
            has_incidents=segment.road_closed,
            match_confidence=match.confidence,
            mapping_version=match.mapping_version or "",
        )
        for graph_id in match.directed_edge_ids
    )


def _selection_key(edge: ManagedTrafficEdge) -> tuple[object, ...]:
    return (
        edge.closed,
        edge.observed_at,
        edge.match_confidence,
        edge.provider_segment_id,
    )


def _state_to_json(state: TrafficOverlayState) -> dict[str, object]:
    return {
        "schema_version": STATE_SCHEMA_VERSION,
        "tileset_identity": state.tileset_identity,
        "edges": [
            {
                "graph_id": edge.graph_id,
                "provider_segment_id": edge.provider_segment_id,
                "observed_at": edge.observed_at.isoformat(),
                "expires_at": edge.expires_at.isoformat(),
                "speed_kph": edge.speed_kph,
                "congestion": edge.congestion,
                "closed": edge.closed,
                "has_incidents": edge.has_incidents,
                "match_confidence": edge.match_confidence,
                "mapping_version": edge.mapping_version,
            }
            for edge in state.edges
        ],
    }


def _state_from_json(payload: object) -> TrafficOverlayState:
    if not isinstance(payload, dict) or payload.get("schema_version") != STATE_SCHEMA_VERSION:
        raise ValueError("unsupported traffic state schema")
    tileset_identity = payload.get("tileset_identity")
    raw_edges = payload.get("edges")
    if not isinstance(tileset_identity, str) or not isinstance(raw_edges, list):
        raise TypeError("traffic state fields have invalid types")
    edges: list[ManagedTrafficEdge] = []
    for value in raw_edges:
        if not isinstance(value, dict):
            raise TypeError("traffic state edge must be an object")
        edges.append(
            ManagedTrafficEdge(
                graph_id=_string(value, "graph_id"),
                provider_segment_id=_string(value, "provider_segment_id"),
                observed_at=datetime.fromisoformat(_string(value, "observed_at")),
                expires_at=datetime.fromisoformat(_string(value, "expires_at")),
                speed_kph=_number(value, "speed_kph"),
                congestion=_optional_number(value, "congestion"),
                closed=_boolean(value, "closed"),
                has_incidents=_boolean(value, "has_incidents"),
                match_confidence=_number(value, "match_confidence"),
                mapping_version=_string(value, "mapping_version"),
            )
        )
    return TrafficOverlayState(tileset_identity=tileset_identity, edges=tuple(edges))


def _string(value: dict[str, object], field: str) -> str:
    result = value.get(field)
    if not isinstance(result, str) or not result:
        raise TypeError(f"traffic state {field} must be a non-empty string")
    return result


def _number(value: dict[str, object], field: str) -> float:
    result = value.get(field)
    if isinstance(result, bool) or not isinstance(result, int | float):
        raise TypeError(f"traffic state {field} must be numeric")
    return float(result)


def _optional_number(value: dict[str, object], field: str) -> float | None:
    return None if value.get(field) is None else _number(value, field)


def _boolean(value: dict[str, object], field: str) -> bool:
    result = value.get(field)
    if not isinstance(result, bool):
        raise TypeError(f"traffic state {field} must be boolean")
    return result


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("traffic planner timestamps must include a UTC offset")
    return value.astimezone(UTC)
