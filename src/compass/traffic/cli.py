from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

from compass.config import get_settings
from compass.logging import configure_logging
from compass.traffic.domain import (
    TrafficEdgeMatch,
    TrafficFetchRequest,
    TrafficFlowSegment,
)
from compass.traffic.health import (
    JsonTrafficRuntimeHealthStore,
    TrafficRuntimeHealth,
    TrafficRuntimeHealthError,
)
from compass.traffic.route_refresh import (
    JsonRouteRefreshLedgerStore,
    RouteRefreshLedger,
)
from compass.traffic.service import (
    build_traffic_edge_matcher,
    build_traffic_provider,
    traffic_health_from_settings,
    traffic_quality_policy,
)
from compass.traffic.valhalla.executor import (
    TrafficOverlayExecutor,
    clear_managed_plan,
)
from compass.traffic.valhalla.overlay import NativeValhallaTrafficWriter
from compass.traffic.valhalla.planner import (
    JsonTrafficStateStore,
    TrafficEdgeCandidate,
    TrafficOverlayPlanner,
    TrafficOverlayState,
    TrafficStateError,
)


@dataclass(frozen=True, slots=True)
class TrafficUpdaterCycleResult:
    provider: str
    observed_at: datetime
    segments_received: int
    segments_normalized: int
    segments_considered: int
    segments_accepted: int
    segments_unmatched: int
    provider_rejected_segments: int
    provider_stale_segments: int
    provider_api_errors: int
    edges_set: int
    edges_reset: int
    managed_edge_count: int
    mapping_version: str | None


def main() -> int:
    settings = get_settings()
    configure_logging(settings.log_level)
    parser = argparse.ArgumentParser(prog="compass-traffic")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("config-check")
    subparsers.add_parser("status")
    subparsers.add_parser("fetch-once")
    match_parser = subparsers.add_parser("match-once")
    match_parser.add_argument("--segment-id")
    match_parser.add_argument("--limit", type=int, default=20)
    plan_parser = subparsers.add_parser("plan-once")
    plan_parser.add_argument("--limit", type=int, default=20)
    apply_parser = subparsers.add_parser("apply-once")
    apply_parser.add_argument("--limit", type=int, default=20)
    subparsers.add_parser("clear-managed")
    subparsers.add_parser("clear-route-refreshes")
    reinitialize_parser = subparsers.add_parser(
        "reinitialize-state-after-traffic-rebuild"
    )
    reinitialize_parser.add_argument("--previous-tileset", required=True)
    subparsers.add_parser("run-updater")
    subparsers.add_parser("quality-policy")
    args = parser.parse_args()

    if args.command == "config-check":
        _print(_configuration_snapshot(settings))
        return 0
    if args.command == "status":
        _print(traffic_health_from_settings(settings))
        return 0
    if args.command == "quality-policy":
        _print(traffic_quality_policy(settings))
        return 0
    if args.command == "fetch-once":
        return asyncio.run(_fetch_once(settings))
    if args.command == "match-once":
        return asyncio.run(
            _match_once(settings, segment_id=args.segment_id, limit=args.limit)
        )
    if args.command == "plan-once":
        return asyncio.run(_plan_once(settings, limit=args.limit))
    if args.command == "apply-once":
        return asyncio.run(_apply_once(settings, limit=args.limit))
    if args.command == "clear-managed":
        return _clear_managed(settings)
    if args.command == "clear-route-refreshes":
        return _clear_route_refreshes(settings)
    if args.command == "reinitialize-state-after-traffic-rebuild":
        return _reinitialize_state_after_traffic_rebuild(
            settings,
            previous_tileset=args.previous_tileset,
        )
    if args.command == "run-updater":
        return asyncio.run(_run_updater(settings))
    raise AssertionError("unreachable")


