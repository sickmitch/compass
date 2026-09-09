from pathlib import Path

SCRIPT = Path("scripts/run-navigation-ui-phase13-live.sh")


def test_navigation_ui_phase13_gate_is_operator_driven_and_checks_durable_recovery() -> None:
    content = SCRIPT.read_text(encoding="utf-8")

    assert "uiautomator" not in content
    assert "screenshot" not in content.lower() or "optional" in content.lower()
    assert "testDebugUnitTest lintDebug assembleDebug" in content
    assert "navigation connectivity: offline; downloaded_route_retained=true" in content
    assert "navigation route restored from cache: active=true progress=true" in content
    assert "navigation location mode=demo_replay" in content
    assert "route update started: CONNECTIVITY_RECOVERY" in content
    assert "route update committed: CONNECTIVITY_RECOVERY" in content
    assert "Do not proceed to Navigation UI Phase 14" in content
