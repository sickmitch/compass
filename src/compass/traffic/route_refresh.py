from __future__ import annotations

import hashlib
import json
import logging
import os
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from math import asin, cos, radians, sin, sqrt
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Literal, Protocol

import httpx

from compass.candidates.geometry import decode_polyline6
from compass.routing.domain import Coordinate
from compass.traffic.domain import TrafficRefreshTrigger

TrafficRouteRefreshState = Literal[
    "updated",
    "skipped_recent",
    "disabled",
    "unavailable",
]


@dataclass(frozen=True, slots=True)
class TrafficRouteRefreshResult:
    state: TrafficRouteRefreshState
    scope_key: str
    probe_count: int = 0
    last_success_at: datetime | None = None
    next_allowed_at: datetime | None = None
    message: str | None = None

    @property
    def overlay_changed(self) -> bool:
        return self.state == "updated"


class TrafficRouteRefresher(Protocol):
    async def refresh(
        self,
        *,
        scope_key: str,
        trigger: TrafficRefreshTrigger,
        encoded_polylines: tuple[str, ...],
    ) -> TrafficRouteRefreshResult: ...


class DisabledTrafficRouteRefresher:
    async def refresh(
        self,
        *,
        scope_key: str,
        trigger: TrafficRefreshTrigger,
        encoded_polylines: tuple[str, ...],
    ) -> TrafficRouteRefreshResult:
        return TrafficRouteRefreshResult(state="disabled", scope_key=scope_key)


class HttpTrafficRouteRefresher:
    """Best-effort client for the private traffic-updater service."""

    def __init__(
        self,
        *,
        base_url: str,
        timeout_seconds: float,
        user_agent: str,
        client: httpx.AsyncClient,
    ) -> None:
        self._url = f"{base_url.rstrip('/')}/internal/v1/traffic/route-refresh"
        self._timeout = httpx.Timeout(timeout_seconds)
        self._headers = {"user-agent": user_agent}
        self._client = client

    async def refresh(
        self,
        *,
        scope_key: str,
        trigger: TrafficRefreshTrigger,
        encoded_polylines: tuple[str, ...],
    ) -> TrafficRouteRefreshResult:
        try:
            response = await self._client.post(
                self._url,
                json={
                    "scope_key": scope_key,
                    "trigger": trigger,
                    "encoded_polylines": list(encoded_polylines),
                },
                headers=self._headers,
                timeout=self._timeout,
            )
            response.raise_for_status()
            value = response.json()
            return _parse_refresh_result(value, expected_scope_key=scope_key)
        except (httpx.HTTPError, ValueError, TypeError, KeyError):
            logging.getLogger("compass.traffic.refresh").warning(
                "route-scoped traffic refresh unavailable; using existing Valhalla speeds",
                extra={"traffic_scope_key": scope_key},
            )
            return TrafficRouteRefreshResult(
                state="unavailable",
                scope_key=scope_key,
                message="traffic updater unavailable",
            )