def _configuration_snapshot(settings) -> dict[str, object]:
    """Validate production prerequisites without fetching data or exposing secrets."""
    if not settings.traffic_enabled:
        raise ValueError("traffic_enabled must be true")
    provider = build_traffic_provider(settings)
    if provider is None:
        raise ValueError("a traffic provider must be configured")
    _require_overlay_writer(settings)

    traffic_extract = Path(settings.valhalla_traffic_extract)
    if not traffic_extract.is_file() or traffic_extract.stat().st_size <= 0:
        raise ValueError("Valhalla traffic_extract must exist and be non-empty")

    native_helper = Path(settings.traffic_openlr_decoder_path)
    if not native_helper.is_file() or not os.access(native_helper, os.X_OK):
        raise ValueError("native Valhalla traffic helper must exist and be executable")

    state_store = JsonTrafficStateStore(Path(settings.traffic_state_path))
    state_present = Path(settings.traffic_state_path).exists()
    state = (
        state_store.load_existing()
        if state_present
        else TrafficOverlayState(
            tileset_identity=settings.traffic_valhalla_tileset_version
        )
    )
    probe_count = (
        sum(
            bool(item.strip())
            for item in settings.tomtom_flow_segment_points.replace("\n", ";").split(";")
        )
        if settings.traffic_provider == "tomtom"
        and settings.tomtom_traffic_api_mode == "flow_segment"
        else None
    )
    return {
        "enabled": True,
        "provider": settings.traffic_provider,
        "provider_adapter": type(provider).__name__,
        "provider_configuration_valid": True,
        "provider_api_mode": (
            settings.tomtom_traffic_api_mode
            if settings.traffic_provider == "tomtom"
            else None
        ),
        "refresh_mode": settings.traffic_refresh_mode,
        "configured_probe_count": probe_count,
        "route_probe_spacing_km": settings.traffic_route_probe_spacing_km,
        "route_max_probes": settings.traffic_route_max_probes,
        "route_refresh_min_interval_seconds": (
            settings.traffic_route_refresh_min_interval_seconds
        ),
        "provider_credentials_configured": (
            bool(settings.tomtom_api_key)
            if settings.traffic_provider == "tomtom"
            else None
        ),
        "overlay_enabled": True,
        "configured_tileset_identity": settings.traffic_valhalla_tileset_version,
        "mapping_version": settings.traffic_mapping_version,
        "traffic_extract_present": True,
        "traffic_extract_size_bytes": traffic_extract.stat().st_size,
        "native_helper_present": True,
        "state_present": state_present,
        "state_tileset_identity": state.tileset_identity,
        "state_identity_matches_configured": (
            state.tileset_identity == settings.traffic_valhalla_tileset_version
        ),
        "managed_edge_count": len(state.edges),
    }


async def _fetch_once(settings) -> int:
    provider = build_traffic_provider(settings)
    if provider is None:
        _print(traffic_health_from_settings(settings))
        return 0
    snapshot = await provider.fetch_flow()
    _print(
        {
            "provider": snapshot.provider,
            "observed_at": snapshot.observed_at,
            "segment_count": len(snapshot.segments),
            "metrics": snapshot.metrics,
        }
    )
    return 0


async def _match_once(settings, *, segment_id: str | None, limit: int) -> int:
    if limit <= 0:
        raise ValueError("match limit must be positive")
    provider = build_traffic_provider(settings)
    if provider is None:
        _print(traffic_health_from_settings(settings))
        return 0
    snapshot = await provider.fetch_flow()
    matcher = build_traffic_edge_matcher(settings)
    quality_policy = traffic_quality_policy(settings)
    evaluated_at = datetime.now(UTC)
    segments = [
        segment
        for segment in snapshot.segments
        if segment_id is None or segment.provider_segment_id == segment_id
    ][:limit]
    results = []
    for segment in segments:
        match = await matcher.match(segment)
        quality_accepted = quality_policy.accepts(
            segment, evaluated_at=evaluated_at
        )
        write_eligible = (
            quality_accepted
            and bool(match.directed_edge_ids)
            and match.confidence >= quality_policy.min_match_confidence
            and match.direction_match is True
            and bool(settings.traffic_valhalla_tileset_version)
            and match.valhalla_tileset_version
            == settings.traffic_valhalla_tileset_version
        )
        results.append(
            {
                "provider_segment_id": segment.provider_segment_id,
                "source_reference": _matching_source_reference(segment),
                "quality_accepted": quality_accepted,
                "write_eligible": write_eligible,
                "match": match,
            }
        )
    _print(
        {
            "provider": snapshot.provider,
            "segments_considered": len(segments),
            "configured_tileset_identity": (
                settings.traffic_valhalla_tileset_version or None
            ),
            "minimum_match_confidence": quality_policy.min_match_confidence,
            "provider_overlay_write_enabled": (
                settings.traffic_valhalla_overlay_enabled
            ),
            "results": results,
        }
    )
    return 0


