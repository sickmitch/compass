#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_root="$repo_root/android"
apk_source="$android_root/app/build/outputs/apk/debug/app-debug.apk"
apk_installable="$repo_root/dist/Compass-0.28.9-debug-installabile.apk"

cd "$android_root"
JAVA_HOME="${JAVA_HOME:-/home/mike/toolchains/jdk17}" \
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/home/mike/toolchains/android-sdk}" \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

install -D -m 0644 "$apk_source" "$apk_installable"
sha256sum "$apk_installable"

echo "Install with: adb install -r '$apk_installable'"
echo "Follow docs/phases/android-0.28.9-options-navigation-map-acceptance.md"
