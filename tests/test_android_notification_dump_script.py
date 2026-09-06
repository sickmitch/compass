from __future__ import annotations

import subprocess
from pathlib import Path

EXTRACTOR = Path("scripts/extract-active-android-notifications.awk")


def _extract(notification_dump: str) -> str:
    completed = subprocess.run(
        ["awk", "-f", str(EXTRACTOR)],
        input=notification_dump,
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout


def test_active_notification_extractor_excludes_historical_archive() -> None:
    result = _extract(
        """Current Notification Manager state:
  Notification List:
    NotificationRecord(pkg=example.current channel=music)
  mArchive=Archive (2 notifications)
    StatusBarNotification(pkg=org.compass.cng.debug channel=compass_navigation)
"""
    )

    assert "example.current" in result
    assert "org.compass.cng.debug" not in result
    assert "compass_navigation" not in result


def test_active_notification_extractor_retains_current_compass_notification() -> None:
    result = _extract(
        """Current Notification Manager state:
  Notification List:
    NotificationRecord(pkg=org.compass.cng.debug channel=compass_navigation)
  mArchive=Archive (0 notifications)
"""
    )

    assert "org.compass.cng.debug" in result
    assert "compass_navigation" in result
