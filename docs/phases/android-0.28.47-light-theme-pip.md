# Android 0.28.47 — Light theme and PiP

The light navigation route, ETA text and progress ring use the same dark green as
menu icons (#006C4C). Material surface-container roles are explicitly pale green,
including the settings cards; the dark palette is unchanged.

PiP parameters now follow navigation eligibility independently of recomposition and
are refreshed on resume/layout changes. Android 12+ uses preconfigured auto-entry for
Home gestures; Android 8–11 retains guarded manual entry. Device support and system
rejections are handled without crashing. Preview, idle and completed journeys disable
auto-entry; rerouting, GPS loss and active stop visits remain eligible while navigation
is visible. Non-video seamless resizing is disabled.

Platform reference: [Android PiP](https://developer.android.com/develop/ui/views/picture-in-picture).
The system still controls PiP authorization and availability; this change does not
bypass disabled app permissions or device restrictions.

## Operator acceptance

Install the published `dist/compass-0.28.47.apk` through the normal update workflow.
No backend deployment or configuration changes are needed.

- Select the light theme: inspect options cards, route, circular ETA and expanded ETA.
  Compare against the dark theme to confirm its colors are unchanged.
- With PiP allowed in Android settings, start navigation, leave with Home (gesture and
  button where available), reopen, and repeat at least five times in one journey.
- Repeat during recalculation and a stop visit. Preview/finished journeys must not
  enter PiP. Return from PiP and confirm location/maneuver updates continue.
- Disable Android's PiP permission: leaving must not crash. Re-enable, reopen the app
  and repeat. Check Android 8–11 separately if a device/emulator is available.

Return Android version/device model and the failing sequence if entry remains unreliable:

```bash
adb logcat -d -s CompassPiP ActivityTaskManager
```

Local JVM tests exercise eligibility; Android lint/build validate API usage. They do not
prove Home-gesture behavior or OEM window-manager behavior on a physical device.