def _matching_source_reference(segment: TrafficFlowSegment) -> dict[str, object]:
    return {
        "openlr": segment.openlr,
        "direction": segment.direction,
        "geometry": [
            {"latitude": point.latitude, "longitude": point.longitude}
            for point in (segment.geometry or ())
        ],
    }


async def _plan_once(settings, *, limit: int) -> int:
    if limit <= 0:
        raise ValueError("plan limit must be positive")
    provider = build_traffic_provider(settings)
    if provider is None:
        _print(traffic_health_from_settings(settings))
        return 0
    if not settings.traffic_valhalla_tileset_version:
        raise ValueError("traffic_valhalla_tileset_version is required for edge planning")
    snapshot, candidates = await _fetch_and_match(settings, provider=provider, limit=limit)
    planner = TrafficOverlayPlanner(
        state=TrafficOverlayState(
            tileset_identity=settings.traffic_valhalla_tileset_version
        ),
        quality_policy=traffic_quality_policy(settings),
    )
    plan = planner.plan(tuple(candidates), evaluated_at=datetime.now(UTC))
    _print(
        {
            "provider": snapshot.provider,
            "configured_tileset_identity": settings.traffic_valhalla_tileset_version,
            "segments_considered": len(candidates),
            "segments_accepted": len(candidates) - len(plan.rejected_segments),
            "planned_edge_update_count": len(plan.set_updates),
            "planned_edge_reset_count": len(plan.reset_graph_ids),
            "active_edge_count_after": len(plan.resulting_state.edges),
            "mapping_versions": sorted(
                {edge.mapping_version for edge in plan.resulting_state.edges}
            ),
            "provider_overlay_write_enabled": (
                settings.traffic_valhalla_overlay_enabled
            ),
            "state_persisted": False,
            "set_updates": plan.set_updates,
            "reset_graph_ids": plan.reset_graph_ids,
            "rejected_segments": plan.rejected_segments,
        }
    )
    return 0


async def _apply_once(settings, *, limit: int) -> int:
    _require_overlay_writer(settings)
    if limit <= 0:
        raise ValueError("apply limit must be positive")
    provider = build_traffic_provider(settings)
    if provider is None:
        raise ValueError("traffic provider must be enabled before applying an overlay")
    state_store = JsonTrafficStateStore(Path(settings.traffic_state_path))
    previous_state = state_store.load(
        expected_tileset_identity=settings.traffic_valhalla_tileset_version
    )
    snapshot, candidates = await _fetch_and_match(settings, provider=provider, limit=limit)
    planner = TrafficOverlayPlanner(
        state=previous_state,
        quality_policy=traffic_quality_policy(settings),
    )
    plan = planner.plan(candidates, evaluated_at=datetime.now(UTC))
    receipt = _overlay_executor(settings, state_store=state_store).execute(
        previous_state=previous_state,
        plan=plan,
    )
    _print(
        {
            "provider": snapshot.provider,
            "configured_tileset_identity": settings.traffic_valhalla_tileset_version,
            "segments_considered": len(candidates),
            "segments_accepted": len(candidates) - len(plan.rejected_segments),
            "edges_set": receipt.write.set_count,
            "edges_reset": receipt.write.reset_count,
            "managed_edge_count": len(plan.resulting_state.edges),
            "state_persisted": receipt.state_persisted,
            "provider_overlay_write_enabled": True,
            "rejected_segments": plan.rejected_segments,
        }
    )
    return 0


