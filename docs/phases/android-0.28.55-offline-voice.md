# Android 0.28.55 — offline Italian voice search

Field update, 6 November 2026: the operator reported unusable recognition on FP4 / Android 15.
The quality gate has failed; see [the investigation and 0.28.56 favorite-resolution fix](android-0.28.56-voice-quality.md).
The local results below remain integration/build evidence, not speech-accuracy evidence.

## Scope and acceptance stages

This Android-only increment adds an explicit microphone to the existing destination, origin and
ordinary-stop search field. It preserves typed search, geographic bias, ranking and selection.
Architecture inspection and decisions are in [ADR 0023](../adr/0023-offline-voice-destination-search.md).
No API, database, backend configuration, migration or server redeploy is required.

| Stage | Implemented / local verification | Required device acceptance |
| --- | --- | --- |
| Existing search integration | ViewModel tests: favorite Lavoro, ambiguous/partial favorites, CNG dispatch, 800 ms debounce and GPS bias | Type and speak the same POI; compare result flow; confirm saved coordinates |
| Model management | Pinned CPU AAR and four model assets; checksum/corruption/cancellation/reuse tests | Download, cancel, retry, offline reuse, delete and reinstall |
| Capture and endpoint | Mono PCM16, 16 kHz; 44.1/48 kHz resampling tests; VAD endpoint/preroll/timeout tests | Real speech, pauses, cabin noise, permission denial, calls and Bluetooth |
| Native recognition and lifecycle | Official Kotlin API compiles; worker ownership, cancellation checks and resource cleanup | Real model loading/recognition, background during capture/decode, no late result |
| Performance | Opt-in instrumentation benchmark compiles; numeric diagnostics in production | Run on actual devices; report latency, memory and recognition accuracy |

Native recognition, hardware microphone capture and physical-device performance have **not** been
validated by repository-local tests. No performance numbers are asserted for Whisper base yet.

## Local build

From the repository root, with JDK 17 and Android SDK 37 configured in `JAVA_HOME` and
`ANDROID_SDK_ROOT`:

```bash
android/gradlew -p android --no-daemon --max-workers=2 \
  testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.venv/bin/ruff check src tests scripts
.venv/bin/pytest -q -m 'not integration'
.venv/bin/python scripts/export-openapi.py --check
```

The initial build downloads the official 38.6 MB sherpa AAR from GitHub, with a pinned SHA-256;
subsequent builds verify the cached artifact. Model weights are not bundled or fetched by these
tests. Assembly publishes `dist/compass-0.28.55.apk`, versionCode 101, and updates `dist/last.txt`.
The universal APK is approximately 77 MiB with compressed native libraries. Runtime models add
161,253,144 bytes, plus temporary download space; installed native libraries also take space.

Local result for this increment: **335 Android JVM tests passed**, **456 Python tests passed**
(six database integration tests excluded). Ruff and OpenAPI synchronization passed. `lintDebug`
completed with zero errors and 94 warnings; APK and instrumentation-test APK assembly passed.
APK inspection confirmed versionCode 101, all four ABIs, packaged licenses and no bundled ONNX
weights; signature verification matched 0.28.54. The device benchmark was compiled, not executed.

## Operator phone checks

Install 0.28.55 using the existing automatic updater. Keep the existing server profile and
credentials configured for ordinary online place search; no new secret or voice service is needed.
Run these checks while stationary:

1. In destination search open the microphone, approve the approximately 162 MB model download,
   then `Parla` and grant microphone access. Wait for `Parla usando il microfono del telefono`
   before speaking. Say `Portami in Via Roma numero ventotto Verona`, pause, inspect/edit the
   transcript and choose `Usa testo`. Expect the existing result list and manual selection.
2. Save a favorite named `Lavoro`, then say `Portami al lavoro`. After `Usa testo`, expect that
   favorite's saved coordinates selected, without automatic navigation or a Places lookup.
   Test `Portami al parcheggio` if a parking location exists. With only `Lavoro Milano` and
   `Lavoro Verona`, expect a favorites choice. A partial single match still requires selection.
3. With a valid driving route, say `Cerca un distributore di metano`: expect the existing CNG
   planning controls. Without a valid route, expect an instruction to set one first, not a
   generic Places result masquerading as the CNG inventory. Test ordinary intermediate-stop
   voice search as well as origin/destination search.
