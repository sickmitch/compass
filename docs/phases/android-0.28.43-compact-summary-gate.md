# Android 0.28.43 — compact navigation summary

- Voice toggle uses the shared 52 dp map button and standard icon size, colors and elevation.
- The compact distance/duration/arrival panel remains; its Details and Hide buttons are removed.
- Its map toggle stays visible while the panel is open and becomes a downward chevron.
  Pressing it again closes the panel and restores the list icon.
- Removed the separate general trip-details view. CNG-specific information/actions remain
  reachable by tapping the CNG card, without repeating the general trip summary.
- Important traffic/GPS warning messages remain visible in the compact panel.

No backend change is required. Build from `android/`:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

This publishes `dist/compass-0.28.43.apk` and updates `dist/last.txt`. Publish both together
to make the client update available. For direct installation, from repository root:

```bash
adb install -r dist/compass-0.28.43.apk
```

Device gate: open navigation/replay, expand the actions menu and compare the voice button
with its neighbors. Open/close the summary repeatedly using the persistent map toggle;
verify its down-chevron state, absence of internal buttons, and visibility of ETA/MapLibre
attribution. Also check CNG card access and completion controls on a route with refuelling.

If layout differs, return screenshots with the summary and action menu open/closed.
For crashes, capture `adb logcat -d -v threadtime AndroidRuntime:E CompassNavigationUi:I '*:S'`.
Physical-device layout acceptance remains pending operator feedback.
