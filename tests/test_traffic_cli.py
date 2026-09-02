from __future__ import annotations

import asyncio
import json
import os
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from compass.traffic import cli
from compass.traffic.domain import (
    TrafficEdgeMatch,
    TrafficFlowSegment,
    TrafficProviderMetrics,
    TrafficProviderSnapshot,
)
from compass.traffic.valhalla.executor import TrafficOverlayExecutionReceipt
from compass.traffic.valhalla.overlay import TrafficWriteReceipt
from compass.traffic.valhalla.planner import (
    JsonTrafficStateStore,
    ManagedTrafficEdge,
    TrafficOverlayState,
)

TILESET = "valhalla-3.8.3:123"


def test_configuration_snapshot_validates_writer_without_exposing_secret(
    monkeypatch,
    tmp_path,
) -> None:
    traffic_extract = tmp_path / "traffic.tar"
    traffic_extract.write_bytes(b"native traffic extract")
    helper = tmp_path / "traffic-tool"
    helper.write_text("#!/bin/sh\n", encoding="utf-8")
    helper.chmod(0o700)
    settings = SimpleNamespace(
        traffic_enabled=True,
        traffic_provider="tomtom",
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version=TILESET,
        traffic_openlr_decoder_path=str(helper),
        valhalla_traffic_extract=str(traffic_extract),
        traffic_state_path=tmp_path / "state.json",
        traffic_mapping_version="valhalla-openlr-geometry-v1",
        traffic_refresh_mode="on_demand",
        traffic_route_probe_spacing_km=25,
        traffic_route_max_probes=16,
        traffic_route_refresh_min_interval_seconds=300,
        tomtom_traffic_api_mode="flow_segment",
        tomtom_flow_segment_points="45.1,9.1;45.2,9.2",
        tomtom_api_key="never-print-this-secret",
    )
    monkeypatch.setattr(cli, "build_traffic_provider", lambda _settings: object())

    snapshot = cli._configuration_snapshot(settings)

    assert snapshot["configured_probe_count"] == 2
    assert snapshot["managed_edge_count"] == 0
    assert snapshot["state_identity_matches_configured"] is True
    assert snapshot["state_tileset_identity"] == TILESET
    assert snapshot["traffic_extract_present"] is True
    assert snapshot["native_helper_present"] is True
    assert "never-print-this-secret" not in json.dumps(snapshot)


def test_configuration_snapshot_rejects_non_executable_native_helper(
    monkeypatch,
    tmp_path,
) -> None:
    traffic_extract = tmp_path / "traffic.tar"
    traffic_extract.write_bytes(b"native traffic extract")
    helper = tmp_path / "traffic-tool"
    helper.write_text("not executable", encoding="utf-8")
    os.chmod(helper, 0o600)
    settings = SimpleNamespace(
        traffic_enabled=True,
        traffic_provider="tomtom",
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version=TILESET,
        traffic_openlr_decoder_path=str(helper),
        valhalla_traffic_extract=str(traffic_extract),
        traffic_state_path=tmp_path / "state.json",
        traffic_mapping_version="valhalla-openlr-geometry-v1",
        traffic_refresh_mode="on_demand",
        traffic_route_probe_spacing_km=25,
        traffic_route_max_probes=16,
        traffic_route_refresh_min_interval_seconds=300,
        tomtom_traffic_api_mode="flow_segment",
        tomtom_flow_segment_points="45.1,9.1",
        tomtom_api_key="configured",
    )
    monkeypatch.setattr(cli, "build_traffic_provider", lambda _settings: object())

    try:
        cli._configuration_snapshot(settings)
    except ValueError as error:
        assert "executable" in str(error)
    else:
        raise AssertionError("non-executable helper must fail the preflight")


def test_fetch_and_match_awaits_each_match_without_creating_async_generator(
    monkeypatch,
) -> None:
    now = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
    segments = tuple(
        TrafficFlowSegment(
            provider="tomtom",
            provider_segment_id=f"flow-{index}",
            observed_at=now,
            expires_at=now + timedelta(minutes=1),
            current_speed_kph=40,
        )
        for index in range(2)
    )

    class Provider:
        async def fetch_flow(self) -> TrafficProviderSnapshot:
            return TrafficProviderSnapshot(
                provider="tomtom",
                observed_at=now,
                segments=segments,
                metrics=TrafficProviderMetrics(segments_normalized=2),
            )

    class Matcher:
        async def match(self, segment: TrafficFlowSegment) -> TrafficEdgeMatch:
            return TrafficEdgeMatch(
                directed_edge_ids=(f"0/1/{segment.provider_segment_id[-1]}",),
                match_method="geometry_trace",
                confidence=0.9,
                direction_match=True,
                valhalla_tileset_version="valhalla-3.8.3:123",
                mapping_version="valhalla-openlr-geometry-v1",
            )

    monkeypatch.setattr(cli, "build_traffic_edge_matcher", lambda _settings: Matcher())

    snapshot, candidates = asyncio.run(
        cli._fetch_and_match(object(), provider=Provider(), limit=2)
    )

    assert snapshot.segments == segments
    assert isinstance(candidates, tuple)
    assert [candidate.segment.provider_segment_id for candidate in candidates] == [
        "flow-0",
        "flow-1",
    ]


