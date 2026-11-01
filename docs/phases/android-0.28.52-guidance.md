# Android 0.28.52 — Parking, arrival and quieter guidance

The base GPS-follow screen includes a parking button. It reuses the existing fresh-GPS
validation, single saved parking slot, replacement confirmation and favorites notice.
The destination-reached dialog dismisses after five seconds; OK/manual dismissal remains
available. This does not delay navigation completion or change the spoken arrival.

Maneuver guidance has at most three progressive announcements per maneuver in a calculated
route. There is no unconditional announcement as soon as a distant maneuver becomes active.
Thresholds use current speed in m/s multiplied by 30, 12 and 3 seconds respectively:

| Mode | Advance range (m) | Prepare range (m) | Immediate range (m) |
| --- | --- | --- | --- |
| Driving | 80–1200 | 35–480 | 10–120 |
| Cycling | 80–300 | 35–120 | 10–30 |
| Walking | 80–100 | 35–40 | 10 |

At 36 km/h the stages are 300/120/30 m; at 108 km/h they are 900/360/90 m.
Invalid speeds fall back to minimum distances; invalid maneuver distances are ignored.
A jump in GPS position speaks only the most urgent eligible stage. Previously passed stages
are never backfilled, including after speed changes, distance jitter or temporarily returning
to the same maneuver. A new calculated route starts a new announcement history. Spoken
distance comes from the current maneuver distance, not a fixed threshold. Stop/arrival
notifications retain their separate once-only behavior, and muted guidance remains muted.

## Validation and device handoff

Local command from `android/`, with JDK 17 and Android SDK configured:

```bash
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
```

Tests cover three-stage maximum, speed-based anticipation, skipped stages, repeated GPS
positions and existing parking replacement/arrival behavior.

Local result: 298 Android unit tests passed; lint, APK assembly and signature verification
passed. Device acceptance remains pending.

Install `dist/compass-0.28.52.apk` or accept the automatic update. No backend redeploy.

1. From base follow, save parking: expect a favorites confirmation and a single Parcheggio
   entry. Save again: cancel must preserve the old position, confirm must replace it.
2. Reach the destination: expect follow mode and the arrival notice disappearing after
   five seconds without a tap; test manual dismissal as well.
3. During a safe passenger-assisted test or replay, check there are at most three spoken
   alerts for one maneuver. At higher speed alerts begin farther away; close successive
   turns should skip obsolete levels rather than replay them. Check mute and stop notices.

If behavior differs, return APK/device version, action sequence and bounded diagnostics:

```bash
adb logcat -d -s CompassNavigation CompassVoice AndroidRuntime
```

Physical-device timing and audio behavior remain operator validation, not a local-test claim.
