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
    private fun manifest(data: ByteArray = graph) = OfflineManifest(1, "3.9.0", "Veneto", "2026-11-17",
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        data.size.toLong(), 45.0, 10.0, 46.0, 12.0, listOf(listOf(45.4, 11.0), listOf(45.5, 11.1)),
        "© OpenStreetMap contributors — ODbL 1.0")
    private fun pack(header: OfflineManifest = manifest(), data: ByteArray = graph, name: String = "tiles.tar") =
        ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write(Json.encodeToString(header).toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry(name)); it.write(data); it.closeEntry()
        } }.toByteArray().inputStream()

    @Test fun verifiedPackagesAccumulateAndSurviveRestart() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        var probed = false
        val installed = store.install(pack()) {
            assertTrue(store.all().isEmpty())
            assertArrayEquals(graph, File(it.directory, "tiles.tar").readBytes())
            probed = true
        }
        assertTrue(probed)
        val data = "another region".toByteArray()
        val newer = store.install(pack(manifest(data).copy(label = "Nuovo"), data)) { }
        assertTrue(installed.directory.exists())
        assertEquals(listOf(installed, newer), OfflineDatasetStore(root).recover())
    }

    @Test fun duplicateGraphDoesNotConsumeAnotherPackageOrDiscardExistingOnes() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val original = store.install(pack()) { }
        val duplicate = store.install(pack()) { fail("Already verified graph need not be probed again") }
        assertEquals(original, duplicate)
        assertEquals(listOf(original), store.all())
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
    }

    @Test fun corruptIncompatibleUnsafeOrCancelledImportKeepsWorkingDatasets() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val original = store.install(pack()) { }
        val badPackages = listOf(
            pack(data = ByteArray(graph.size)), pack(manifest().copy(engineVersion = "3.8.3")),
            pack(name = "../tiles.tar"), pack(manifest().copy(bytes = graph.size.toLong() - 1)),
        )
        badPackages.forEach { input ->
            try { store.install(input) { fail("must reject before native probe") }; fail() }
            catch (_: IllegalArgumentException) { assertEquals(listOf(original), store.all()) }
        }
        val data = "new graph".toByteArray()
        try { store.install(pack(manifest(data), data)) { error("native route failed") }; fail() }
        catch (_: IllegalStateException) { assertEquals(listOf(original), store.all()) }
        try { store.install(pack(), checkCancelled = { throw CancellationException() }) {}; fail() }
        catch (_: CancellationException) { assertEquals(listOf(original), store.all()) }
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
    }

    @Test fun legacyPointerMigratesWithoutReimportAndPartialImportsAreReclaimed() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val active = store.install(pack()) { }
        File(root, "catalog.json").delete()
        File(root, "active.txt").writeText(active.directory.name)
        val partial = File(root, "dataset-${java.util.UUID.randomUUID()}").also { it.mkdir() }
        File(partial, "tiles.tar").writeBytes(graph)
        File(root, "active.tmp").writeText(partial.name)
        assertEquals(listOf(active), OfflineDatasetStore(root).recover())
        assertTrue(active.directory.exists())
        assertTrue(File(root, "catalog.json").exists())
        assertFalse(File(root, "active.txt").exists())
        assertFalse(partial.exists())
        assertFalse(File(root, "active.tmp").exists())
        assertEquals(listOf(active), OfflineDatasetStore(root).recover())
    }

    @Test fun catalogHasNoFixedPackageCountAndRemovalOnlyAffectsChosenGraph() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val packages = (1..40).map { i ->
            val data = "graph $i".toByteArray()
            store.install(pack(manifest(data).copy(label = "Area $i"), data)) { }
        }
        assertEquals(packages, OfflineDatasetStore(root).recover())
        val removed = packages[20]
        val remaining = packages.filterNot { it == removed }
        assertEquals(remaining, store.remove(removed.directory.name))
        assertFalse(removed.directory.exists())
        assertTrue(remaining.all { it.directory.exists() })
        assertEquals(remaining, OfflineDatasetStore(root).recover())
        remaining.forEach { store.remove(it.directory.name) }
        assertTrue(OfflineDatasetStore(root).recover().isEmpty())
    }

    @Test fun interruptedCatalogWriteAndInvalidNamesDoNotDeletePublishedGraphs() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val original = store.install(pack()) { }
        File(root, "catalog.tmp").writeText("incomplete")
        assertEquals(listOf(original), store.recover())
        assertFalse(File(root, "catalog.tmp").exists())
        try { store.remove("../outside"); fail() } catch (_: IllegalArgumentException) { }
        File(root, "catalog.json").writeText("""{"datasets":["../outside"]}""")
        try { store.recover(); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(original.directory.exists())
    }

    @Test fun cancellationAfterNativeProbeDoesNotPublishTheNewPackage() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val original = store.install(pack()) { }
        val data = "cancel after probe".toByteArray()
        var cancel = false
        try {
            store.install(pack(manifest(data), data), checkCancelled = {
                if (cancel) throw CancellationException()
            }) { cancel = true }
            fail()
        } catch (_: CancellationException) { }
        assertEquals(listOf(original), store.recover())
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
    }

    @Test fun failedRemovalPublicationKeepsTheOriginalGraph() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        val original = store.install(pack()) { }
        File(root, "catalog.tmp").mkdir() // Publishing cannot open a directory as a file.
        try { store.remove(original.directory.name); fail() } catch (_: java.io.IOException) { }
        assertTrue(original.directory.exists())
        assertEquals(listOf(original), store.all())
    }

    @Test fun oneUnreadableAreaDoesNotDisableOtherPackages() {
        val store = OfflineDatasetStore(temporary.newFolder())
        val original = store.install(pack()) { }
        val data = "second graph".toByteArray()
        val second = store.install(pack(manifest(data), data)) { }
        File(original.directory, "tiles.tar").delete()
        assertEquals(listOf(second), store.recover())
        assertEquals(1, store.unavailableIds().size)
        store.remove(original.directory.name)
        assertEquals(0, store.unavailableIds().size)
    }
}
