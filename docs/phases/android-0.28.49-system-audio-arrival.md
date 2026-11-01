# Android 0.28.49 — System audio and arrival notification

TTS uses navigation-guidance speech audio attributes and transient MAY_DUCK audio focus.
The app's volume keys control the system media stream; Compass does not force a volume
level or modify other applications' sliders. Focus is released when queued speech finishes,
fails, is stopped/muted or loses focus. A watchdog releases it if a TTS engine never
completes. Initialization failure and denied focus complete the pending request without
playing over another protected audio session. Android and the other audio app determine
whether the latter ducks or pauses.

At final arrival the service stops location/recalculation work, announces “Destinazione
raggiunta.” if guidance is enabled, and publishes an arrival event. The activity returns
to follow and displays a dismissible destination-reached dialog. The foreground service
remains alive only until speech completion/failure (maximum 30 seconds), avoiding premature
TTS shutdown and preserving background audio-focus eligibility. A new navigation cancels
the old completion callback so it cannot stop the new journey. Muted guidance remains muted.

References: [Android audio focus](https://developer.android.com/media/optimize/audio-focus).

## Operator device check

Install `dist/compass-0.28.49.apk`; no backend redeploy is required.

1. Play music, start guidance and trigger a maneuver. Music should attenuate (or pause if
   the player chooses), then recover after speech. Change media volume with hardware keys:
   speech follows that level, including zero. Repeat using Bluetooth if applicable.
2. Disable guidance while speaking: speech stops and music recovers. Re-enable for another
   maneuver. Check screen-off/PiP guidance and behavior during an incoming call.
3. Reach the final destination: one spoken arrival plus the screen notice, then follow.
   The notification/service should end after speech, with no repeated arrival. Repeat muted:
   the notice remains but no speech plays. Intermediate stops must not end the journey.
4. Start another journey immediately after arrival: an old speech-completion callback must
   not terminate it. Reopening the app must not resume the completed trip.

If it differs, return Android/device version, player/output used, the failing sequence,
and bounded diagnostics (no credentials):

```bash
adb logcat -d -s CompassVoice CompassNavigation
adb shell dumpsys audio
```

Unit tests cover completion ownership and arrival UI state. Compilation/lint validate Android
API usage; actual mixing, TTS engines and device audio routing need the operator checks above.
