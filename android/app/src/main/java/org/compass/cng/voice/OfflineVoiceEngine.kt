package org.compass.cng.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.atomic.AtomicBoolean

/** Native ownership is confined to the calling worker. Cancellation never deletes a decoding model. */
class OfflineVoiceEngine(private val context: Context, private val models: VoiceModels,
    private val timing: VoiceTiming = VoiceTiming()) {
    fun recognize(cancel: VoiceCancellation, finish: AtomicBoolean,
        phase: (VoicePhase) -> Unit, metric: (String, Long) -> Unit,
        benchmarkAudio: FloatArray? = null): String {
        var recognizer: OfflineRecognizer? = null
        var vad: Vad? = null
        try {
            val load = SystemClock.elapsedRealtime()
            cancel.check()
            if (!models.valid(cancel)) throw VoiceFailure("model_missing", "Scarica prima il modello vocale.")
            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = models.file(models.spec.encoder).path,
                        decoder = models.file(models.spec.decoder).path,
                        language = "it", task = "transcribe"),
                    tokens = models.file(models.spec.tokens).path, numThreads = 2, provider = "cpu", debug = false)))
            cancel.check()
            vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = models.file(models.spec.vad).path), sampleRate = 16000, numThreads = 1, debug = false))
            metric("load_ms", SystemClock.elapsedRealtime() - load)
            metric("loaded_pss_kb", android.os.Debug.getPss())
            cancel.check()
            val recording = SystemClock.elapsedRealtime()
            require(benchmarkAudio == null || benchmarkAudio.size in 1600..320000)
            val audio = benchmarkAudio?.copyOf() ?: capture(vad, cancel, finish) { phase(VoicePhase.LISTENING) }
            metric("record_ms", SystemClock.elapsedRealtime() - recording)
            metric("audio_ms", audio.size * 1000L / 16000)
            cancel.check()
            phase(VoicePhase.PROCESSING)
            val inference = SystemClock.elapsedRealtime()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(audio, 16000)
                recognizer.decode(stream) // Synchronous JNI; release only after this call returns.
                metric("inference_ms", SystemClock.elapsedRealtime() - inference)
                metric("inference_pss_kb", android.os.Debug.getPss())
                cancel.check()
                return recognizer.getResult(stream).text.trim().takeIf { it.isNotBlank() }
                    ?: throw VoiceFailure("empty", "Non ho riconosciuto la richiesta. Riprova.")
            } finally { audio.fill(0f); stream.release() }
        } finally { try { vad?.release() } finally { recognizer?.release() } }
    }

    @SuppressLint("MissingPermission") // Explicit permission check immediately before AudioRecord creation.
    private fun capture(vad: Vad, cancel: VoiceCancellation, finish: AtomicBoolean,
        listening: () -> Unit): FloatArray {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw VoiceFailure("permission", "Consenti l’accesso al microfono per la ricerca vocale.")
        val manager = context.getSystemService(AudioManager::class.java)
        if (manager.mode != AudioManager.MODE_NORMAL)
            throw VoiceFailure("call", "Microfono occupato da una chiamata o da un’altra registrazione.")
        val interrupted = AtomicBoolean(false)
        val captureActive = AtomicBoolean(true)
        val handler = Handler(Looper.getMainLooper())
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ if (it < 0) interrupted.set(true) }, handler).build()
        val captured = FloatArray(timing.maximumSamples)
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw VoiceFailure("focus", "Audio occupato. Riprova tra poco.")
        var recorder: AudioRecord? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        var routeListener: AudioRouting.OnRoutingChangedListener? = null
        try {
            for (rate in listOf(16000, 48000, 44100)) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) continue
                val candidate = runCatching { AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, rate / 2)).build() }.getOrNull() ?: continue
                if (candidate.state == AudioRecord.STATE_INITIALIZED) { recorder = candidate; break }
                candidate.release()
            }
            val record = recorder ?: throw VoiceFailure("microphone", "Microfono non disponibile sul dispositivo.")
            // A predictable handset route avoids silent SCO/8 kHz capture and needs no Bluetooth permission.
            val builtin = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: throw VoiceFailure("microphone", "Microfono del telefono non disponibile.")
            if (!record.setPreferredDevice(builtin)) throw VoiceFailure("route", "Impossibile selezionare il microfono del telefono.")
            routeListener = AudioRouting.OnRoutingChangedListener {
                if (captureActive.get()) {
                    val device = runCatching { record.routedDevice }.getOrNull()
                    if (device != null && device.id != builtin.id) interrupted.set(true)
                }
            }
            record.addOnRoutingChangedListener(routeListener, handler)
            if (Build.VERSION.SDK_INT >= 29) {
                callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (captureActive.get() && configs.any { it.clientAudioSessionId == record.audioSessionId && it.isClientSilenced }) interrupted.set(true)
                    }
                }
                record.registerAudioRecordingCallback(context.mainExecutor, callback)
            }
            cancel.check()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                throw VoiceFailure("microphone", "Impossibile avviare il microfono.")
            if (record.routedDevice?.let { it.id != builtin.id } == true)
                throw VoiceFailure("route", "Il microfono selezionato non è disponibile. Scollega l’auricolare e riprova.")
            listening()
            val resampler = PcmResampler(record.sampleRate)
            val pcm = ShortArray(1024)
            val frames = ArrayDeque<Float>()
            val endpoint = SpeechEndpoint(timing)
            var count = 0
            var lastRead = SystemClock.elapsedRealtime()
            while (!endpoint.complete) {
                cancel.check()
                if (interrupted.get() || manager.mode != AudioManager.MODE_NORMAL)
                    throw VoiceFailure("interrupted", "Registrazione interrotta da una chiamata o da un cambio audio. Riprova.")
                if (finish.get()) break
                val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) throw VoiceFailure("capture", "Errore di registrazione. Riprova.")
                if (n == 0) {
                    if (SystemClock.elapsedRealtime() - lastRead > 3000)
                        throw VoiceFailure("capture_timeout", "Il microfono non sta fornendo audio. Riprova.")
                    Thread.sleep(10); continue
                }
                lastRead = SystemClock.elapsedRealtime()
                resampler.accept(pcm, n).forEach { frames.addLast(it) }
                while (frames.size >= 512 && !endpoint.complete) {
                    val frame = FloatArray(512) { frames.removeFirst() }
                    if (count + frame.size > captured.size) throw VoiceFailure("too_long", "Richiesta troppo lunga. Riprova.")
                    frame.copyInto(captured, count); count += frame.size
                    endpoint.accept(vad.compute(frame))
                }
            }
            if (endpoint.speechStart < 0) throw VoiceFailure("no_speech", "Non ho sentito una richiesta. Riprova.")
            return captured.copyOfRange(endpoint.firstSample, count)
        } finally {
            captureActive.set(false)
            captured.fill(0f)
            recorder?.let { record ->
                if (Build.VERSION.SDK_INT >= 29) callback?.let { runCatching { record.unregisterAudioRecordingCallback(it) } }
                routeListener?.let { runCatching { record.removeOnRoutingChangedListener(it) } }
                runCatching { record.stop() }; runCatching { record.release() }
            }
            manager.abandonAudioFocusRequest(focus)
        }
    }
}
