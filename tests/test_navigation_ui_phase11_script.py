from pathlib import Path

RUNNER = Path("scripts/run-navigation-ui-phase11-live.sh")
INSTALLER = Path("scripts/install-android-0.21.0.sh")
DRIVING_UI = Path(
    "android/app/src/main/java/org/compass/cng/ui/route/ActiveNavigationScreen.kt"
)
NAVIGATION_MODELS = Path(
    "android/app/src/main/java/org/compass/cng/navigation/NavigationModels.kt"
)


def test_phase11_gate_is_operator_driven_and_preserves_profiles() -> None:
    content = RUNNER.read_text()

    assert content.startswith("#!/usr/bin/env bash\nset -Eeuo pipefail")
    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "Pianificata" in content
    assert "In avvicinamento" in content
    assert "Sei arrivato" in content
    assert "UIAutomator" in content
    assert "uiautomator dump" not in content.lower()
    assert "screencap" not in content
    assert "Do not proceed to Navigation UI Phase 12" in content


def test_phase11_gate_prefers_https_with_explicit_http_fallback() -> None:
    content = RUNNER.read_text()

    assert "COMPASS_ALLOW_HTTP_LIVE_GATE" in content
    assert "HTTPS is required" in content
    assert "COMPASS_API_BASE_URL" in content


def test_phase11_installer_builds_replaces_and_cold_launches() -> None:
    content = INSTALLER.read_text()

    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "assembleDebug" in content
    assert "ANDROID 0.21.0 INSTALLED" in content


def test_phase11_driving_surface_has_first_class_cng_card() -> None:
    content = DRIVING_UI.read_text()

    assert 'testTag("navigation_next_cng_stop")' in content
    assert "Aperto all'arrivo" not in content  # Derived by the UI model, not Compose.
    assert 'Text("Salta o sostituisci la prossima tappa CNG")' in content
    assert "stop.reason" in content
    assert "stop.dwellDuration" in content
    driving_overlay = content.index("ManeuverOverlay(")
    cng_card = content.index("ui.nextCngStop?.let", driving_overlay)
    recalculation = content.index(
        "visible = ui.isRouteRecalculationInProgress",
        cng_card,
    )
    assert cng_card < recalculation


def test_phase11_lifecycle_is_owned_by_navigation_state() -> None:
    content = NAVIGATION_MODELS.read_text()

    lifecycle_states = (
        "PLANNED",
        "APPROACHING",
        "ARRIVED",
        "REFUELING",
        "COMPLETED",
        "SKIPPED",
        "REPLACED",
    )
    for state in lifecycle_states:
        assert state in content
    assert "fuelStopProgress" in content
