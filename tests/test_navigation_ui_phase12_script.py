from pathlib import Path

RUNNER = Path("scripts/run-navigation-ui-phase12-live.sh")
RANKING_RUNNER = Path("scripts/run-navigation-ui-phase12-ranking-live.sh")
INSTALLER = Path("scripts/install-android-0.22.1.sh")
ENGINE = Path(
    "android/app/src/main/java/org/compass/cng/navigation/NavigationEngine.kt"
)
SERVICE = Path(
    "android/app/src/main/java/org/compass/cng/navigation/NavigationForegroundService.kt"
)
DRIVING_UI = Path(
    "android/app/src/main/java/org/compass/cng/ui/route/ActiveNavigationScreen.kt"
)
CANDIDATE_UI = Path(
    "android/app/src/main/java/org/compass/cng/ui/route/RoutePlannerScreen.kt"
)
CANDIDATE_PRESENTATION = Path(
    "android/app/src/main/java/org/compass/cng/ui/route/CngCandidatePresentation.kt"
)


def test_phase12_gate_is_operator_driven_and_preserves_profiles() -> None:
    content = RUNNER.read_text()

    assert content.startswith("#!/usr/bin/env bash\nset -Eeuo pipefail")
    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "Rifornimento" in content
    assert "Completata" in content
    assert "UIAutomator" in content
    assert "uiautomator dump" not in content.lower()
    assert "screencap" not in content
    assert "Do not proceed to Navigation UI Phase 13" in content


def test_phase12_installer_builds_replaces_and_cold_launches() -> None:
    content = INSTALLER.read_text()

    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "assembleDebug" in content
    assert "ANDROID 0.22.1 INSTALLED" in content


def test_phase12_visit_is_engine_owned_and_requires_explicit_completion() -> None:
    engine = ENGINE.read_text()
    service = SERVICE.read_text()

    assert "activeFuelStopVisit" in engine
    assert "remainingFuelDwellSeconds" in engine
    assert "fun completeFuelStop" in engine
    assert "GPS proximity alone never resumes navigation" in engine
    assert "ACTION_COMPLETE_FUEL_STOP" in service
    assert "demo replay paused for CNG refuelling" in service


def test_phase12_driving_surface_exposes_refuelling_feedback_and_action() -> None:
    content = DRIVING_UI.read_text()

    assert 'testTag("navigation_complete_refueling")' in content
    assert 'Text("Completato")' in content
    assert "refuelingRemainingDuration" in content
    assert 'testTag("navigation_details_complete_refueling")' in content


def test_phase12_candidate_order_and_pastel_price_tiers_are_explicit() -> None:
    presentation = CANDIDATE_PRESENTATION.read_text()
    ui = CANDIDATE_UI.read_text()

    assert "orderCngCandidatesForSelection" in presentation
    assert "it.detourMinutes" in presentation
    assert presentation.index("it.detourMinutes") < presentation.index("it.ranking.rank")
    assert "CHEAPEST" in presentation
    assert "SECOND_CHEAPEST" in presentation
    assert "orderedCandidates" in ui
    assert "semanticColors.successContainer" in ui
    assert "semanticColors.warningContainer" in ui
    assert "MaterialTheme.colorScheme.errorContainer" in ui


def test_phase12_ranking_gate_is_operator_driven_and_checks_logged_invariant() -> None:
    content = RANKING_RUNNER.read_text()

    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "monotonic=true" in content
    assert "second_cheapest" in content
    assert "UIAutomator" in content
    assert "uiautomator dump" not in content.lower()
    assert "Do not proceed to Navigation UI Phase 13" in content