def _clear_managed(settings) -> int:
    _require_overlay_writer(settings)
    state_store = JsonTrafficStateStore(Path(settings.traffic_state_path))
    previous_state = state_store.load(
        expected_tileset_identity=settings.traffic_valhalla_tileset_version
    )
    plan = clear_managed_plan(previous_state)
    receipt = _overlay_executor(settings, state_store=state_store).execute(
        previous_state=previous_state,
        plan=plan,
    )
    _persist_runtime_health_safely(
        settings,
        logger=logging.getLogger("compass.traffic.updater"),
        value=_cleared_runtime_health(settings, edges_reset=receipt.write.reset_count),
    )
    _print(
        {
            "configured_tileset_identity": settings.traffic_valhalla_tileset_version,
            "edges_reset": receipt.write.reset_count,
            "managed_edge_count": 0,
            "state_persisted": receipt.state_persisted,
            "provider_overlay_write_enabled": True,
        }
    )
    return 0


def _clear_route_refreshes(settings) -> int:
    if not settings.traffic_valhalla_tileset_version:
        raise ValueError("traffic_valhalla_tileset_version is required")
    JsonRouteRefreshLedgerStore(Path(settings.traffic_refresh_ledger_path)).save(
        RouteRefreshLedger(
            tileset_identity=settings.traffic_valhalla_tileset_version,
            successes={},
        )
    )
    _print(
        {
            "configured_tileset_identity": settings.traffic_valhalla_tileset_version,
            "route_refresh_count": 0,
            "state_persisted": True,
        }
    )
    return 0


def _reinitialize_state_after_traffic_rebuild(
    settings,
    *,
    previous_tileset: str,
) -> int:
    """Discard stale GraphIds only after the operator rebuilt traffic.tar.

    The deliberately explicit command name and required previous identity prevent this
    migration operation from becoming an accidental substitute for ``clear-managed``.
    """
    _require_overlay_writer(settings)
    if not settings.traffic_valhalla_tileset_version:
        raise ValueError("traffic_valhalla_tileset_version is required")
    state_store = JsonTrafficStateStore(Path(settings.traffic_state_path))
    previous_state = state_store.load_existing()
    if previous_state.tileset_identity != previous_tileset:
        raise TrafficStateError(
            "persisted traffic state does not match --previous-tileset"
        )
    if previous_tileset == settings.traffic_valhalla_tileset_version:
        raise TrafficStateError(
            "state already belongs to the configured tileset; use clear-managed"
        )

    state_store.save(
        TrafficOverlayState(
            tileset_identity=settings.traffic_valhalla_tileset_version
        )
    )
    JsonRouteRefreshLedgerStore(Path(settings.traffic_refresh_ledger_path)).save(
        RouteRefreshLedger(
            tileset_identity=settings.traffic_valhalla_tileset_version,
            successes={},
        )
    )
    _persist_runtime_health_safely(
        settings,
        logger=logging.getLogger("compass.traffic.updater"),
        value=_cleared_runtime_health(settings, edges_reset=0),
    )
    _print(
        {
            "configured_tileset_identity": settings.traffic_valhalla_tileset_version,
            "previous_tileset_identity": previous_tileset,
            "discarded_stale_edge_count": len(previous_state.edges),
            "managed_edge_count": 0,
            "state_persisted": True,
            "traffic_extract_rebuild_required": True,
        }
    )
    return 0


async def _fetch_and_match(
    settings,
    *,
    provider,
    limit: int,
    fetch_request: TrafficFetchRequest | None = None,
):
    snapshot = (
        await provider.fetch_flow()
        if fetch_request is None
        else await provider.fetch_flow(fetch_request)
    )
    matcher = build_traffic_edge_matcher(settings)
    candidates = []
    for segment in snapshot.segments[:limit]:
        try:
            match = await matcher.match(segment)
        except Exception as error:
            logging.getLogger("compass.traffic.updater").warning(
                "traffic segment matching failed; segment will not modify the overlay",
                extra={
                    "provider_segment_id": segment.provider_segment_id,
                    "error_type": type(error).__name__,
                },
            )
            match = TrafficEdgeMatch(
                directed_edge_ids=(),
                match_method="unmatched",
                confidence=0,
                warnings=("matcher_error",),
            )
        candidates.append(TrafficEdgeCandidate(segment=segment, match=match))
    return snapshot, tuple(candidates)


