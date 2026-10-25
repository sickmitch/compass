from pathlib import Path

SOURCE = Path(
    "android/app/src/main/java/org/compass/cng/ui/route/ActiveNavigationScreen.kt"
)


def test_voice_uses_shared_map_button_without_size_overrides():
    source = SOURCE.read_text()
    voice = source.split("private fun VoiceGuidanceToggle(", 1)[1].split(
        "private fun ManeuverOverlay(", 1
    )[0]
    assert "MapIconControlButton(" in voice
    assert ".size(" not in voice
    assert 'testTag = "navigation_voice_toggle"' in voice


def test_compact_summary_has_no_internal_actions_and_map_toggle_remains_visible():
    source = SOURCE.read_text()
    summary = source.split("private fun TripBottomBar(", 1)[1].split(
        "private fun DrivingSummaryValue(", 1
    )[0]
    assert "onOpenDetails" not in summary
    assert "onHide" not in summary
    assert "CompassTextButton" not in summary
    assert '"Dettagli viaggio"' not in source
    assert "showTripSummary = !showTripSummary" in source
    controls = source.split("private fun MapModeControls(", 1)[1].split(
        "private fun MapIconControlButton(", 1
    )[0]
    assert "if (!tripSummaryVisible)" not in controls
    assert "if (tripSummaryVisible) Icons.Rounded.KeyboardArrowDown" in controls
    assert 'testTag = "navigation_trip_toggle"' in controls
