package org.compass.cng.offline

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineSubscriptionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pack(family: String, label: String): File {
        val bytes = "$family $label".toByteArray()
        val tile = OfflineTile("2/000/777/001.gph", bytes.size.toLong(), hash(bytes))
        val manifest = OfflineManifest(2, "3.9.0", label, "2026-11-22", offlineInventoryDigest(listOf(tile)),
            bytes.size.toLong(), 45.0, 10.0, 46.0, 12.0, listOf(listOf(45.1, 10.1), listOf(45.2, 10.2)),
            "© OpenStreetMap contributors — ODbL 1.0", family, listOf(tile))
        return temporary.newFile().also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(tile.path)); zip.write(bytes); zip.closeEntry()
        } }
    }
    private fun catalog(family: String, files: Map<String, File>) = RemoteOfflineCatalog(family, "2026-11-22T04:00:00+01:00",
        offlineAreaLabels.map { (area, label) -> RemoteOfflinePackage(area, label, "${area}_22112026.zip",
            files[area]?.length() ?: 1, files[area]?.readBytes()?.let(::hash) ?: "0".repeat(64)) })

    @Test fun replacementOfAllSubscribedAreasIsAtomicAndSurvivesRestart() {
        val root = temporary.newFolder()
        val store = OfflineDatasetStore(root)
        store.subscribe("server", "nord-est", true)
        store.subscribe("server", "nord-ovest", true)
        val first = mapOf("nord-est" to pack("a".repeat(64), "same"), "nord-ovest" to pack("a".repeat(64), "same"))
        store.replaceSubscribed("server", store.subscriptions(), catalog("a".repeat(64), first), first) { }
        val original = store.all()
        assertEquals(2, store.subscriptions().size)
        val next = mapOf("nord-est" to pack("b".repeat(64), "same"), "nord-ovest" to pack("b".repeat(64), "same"))
        var probes = 0
        try {
            store.replaceSubscribed("server", store.subscriptions(), catalog("b".repeat(64), next), next) {
                if (++probes == 2) error("native failure")
            }; fail()
        } catch (_: IllegalStateException) { }
        assertEquals(original, store.recover())
        assertTrue(store.subscriptions().all { it.datasetId in original.map { p -> p.directory.name } })
        store.replaceSubscribed("server", store.subscriptions(), catalog("b".repeat(64), next), next) { }
        val recovered = OfflineDatasetStore(root)
        assertTrue(recovered.recover().all { it.manifest.graphFamily == "b".repeat(64) })
        assertTrue(original.none { it.directory.exists() })
        assertEquals(2, recovered.subscriptions().size)
    }

    @Test fun removingUnsubscribesButTurningOffUpdatesKeepsInstalledArea() {
        val store = OfflineDatasetStore(temporary.newFolder())
        store.subscribe("server", "italia", true)
        val files = mapOf("italia" to pack("a".repeat(64), "Italia"))
        store.replaceSubscribed("server", store.subscriptions(), catalog("a".repeat(64), files), files) { }
        val id = store.all().single().directory.name
        store.subscribe("server", "italia", false)
        assertTrue(store.subscriptions().isEmpty())
        assertEquals(id, store.all().single().directory.name)
        store.subscribe("server", "italia", true)
        store.replaceSubscribed("server", store.subscriptions(), catalog("a".repeat(64), files), files) { }
        store.remove(id)
        assertTrue(store.subscriptions().isEmpty())
    }

    @Test fun cancellationOrChangedSubscriptionsPreserveTheOldCatalog() {
        val store = OfflineDatasetStore(temporary.newFolder())
        store.subscribe("server", "italia", true)
        val expected = store.subscriptions()
        val files = mapOf("italia" to pack("a".repeat(64), "Italia"))
        store.subscribe("server", "italia", false)
        try { store.replaceSubscribed("server", expected, catalog("a".repeat(64), files), files) {}; fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.all().isEmpty())
    }

    @Test fun displayNamesAndMondayWindowAreStableAcrossDst() {
        assertEquals("Nord-Est", offlineDisplayName("nord-est_22112026.zip"))
        assertEquals("Italia", offlineDisplayName("italia_22112026.zip"))
        assertEquals(Instant.parse("2026-11-23T05:00:00Z"), offlineUpdateWeek(Instant.parse("2026-11-23T05:00:00Z")))
        assertEquals(Instant.parse("2026-11-16T05:00:00Z"), offlineUpdateWeek(Instant.parse("2026-11-23T04:59:59Z")))
        assertEquals(Instant.parse("2026-03-30T04:00:00Z"), offlineUpdateWeek(Instant.parse("2026-03-30T05:00:00Z")))
    }

    @Test fun mobileWarningAppliesToLargeDownloadsAndUpdatesButNeverWifi() {
        val large = 1024L * 1024 * 1024
        assertTrue(needsOfflineMobileConfirmation(wifi = false, metered = true, bytes = large))
        assertTrue(needsOfflineMobileConfirmation(wifi = false, metered = false, bytes = large, mobile = true))
        assertFalse(needsOfflineMobileConfirmation(wifi = true, metered = false, bytes = large, mobile = true))
        assertFalse(needsOfflineMobileConfirmation(wifi = true, metered = true, bytes = large))
        assertFalse(needsOfflineMobileConfirmation(wifi = true, metered = false, bytes = large))
        assertFalse(needsOfflineMobileConfirmation(wifi = false, metered = true, bytes = 1024))
    }
}