def test_fetch_and_match_turns_one_matcher_failure_into_unmatched(
    monkeypatch,
) -> None:
    now = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id="flow-bad",
        observed_at=now,
        expires_at=now + timedelta(minutes=1),
        current_speed_kph=40,
    )

    class Provider:
        async def fetch_flow(self) -> TrafficProviderSnapshot:
            return _snapshot(now, (segment,))

    class Matcher:
        async def match(self, _segment: TrafficFlowSegment) -> TrafficEdgeMatch:
            raise RuntimeError("one malformed location reference")

    monkeypatch.setattr(cli, "build_traffic_edge_matcher", lambda _settings: Matcher())

    _snapshot_value, candidates = asyncio.run(
        cli._fetch_and_match(object(), provider=Provider(), limit=1)
    )

    assert candidates[0].match.match_method == "unmatched"
    assert candidates[0].match.warnings == ("matcher_error",)


def test_updater_cycle_commits_one_provider_snapshot(
    monkeypatch,
    tmp_path,
) -> None:
    now = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
    segment = TrafficFlowSegment(
        provider="tomtom",
        provider_segment_id="flow-1",
        observed_at=now,
        expires_at=now + timedelta(minutes=1),
        current_speed_kph=35,
        confidence=0.9,
    )
    settings = _settings(tmp_path)

    class Provider:
        async def fetch_flow(self) -> TrafficProviderSnapshot:
            return _snapshot(now, (segment,))

    class Matcher:
        async def match(self, _segment: TrafficFlowSegment) -> TrafficEdgeMatch:
            return _match("0/1/10")

    monkeypatch.setattr(cli, "build_traffic_edge_matcher", lambda _settings: Matcher())
    monkeypatch.setattr(cli, "_overlay_executor", _fake_executor)

    result = asyncio.run(
        cli._run_updater_cycle(settings, provider=Provider(), evaluated_at=now)
    )

    state = JsonTrafficStateStore(settings.traffic_state_path).load(
        expected_tileset_identity=TILESET
    )
    assert result.edges_set == 1
    assert result.segments_accepted == 1
    assert result.managed_edge_count == 1
    assert state.edges[0].graph_id == "0/1/10"


def test_runtime_health_preserves_last_success_and_counts_provider_failures(
    tmp_path,
) -> None:
    now = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
    settings = _settings(tmp_path)
    result = cli.TrafficUpdaterCycleResult(
        provider="tomtom",
        observed_at=now,
        segments_received=1,
        segments_normalized=1,
        segments_considered=1,
        segments_accepted=1,
        segments_unmatched=0,
        provider_rejected_segments=0,
        provider_stale_segments=0,
        provider_api_errors=0,
        edges_set=1,
        edges_reset=0,
        managed_edge_count=1,
        mapping_version="valhalla-openlr-geometry-v1",
    )
    success = cli._successful_runtime_health(
        settings,
        result=result,
        fetch_started_at=now,
        fetch_completed_at=now + timedelta(seconds=1),
    )
    cli.JsonTrafficRuntimeHealthStore(settings.traffic_health_path).save(success)

    failed = cli._failed_runtime_health(
        settings,
        fetch_started_at=now + timedelta(minutes=1),
        fetch_completed_at=now + timedelta(minutes=1, seconds=1),
        error=RuntimeError("provider unavailable"),
        edges_expired=1,
    )

    assert failed.provider_status == "unavailable"
    assert failed.last_success_at == success.last_success_at
    assert failed.feed_observed_at == success.feed_observed_at
    assert failed.provider_api_errors == 1
    assert failed.updater_consecutive_failures == 1
    assert failed.edges_expired == 1


def test_failed_provider_cycle_can_expire_only_compass_managed_edges(
    monkeypatch,
    tmp_path,
) -> None:
    now = datetime(2026, 9, 1, 8, 0, tzinfo=UTC)
    settings = _settings(tmp_path)
    store = JsonTrafficStateStore(settings.traffic_state_path)
    store.save(
        TrafficOverlayState(
            tileset_identity=TILESET,
            edges=(
                ManagedTrafficEdge(
                    graph_id="0/1/10",
                    provider_segment_id="flow-expired",
                    observed_at=now - timedelta(minutes=2),
                    expires_at=now - timedelta(minutes=1),
                    speed_kph=20,
                    congestion=0.8,
                    closed=False,
                    has_incidents=False,
                    match_confidence=0.9,
                    mapping_version="valhalla-openlr-geometry-v1",
                ),
            ),
        )
    )
    monkeypatch.setattr(cli, "_overlay_executor", _fake_executor)

    reset_count = cli._expire_managed_edges(settings, evaluated_at=now)

    state = store.load(expected_tileset_identity=TILESET)
    assert reset_count == 1
    assert state.edges == ()


