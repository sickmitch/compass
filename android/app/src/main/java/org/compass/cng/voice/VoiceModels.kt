package org.compass.cng.voice

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

class VoiceCancelled : Exception()
class VoiceFailure(val code: String, val userMessage: String) : Exception(code)
class VoiceCancellation {
    val cancelled = AtomicBoolean(false)
    @Volatile var cancelNetwork: (() -> Unit)? = null
    fun cancel() { cancelled.set(true); cancelNetwork?.invoke() }
    fun check() { if (cancelled.get()) throw VoiceCancelled() }
}

data class ModelAsset(val name: String, val url: String, val bytes: Long, val sha256: String)
data class WhisperModelSpec(val id: String, val encoder: String, val decoder: String,
    val tokens: String, val vad: String, val assets: List<ModelAsset>) {
    init {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(assets.map { it.name }.toSet() == setOf(encoder, decoder, tokens, vad))
        assets.forEach { require(it.name.matches(Regex("[a-zA-Z0-9._-]+")) && it.name !in setOf(".", "..") &&
            it.sha256.matches(Regex("[a-f0-9]{64}")) && it.bytes > 0 && it.url.startsWith("https://")) }
    }
    val totalBytes get() = assets.sumOf { it.bytes }
}

object VoiceModelCatalog {
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/" +
        "bb53ee204431c90d314c1cc08d28d23e5b7927cc/"
    val base = WhisperModelSpec("whisper-base-multilingual-int8-v1", "base-encoder.int8.onnx",
        "base-decoder.int8.onnx", "base-tokens.txt", "silero_vad.onnx", listOf(
            ModelAsset("base-encoder.int8.onnx", BASE + "base-encoder.int8.onnx", 29120534,
                "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
            ModelAsset("base-decoder.int8.onnx", BASE + "base-decoder.int8.onnx", 130672026,
                "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
            ModelAsset("base-tokens.txt", BASE + "base-tokens.txt", 816730,
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ModelAsset("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "asr-models/silero_vad.onnx", 643854,
                "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
        ))
}

/** Private files, pinned hashes, no archives or credentials. All methods run on the voice worker. */
class VoiceModels(private val root: File, val spec: WhisperModelSpec = VoiceModelCatalog.base,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.MINUTES).build()) {
    val directory get() = File(root, spec.id)
    fun file(name: String) = File(directory, name)

    fun valid(cancel: VoiceCancellation): Boolean = spec.assets.all { matches(file(it.name), it, cancel) }
    fun remove() { directory.deleteRecursively() }

    fun install(cancel: VoiceCancellation, progress: (Int) -> Unit) {
        directory.mkdirs()
        if (directory.usableSpace < spec.totalBytes + 20_000_000)
            throw VoiceFailure("storage", "Spazio insufficiente per il modello vocale.")
        var complete = 0L
        for (asset in spec.assets) {
            cancel.check()
            if (matches(file(asset.name), asset, cancel)) { complete += asset.bytes; continue }
            val part = file(asset.name + ".part")
            val call = client.newCall(Request.Builder().url(asset.url).build())
            cancel.cancelNetwork = { call.cancel() }
            try {
                cancel.check()
                call.execute().use { response ->
                    if (!response.isSuccessful || !response.request.url.isHttps)
                        throw IOException("Model download failed")
                    response.body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val hash = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(65536)
                            var received = 0L
                            var lastPercent = -1
                            while (true) {
                                cancel.check()
                                val n = input.read(buffer)
                                if (n < 0) break
                                received += n
                                if (received > asset.bytes) throw IOException("Model too large")
                                hash.update(buffer, 0, n); output.write(buffer, 0, n)
                                val percent = ((complete + received) * 100 / spec.totalBytes).toInt()
                                if (percent != lastPercent) { progress(percent); lastPercent = percent }
                            }
                            if (received != asset.bytes || hash.hex() != asset.sha256)
                                throw VoiceFailure("checksum", "Verifica del modello fallita. Riprova il download.")
                            output.fd.sync()
                        }
                    }
                }
                cancel.check()
                Files.move(part.toPath(), file(asset.name).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
                complete += asset.bytes
            } finally { cancel.cancelNetwork = null; part.delete() }
        }
        progress(100)
    }

    companion object {
        internal fun matches(file: File, asset: ModelAsset, cancel: VoiceCancellation): Boolean {
            if (!file.isFile || file.length() != asset.bytes) return false
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { cancel.check(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.hex() == asset.sha256
        }
        private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
    }
}
