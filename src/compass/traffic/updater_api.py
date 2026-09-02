from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager, suppress
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field

from compass.config import get_settings
from compass.logging import configure_logging
from compass.traffic.cli import (
    _expire_managed_edges,
    _failed_runtime_health,
    _load_runtime_health,
    _managed_edge_count,
    _persist_runtime_health_safely,
    _run_updater_cycle,
    _successful_runtime_health,
)
from compass.traffic.domain import TrafficFetchRequest, TrafficRefreshTrigger
from compass.traffic.route_refresh import (
    JsonRouteRefreshLedgerStore,
    TrafficRouteRefreshResult,
    recent_refresh,
    record_refresh_success,
    sample_route_probe_points,
)
from compass.traffic.service import build_traffic_provider

logger = logging.getLogger("compass.traffic.on_demand")
_refresh_lock = asyncio.Lock()


class RouteTrafficRefreshRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scope_key: str = Field(pattern=r"^[a-f0-9]{64}$")
    trigger: TrafficRefreshTrigger
    encoded_polylines: list[str] = Field(min_length=1, max_length=33)


class RouteTrafficRefreshResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: Literal["updated", "skipped_recent", "disabled", "unavailable"]
    scope_key: str
    probe_count: int = Field(ge=0)
    last_success_at: datetime | None = None
    next_allowed_at: datetime | None = None
    message: str | None = None


@asynccontextmanager
async def lifespan(_app: FastAPI):
    settings = get_settings()
    configure_logging(settings.log_level)
    expiry_task = asyncio.create_task(_expiry_loop())
    try:
        yield
    finally:
        expiry_task.cancel()
        with suppress(asyncio.CancelledError):
            await expiry_task


app = FastAPI(
    title="Compass internal traffic updater",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)


@app.get("/health/live")
async def live() -> dict[str, str]:
    return {"status": "ok", "service": "compass-traffic-updater"}


