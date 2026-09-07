from pathlib import Path

SCRIPT = Path("scripts/run-android-0.19.4-google-destination-live.sh")
INSTALLER = Path("scripts/install-android-0.19.4.sh")


def test_google_destination_live_gate_is_operator_driven_and_installs_apk() -> None:
    content = SCRIPT.read_text()

    assert content.startswith("#!/usr/bin/env bash\nset -Eeuo pipefail")
    assert 'install -r "$apk_path"' in content
    assert "assembleDebug" in content
    assert "destinations/metrics" in content
    assert 'tomtom_destination_calls"] != 0' in content
    assert "GOOGLE DESTINATION LIVE GATE COMPLETE" in content
    assert "UIAutomator" in content
    assert "uiautomator dump" not in content.lower()
    assert "screencap" not in content


def test_google_destination_gate_prefers_https_with_explicit_http_fallback() -> None:
    content = SCRIPT.read_text()

    assert "COMPASS_ALLOW_HTTP_LIVE_GATE" in content
    assert "HTTPS is required" in content
    assert "COMPASS_API_BASE_URL" in content


def test_android_0194_installer_preserves_profiles_and_uses_replace_install() -> None:
    content = INSTALLER.read_text()

    assert 'install -r "$apk_path"' in content
    assert "pm clear" not in content
    assert "assembleDebug" in content
    assert "ANDROID 0.19.4 INSTALLED" in content
