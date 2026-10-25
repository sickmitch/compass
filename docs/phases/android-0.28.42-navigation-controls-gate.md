# Android 0.28.42 — voice menu and ETA position

Voice guidance toggle moved from the persistent bottom-left row into the chevron action
menu, immediately below settings. Toggling voice closes the menu and preserves the existing
voice-enabled state and accessibility label. ETA is now the leftmost control, with 20 dp
less bottom padding than before. The 24 dp padding plus the row's 8 dp inset reserves a
32 dp bottom clearance for MapLibre attribution. With trip details open, ETA stays above
the panel on the same baseline as the map controls. No routing/timing behavior changed.

Build from `android/` with JDK/SDK configured:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

The build publishes `dist/compass-0.28.42.apk` and updates `dist/last.txt` automatically.
Publish both to GitHub for automatic update delivery. No backend update is required.

## Device check

Install directly or, from repository root with an authorized device:

```bash
adb install -r dist/compass-0.28.42.apk
```

During a safe navigation/replay, verify:

- ETA sits bottom-left and MapLibre logo/attribution remain visible and unobstructed.
- Voice button appears only in the expanded chevron menu; toggle and reopen to verify state.
- Details open/closed, overview/recenter and gesture/button navigation do not overlap ETA.

Return a screenshot with details both open and closed if alignment differs. For crashes:

```bash
adb logcat -d -v threadtime AndroidRuntime:E CompassNavigationUi:I '*:S'
```

Device layout validation remains pending operator feedback.

Local result: 283 Android unit tests passed; APK assembly, signing verification and
`git diff --check` passed. `dist/last.txt` contains `compass-0.28.42.apk`.