def _overlay_executor(settings, *, state_store: JsonTrafficStateStore):
    return TrafficOverlayExecutor(
        writer=NativeValhallaTrafficWriter(
            executable_path=settings.traffic_openlr_decoder_path,
            traffic_extract=Path(settings.valhalla_traffic_extract),
            timeout_seconds=settings.traffic_writer_timeout_seconds,
        ),
        state_store=state_store,
    )


def _require_overlay_writer(settings) -> None:
    if not settings.traffic_valhalla_overlay_enabled:
        raise ValueError(
            "traffic_valhalla_overlay_enabled must be true for overlay mutation"
        )
    if not settings.traffic_valhalla_tileset_version:
        raise ValueError("traffic_valhalla_tileset_version is required")
    if not settings.traffic_openlr_decoder_path:
        raise ValueError("native Valhalla traffic helper is required")


async def _run_updater(settings) -> int:
    logger = logging.getLogger("compass.traffic.updater")
    provider = build_traffic_provider(settings)
    if provider is None:
        logger.info(
            "traffic updater idle because traffic is disabled",
            extra={"traffic_enabled": False},
        )
        while True:
            await asyncio.sleep(settings.traffic_refresh_seconds)

    if settings.traffic_valhalla_overlay_enabled:
        _require_overlay_writer(settings)

    while True:
        fetch_started_at = datetime.now(UTC)
        try:
            if settings.traffic_valhalla_overlay_enabled:
                result = await _run_updater_cycle(settings, provider=provider)
                logger.info(
                    "traffic overlay update committed",
                    extra=asdict(result),
                )
                _persist_runtime_health_safely(
                    settings,
                    logger=logger,
                    value=_successful_runtime_health(
                        settings,
                        result=result,
                        fetch_started_at=fetch_started_at,
                        fetch_completed_at=datetime.now(UTC),
                    ),
                )
            else:
                snapshot = await provider.fetch_flow()
                logger.info(
                    "traffic provider snapshot normalized in read-only mode",
                    extra={
                        "provider": snapshot.provider,
                        "observed_at": snapshot.observed_at.isoformat(),
                        "segments_normalized": len(snapshot.segments),
                        "segments_received": (
                            snapshot.metrics.provider_segments_received
                        ),
                        "rejected_segments": snapshot.metrics.rejected_segments,
                        "stale_segments": snapshot.metrics.stale_segments,
                        "traffic_overlay_writer": "disabled",
                    },
                )
        except Exception as error:
            expired_edges = 0
            if settings.traffic_valhalla_overlay_enabled:
                try:
                    expired_edges = _expire_managed_edges(
                        settings, evaluated_at=datetime.now(UTC)
                    )
                except Exception as expiry_error:
                    logger.error(
                        "traffic expiry cleanup failed; operator attention is required",
                        extra={"error_type": type(expiry_error).__name__},
                        exc_info=True,
                    )
            logger.warning(
                "traffic provider update failed; routing remains available without new traffic",
                extra={
                    "error_type": type(error).__name__,
                    "expired_edges_reset": expired_edges,
                },
                exc_info=True,
            )
            if settings.traffic_valhalla_overlay_enabled:
                _persist_runtime_health_safely(
                    settings,
                    logger=logger,
                    value=_failed_runtime_health(
                        settings,
                        fetch_started_at=fetch_started_at,
                        fetch_completed_at=datetime.now(UTC),
                        error=error,
                        edges_expired=expired_edges,
                    ),
                )
        await asyncio.sleep(settings.traffic_refresh_seconds)


