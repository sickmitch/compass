package org.compass.cng.voice

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.compass.cng.domain.favorite.FavoritePlace
import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.*
import org.junit.Test

class VoiceTest {
    @Test fun splitNavigationCommandsDoNotHideFavoritesOrRewritePlaceNames() {
        for (command in listOf("Portami", "Porta mi", "Accompagna mi", "Mostra mi", "Per favore porta mi")) {
            val query = ItalianVoiceQuery.normalize("$command al lavoro")
            assertEquals("lavoro", query)
            val work = FavoritePlace("work", "Lavoro", Coordinate(45.0, 9.0))
            assertEquals(work, FavoriteVoiceResolver.resolve(query, listOf(work)).selected)
        }
        for (name in listOf("Porta Milano", "Porta Milanese", "Porta Mimosa", "Mostra Milano"))
            assertEquals(name, ItalianVoiceQuery.normalize(name))
        // Recognition errors must not be turned into arbitrary geographic guesses.
        assertEquals("Vietro Vanni pascoli", ItalianVoiceQuery.normalize("Vietro Vanni pascoli"))
        assertEquals("Buon navigo via 5 case", ItalianVoiceQuery.normalize("Buon navigo via 5 case"))
        assertEquals("Piazza braverona", ItalianVoiceQuery.normalize("piazza braverona"))
    }

