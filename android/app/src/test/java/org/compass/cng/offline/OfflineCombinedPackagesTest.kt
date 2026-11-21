package org.compass.cng.offline

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineCombinedPackagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val shared = "0/003/017.gph"
    private val west = "2/000/777/001.gph"
    private val east = "2/000/777/002.gph"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun manifest(files: Map<String, ByteArray>, start: Double = 10.0, family: String = "a".repeat(64)): OfflineManifest {
        val tiles = files.map { OfflineTile(it.key, it.value.size.toLong(), digest(it.value)) }
        return OfflineManifest(2, "3.9.0", "Area $start", "2026-11-21", offlineInventoryDigest(tiles),
            tiles.sumOf { it.bytes }, 45.0, start, 46.0, start + 1,
            listOf(listOf(45.1, start + .1), listOf(45.2, start + .2)),
            "© OpenStreetMap contributors — ODbL 1.0", family, tiles)
    }
    private fun pack(files: Map<String, ByteArray>, header: OfflineManifest = manifest(files)) =
        ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.encodeToString(header).toByteArray()); zip.closeEntry()
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        } }.toByteArray().inputStream()
    private fun files(path: String) = mapOf(shared to "hierarchy".toByteArray(), path to path.toByteArray())

    @Test fun importedRegionsShareARealTileDirectoryAndRemovalRebuildsTheView() {
        val store = OfflineDatasetStore(temporary.newFolder())
        val a = store.install(pack(files(west))) { }
        val b = store.install(pack(files(east), manifest(files(east), 11.0))) { }
        assertEquals(listOf(a, b), store.recover())
        val assembly = OfflineGraphAssembly(temporary.newFolder())
        val union = assembly.prepare(listOf(a, b))
        assertTrue(File(union, west).isFile)
        assertTrue(File(union, east).isFile)
        assertEquals(3, union.walkTopDown().count { it.isFile })
        assertTrue(Files.isSameFile(File(union, shared).toPath(), File(a.directory, "tiles/$shared").toPath()))
        assertEquals(union, assembly.prepare(listOf(b, a)))
        assembly.clear()
        store.remove(b.directory.name)
        val remaining = assembly.prepare(store.all())
        assertTrue(File(remaining, west).isFile)
        assertFalse(File(remaining, east).exists())
        assertTrue(File(a.directory, "tiles/$shared").isFile)
    }

    @Test fun conflictingOverlapsFailBeforePublicationAndPreserveExistingRegions() {
        val store = OfflineDatasetStore(temporary.newFolder())
        val original = store.install(pack(files(west))) { }
        val conflict = files(east) + (shared to "different hierarchy".toByteArray())
        try { store.install(pack(conflict, manifest(conflict, 11.0))) { fail("Conflict must precede native use") }; fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("incompatibili")) }
        assertEquals(listOf(original), store.recover())
    }

    @Test fun badTileHashUnsafePathsAndCancellationDoNotDamageInstalledRegions() {
        val store = OfflineDatasetStore(temporary.newFolder())
        val original = store.install(pack(files(west))) { }
        val bad = files(east) + (east to ByteArray(east.length))
        try { store.install(pack(bad, manifest(files(east), 11.0))) { fail() }; fail() }
        catch (_: IllegalArgumentException) { }
        for (path in listOf("../escape", "2/777/001.gph", "2/999/999/999.gph")) {
            val unsafe = mapOf(path to byteArrayOf(1))
            try { store.install(pack(unsafe)) { fail() }; fail() } catch (_: IllegalArgumentException) { }
        }
        var cancel = false
        try {
            store.install(pack(files(east), manifest(files(east), 11.0)), checkCancelled = {
                if (cancel) throw CancellationException()
            }) { cancel = true }
            fail()
        } catch (_: CancellationException) { }
        assertEquals(listOf(original), store.recover())
    }

    @Test fun interruptedAssemblyCannotPublishAPartialGraph() {
        val store = OfflineDatasetStore(temporary.newFolder())
        val a = store.install(pack(files(west))) { }
        val b = store.install(pack(files(east), manifest(files(east), 11.0))) { }
        val root = temporary.newFolder()
        val assembly = OfflineGraphAssembly(root)
        var checks = 0
        try { assembly.prepare(listOf(a, b)) { if (++checks == 2) throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(listOf(a, b), store.recover())
        assertTrue(File(assembly.prepare(listOf(a, b)), east).exists())
    }

    @Test fun aSingleRouteAndWaypointsUseBothRegionsTogether() = runTest {
        val store = OfflineDatasetStore(temporary.newFolder())
        val a = store.install(pack(files(west))) { }
        val b = store.install(pack(files(east), manifest(files(east), 11.0))) { }
        val points = listOf(Coordinate(45.1, 10.1), Coordinate(45.2, 10.9), Coordinate(45.3, 11.8))
        val route = listOf(RoutePreview(points.first(), points.last(), 1000.0, 100.0, points, emptyList(), OFFLINE_PROVIDER))
        var calls = 0
        assertEquals(route, routeWithOfflinePackages(store.all(), points) {
            calls++
            assertEquals(setOf(a, b), it.toSet())
            route
        })
        assertEquals(1, calls) // One native route, not stitched independently calculated legs.
    }

    @Test fun differentBuildsCannotBeJoinedEvenWhenBothEndpointsHaveCoverage() = runTest {
        val store = OfflineDatasetStore(temporary.newFolder())
        store.install(pack(files(west))) { }
        store.install(pack(files(east), manifest(files(east), 11.0, "b".repeat(64)))) { }
        try {
            routeWithOfflinePackages(store.all(), listOf(Coordinate(45.1, 10.1), Coordinate(45.3, 11.8))) {
                fail("Different families cannot be combined"); emptyList()
            }
            fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("compatibili")) }
    }
}