async def _run_updater_cycle(
    settings,
    *,
    provider,
    evaluated_at: datetime | None = None,
    fetch_request: TrafficFetchRequest | None = None,
) -> TrafficUpdaterCycleResult:
    _require_overlay_writer(settings)
    state_store = JsonTrafficStateStore(Path(settings.traffic_state_path))
    previous_state = state_store.load(
        expected_tileset_identity=settings.traffic_valhalla_tileset_version
    )
    snapshot, candidates = await _fetch_and_match(
        settings,
        provider=provider,
        limit=settings.traffic_update_segment_limit,
        fetch_request=fetch_request,
    )
    evaluated_at = evaluated_at or datetime.now(UTC)
    plan = TrafficOverlayPlanner(
        state=previous_state,
        quality_policy=traffic_quality_policy(settings),
    ).plan(candidates, evaluated_at=evaluated_at)
    edges_set = 0
    edges_reset = 0
    if plan.set_updates or plan.reset_graph_ids:
        receipt = _overlay_executor(settings, state_store=state_store).execute(
            previous_state=previous_state,
            plan=plan,
        )
        edges_set = receipt.write.set_count
        edges_reset = receipt.write.reset_count
    unmatched = sum(not candidate.match.directed_edge_ids for candidate in candidates)
    mapping_versions = sorted(
        {edge.mapping_version for edge in plan.resulting_state.edges}
    )
    return TrafficUpdaterCycleResult(
        provider=snapshot.provider,
        observed_at=snapshot.observed_at,
        segments_received=snapshot.metrics.provider_segments_received,
        segments_normalized=len(snapshot.segments),
        segments_considered=len(candidates),
        segments_accepted=len(candidates) - len(plan.rejected_segments),
        segments_unmatched=unmatched,
        provider_rejected_segments=snapshot.metrics.rejected_segments,
        provider_stale_segments=snapshot.metrics.stale_segments,
        provider_api_errors=snapshot.metrics.api_errors,
        edges_set=edges_set,
        edges_reset=edges_reset,
        managed_edge_count=len(plan.resulting_state.edges),
        mapping_version=(",".join(mapping_versions) if mapping_versions else None),
    )


def _expire_managed_edges(settings, *, evaluated_at: datetime) -> int:
    """Reset only expired Compass-owned edges after a failed provider cycle."""
    state_store = JsonTrafficStateStore(Path(settings.traffic_state_path))
    previous_state = state_store.load(
        expected_tileset_identity=settings.traffic_valhalla_tileset_version
    )
    plan = TrafficOverlayPlanner(
        state=previous_state,
        quality_policy=traffic_quality_policy(settings),
    ).plan((), evaluated_at=evaluated_at)
    if not plan.reset_graph_ids:
        return 0
    receipt = _overlay_executor(settings, state_store=state_store).execute(
        previous_state=previous_state,
        plan=plan,
    )
    return receipt.write.reset_count


def _successful_runtime_health(
    settings,
    *,
    result: TrafficUpdaterCycleResult,
    fetch_started_at: datetime,
    fetch_completed_at: datetime,
) -> TrafficRuntimeHealth:
    previous = _load_runtime_health(settings)
    return TrafficRuntimeHealth(
        provider=result.provider,
        provider_status="mock" if result.provider == "mock" else "fresh",
        last_fetch_started_at=fetch_started_at,
        last_fetch_completed_at=fetch_completed_at,
        last_success_at=fetch_completed_at,
        feed_observed_at=result.observed_at,
        provider_segments_received=result.segments_received,
        segments_normalized=result.segments_normalized,
        segments_matched=result.segments_considered - result.segments_unmatched,
        segments_unmatched=result.segments_unmatched,
        edges_updated=result.edges_set,
        edges_expired=result.edges_reset,
        provider_api_errors=(
            (previous.provider_api_errors if previous is not None else 0)
            + result.provider_api_errors
        ),
        updater_consecutive_failures=0,
        managed_edge_count=result.managed_edge_count,
        mapping_version=result.mapping_version or settings.traffic_mapping_version,
        valhalla_tileset_version=settings.traffic_valhalla_tileset_version,
        message=(
            "mock traffic overlay update committed"
            if result.provider == "mock"
            else "live traffic overlay update committed"
        ),
    )


