# Android 0.28.41 — automatic client updates

## Behavior

- On foreground/start, checks the HTTPS GitHub `dist/last.txt` endpoint. Checks are
  throttled to once per six hours within the current ViewModel lifetime; a fresh app
  process checks again. Network/check failures never block navigation.
- Accepts only a single `compass-MAJOR.MINOR.PATCH.apk` basename (also reads historical
  `-debug.apk` filenames); compares numeric versions, not alphabetical strings.
- A newer version prompts for consent before any APK download. The prompt and installer
  are deferred while the navigation screen is open; no installation is launched in background.
- Downloads from the requested GitHub raw URL with progress, timeout and 256 MiB limit.
  The client is separate from the backend HTTP client and carries no backend credentials.
  HTTPS-to-HTTP redirects are disabled. Partial downloads stay in the private update cache.
- Before installation checks APK readability, application ID, advertised version, increasing
  version code and exact signer compatibility. Invalid/incompatible APKs are rejected.
- Opens Android's per-app unknown-source permission screen if needed, then the system
  installer. Android still requires the user's confirmation. Denial/cancellation is allowed.
  This is not silent installation and does not bypass Android policy.
- Downloads survive rotation via the ViewModel, not process death. If Android kills the
  process, reopen Compass and retry; the partial file is overwritten on the next attempt.

Reference: [Android package installation permission](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()).

## Build/publication contract

`assembleDebug` (and signed release assembly) automatically publishes
`dist/compass-<version>.apk`, without `-debug`, then replaces `dist/last.txt` with that
basename and a newline. The pointer changes only after the APK is copied successfully.
The private AGP build variant/output remains debug; the distributed artifact has no suffix.
Unsigned release artifacts are refused as updates.

Version name is now `0.28.41`, code 87. The existing `.debug` application ID and signing
identity are deliberately retained: changing those would prevent an in-place update.
All future releases need increasing version names AND codes and the same signing key.
Do not regenerate the debug keystore on a different build machine. If migrating to a
release signing identity later, plan that separately; the updater rejects different keys.

From `android/`, with JDK/SDK configured:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The human operator must publish BOTH the APK and `dist/last.txt` to GitHub `main`, ideally
in the same commit. Local assembly does not push anything. Publish the APK before the
pointer if deployment is not atomic. This build requires no backend update.

## Device acceptance

Local verification: 283 Android tests and 454 Python tests passed (five PostGIS tests
excluded). Build/signature verification passed; lint reports zero errors, 85 warnings,
three hints. APK metadata is version 0.28.41/code 87 and `dist/last.txt` was updated by
the Gradle task. No device upgrade or GitHub publication was performed.

APK SHA-256: `de20a7904d075f4f9b4d98aa98f32c9d227765515f9c4483a691b6724e45af0d`.

Install `dist/compass-0.28.41.apk` directly, or from the repository root:

```bash
adb install -r dist/compass-0.28.41.apk
```

No prompt is expected while GitHub advertises 0.28.40 or the installed version. To validate
the full upgrade, publish the NEXT signed, compatible version and its pointer, then reopen
the older installed app. Do not fake the manifest version without a matching APK.

1. Accept the update: observe progress, grant unknown-source permission if requested,
   and confirm installation. Verify existing server settings/favorites are preserved.
2. Decline: verify normal app operation and no download.
3. Deny installation permission or cancel installer: verify no forced installation.
4. Offline/404/invalid APK: verify navigation still works and download errors are actionable.
5. While navigating/PiP, verify no update dialog interrupts guidance.

Diagnostics (do not clear app data):

```bash
adb shell dumpsys package org.compass.cng.debug | grep -E 'versionCode|versionName'
adb logcat -d -v threadtime CompassUpdate:W AndroidRuntime:E '*:S'
```

Return the installed/advertised filenames, screenshot and relevant logs. Unit/build checks
are not proof of a successful system-installer/device upgrade; that live gate remains pending.
