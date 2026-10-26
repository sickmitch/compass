# Codebase cleanup — Android 0.28.38

## Scope


- Removed the unreachable private `ActiveNavigationContent` implementation from
  `RoutePlannerScreen.kt`; the active navigation UI remains `ActiveNavigationScreen`.
- Removed its unused GPS/time formatting helpers, unused vehicle choice buttons and
  maneuver row, plus three superseded canvas glyphs in `ActiveNavigationScreen.kt`.
- Removed unused Kotlin imports and the unreferenced `_path_length` search helper.
- Shared the implementation of E5/E6 polyline encoding while retaining both public
  functions, their precision and validation messages. Added wire-format and invalid-input tests.
- Isolated Python fixture tests from local application environment variables and `.env`,
  clearing the settings cache between tests. Explicit test overrides still apply.
- Updated an obsolete static test requiring manual-only CNG completion: manual completion
  remains supported alongside GPS-based automatic departure, covered by Android engine tests.

No migrations, database data, configuration secrets, APK history or documented installer scripts
were deleted. Removed code remains recoverable from Git history. Legacy data compatibility,
degraded/offline paths and provider fallbacks remain in place. No dependency upgrade or service
deployment is part of this cleanup.

## Local validation

From the repository root:

```bash
.venv/bin/ruff check src tests scripts tools
.venv/bin/pytest -q -m 'not integration'
git diff --check
```

From `android/`, with JDK 17 and the Android SDK configured:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

This does not validate live providers, PostGIS integration or device behavior. Existing lint
warnings are not a reason to remove diagnostic logging, reflection used for system navigation
detection, or to upgrade dependencies during a cleanup.

Validation recorded on 2026-10-24:

- Python: 446 passed; five PostGIS integration tests deliberately excluded. Ruff passed.
- Android unit tests: 279 passed across 30 suites, no skipped tests.
- Android `lintDebug`: passed with zero errors, 83 warnings and four hints. These warnings
  remain follow-up work; this cleanup does not claim a warning-free codebase.
- `docker compose config --quiet`, syntax checks for 61 shell scripts and `git diff --check` passed.
- Debug APK assembled; signature verification passed; package/version metadata checked.
- Python emitted 44 existing FastAPI deprecation warnings under Python 3.14.

APK SHA-256: `a0dd3ca002219a8cc8305471a5ef0daa2a55e48a6e3c4e19126f002060ee4fed`.

## Device acceptance — operator

Artifact: `dist/compass-0.28.38-debug.apk` (version code 84). Install directly on the device,
or from the repository root with a connected, authorized device:

```bash
adb install -r dist/compass-0.28.38-debug.apk
adb shell am start -n org.compass.cng.debug/org.compass.cng.MainActivity
```

Keep the existing backend URL and authentication settings. No new secrets or configuration
keys are required. Do not uninstall the app or clear its data.

Check the following in a safe test context:

1. Create a route in Auto, A piedi and Bici modes; verify the corresponding waypoint actions.
2. Open saved vehicle profiles, configure a CNG plan, and edit an intermediate stop.
3. Open the summary, then navigation: verify ETA ring, details, action menu and PiP.
4. Verify that manual and GPS-based stop completion still behave as before this cleanup.

Expected: no missing controls, crashes or behavioral differences from 0.28.37. No changes
to the ETA-ring or automatic stop-completion algorithms are included in this increment.

If anything fails, capture logs without clearing the device log buffer:

```bash
adb logcat -d -v threadtime AndroidRuntime:E CompassNavigation:D CompassRoutePlanner:D '*:S'
```

Return the failing step, travel mode, screenshot and relevant logs, removing credentials and
private coordinates if necessary. Device acceptance remains pending until the operator reports
the result; the next step is to address any regression, not start another refactoring phase.