def _failed_runtime_health(
    settings,
    *,
    fetch_started_at: datetime,
    fetch_completed_at: datetime,
    error: Exception,
    edges_expired: int,
) -> TrafficRuntimeHealth:
    previous = _load_runtime_health(settings)
    managed_edge_count = _managed_edge_count(settings)
    return TrafficRuntimeHealth(
        provider=settings.traffic_provider,
        provider_status="unavailable",
        last_fetch_started_at=fetch_started_at,
        last_fetch_completed_at=fetch_completed_at,
        last_success_at=previous.last_success_at if previous is not None else None,
        feed_observed_at=previous.feed_observed_at if previous is not None else None,
        provider_segments_received=(
            previous.provider_segments_received if previous is not None else 0
        ),
        segments_normalized=previous.segments_normalized if previous is not None else 0,
        segments_matched=previous.segments_matched if previous is not None else 0,
        segments_unmatched=previous.segments_unmatched if previous is not None else 0,
        edges_updated=0,
        edges_expired=edges_expired,
        provider_api_errors=(previous.provider_api_errors if previous is not None else 0)
        + 1,
        updater_consecutive_failures=(
            previous.updater_consecutive_failures if previous is not None else 0
        )
        + 1,
        managed_edge_count=managed_edge_count,
        mapping_version=(
            previous.mapping_version
            if previous is not None
            else settings.traffic_mapping_version
        ),
        valhalla_tileset_version=settings.traffic_valhalla_tileset_version,
        message=(
            "traffic provider update failed; Valhalla remains available and "
            f"expired traffic was reset ({type(error).__name__})"
        ),
    )


def _cleared_runtime_health(settings, *, edges_reset: int) -> TrafficRuntimeHealth:
    previous = _load_runtime_health(settings)
    now = datetime.now(UTC)
    return TrafficRuntimeHealth(
        provider=settings.traffic_provider,
        provider_status="unavailable",
        last_fetch_started_at=(
            previous.last_fetch_started_at if previous is not None else now
        ),
        last_fetch_completed_at=(
            previous.last_fetch_completed_at if previous is not None else now
        ),
        last_success_at=previous.last_success_at if previous is not None else None,
        feed_observed_at=previous.feed_observed_at if previous is not None else None,
        provider_segments_received=(
            previous.provider_segments_received if previous is not None else 0
        ),
        segments_normalized=previous.segments_normalized if previous is not None else 0,
        segments_matched=previous.segments_matched if previous is not None else 0,
        segments_unmatched=previous.segments_unmatched if previous is not None else 0,
        edges_updated=0,
        edges_expired=edges_reset,
        provider_api_errors=previous.provider_api_errors if previous is not None else 0,
        updater_consecutive_failures=0,
        managed_edge_count=0,
        mapping_version=(
            previous.mapping_version
            if previous is not None
            else settings.traffic_mapping_version
        ),
        valhalla_tileset_version=settings.traffic_valhalla_tileset_version,
        message="managed live traffic cleared; Valhalla fallback speeds are active",
    )


def _load_runtime_health(settings) -> TrafficRuntimeHealth | None:
    try:
        return JsonTrafficRuntimeHealthStore(Path(settings.traffic_health_path)).load(
            expected_provider=settings.traffic_provider,
            expected_tileset_identity=settings.traffic_valhalla_tileset_version,
        )
    except TrafficRuntimeHealthError:
        return None


def _managed_edge_count(settings) -> int:
    try:
        return len(
            JsonTrafficStateStore(Path(settings.traffic_state_path))
            .load(expected_tileset_identity=settings.traffic_valhalla_tileset_version)
            .edges
        )
    except Exception:
        return 0


def _persist_runtime_health_safely(
    settings,
    *,
    logger: logging.Logger,
    value: TrafficRuntimeHealth,
) -> None:
    try:
        JsonTrafficRuntimeHealthStore(Path(settings.traffic_health_path)).save(value)
    except TrafficRuntimeHealthError:
        logger.error(
            "traffic runtime health could not be persisted; overlay state is unchanged",
            exc_info=True,
        )


def _print(value: object) -> None:
    print(json.dumps(_jsonable(value), indent=2, sort_keys=True))


def _jsonable(value: object) -> object:
    if hasattr(value, "isoformat"):
        return value.isoformat()  # type: ignore[no-any-return]
    if hasattr(value, "__dataclass_fields__"):
        return _jsonable(asdict(value))
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, list | tuple):
        return [_jsonable(item) for item in value]
    return value


if __name__ == "__main__":
    raise SystemExit(main())
