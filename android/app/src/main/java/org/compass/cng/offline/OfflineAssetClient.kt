package org.compass.cng.offline

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.compass.cng.data.api.authenticatedRequest
import org.compass.cng.data.api.decodeCancellable
import org.compass.cng.domain.server.ServerConnection

internal fun offlineOwner(connection: ServerConnection): String = MessageDigest.getInstance("SHA-256")
    .digest("${connection.baseUrl}\n${connection.username}".toByteArray()).joinToString("") { "%02x".format(it) }

class OfflineAssetClient(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = false }
    private fun url(connection: ServerConnection, suffix: String) = requireNotNull(
        connection.baseUrl.toHttpUrl().resolve("api/v1/offline-routing/packages$suffix"))

    suspend fun catalog(connection: ServerConnection): RemoteOfflineCatalog {
        val request = connection.authenticatedRequest().url(url(connection, "")).build()
        return http.newCall(request).apply { timeout().timeout(60, TimeUnit.SECONDS) }.decodeCancellable { response ->
            if (!response.isSuccessful) throw IOException("Catalogo offline non disponibile (HTTP ${response.code})")
            val content = java.io.ByteArrayOutputStream().also { buffer ->
                response.body.byteStream().use { input ->
                    val chunk = ByteArray(4096)
                    while (buffer.size() <= 65536) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        buffer.write(chunk, 0, count)
                    }
                }
            }.toByteArray()
            require(content.size <= 65536) { "Catalogo troppo grande" }
            json.decodeFromString<RemoteOfflineCatalog>(content.decodeToString()).also { it.validate() }
        }
    }

    /** Partial data is keyed by server + SHA; Range resumes across OS job interruptions. */
    suspend fun download(connection: ServerConnection, item: RemoteOfflinePackage, directory: File,
        checkTransfer: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        item.validate()
        directory.mkdirs()
        val file = File(directory, item.sha256 + ".zip.part")
        if (file.length() > item.bytes) file.delete()
        val offset = file.length()
        if (offset < item.bytes) {
            checkTransfer()
            check(directory.usableSpace > item.bytes - offset + 64L * 1024 * 1024) { "Spazio insufficiente per il download" }
            val request = connection.authenticatedRequest().url(url(connection, "/${item.filename}"))
                .header("Accept-Encoding", "identity").apply {
                    if (offset > 0) header("Range", "bytes=$offset-").header("If-Range", "\"${item.sha256}\"")
                }.build()
            val job = currentCoroutineContext()
            coroutineScope {
                val call = http.newCall(request)
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        call.execute().use { response ->
                            if (response.code != 200 && response.code != 206) {
                                throw IOException("Download non disponibile (HTTP ${response.code})")
                            }
                            val resumed = response.code == 206
                            if (resumed) require(response.header("Content-Range") == "bytes $offset-${item.bytes - 1}/${item.bytes}") {
                                "Risposta parziale non valida"
                            }
                            var count = if (resumed) offset else 0L
                            java.io.FileOutputStream(file, resumed).use { output ->
                                response.body.byteStream().use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var reported = 0L
                                    while (true) {
                                        job.ensureActive()
                                        checkTransfer()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        count += n
                                        require(count <= item.bytes) { "Dimensione del download non valida" }
                                        output.write(buffer, 0, n)
                                        if (count - reported >= 1024 * 1024) {
                                            progress(count, item.bytes)
                                            reported = count
                                        }
                                    }
                                }
                                output.fd.sync()
                            }
                            if (count != item.bytes) throw IOException("Download interrotto: verrà ripreso")
                        }
                    }
                } finally { cancellation.cancel() }
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                ensureActive()
                val n = input.read(buffer); if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        if (hash.digest().joinToString("") { "%02x".format(it) } != item.sha256) {
            file.delete(); error("Verifica SHA-256 del download fallita")
        }
        file
    }
}
