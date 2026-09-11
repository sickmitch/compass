from pathlib import Path

SCRIPT = Path("scripts/run-navigation-ui-phase15-live.sh")


def test_navigation_ui_phase15_gate_is_operator_driven_and_checks_waypoint_contract() -> None:
    content = SCRIPT.read_text(encoding="utf-8")

    assert "uiautomator" not in content.lower()
    assert "screencap" not in content.lower()
    assert "testDebugUnitTest lintDebug assembleDebug" in content
    assert "/api/v1/routes/with-intermediate-stops" in content
    assert '"refueling_stop_count"] == 0' in content
    assert '"total_refueling_dwell_seconds"] == 0' in content
    assert "30% of the direct-route length" in content
    assert "Long-press and drag" in content
    assert "common summary" in content
    assert "Carica o crea profilo" in content
    assert "route ID" in content
    assert "no dwell" in content
    assert "zoom-dependent puck lower bound" in content
    assert "Compass-0.25.3-debug-installabile.apk" in content
    assert "UNIFIED MATERIAL 3 PRESENTATION" in content
    assert "presentation-only increment" in content
    assert 'sha256sum "$installable_apk"' in content
    assert "Do not proceed to Navigation UI Phase 16" in content
