# Android 0.28.56 — favorite resolution and voice-quality investigation

## Field outcome and scope

On 6 November 2026 the operator reported unusable transcription on **Fairphone 4, Android 15**.
The 0.28.55 voice-quality acceptance gate has therefore failed. Passing integration/unit tests
and producing nonempty speech-recognition output did not establish usable recognition accuracy.
This increment fixes favorite resolution across the saved catalog and improves the diagnostic
benchmark. It does **not** claim to fix the acoustic errors or change the deployed Whisper model.

| Spoken | Reported transcription | Diagnosis |
| --- | --- | --- |
| Portami al lavoro | porta mi al lavoro | Word separation plus an overly strict command parser prevented the local favorite lookup |
| Via Giovanni Pascoli | Vietro Vanni pascoli | Content substitution in the raw recognizer output, before query normalization |
| Bonavigo via cinque case | Buon navigo via 5 case | Incorrect place name; the spoken-number/digit representation alone does not establish an error |
| piazza bra verona | piazza braverona | Joined words in recognizer output; no evidence yet that the existing provider can/cannot recover the query |

These are operator-provided examples, not a representative error-rate measurement. The recordings,
ambient noise, Bluetooth state, start-of-speech timing and device latency measurements were not
provided. No live phone, microphone or native-recognition experiment was performed by the agent.

## Implemented: general favorite resolution

`FavoriteVoiceResolver.resolve` searches the user's **actual saved catalog**, including parking.
No destination names are hard-coded in production. It runs locally before external place search.

- Complete saved names take precedence, including names that start with a command word.
- Navigation phrases include joined/split pronouns, `vai`, `naviga`, `raggiungi`, `vorrei/voglio
  andare`, `avvia la navigazione`, and the relevant leading Italian prepositions.
- Matching normalizes case, accents, punctuation and spoken integer tokens. Articles/possessives
  and an explicit `preferito` / `posto preferito` / `luogo preferito` wrapper are considered.
- Whole-name word joins/splits can select a unique saved favorite. Collisions between equivalent
  names or competing contextual interpretations require a choice.
- Whole-word partial names and small spelling errors produce candidates only: **never automatic
  selection**. Spelling suggestions use deterministic edit distance over the complete compact
  name: at least four characters, at most one edit, or two when both names have at least eight
  characters, and no more than 25% of the longer name. This is a lexical matching rule, not an
  ASR confidence score. Arbitrary phonetic guesses are not applied to external place names.
- An explicit favorite request with no match opens the existing saved-places list. It does not
  send the unrecognized private favorite name to Google. An ordinary unmatched external query
  retains the existing search behavior. Existing coordinate selection and manual navigation
  start remain unchanged; ordinary typed search is untouched.

Fixtures cover different names/commands, articles, parking, number forms, joined/split words,
minor typos, collisions, absent favorites and a business whose name looks like a command.
ViewModel tests check saved coordinates and absence of provider calls, not just displayed text.

## Findings from the running code and pinned upstream source

References below describe the code inspected at this increment, not a measured diagnosis of
the operator's recording hardware.

| Finding | Evidence | Confidence / consequence |
| --- | --- | --- |
| Multilingual base int8, Italian forced, transcribe task, CPU with two threads | `voice/VoiceModels.kt:33`, `voice/OfflineVoiceEngine.kt:28` under Android main sources | Confirmed; this is not an English-only model or translate-task mistake |
| PCM16 converted to floats divided by 32768, 16 kHz or streaming low-pass resampling | `voice/VoiceAudio.kt:48`, `voice/OfflineVoiceEngine.kt:122` | No static sample-rate/amplitude-format mismatch identified; hardware quality remains unmeasured |
| Model verification/loading happens before recording starts on every request | `voice/OfflineVoiceEngine.kt:25` through `:44`, recorder start at `:118` | Confirmed gap after pressing Parla; losing early speech is possible, not demonstrated by the supplied transcripts |
| Phone microphone is forced even with Bluetooth | `voice/OfflineVoiceEngine.kt:96` | Confirmed; distant handset capture can differ from headset expectations. Actual route/noise unknown |
| No recording RMS, clipping, discontinuity or input-route metrics | Current capture and metric calls in `OfflineVoiceEngine` | Cannot distinguish acoustic damage from decoder errors from current logs |
| The result window contains raw recognized text | `voice/VoiceSearchController.kt:76` | Normalization and Places ranking cannot cause errors already visible in that window |
| Favorites previously relied on exact words/simple prefixes | Replaced by `FavoriteVoiceResolver`; operator example reproduced by regression tests | Confirmed independent defect; fixed without changing the acoustic model |
| The original benchmark asserted only nonempty output | `VoiceBenchmarkTest`, prior release 0.28.55 | Could pass with completely wrong words; optional reference comparison is added below |

