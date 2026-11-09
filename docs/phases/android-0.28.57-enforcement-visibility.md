# Android 0.28.57 — camera visibility

Inventory markers in both guidance and free follow use a pastel-yellow disc with a dark
camera and outline. The bitmap has an explicit xhdpi density, producing a stable 40 dp
symbol rather than inheriting the phone's bitmap density. Camera and label stay facing
the viewport in pitched maps. Icons can overlap other symbols; labels remain collision
managed and optional so a hidden label does not hide the camera. Known limits include
`km/h`; missing limits are not invented. The existing tap opens source/limit details.

Verified approach cards share the yellow/dark palette. Eligibility, GPS matching,
voice, offline lifetime, and backend behavior are unchanged. An inventory marker alone
does not authorize an approach alert. The supplied screenshot cannot identify why no
approach notice was available; this release does not claim to fix that separate issue.

## Local validation

From the repository root, with JDK 17 and Android SDK configured:

```sh
android/gradlew -p android --no-daemon --max-workers=2 \
  testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

No device or live-server validation is implied by those tasks.
Local result: 347 JVM tests passed, no failures or skipped tests; lint passed with
94 warnings and three hints, no errors. Both APKs compiled. The distributed APK reports
versionCode 103/versionName 0.28.57 and retains the existing signing certificate;
`dist/last.txt` points to `compass-0.28.57.apk`.

## Device acceptance (operator)

Install `dist/compass-0.28.57.apk` via the existing updater or Android installer. No backend
redeploy or secrets are needed. Using a passenger or a stationary phone:

1. Open the camera area in the supplied screenshot in free follow and active guidance.
   Check a yellow camera with dark outline, clearly separated `Velox · 90 km/h` label,
   and preserved position on the road. Repeat in light theme and at different map tilts.
2. Tap the marker: existing limit/source details must open. In crowded areas labels may
   declutter, but the camera must remain visible from zoom 11 upwards.
3. On a verified directional approach, check a yellow distance/limit card and the existing
   voice warning. A map-only record must not generate an unverified automatic warning.

Return screenshots for both themes and modes if the marker/label is still unreadable.
For an absent approach card, from `~/docker/compass` on the server with the existing `.env`,
API, DB and Valhalla running (no rebuild required), return these outputs together with the
camera location, travel direction and app mode:

```sh
docker compose logs --since 10m --tail 300 api | rg 'approach|road-context'
docker compose exec -T api python3 < scripts/check-enforcement-associations.py
```

These diagnostics read stored associations; they do not prove a device approach succeeded.
Marker visibility and eligibility require separate checks. The voice-quality investigation remains open in
[0.28.56](android-0.28.56-voice-quality.md).
