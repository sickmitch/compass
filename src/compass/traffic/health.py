from __future__ import annotations

import json
import os
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from tempfile import NamedTemporaryFile

from compass.traffic.domain import TrafficHealth, TrafficHealthState

HEALTH_SCHEMA_VERSION = 1


class TrafficRuntimeHealthError(RuntimeError):
    """Runtime health is malformed or belongs to another provider/tileset."""


@dataclass(frozen=True, slots=True)
class TrafficRuntimeHealth:
    provider: str
    provider_status: TrafficHealthState
    last_fetch_started_at: datetime
    last_fetch_completed_at: datetime
    last_success_at: datetime | None
    feed_observed_at: datetime | None
    provider_segments_received: int
    segments_normalized: int
    segments_matched: int
    segments_unmatched: int
    edges_updated: int
    edges_expired: int
    provider_api_errors: int
    updater_consecutive_failures: int
    managed_edge_count: int
    mapping_version: str | None
    valhalla_tileset_version: str
    message: str

    def __post_init__(self) -> None:
        if not self.provider or not self.valhalla_tileset_version:
            raise ValueError("traffic runtime provider and tileset must not be empty")
        if self.provider_status not in {"fresh", "stale", "unavailable", "mock"}:
            raise ValueError("unsupported runtime traffic health state")
        for field_name in (
            "last_fetch_started_at",
            "last_fetch_completed_at",
            "last_success_at",
            "feed_observed_at",
        ):
            value = getattr(self, field_name)
            if value is not None:
                object.__setattr__(self, field_name, _aware_utc(value))
        if self.last_fetch_completed_at < self.last_fetch_started_at:
            raise ValueError("traffic fetch completion precedes its start")
        counts = (
            self.provider_segments_received,
            self.segments_normalized,
            self.segments_matched,
            self.segments_unmatched,
            self.edges_updated,
            self.edges_expired,
            self.provider_api_errors,
            self.updater_consecutive_failures,
            self.managed_edge_count,
        )
        if any(value < 0 for value in counts):
            raise ValueError("traffic runtime counters must not be negative")

    def as_health(
        self,
        *,
        evaluated_at: datetime,
        max_age_seconds: float,
        traffic_extract_path: str,
    ) -> TrafficHealth:
        evaluated_at = _aware_utc(evaluated_at)
        feed_age_seconds = None
        provider_status = self.provider_status
        if self.feed_observed_at is not None:
            feed_age_seconds = max(
                0.0, (evaluated_at - self.feed_observed_at).total_seconds()
            )
            if provider_status in {"fresh", "mock"} and feed_age_seconds > max_age_seconds:
                provider_status = "stale"
        return TrafficHealth(
            enabled=True,
            provider=self.provider,
            provider_status=provider_status,
            traffic_aware_routing=True,
            last_fetch_started_at=self.last_fetch_started_at,
            last_fetch_completed_at=self.last_fetch_completed_at,
            last_success_at=self.last_success_at,
            provider_segments_received=self.provider_segments_received,
            segments_normalized=self.segments_normalized,
            segments_matched=self.segments_matched,
            segments_unmatched=self.segments_unmatched,
            edges_updated=self.edges_updated,
            edges_expired=self.edges_expired,
            provider_api_errors=self.provider_api_errors,
            updater_consecutive_failures=self.updater_consecutive_failures,
            managed_edge_count=self.managed_edge_count,
            feed_age_seconds=feed_age_seconds,
            mapping_version=self.mapping_version,
            valhalla_tileset_version=self.valhalla_tileset_version,
            traffic_extract_path=traffic_extract_path,
            message=self.message,
        )


class JsonTrafficRuntimeHealthStore:
    def __init__(self, path: Path) -> None:
        self._path = path

    def load(
        self,
        *,
        expected_provider: str,
        expected_tileset_identity: str,
    ) -> TrafficRuntimeHealth | None:
        if not self._path.exists():
            return None
        try:
            value = _from_json(json.loads(self._path.read_text(encoding="utf-8")))
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise TrafficRuntimeHealthError("traffic runtime health is malformed") from error
        if value.provider != expected_provider:
            raise TrafficRuntimeHealthError("traffic runtime provider differs from configuration")
        if value.valhalla_tileset_version != expected_tileset_identity:
            raise TrafficRuntimeHealthError("traffic runtime health belongs to another tileset")
        return value

    def save(self, value: TrafficRuntimeHealth) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(_to_json(value), indent=2, sort_keys=True) + "\n"
        temporary_path: Path | None = None
        try:
            with NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=self._path.parent,
                prefix=f".{self._path.name}.",
                delete=False,
            ) as handle:
                temporary_path = Path(handle.name)
                os.fchmod(handle.fileno(), 0o644)
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            temporary_path.replace(self._path)
        except OSError as error:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
            raise TrafficRuntimeHealthError(
                "traffic runtime health could not be saved"
            ) from error