@app.post(
    "/internal/v1/traffic/route-refresh",
    response_model=RouteTrafficRefreshResponse,
)
async def refresh_route(
    request: RouteTrafficRefreshRequest,
) -> RouteTrafficRefreshResponse:
    settings = get_settings()
    if (
        not settings.traffic_enabled
        or not settings.traffic_valhalla_overlay_enabled
        or settings.traffic_refresh_mode != "on_demand"
    ):
        return _response(
            TrafficRouteRefreshResult(
                state="disabled",
                scope_key=request.scope_key,
                message="on-demand traffic refresh is disabled",
            )
        )

    async with _refresh_lock:
        evaluated_at = datetime.now(UTC)
        store = JsonRouteRefreshLedgerStore(
            Path(settings.traffic_refresh_ledger_path)
        )
        try:
            ledger = store.load(
                expected_tileset_identity=settings.traffic_valhalla_tileset_version
            )
        except ValueError as error:
            logger.error(
                "traffic refresh ledger is invalid or belongs to another tileset",
                extra={"error_type": type(error).__name__},
            )
            return _response(
                TrafficRouteRefreshResult(
                    state="unavailable",
                    scope_key=request.scope_key,
                    message="traffic refresh ledger is invalid",
                )
            )

        recent = recent_refresh(
            ledger,
            scope_key=request.scope_key,
            evaluated_at=evaluated_at,
            minimum_interval_seconds=(
                settings.traffic_route_refresh_min_interval_seconds
            ),
        )
        if recent is not None:
            logger.info(
                "route-scoped traffic refresh skipped by minimum interval",
                extra={
                    "traffic_scope_key": request.scope_key,
                    "traffic_refresh_trigger": request.trigger,
                },
            )
            return _response(recent)

        try:
            probes = sample_route_probe_points(
                tuple(request.encoded_polylines),
                spacing_km=settings.traffic_route_probe_spacing_km,
                max_probes=settings.traffic_route_max_probes,
                max_geometry_points=settings.route_geometry_max_points,
            )
            provider = build_traffic_provider(settings)
            if provider is None:
                return _response(
                    TrafficRouteRefreshResult(
                        state="disabled",
                        scope_key=request.scope_key,
                        message="traffic provider is disabled",
                    )
                )
            fetch_started_at = datetime.now(UTC)
            result = await _run_updater_cycle(
                settings,
                provider=provider,
                fetch_request=TrafficFetchRequest(
                    scope_key=request.scope_key,
                    trigger=request.trigger,
                    probe_points=probes,
                ),
            )
            completed_at = datetime.now(UTC)
            store.save(
                record_refresh_success(
                    ledger,
                    scope_key=request.scope_key,
                    completed_at=completed_at,
                    retention_seconds=max(
                        86_400,
                        settings.traffic_route_refresh_min_interval_seconds * 4,
                    ),
                )
            )
            _persist_runtime_health_safely(
                settings,
                logger=logger,
                value=_successful_runtime_health(
                    settings,
                    result=result,
                    fetch_started_at=fetch_started_at,
                    fetch_completed_at=completed_at,
                ),
            )
            logger.info(
                "route-scoped traffic overlay update committed",
                extra={
                    "traffic_scope_key": request.scope_key,
                    "traffic_refresh_trigger": request.trigger,
                    "traffic_probe_count": len(probes),
                    "edges_updated": result.edges_set,
                    "managed_edge_count": result.managed_edge_count,
                },
            )
            return _response(
                TrafficRouteRefreshResult(
                    state="updated",
                    scope_key=request.scope_key,
                    probe_count=len(probes),
                    last_success_at=completed_at,
                    next_allowed_at=completed_at
                    + timedelta(
                        seconds=settings.traffic_route_refresh_min_interval_seconds
                    ),
                    message="route-scoped live traffic overlay update committed",
                )
            )
        except Exception as error:
            completed_at = datetime.now(UTC)
            edges_expired = 0
            try:
                edges_expired = _expire_managed_edges(
                    settings, evaluated_at=completed_at
                )
            except Exception:
                logger.error("traffic expiry cleanup failed", exc_info=True)
            _persist_runtime_health_safely(
                settings,
                logger=logger,
                value=_failed_runtime_health(
                    settings,
                    fetch_started_at=evaluated_at,
                    fetch_completed_at=completed_at,
                    error=error,
                    edges_expired=edges_expired,
                ),
            )
            logger.warning(
                "route-scoped traffic refresh failed; Valhalla fallback remains available",
                extra={
                    "traffic_scope_key": request.scope_key,
                    "traffic_refresh_trigger": request.trigger,
                    "error_type": type(error).__name__,
                },
                exc_info=True,
            )
            return _response(
                TrafficRouteRefreshResult(
                    state="unavailable",
                    scope_key=request.scope_key,
                    message="traffic refresh failed; fallback speeds remain active",
                )
            )


async def _expiry_loop() -> None:
    settings = get_settings()
    while True:
        await asyncio.sleep(settings.traffic_expiry_sweep_seconds)
        if (
            not settings.traffic_enabled
            or not settings.traffic_valhalla_overlay_enabled
            or settings.traffic_refresh_mode != "on_demand"
        ):
            continue
        async with _refresh_lock:
            try:
                expired = _expire_managed_edges(
                    settings, evaluated_at=datetime.now(UTC)
                )
                if expired:
                    previous_health = _load_runtime_health(settings)
                    if previous_health is not None:
                        _persist_runtime_health_safely(
                            settings,
                            logger=logger,
                            value=replace(
                                previous_health,
                                edges_updated=0,
                                edges_expired=expired,
                                managed_edge_count=_managed_edge_count(settings),
                                message=(
                                    "expired live traffic reset to Valhalla UNKNOWN; "
                                    "no provider request was made"
                                ),
                            ),
                        )
                    logger.info(
                        "expired live traffic reset to Valhalla UNKNOWN",
                        extra={"edges_expired": expired},
                    )
            except Exception:
                logger.error(
                    "traffic expiry sweep failed; operator attention is required",
                    exc_info=True,
                )


def _response(value: TrafficRouteRefreshResult) -> RouteTrafficRefreshResponse:
    return RouteTrafficRefreshResponse(
        state=value.state,
        scope_key=value.scope_key,
        probe_count=value.probe_count,
        last_success_at=value.last_success_at,
        next_allowed_at=value.next_allowed_at,
        message=value.message,
    )
