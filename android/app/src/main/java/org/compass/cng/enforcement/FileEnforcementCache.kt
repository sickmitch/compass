package org.compass.cng.enforcement

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private, one area, bounded to about 2 MB; no credentials or GPS trace stored. */
class FileEnforcementCache(file: File, private val json: Json) : EnforcementCache {
    private val atomic = AtomicFile(file)
    override fun read(): SavedEnforcementPackage? = runCatching {
        atomic.openRead().use { stream ->
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            json.decodeFromString<SavedEnforcementPackage>(bytes.decodeToString()).also {
                it.data.validated()
            }
        }
    }.getOrNull()
    override fun write(value: SavedEnforcementPackage) {
        val bytes = json.encodeToString(value).toByteArray()
        require(bytes.size <= MAX_BYTES)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
    override fun clear() = atomic.delete()
    companion object { private const val MAX_BYTES = 2_100_000 }
}
