from __future__ import annotations

import stat
from datetime import UTC, datetime, timedelta

import pytest

from compass.config import Settings
from compass.traffic.health import (
    JsonTrafficRuntimeHealthStore,
    TrafficRuntimeHealth,
    TrafficRuntimeHealthError,
)
from compass.traffic.service import traffic_health_from_settings

TILESET = "valhalla-3.8.3:123"


def test_runtime_health_store_round_trips_atomically_and_is_api_readable(tmp_path) -> None:
    path = tmp_path / "health.json"
    store = JsonTrafficRuntimeHealthStore(path)
    expected = _runtime_health()

    store.save(expected)

    actual = store.load(
        expected_provider="tomtom",
        expected_tileset_identity=TILESET,
    )
    assert actual == expected
    assert stat.S_IMODE(path.stat().st_mode) == 0o644


def test_runtime_health_rejects_another_tileset(tmp_path) -> None:
    store = JsonTrafficRuntimeHealthStore(tmp_path / "health.json")
    store.save(_runtime_health())

    with pytest.raises(TrafficRuntimeHealthError, match="another tileset"):
        store.load(
            expected_provider="tomtom",
            expected_tileset_identity="valhalla-3.8.3:new-graph",
        )


def test_runtime_health_becomes_stale_without_changing_the_persisted_snapshot() -> None:
    runtime = _runtime_health()

    health = runtime.as_health(
        evaluated_at=runtime.feed_observed_at + timedelta(seconds=301),  # type: ignore[operator]
        max_age_seconds=300,
        traffic_extract_path="/custom_files/traffic.tar",
    )

    assert health.provider_status == "stale"
    assert health.feed_age_seconds == 301
    assert runtime.provider_status == "fresh"


def test_api_health_loads_the_updater_snapshot(tmp_path) -> None:
    path = tmp_path / "health.json"
    now = datetime.now(UTC)
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="mock",
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version=TILESET,
        traffic_health_path=str(path),
    )
    runtime = _runtime_health(provider="mock", observed_at=now, provider_status="mock")
    JsonTrafficRuntimeHealthStore(path).save(runtime)

    health = traffic_health_from_settings(settings)

    assert health.provider_status == "mock"
    assert health.provider_segments_received == 3
    assert health.managed_edge_count == 6
    assert health.traffic_aware_routing is True


def test_api_health_reports_unavailable_for_malformed_runtime_file(tmp_path) -> None:
    path = tmp_path / "health.json"
    path.write_text("not-json", encoding="utf-8")
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="mock",
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version=TILESET,
        traffic_health_path=str(path),
    )

    health = traffic_health_from_settings(settings)

    assert health.provider_status == "unavailable"
    assert health.traffic_aware_routing is True
    assert "invalid" in (health.message or "")


def _runtime_health(
    *,
    provider: str = "tomtom",
    observed_at: datetime | None = None,
    provider_status: str = "fresh",
) -> TrafficRuntimeHealth:
    now = observed_at or datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
    return TrafficRuntimeHealth(
        provider=provider,
        provider_status=provider_status,  # type: ignore[arg-type]
        last_fetch_started_at=now,
        last_fetch_completed_at=now + timedelta(seconds=1),
        last_success_at=now + timedelta(seconds=1),
        feed_observed_at=now,
        provider_segments_received=3,
        segments_normalized=3,
        segments_matched=3,
        segments_unmatched=0,
        edges_updated=6,
        edges_expired=0,
        provider_api_errors=0,
        updater_consecutive_failures=0,
        managed_edge_count=6,
        mapping_version="valhalla-openlr-geometry-v1",
        valhalla_tileset_version=TILESET,
        message="live traffic overlay update committed",
    )
