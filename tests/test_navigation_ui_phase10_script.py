from pathlib import Path

RUNNER = Path("scripts/run-navigation-ui-phase10-live.sh")
INSTALLER = Path("scripts/install-android-0.20.0.sh")
DRIVING_UI = Path(
    "android/app/src/main/java/org/compass/cng/ui/route/ActiveNavigationScreen.kt"
)


def test_phase10_gate_is_operator_driven_and_preserves_profiles() -> None:
    content = RUNNER.read_text()

    assert content.startswith("#!/usr/bin/env bash\nset -Eeuo pipefail")
    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "Simula deviazione (debug)" in content
    assert "Ricalcolo rotta" in content
    assert "UIAutomator" in content
    assert "uiautomator dump" not in content.lower()
    assert "screencap" not in content
    assert "route update committed: OFF_ROUTE" in content
    assert "Do not proceed to Navigation UI Phase 11" in content


def test_phase10_gate_prefers_https_with_explicit_http_fallback() -> None:
    content = RUNNER.read_text()

    assert "COMPASS_ALLOW_HTTP_LIVE_GATE" in content
    assert "HTTPS is required" in content
    assert "COMPASS_API_BASE_URL" in content


def test_phase10_installer_builds_replaces_and_cold_launches() -> None:
    content = INSTALLER.read_text()

    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "assembleDebug" in content
    assert "ANDROID 0.20.0 INSTALLED" in content


def test_phase10_driving_surface_contains_accessible_recalculation_indicator() -> None:
    content = DRIVING_UI.read_text()

    assert "navigation_route_recalculation_indicator" in content
    assert 'contentDescription = "Ricalcolo rotta in corso"' in content
    assert "CircularProgressIndicator" in content
    assert 'text = "Ricalcolo rotta"' in content
