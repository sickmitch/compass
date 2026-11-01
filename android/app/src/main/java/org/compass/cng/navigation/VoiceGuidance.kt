package org.compass.cng.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

interface VoiceGuidance {
    fun speak(announcement: VoiceAnnouncement, onFinished: () -> Unit = {})
    fun stop()
    fun shutdown()
}

/** Completion ownership is independent of late/duplicate TTS callbacks. */
internal class SpeechCompletions {
    private val callbacks = mutableMapOf<String, () -> Unit>()
    val isEmpty: Boolean get() = callbacks.isEmpty()
    fun add(id: String, callback: () -> Unit) { callbacks[id] = callback }
    fun complete(id: String) { callbacks.remove(id)?.invoke() }
    fun cancelAll() {
        val cancelled = callbacks.values.toList()
        callbacks.clear()
        cancelled.forEach { it() }
    }
}

/** Service-owned speech using system navigation/media volume and transient ducking. */
class AndroidTextToSpeechVoiceGuidance(context: Context) : VoiceGuidance,
    TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) stop()
        }, handler)
        .build()
    private var hasFocus = false
    private var ready = false
    private var failed = false
    private var closed = false
    private var sequence = 0L
    private val completions = SpeechCompletions()
    private var pending: Pair<VoiceAnnouncement, () -> Unit>? = null
    private val timeout = Runnable {
        Log.w("CompassVoice", "Speech timeout; releasing audio focus")
        stop()
    }
    private val textToSpeech = TextToSpeech(context.applicationContext, this)

    init {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Legacy TTS callback")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        handler.post {
            if (id != null) completions.complete(id)
            if (completions.isEmpty) {
                handler.removeCallbacks(timeout)
                releaseFocus()
            }
        }
    }

    override fun onInit(status: Int) {
        handler.post {
            if (closed) return@post
            val language = if (status == TextToSpeech.SUCCESS) textToSpeech.setLanguage(Locale.ITALIAN)
                else TextToSpeech.LANG_NOT_SUPPORTED
            ready = status == TextToSpeech.SUCCESS && language >= 0
            failed = !ready
            if (ready) textToSpeech.setAudioAttributes(attributes)
            val waiting = pending
            pending = null
            if (waiting != null) {
                if (ready) speak(waiting.first, waiting.second) else waiting.second()
            }
            if (!ready) {
                handler.removeCallbacks(timeout)
                Log.w("CompassVoice", "Italian text-to-speech unavailable")
            }
        }
    }

    override fun speak(announcement: VoiceAnnouncement, onFinished: () -> Unit) {
        if (closed || failed) { onFinished(); return }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 30_000L)
        if (!ready) {
            val previous = pending
            pending = announcement to onFinished
            previous?.second?.invoke()
            return
        }
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!hasFocus) {
            handler.removeCallbacks(timeout)
            Log.w("CompassVoice", "Audio focus denied; speech skipped")
            onFinished()
            return
        }
        val id = "${announcement.id}:${++sequence}"
        completions.add(id, onFinished)
        if (textToSpeech.speak(announcement.text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
            finish(id)
        }
    }

    private fun releaseFocus() {
        if (hasFocus) {
            hasFocus = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    override fun stop() {
        handler.removeCallbacks(timeout)
        val waiting = pending
        pending = null
        textToSpeech.stop()
        releaseFocus()
        completions.cancelAll()
        waiting?.second?.invoke()
    }

    override fun shutdown() {
        closed = true
        stop()
        textToSpeech.shutdown()
    }
}