4. Disable networking **after** installing the model. Recognition and choosing Lavoro must
   still work. External POIs/new route calculation may fail through the existing offline/error
   behavior; this is not an offline geocoder or routing engine. Restore networking afterwards.
5. Pause for less than 1.2 s inside a phrase: recording should continue. Silence at the start
   should report no speech after about 8 s; speech beyond 20 s should ask for a shorter request.
   Check that the first/last words survive ordinary pauses. Try backgrounding while listening
   and while processing: capture must stop, no result may subsequently navigate/change the query.
6. Deny the microphone permission; typing must remain usable. Retry after allowing it in app
   settings. Test an incoming call and Bluetooth connection/disconnection during capture. The
   supported input is the **phone microphone**, including with a headset connected. An unavailable
   route or call must yield an interruption/error, not a silent successful result.
7. Cancel a model download and retry: verified complete files are reused, partial ones restarted.
   Delete via `Rimuovi modello scaricato`: the next voice request must offer download again.
   Check the dialog and license viewer in both themes and with the keyboard visible.

## Reproducible CPU benchmark (optional device test)

This compares repeated native loads/decodes with the **same** audio instead of variable speech.
It does not replace microphone/VAD or recognition-quality checks. Prerequisites: an authorized
ADB device, the matching app/test APKs from the local build, model already installed through the
app, and a personal 0.1–20 second Italian WAV called `voice-benchmark.wav` in the repository root.
The WAV must be RIFF PCM16, mono, 16 kHz; do not commit it. No cloud transcription or provider API
is called. From the repository root (`adb` from Android Platform-Tools on PATH):

```bash
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell 'run-as org.compass.cng.debug sh -c "cat > files/voice-benchmark.wav"' < voice-benchmark.wav
adb shell am instrument -w \
  -e class org.compass.cng.voice.VoiceBenchmarkTest \
  -e voiceBenchmarkFile voice-benchmark.wav \
  org.compass.cng.debug.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s CompassVoiceBenchmark:I CompassVoice:I '*:S'
adb shell run-as org.compass.cng.debug rm files/voice-benchmark.wav
```

Expected: one instrumentation test passes, with three numbered runs producing `load_ms`,
`record_ms`, `audio_ms`, `inference_ms`, `loaded_pss_kb`, `inference_pss_kb` and `total_ms`.
For supplied WAVs, `record_ms` is only buffer preparation; actual microphone timing is measured
by the normal UI flow under `CompassVoice`. `load_ms` includes checksum verification and native
model construction. PSS is whole-process sampled memory, **not peak recognizer memory**.
The models are re-created for each run, but OS filesystem caches may remain warm. Without the
explicit argument the instrumentation benchmark is skipped, not reported as measured.

Use the same phrase on at least a lower-memory and a typical target device; report device model,
Android version, RAM, audio duration, three timing samples, PSS, and whether the transcription was
correct. No transcript is logged by the benchmark; do not post private speech or audio. Calculate
decode real-time factor as `inference_ms / audio_ms`; acceleration or small-model decisions wait
for these measurements. No hardware acceleration is enabled by this iteration.

## Diagnostics and limits

On failure, from a workstation with the phone attached:

```bash
adb logcat -d -s CompassVoice:I CompassVoiceBenchmark:I AndroidRuntime:E '*:S'
adb shell dumpsys package org.compass.cng.debug | rg 'versionName|versionCode|RECORD_AUDIO'
adb shell dumpsys meminfo org.compass.cng.debug
```

Return the failing numbered scenario, phone/Android version, visible error and these bounded logs
after checking for unrelated sensitive information. Do not return server credentials or private
audio. Missing models/network failures must leave typed search available. The download needs
GitHub/Hugging Face access but does not send recordings. Whisper may misrecognize short proper
names: editable confirmation remains intentional. Very short words/noise may fail VAD onset;
thresholds require device/cabin evidence before tuning.

The synchronous JNI decoder cannot be killed safely mid-call. Cancellation suppresses delivery
and waits for that call to return before unloading; the UI remains responsive but starting another
recording waits for cleanup. Low-memory/native crashes, OEM audio routing, speech accuracy and
16 KiB-page device compatibility still require hardware evidence. The shipped sherpa ELF LOAD
segments have 16 KiB alignment; static alignment inspection alone is not a device test.

Next gate: return the phone scenarios and benchmark output. This iteration adds no further models,
cloud speech, automatic ambiguous-destination navigation or backend service.