def route_scope_key(
    *,
    origin: Coordinate,
    destination: Coordinate,
    waypoints: tuple[Coordinate, ...] = (),
    costing: str,
) -> str:
    coordinates = (origin, *waypoints, destination)
    canonical = {
        "costing": costing,
        "coordinates": [
            [round(point.latitude, 5), round(point.longitude, 5)]
            for point in coordinates
        ],
    }
    payload = json.dumps(canonical, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def sample_route_probe_points(
    encoded_polylines: tuple[str, ...],
    *,
    spacing_km: float,
    max_probes: int,
    max_geometry_points: int,
) -> tuple[Coordinate, ...]:
    if not encoded_polylines:
        raise ValueError("at least one route geometry is required")
    if spacing_km <= 0:
        raise ValueError("traffic route probe spacing must be positive")
    if max_probes < 2:
        raise ValueError("traffic route maximum probes must be at least two")

    route: list[Coordinate] = []
    for encoded in encoded_polylines:
        leg = decode_polyline6(encoded, max_points=max_geometry_points)
        if route and route[-1] == leg[0]:
            route.extend(leg[1:])
        else:
            route.extend(leg)
    if len(route) < 2:
        raise ValueError("route geometry must contain at least two points")

    cumulative = [0.0]
    for previous, current in zip(route, route[1:], strict=False):
        cumulative.append(cumulative[-1] + _distance_km(previous, current))
    total_km = cumulative[-1]
    if total_km == 0:
        return (route[0], route[-1])

    targets = [0.0]
    next_distance = spacing_km
    while next_distance < total_km:
        targets.append(next_distance)
        next_distance += spacing_km
    targets.append(total_km)
    if len(targets) > max_probes:
        targets = [
            total_km * index / (max_probes - 1)
            for index in range(max_probes)
        ]

    probes: list[Coordinate] = []
    segment_index = 1
    for target in targets:
        while segment_index < len(cumulative) - 1 and cumulative[segment_index] < target:
            segment_index += 1
        lower_distance = cumulative[segment_index - 1]
        upper_distance = cumulative[segment_index]
        fraction = (
            0.0
            if upper_distance == lower_distance
            else (target - lower_distance) / (upper_distance - lower_distance)
        )
        lower = route[segment_index - 1]
        upper = route[segment_index]
        point = Coordinate(
            latitude=lower.latitude + (upper.latitude - lower.latitude) * fraction,
            longitude=lower.longitude + (upper.longitude - lower.longitude) * fraction,
        )
        if not probes or _distance_km(probes[-1], point) > 0.001:
            probes.append(point)
    return tuple(probes)


@dataclass(frozen=True, slots=True)
class RouteRefreshLedger:
    tileset_identity: str
    successes: dict[str, datetime]


class JsonRouteRefreshLedgerStore:
    def __init__(self, path: Path) -> None:
        self._path = path

    def load(self, *, expected_tileset_identity: str) -> RouteRefreshLedger:
        if not self._path.exists():
            return RouteRefreshLedger(expected_tileset_identity, {})
        try:
            value = json.loads(self._path.read_text(encoding="utf-8"))
            if value.get("schema_version") != 1:
                raise ValueError("unsupported traffic refresh ledger schema")
            if value.get("tileset_identity") != expected_tileset_identity:
                raise ValueError("traffic refresh ledger belongs to another tileset")
            raw_successes = value.get("successes")
            if not isinstance(raw_successes, dict):
                raise ValueError("traffic refresh ledger successes must be an object")
            successes = {
                str(key): _parse_utc_datetime(timestamp)
                for key, timestamp in raw_successes.items()
            }
        except (OSError, json.JSONDecodeError, TypeError, ValueError) as error:
            raise ValueError("traffic refresh ledger is invalid") from error
        return RouteRefreshLedger(expected_tileset_identity, successes)

    def save(self, value: RouteRefreshLedger) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schema_version": 1,
            "tileset_identity": value.tileset_identity,
            "successes": {
                key: timestamp.astimezone(UTC).isoformat()
                for key, timestamp in sorted(value.successes.items())
            },
        }
        try:
            with NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=self._path.parent,
                prefix=f".{self._path.name}.",
                delete=False,
            ) as temporary:
                json.dump(payload, temporary, indent=2, sort_keys=True)
                temporary.write("\n")
                temporary.flush()
                os.fsync(temporary.fileno())
                temporary_path = Path(temporary.name)
            temporary_path.replace(self._path)
        except OSError as error:
            raise ValueError("traffic refresh ledger could not be saved") from error


def recent_refresh(
    ledger: RouteRefreshLedger,
    *,
    scope_key: str,
    evaluated_at: datetime,
    minimum_interval_seconds: float,
) -> TrafficRouteRefreshResult | None:
    last_success = ledger.successes.get(scope_key)
    if last_success is None:
        return None
    next_allowed = last_success + timedelta(seconds=minimum_interval_seconds)
    if evaluated_at >= next_allowed:
        return None
    return TrafficRouteRefreshResult(
        state="skipped_recent",
        scope_key=scope_key,
        last_success_at=last_success,
        next_allowed_at=next_allowed,
        message="route traffic was refreshed less than five minutes ago",
    )


def record_refresh_success(
    ledger: RouteRefreshLedger,
    *,
    scope_key: str,
    completed_at: datetime,
    retention_seconds: float,
) -> RouteRefreshLedger:
    cutoff = completed_at - timedelta(seconds=retention_seconds)
    successes = {
        key: timestamp
        for key, timestamp in ledger.successes.items()
        if timestamp >= cutoff
    }
    successes[scope_key] = completed_at.astimezone(UTC)
    return RouteRefreshLedger(ledger.tileset_identity, successes)


def _parse_refresh_result(
    value: object, *, expected_scope_key: str
) -> TrafficRouteRefreshResult:
    if not isinstance(value, dict):
        raise ValueError("traffic refresh response must be an object")
    state = value["state"]
    if state not in {"updated", "skipped_recent", "disabled", "unavailable"}:
        raise ValueError("invalid traffic refresh state")
    if value["scope_key"] != expected_scope_key:
        raise ValueError("traffic refresh scope mismatch")
    return TrafficRouteRefreshResult(
        state=state,
        scope_key=expected_scope_key,
        probe_count=int(value.get("probe_count", 0)),
        last_success_at=_optional_datetime(value.get("last_success_at")),
        next_allowed_at=_optional_datetime(value.get("next_allowed_at")),
        message=value.get("message"),
    )


def _optional_datetime(value: object) -> datetime | None:
    if value is None:
        return None
    return _parse_utc_datetime(value)


def _parse_utc_datetime(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError("traffic refresh timestamp must be a string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("traffic refresh timestamp must include an offset")
    return parsed.astimezone(UTC)


def _distance_km(first: Coordinate, second: Coordinate) -> float:
    first_latitude = radians(first.latitude)
    second_latitude = radians(second.latitude)
    latitude_delta = second_latitude - first_latitude
    longitude_delta = radians(second.longitude - first.longitude)
    haversine = (
        sin(latitude_delta / 2) ** 2
        + cos(first_latitude) * cos(second_latitude) * sin(longitude_delta / 2) ** 2
    )
    return 6371.0088 * 2 * asin(min(1.0, sqrt(haversine)))
