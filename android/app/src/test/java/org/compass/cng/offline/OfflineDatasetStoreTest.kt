package org.compass.cng.offline

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDatasetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val graph = "fixture graph verified by fake native probe".toByteArray()
    private fun manifest() = OfflineManifest(1, "3.9.0", "Veneto", "2026-11-17",
        MessageDigest.getInstance("SHA-256").digest(graph).joinToString("") { "%02x".format(it) },
        graph.size.toLong(), 45.0, 10.0, 46.0, 12.0, listOf(listOf(45.4, 11.0), listOf(45.5, 11.1)),
        "© OpenStreetMap contributors — ODbL 1.0")
    private fun pack(header: OfflineManifest = manifest(), data: ByteArray = graph, name: String = "tiles.tar") =
        ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write(Json.encodeToString(header).toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry(name)); it.write(data); it.closeEntry()
        } }.toByteArray().inputStream()

    @Test fun onlyVerifiedPackageBecomesActiveAndSurvivesRestart() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        var probed = false
        val installed = store.install(pack()) {
            assertNull(store.current()); assertArrayEquals(graph, File(it.directory, "tiles.tar").readBytes())
            probed = true
        }
        assertTrue(probed)
        assertEquals(installed, OfflineDatasetStore(root).current())
        val newer = store.install(pack(manifest().copy(label = "Nuovo"))) { }
        assertFalse(installed.directory.exists())
        assertEquals(newer, store.current())
    }

    @Test fun corruptIncompatibleUnsafeOrCancelledImportKeepsWorkingDataset() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val original = store.install(pack()) { }
        val badPackages = listOf(
            pack(data = ByteArray(graph.size)), pack(manifest().copy(engineVersion = "3.8.3")),
            pack(name = "../tiles.tar"), pack(manifest().copy(bytes = graph.size.toLong() - 1)),
        )
        badPackages.forEach { input ->
            try { store.install(input) { fail("must reject before native probe") }; fail() }
            catch (_: IllegalArgumentException) { assertEquals(original, store.current()) }
        }
        try { store.install(pack()) { error("native route failed") }; fail() }
        catch (_: IllegalStateException) { assertEquals(original, store.current()) }
        try { store.install(pack(), checkCancelled = { throw CancellationException() }) {}; fail() }
        catch (_: CancellationException) { assertEquals(original, store.current()) }
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
    }

    @Test fun restartReclaimsInterruptedImportWithoutDeletingActiveGraph() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val active = store.install(pack()) { }
        val partial = File(root, "dataset-${java.util.UUID.randomUUID()}").also { it.mkdir() }
        File(partial, "tiles.tar").writeBytes(graph)
        File(root, "active.tmp").writeText(partial.name)
        assertEquals(active, OfflineDatasetStore(root).recover())
        assertTrue(active.directory.exists())
        assertFalse(partial.exists())
        assertFalse(File(root, "active.tmp").exists())
    }
}
