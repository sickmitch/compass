import subprocess
from pathlib import Path

SCRIPT = Path("scripts/install-android-0.19.3.sh")


def test_android_0193_installer_has_valid_shell_syntax() -> None:
    subprocess.run(["bash", "-n", str(SCRIPT)], check=True)


def test_android_0193_installer_preserves_profile_and_avoids_ui_inspection() -> None:
    source = SCRIPT.read_text(encoding="utf-8")

    assert 'install -r "$apk_path"' in source
    assert "pm clear" not in source
    assert "shell uiautomator" not in source.lower()
    assert "shell screencap" not in source.lower()
    assert "assembleDebug" in source