All Android production paths above are under `android/app/src/main/java/org/compass/cng/`.

In **sherpa-onnx v2.0.1**, the [Whisper implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h#L50)
accepts only `greedy_search`; selecting `modified_beam_search` is not a valid improvement and
can terminate the native process. The [Kotlin Whisper configuration](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflineRecognizer.kt#L91)
does not expose initial vocabulary prompts. Generic hotword options elsewhere in that API must
not be assumed to bias this Whisper decoder. Current favorite names are not acoustic hints.

The Kotlin default is `tailPaddings=1000` **feature frames**, not milliseconds. The native code
uses `min(audio_frames + tail_frames, 3000)`. A short request thus uses a shorter encoder input
than the original 30-second Whisper context. The [maintainer discussion](https://github.com/k2-fsa/sherpa-onnx/discussions/2787)
acknowledges hallucinations on some short inputs and an accuracy/speed tradeoff. This is a
testable suspect, not proof of the cause on FP4. A `tailPaddings=3000` experiment fills the encoder
length to 3000 frames; it is not a claim of numerically identical preprocessing to OpenAI Whisper.
It also does not mean recording 30 seconds or waiting through 30 seconds of silence.

## Recommended experiments, in order

1. **Separate input quality from decoding.** Add numeric input-rate, routed-microphone type,
   speech-start delay, RMS/peak/clipping and capture-discontinuity diagnostics. Verify recording
   begins before the user speaks, with an unambiguous ready indication. Evaluate model prewarming
   and bounded idle retention only with measured memory; never keep the microphone open in the
   background. Do not add blanket amplification or aggressive noise suppression without audio
   evidence. Android's [recording documentation](https://developer.android.com/media/platform/mediarecorder)
   describes `VOICE_RECOGNITION` as avoiding AGC/noise suppression; it is not an automatic cabin
   denoiser. Optional local replay/export would need explicit user consent, not production logging.
2. **A/B the same audio:** current base int8/default padding versus base int8/full encoder length.
   Keep language, VAD boundaries and audio identical. Measure exact request/place-name accuracy
   and latency; changing several things together would hide the source of improvement.
3. **Compare multilingual small int8 under the winning conditions.** This is the first model
   upgrade to evaluate while preserving sherpa-onnx. In the [Whisper paper, FLEURS Italian table 13](https://cdn.openai.com/papers/whisper.pdf),
   base has 17.9% WER and small 9.8%. These published results are not Compass/ONNX-int8/FP4 results
   and do not guarantee performance for short street names. The current
   [small export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/tree/8f3c18b358db4d1f2fc1eae49d75cd20989e4309)
   has 112,442,483-byte encoder, 262,226,114-byte decoder and 816,730-byte tokens: approximately
   **376 MB including VAD**, versus the current 162 MB. Download size is not runtime RAM.
   FP4's [Qualcomm 750G platform](https://www.fairphone.com/wp-content/uploads/2022/09/FP4_Information-for-repairers-and-recyclers.pdf)
   makes device latency measurements necessary; desktop/GPU timings are not phone estimates.
4. **Only if these fail the quality/latency gate, consider a runtime change.**
   [whisper.cpp's API](https://github.com/ggml-org/whisper.cpp/blob/master/include/whisper.h)
   exposes beam search, initial prompts and recognition rejection/fallback controls. Local
   favorite names could become bounded vocabulary context, with bias/hallucination tests.
   This would change the original sherpa requirement and JNI/build/model management, so it is
   an alternative architecture, not an already implemented switch. Android 15 also permits
   [on-device SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer#createOnDeviceSpeechRecognizer(android.content.Context))
   where a compatible offline Italian recognizer is installed. Android version alone does not
   guarantee availability or quality. The ordinary recognizer or `PREFER_OFFLINE` alone must not
   silently replace the strict no-cloud requirement. Neither alternative is enabled here.

Do not assume int8 quantization is the cause without a same-audio comparison. Do not add
special substitutions such as `Vietro Vanni → Via Giovanni` or a blacklist of hallucinations:
these would conceal recognition failures and can turn a different name into the wrong place.

## Quality measurement and operator handoff

The next gate uses the operator's examples plus a broader set of actual favorite labels,
addresses, civic numbers, businesses, ambiguous names and silence/noise negatives. Report
raw word errors separately from whether the **correct favorite/place** is recovered; `5` versus
`cinque` need not change the intended destination. Use identical recordings for A/B runs and
separate quiet/cabin conditions. Define a held-out set so rules are not tailored to four examples.
Suggested targets (not achieved results): at least 95% correct simple favorite intents and 90%
correct address/POI requests in quiet, no automatic approximate/ambiguous selection, no false
navigation on noise, and an operator-acceptable latency measured on FP4. Record P50/P95 latency
after end of speech plus model loading time and sampled memory separately.

Install **0.28.56** via the existing updater. The existing downloaded model is reused; no backend
sync, redeploy, migration or model download is required. While stationary, test several **saved**
names, not just Lavoro:

- An exact label, `vai da <nome>`, `portami alla mia <nome>`, a label with separated/joined words,
  and a favorite with a number pronounced as a word: unique whole-name matches use saved coordinates.
- A slight typo and a partial name shared by two favorites: the saved-places choice must appear,
  with no automatic navigation. Confirm one and verify its coordinates/name.
- `vai al preferito <unknown>`: show the local favorites list, not external search.
- An unrelated external POI still enters normal search. Text search, CNG planning and parking
  remain available. The other reported acoustic errors are still open acceptance cases.

For reproducible recognition checks, follow the [existing WAV benchmark preparation](android-0.28.55-offline-voice.md#reproducible-cpu-benchmark-optional-device-test).
Additionally create a local UTF-8 `voice-expected.txt` containing the intended utterance; do not
commit private audio/reference files. With the matching app/test APK installed and ADB authorized,
from repository root:

```bash
adb shell 'run-as org.compass.cng.debug sh -c "cat > files/voice-expected.txt"' < voice-expected.txt
adb shell am instrument -w \
  -e class org.compass.cng.voice.VoiceBenchmarkTest \
  -e voiceBenchmarkFile voice-benchmark.wav \
  -e voiceBenchmarkExpectedFile voice-expected.txt \
  org.compass.cng.debug.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s CompassVoiceBenchmark:I CompassVoice:I '*:S'
adb shell run-as org.compass.cng.debug rm files/voice-expected.txt
```

With a reference, `normalized_query_exact=0` now fails the test, even if recognition returns text.
Comparison uses command/civic normalization and case/accent normalization; it is **not** WER or
geographic-equivalence scoring. Without a reference the benchmark explicitly logs
`accuracy_measured=0`: a timing-only pass must not be presented as a quality pass. No expected
or recognized transcript is logged. Return the compact output and failing scenario; timing and
normalization success alone do not prove acoustic quality. For UI failures also return a bounded
`adb logcat -d -s CompassVoice:I AndroidRuntime:E '*:S'`, without credentials/private audio.

## Repository validation

```bash
android/gradlew -p android --no-daemon --max-workers=2 \
  testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Use the documented JDK 17 / Android SDK 37 environment. This release adds no runtime dependency
or model and keeps the existing offline/privacy boundaries. Native A/B experiments and the
reference-audio instrumentation test require operator-supplied phone evidence; they have not
been executed by the agent. The next smallest step is input-quality measurement and a controlled
base-padding/small comparison, not claiming the voice-quality phase complete.

Local results: **347 Android JVM tests passed**, zero failures/errors/skips. `lintDebug` completed
with zero errors and 94 warnings. App and instrumentation-test APKs assembled successfully.
The distributed APK is `dist/compass-0.28.56.apk`, versionCode 102, about 77 MiB; signature
verification matches 0.28.55 and `dist/last.txt` points to it. The backend is unchanged; its suite
was not rerun for this Android-only increment. Documentation links and `git diff --check` passed.
