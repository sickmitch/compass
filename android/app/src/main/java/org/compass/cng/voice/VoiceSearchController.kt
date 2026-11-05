package org.compass.cng.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class VoicePhase { IDLE, LISTENING, PROCESSING, SUCCESS, ERROR }
data class VoiceUiState(val phase: VoicePhase = VoicePhase.IDLE, val busy: Boolean = false,
    val hasModel: Boolean = false, val text: String = "", val message: String = "",
    val downloadPercent: Int? = null)

/** One serialized CPU/native owner for the application; microphone lives only during an explicit request. */
class VoiceSearchController(context: Context, private val models: VoiceModels) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CompassVoiceCPU").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(VoiceUiState())
    val state = mutable.asStateFlow()
    private var cancellation: VoiceCancellation? = null
    private val finish = AtomicBoolean(false)

    private fun operation(message: String, body: (VoiceCancellation) -> VoiceUiState) {
        if (mutable.value.busy) return
        val token = VoiceCancellation()
        cancellation = token
        mutable.value = mutable.value.copy(phase = VoicePhase.PROCESSING, busy = true,
            text = "", message = message, downloadPercent = null)
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val next = withContext(worker) { body(token).also { token.check() } }
                mutable.value = next
            } catch (_: VoiceCancelled) {
                mutable.value = VoiceUiState(hasModel = mutable.value.hasModel)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error !is Exception && error !is LinkageError && error !is OutOfMemoryError) throw error
                val code = if (token.cancelled.get()) "cancelled" else (error as? VoiceFailure)?.code ?: when (error) {
                    is SecurityException -> "permission"
                    is LinkageError -> "native_unavailable"
                    is OutOfMemoryError -> "memory"
                    else -> "voice_unavailable"
                }
                Log.w("CompassVoice", "error=$code") // Never log exception messages, audio or recognized text.
                mutable.value = if (token.cancelled.get()) VoiceUiState(hasModel = mutable.value.hasModel)
                else VoiceUiState(phase = VoicePhase.ERROR,
                    hasModel = mutable.value.hasModel && code !in setOf("model_missing", "checksum"),
                    message = (error as? VoiceFailure)?.userMessage ?: when (code) {
                        "permission" -> "Permesso microfono non disponibile. Controlla le impostazioni dell’app."
                        "memory" -> "Memoria insufficiente. Chiudi altre app e riprova."
                        "native_unavailable" -> "Riconoscimento vocale non supportato su questo dispositivo."
                        else -> "Ricerca vocale non disponibile. Controlla il modello o riprova."
                    })
            } finally {
                cancellation = null
                metric("total_ms", SystemClock.elapsedRealtime() - started)
                mutable.value = mutable.value.copy(busy = false, downloadPercent = null)
            }
        }
    }
    private fun post(token: VoiceCancellation, transform: (VoiceUiState) -> VoiceUiState) {
        scope.launch { if (cancellation === token && !token.cancelled.get()) mutable.value = transform(mutable.value) }
    }
    fun checkModel() = operation("Verifico il modello…") { token -> VoiceUiState(hasModel = models.valid(token)) }
    fun download() = operation("Scarico il modello vocale…") { token ->
        models.install(token) { percent -> post(token) { it.copy(downloadPercent = percent) } }
        VoiceUiState(hasModel = true, message = "Modello pronto. Tocca Parla per iniziare.")
    }
    fun removeModel() = operation("Rimuovo il modello…") { token -> token.check(); models.remove(); VoiceUiState() }
    fun start() {
        finish.set(false)
        operation("Preparo il riconoscimento…") { token ->
            val raw = OfflineVoiceEngine(appContext, models).recognize(token, finish, { phase ->
                post(token) { it.copy(phase = phase, message = if (phase == VoicePhase.LISTENING)
                    "Parla usando il microfono del telefono." else "Elaboro sul telefono…") }
            }, ::metric)
            token.check()
            if (raw.length > 200) throw VoiceFailure("query_long", "Richiesta troppo lunga. Usa una frase più breve.")
            if (raw.isBlank()) throw VoiceFailure("empty", "Richiesta vuota. Riprova.")
            VoiceUiState(VoicePhase.SUCCESS, hasModel = true, text = raw)
        }
    }
    fun finishSpeech() { finish.set(true) }
    fun cancel() { cancellation?.cancel() }
    fun permissionDenied() {
        mutable.value = mutable.value.copy(phase = VoicePhase.ERROR,
            message = "Permesso microfono negato. Puoi continuare a scrivere o abilitarlo nelle impostazioni dell’app.")
    }
    private fun metric(name: String, value: Long) { Log.i("CompassVoice", "$name=$value") }
}
