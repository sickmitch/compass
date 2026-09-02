package org.compass.cng.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

interface VoiceGuidance {
    fun speak(announcement: VoiceAnnouncement)
    fun shutdown()
}

/** Android TTS adapter owned by the foreground service, independent from Activity lifetime. */
class AndroidTextToSpeechVoiceGuidance(context: Context) : VoiceGuidance,
    TextToSpeech.OnInitListener {
    private var ready = false
    private var pending: VoiceAnnouncement? = null
    private val textToSpeech = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            textToSpeech.language = Locale.ITALIAN
            pending?.let(::speak)
            pending = null
        }
    }

    override fun speak(announcement: VoiceAnnouncement) {
        if (!ready) {
            pending = announcement
            return
        }
        textToSpeech.speak(
            announcement.text,
            TextToSpeech.QUEUE_ADD,
            null,
            announcement.id,
        )
    }

    override fun shutdown() {
        pending = null
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}
