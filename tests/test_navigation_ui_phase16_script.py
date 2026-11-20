from pathlib import Path

SCRIPT = Path("scripts/run-navigation-ui-phase16-live.sh")
ANDROID_MAIN = Path("android/app/src/main")


def test_phase16_gate_is_operator_driven_and_covers_adaptive_visual_polish() -> None:
    content = SCRIPT.read_text(encoding="utf-8")

    assert "uiautomator" not in content.lower()
    assert "screencap" not in content.lower()
    assert "testDebugUnitTest lintDebug assembleDebug" in content
    assert "PORTRAIT, TYPOGRAPHY AND TOUCH TARGETS" in content
    assert "LANDSCAPE AND CUTOUTS" in content
    assert "AUTOMOTIVE NAVIGATION CHROME" in content
    assert "48/56 dp touch targets" in content
    assert "no phantom stop" in content
    assert "latest acquired GPS position" in content
    assert "spinner" in content
    assert "Ricalcolo percorso" in content
    assert "identical order" in content
    assert "business" in content
    assert "unrelated" in content
    assert "MapLibre beside a" in content
    assert "No automated visual or screenshot inspection" in content
    assert "searched business/POI" in content
    assert "Compass-0.26.5-debug-installabile.apk" in content
    assert "either provider supplies a fix" in content
    assert "true OLED black" in content
    assert 'sha256sum "$installable_apk"' in content
    assert "Do not proceed to Navigation UI Phase 17" in content


def test_android_runtime_has_no_milan_default_or_example() -> None:
    runtime_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in ANDROID_MAIN.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".xml", ".json"}
    )

    assert "Milano" not in runtime_text
    assert "Milan" not in runtime_text
