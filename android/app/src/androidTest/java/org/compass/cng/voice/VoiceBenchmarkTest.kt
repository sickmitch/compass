package org.compass.cng.voice

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in CPU benchmark on a real device, no microphone, downloads or logged transcripts. */
class VoiceBenchmarkTest {
    @Test fun whisperBaseOfflineCpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val argument = InstrumentationRegistry.getArguments().getString("voiceBenchmarkFile")
        assumeTrue("Supply voiceBenchmarkFile with a private mono 16kHz PCM16 WAV", argument != null)
        val root = context.filesDir.canonicalFile
        val expectedArgument = InstrumentationRegistry.getArguments().getString("voiceBenchmarkExpectedFile")
        val expected = expectedArgument?.let {
            val file = File(root, it).canonicalFile
            require(file.parentFile == root && file.length() in 1..4096)
            file.readText(Charsets.UTF_8).trim().also { value -> require(value.isNotBlank()) }
        }
        val wav = File(root, requireNotNull(argument)).canonicalFile
        require(wav.parentFile == root && wav.length() in 44..700_000)
        val bytes = wav.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(offset: Int, length: Int) = String(bytes, offset, length, Charsets.US_ASCII)
        require(ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE")
        var at = 12
        var validFormat = false
        var audio: FloatArray? = null
        while (at + 8 <= bytes.size) {
            val tag = ascii(at, 4)
            val size = buffer.getInt(at + 4)
            require(size >= 0 && at + 8L + size <= bytes.size)
            if (tag == "fmt ") {
                require(size >= 16 && buffer.getShort(at + 8).toInt() == 1 && buffer.getShort(at + 10).toInt() == 1)
                require(buffer.getInt(at + 12) == 16000 && buffer.getShort(at + 22).toInt() == 16)
                validFormat = true
            }
            if (tag == "data") {
                require(validFormat && size % 2 == 0)
                audio = FloatArray(size / 2) { buffer.getShort(at + 8 + it * 2) / 32768f }
            }
            at += 8 + size + (size % 2)
        }
        val samples = requireNotNull(audio)
        val models = VoiceModels(File(context.noBackupFilesDir, "voice-models"))
        require(models.valid(VoiceCancellation())) { "Install the model in Compass first" }
        repeat(3) { run ->
            val start = SystemClock.elapsedRealtime()
            val text = OfflineVoiceEngine(context, models).recognize(VoiceCancellation(), AtomicBoolean(false), {},
                { metric, value -> Log.i("CompassVoiceBenchmark", "run=$run $metric=$value") }, samples)
            assertTrue("Recognition returned no text", text.isNotBlank())
            Log.i("CompassVoiceBenchmark", "run=$run total_ms=${SystemClock.elapsedRealtime() - start}")
            Log.i("CompassVoiceBenchmark", "run=$run accuracy_measured=${if (expected == null) 0 else 1}")
            if (expected != null) {
                val expectedQuery = ItalianVoiceQuery.key(ItalianVoiceQuery.normalize(expected))
                val exact = if (expectedQuery.isNotEmpty()) expectedQuery ==
                    ItalianVoiceQuery.key(ItalianVoiceQuery.normalize(text))
                else ItalianVoiceQuery.key(expected) == ItalianVoiceQuery.key(text)
                Log.i("CompassVoiceBenchmark", "run=$run normalized_query_exact=${if (exact) 1 else 0}")
                assertTrue("Recognized query differs from expected; transcripts are not logged", exact)
            }
        }
        samples.fill(0f); bytes.fill(0)
    }
}