    @Test fun italianCommandsPreserveProperNamesAndOnlyConvertExplicitCivics() {
        assertEquals("lavoro", ItalianVoiceQuery.normalize("Portami al lavoro."))
        assertEquals("casa", ItalianVoiceQuery.normalize("Portami a casa"))
        assertEquals("officina", ItalianVoiceQuery.normalize("Portami all’officina"))
        assertEquals("Via Roma, 28 bis Verona", ItalianVoiceQuery.normalize("Portami in Via Roma numero ventotto bis Verona"))
        assertEquals("Via Quattro Novembre", ItalianVoiceQuery.normalize("Cerca Via Quattro Novembre"))
        assertEquals("Bar Tre Stelle", ItalianVoiceQuery.normalize("Trova Bar Tre Stelle"))
        assertEquals("Via XX Settembre, 12", ItalianVoiceQuery.normalize("Via XX Settembre civico dodici"))
        assertEquals("Via Roma, 124 Verona", ItalianVoiceQuery.normalize("Via Roma numero cento ventiquattro Verona"))
        assertEquals("Via Roma, 180", ItalianVoiceQuery.normalize("Via Roma civico centottanta"))
        assertEquals("Via Roma, 2001", ItalianVoiceQuery.normalize("Via Roma civico duemila uno"))
        assertEquals("Viale San José", ItalianVoiceQuery.normalize("viale San José"))
    }
    @Test fun exactFavoriteMatchesPrecedeExternalSearchAndCollisionsStayAmbiguous() {
        val coordinate = Coordinate(45.0, 9.0)
        val work = FavoritePlace("1", "Lavoro", coordinate)
        assertEquals(work, FavoriteVoiceResolver.resolve("lavoro", listOf(work)).selected)
        assertNull(FavoriteVoiceResolver.resolve("lavor", listOf(work)).selected)
        val cafe = listOf(FavoritePlace("2", "Caffè", coordinate), FavoritePlace("3", "Caffe", coordinate))
        assertEquals(2, FavoriteVoiceResolver.resolve("caffe", cafe).candidates.size)
        val partial = work.copy(name = "Lavoro Milano")
        assertEquals(listOf(partial), FavoriteVoiceResolver.resolve("lavoro", listOf(partial)).candidates)
        assertNull(FavoriteVoiceResolver.resolve("lavoro", listOf(partial)).selected)
    }
    @Test fun metanoIsAnIntentNotAnyPlaceNameContainingTheWord() {
        assertTrue(ItalianVoiceQuery.isCng("distributore di metano vicino a me"))
        assertTrue(ItalianVoiceQuery.isCng("rifornimento CNG"))
        assertTrue(ItalianVoiceQuery.isCng("metano"))
        assertFalse(ItalianVoiceQuery.isCng("Via del Metano 12"))
        assertFalse(ItalianVoiceQuery.isCng("Bar Metano"))
    }
    @Test fun endpointKeepsPreRollAndPausesInsideWordsBeforeFinishing() {
        val endpoint = SpeechEndpoint(VoiceTiming())
        repeat(10) { endpoint.accept(.1f) }
        repeat(10) { endpoint.accept(.9f) }
        assertEquals(10 * 512, endpoint.speechStart)
        repeat(20) { endpoint.accept(.1f) } // 640 ms: not an end-of-speech yet.
        assertFalse(endpoint.complete)
        endpoint.accept(.8f)
        repeat(37) { endpoint.accept(.1f) }
        assertFalse(endpoint.complete)
        endpoint.accept(.1f)
        assertTrue(endpoint.complete)
        assertEquals(0, endpoint.firstSample)
    }
    @Test fun silenceAndTooLongSpeechFailInsteadOfDecodingPartialAudio() {
        val noSpeech = SpeechEndpoint(VoiceTiming(initialSilenceMs = 1000))
        assertThrows(VoiceFailure::class.java) { repeat(60) { noSpeech.accept(.1f) } }
        val endless = SpeechEndpoint(VoiceTiming(maximumSpeechMs = 3000))
        assertThrows(VoiceFailure::class.java) { repeat(100) { endless.accept(.9f) } }
    }
    @Test fun resamplingMaintainsTimeAndRejectsAliasFrequenciesAcrossIrregularReads() {
        for (rate in listOf(16000, 44100, 48000)) {
            fun converted(frequency: Double, chunks: Int): FloatArray {
                val pcm = ShortArray(rate) { (12000 * sin(2 * PI * frequency * it / rate)).toInt().toShort() }
                val converter = PcmResampler(rate)
                return pcm.toList().chunked(chunks).flatMap { converter.accept(it.toShortArray(), it.size).toList() }.toFloatArray()
            }
            val a = converted(1000.0, 137)
            val b = converted(1000.0, 1024)
            assertArrayEquals(a, b, .00001f)
            assertTrue(a.size in 15980..16000)
            val rms = sqrt(a.drop(100).map { it * it.toDouble() }.average())
            assertTrue(rms > .2)
            if (rate > 16000) {
                val high = converted(12000.0, 137)
                assertTrue(sqrt(high.drop(100).map { it * it.toDouble() }.average()) < .02)
            }
        }
    }
    @Test fun modelDownloadVerifiesEachFileAndResumesOnlyVerifiedFiles() {
        val root = Files.createTempDirectory("voice-test").toFile()
        try {
            val content = "fixture".toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            val names = listOf("encoder.onnx", "decoder.onnx", "tokens.txt", "vad.onnx")
            val spec = WhisperModelSpec("test", names[0], names[1], names[2], names[3],
                names.map { ModelAsset(it, "https://example.test/$it", content.size.toLong(), sha) })
            var calls = 0
            var corrupt = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (corrupt) "broken!" else "fixture").toResponseBody()).build()
            }.build()
            val models = VoiceModels(root, spec, client)
            assertFalse(models.valid(VoiceCancellation()))
            models.install(VoiceCancellation()) {}
            assertEquals(4, calls)
            assertTrue(models.valid(VoiceCancellation()))
            models.install(VoiceCancellation()) {}
            assertEquals(4, calls)
            models.file(names[0]).writeText("broken!")
            assertFalse(models.valid(VoiceCancellation()))
            corrupt = true
            assertThrows(VoiceFailure::class.java) { models.install(VoiceCancellation()) {} }
            assertFalse(File(models.directory, names[0] + ".part").exists())
            val token = VoiceCancellation().apply { cancel() }
            assertThrows(VoiceCancelled::class.java) { models.valid(token) }
            models.remove()
            assertFalse(models.directory.exists())
            corrupt = false
            val duringDownload = VoiceCancellation()
            assertThrows(VoiceCancelled::class.java) {
                models.install(duringDownload) { duringDownload.cancel() }
            }
            assertFalse(models.file(names[0]).exists())
            assertFalse(File(models.directory, names[0] + ".part").exists())
        } finally { root.deleteRecursively() }
    }
}
