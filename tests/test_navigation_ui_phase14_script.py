from pathlib import Path

SCRIPT = Path("scripts/run-navigation-ui-phase14-live.sh")


def test_navigation_ui_phase14_gate_is_operator_driven_and_checks_map_controls() -> None:
    content = SCRIPT.read_text(encoding="utf-8")

    assert "uiautomator" not in content.lower()
    assert "screenshot" not in content.lower()
    assert "testDebugUnitTest lintDebug assembleDebug" in content
    assert "camera_mode=north_up reason=orientation_control" in content
    assert "camera_mode=follow reason=orientation_control" in content
    assert "camera_mode=overview reason=control" in content
    assert "camera_mode=follow reason=recenter" in content
    assert "navigation_stop confirmed=false navigation_active=true" in content
    assert "navigation_stop confirmed=true source=map_control" in content
    assert "voice guidance enabled=false" in content
    assert "voice guidance enabled=true" in content
    assert "Do not proceed to Navigation UI Phase 15" in content