def test_cleared_runtime_health_reports_fallback_and_no_managed_edges(tmp_path) -> None:
    settings = _settings(tmp_path)

    health = cli._cleared_runtime_health(settings, edges_reset=4)

    assert health.provider_status == "unavailable"
    assert health.edges_expired == 4
    assert health.managed_edge_count == 0
    assert "fallback" in health.message


def test_config_snapshot_reports_mismatched_state_without_accepting_it(
    monkeypatch,
    tmp_path,
) -> None:
    settings = _settings(tmp_path)
    settings.traffic_enabled = True
    settings.tomtom_traffic_api_mode = "flow_segment"
    settings.tomtom_flow_segment_points = ""
    settings.tomtom_api_key = "configured"
    settings.traffic_refresh_mode = "on_demand"
    settings.traffic_route_probe_spacing_km = 25
    settings.traffic_route_max_probes = 16
    settings.traffic_route_refresh_min_interval_seconds = 300
    settings.valhalla_traffic_extract = tmp_path / "traffic.tar"
    settings.valhalla_traffic_extract.write_bytes(b"traffic")
    helper = tmp_path / "traffic-tool"
    helper.write_text("#!/bin/sh\n", encoding="utf-8")
    helper.chmod(0o700)
    settings.traffic_openlr_decoder_path = str(helper)
    JsonTrafficStateStore(settings.traffic_state_path).save(
        TrafficOverlayState(tileset_identity="valhalla-3.8.3:old")
    )
    monkeypatch.setattr(cli, "build_traffic_provider", lambda _settings: object())

    snapshot = cli._configuration_snapshot(settings)

    assert snapshot["state_identity_matches_configured"] is False
    assert snapshot["state_tileset_identity"] == "valhalla-3.8.3:old"


def test_reinitialize_state_after_rebuild_discards_only_expected_old_identity(
    tmp_path,
) -> None:
    settings = _settings(tmp_path)
    settings.traffic_refresh_ledger_path = tmp_path / "route-refresh.json"
    store = JsonTrafficStateStore(settings.traffic_state_path)
    store.save(TrafficOverlayState(tileset_identity="valhalla-3.8.3:old"))

    result = cli._reinitialize_state_after_traffic_rebuild(
        settings,
        previous_tileset="valhalla-3.8.3:old",
    )

    assert result == 0
    assert store.load(expected_tileset_identity=TILESET).edges == ()


def test_reinitialize_state_refuses_wrong_previous_identity(tmp_path) -> None:
    settings = _settings(tmp_path)
    settings.traffic_refresh_ledger_path = tmp_path / "route-refresh.json"
    JsonTrafficStateStore(settings.traffic_state_path).save(
        TrafficOverlayState(tileset_identity="valhalla-3.8.3:old")
    )

    with pytest.raises(cli.TrafficStateError, match="previous-tileset"):
        cli._reinitialize_state_after_traffic_rebuild(
            settings,
            previous_tileset="valhalla-3.8.3:other",
        )


def _settings(tmp_path):
    return SimpleNamespace(
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version=TILESET,
        traffic_openlr_decoder_path="/native/tool",
        traffic_state_path=tmp_path / "state.json",
        traffic_health_path=tmp_path / "health.json",
        traffic_refresh_ledger_path=tmp_path / "route-refresh.json",
        traffic_provider="tomtom",
        traffic_mapping_version="valhalla-openlr-geometry-v1",
        traffic_update_segment_limit=100,
        traffic_min_confidence=0.5,
        traffic_max_age_seconds=300,
        traffic_max_speed_kph=180,
        traffic_min_match_confidence=0.75,
    )


def _snapshot(now, segments):
    return TrafficProviderSnapshot(
        provider="tomtom",
        observed_at=now,
        segments=segments,
        metrics=TrafficProviderMetrics(
            provider_segments_received=len(segments),
            segments_normalized=len(segments),
        ),
    )


def _match(graph_id):
    return TrafficEdgeMatch(
        directed_edge_ids=(graph_id,),
        match_method="geometry_trace",
        confidence=0.9,
        direction_match=True,
        valhalla_tileset_version=TILESET,
        mapping_version="valhalla-openlr-geometry-v1",
    )


def _fake_executor(_settings, *, state_store):
    class Executor:
        def execute(self, *, previous_state, plan):
            state_store.save(plan.resulting_state)
            return TrafficOverlayExecutionReceipt(
                write=TrafficWriteReceipt(
                    set_count=len(plan.set_updates),
                    reset_count=len(plan.reset_graph_ids),
                    operation_count=len(plan.set_updates) + len(plan.reset_graph_ids),
                ),
                state_persisted=True,
            )

    return Executor()