def _to_json(value: TrafficRuntimeHealth) -> dict[str, object]:
    return {
        "schema_version": HEALTH_SCHEMA_VERSION,
        "provider": value.provider,
        "provider_status": value.provider_status,
        "last_fetch_started_at": value.last_fetch_started_at.isoformat(),
        "last_fetch_completed_at": value.last_fetch_completed_at.isoformat(),
        "last_success_at": _iso(value.last_success_at),
        "feed_observed_at": _iso(value.feed_observed_at),
        "provider_segments_received": value.provider_segments_received,
        "segments_normalized": value.segments_normalized,
        "segments_matched": value.segments_matched,
        "segments_unmatched": value.segments_unmatched,
        "edges_updated": value.edges_updated,
        "edges_expired": value.edges_expired,
        "provider_api_errors": value.provider_api_errors,
        "updater_consecutive_failures": value.updater_consecutive_failures,
        "managed_edge_count": value.managed_edge_count,
        "mapping_version": value.mapping_version,
        "valhalla_tileset_version": value.valhalla_tileset_version,
        "message": value.message,
    }


def _from_json(payload: object) -> TrafficRuntimeHealth:
    if not isinstance(payload, dict) or payload.get("schema_version") != HEALTH_SCHEMA_VERSION:
        raise ValueError("unsupported traffic runtime health schema")
    return TrafficRuntimeHealth(
        provider=_string(payload, "provider"),
        provider_status=_string(payload, "provider_status"),  # type: ignore[arg-type]
        last_fetch_started_at=_datetime(payload, "last_fetch_started_at"),
        last_fetch_completed_at=_datetime(payload, "last_fetch_completed_at"),
        last_success_at=_optional_datetime(payload, "last_success_at"),
        feed_observed_at=_optional_datetime(payload, "feed_observed_at"),
        provider_segments_received=_integer(payload, "provider_segments_received"),
        segments_normalized=_integer(payload, "segments_normalized"),
        segments_matched=_integer(payload, "segments_matched"),
        segments_unmatched=_integer(payload, "segments_unmatched"),
        edges_updated=_integer(payload, "edges_updated"),
        edges_expired=_integer(payload, "edges_expired"),
        provider_api_errors=_integer(payload, "provider_api_errors"),
        updater_consecutive_failures=_integer(payload, "updater_consecutive_failures"),
        managed_edge_count=_integer(payload, "managed_edge_count"),
        mapping_version=_optional_string(payload, "mapping_version"),
        valhalla_tileset_version=_string(payload, "valhalla_tileset_version"),
        message=_string(payload, "message"),
    )


def _string(value: dict[str, object], field: str) -> str:
    item = value.get(field)
    if not isinstance(item, str) or not item:
        raise TypeError(f"{field} must be a non-empty string")
    return item


def _optional_string(value: dict[str, object], field: str) -> str | None:
    item = value.get(field)
    if item is not None and not isinstance(item, str):
        raise TypeError(f"{field} must be a string or null")
    return item


def _integer(value: dict[str, object], field: str) -> int:
    item = value.get(field)
    if not isinstance(item, int) or isinstance(item, bool):
        raise TypeError(f"{field} must be an integer")
    return item


def _datetime(value: dict[str, object], field: str) -> datetime:
    return datetime.fromisoformat(_string(value, field))


def _optional_datetime(value: dict[str, object], field: str) -> datetime | None:
    item = value.get(field)
    if item is None:
        return None
    if not isinstance(item, str):
        raise TypeError(f"{field} must be a timestamp or null")
    return datetime.fromisoformat(item)


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("traffic runtime timestamps must include a UTC offset")
    return value.astimezone(UTC)


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value is not None else None
