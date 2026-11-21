package org.compass.cng.offline

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.*
import org.junit.Test

class OfflinePackageRoutingTest {
    private val a = Coordinate(45.1, 11.0)
    private val b = Coordinate(45.2, 11.1)
    private val route = listOf(RoutePreview(a, b, 1000.0, 100.0, listOf(a, b), emptyList(), OFFLINE_PROVIDER))
    private fun area(name: String, west: Double = 10.0, east: Double = 12.0) = InstalledOfflineDataset(
        File(name), OfflineManifest(1, "3.9.0", name, "2026-11-20", "0".repeat(64), 1,
            45.0, west, 46.0, east, listOf(listOf(45.1, 11.0), listOf(45.2, 11.1)),
            "© OpenStreetMap contributors — ODbL 1.0"),
    )

    @Test fun automaticallySelectsCoveringAreaRatherThanLastImportedUnrelatedGraph() = runTest {
        val local = area("Nord-Est")
        val distant = area("Ovest", 7.0, 9.0)
        val result = routeWithOfflinePackages(listOf(local, distant), listOf(a, b)) {
            assertEquals(listOf(local), it)
            route
        }
        assertEquals(route, result)
    }

    @Test fun overlapPrefersNewestImportAndTriesAnotherOnNativeNoRoute() = runTest {
        val old = area("old")
        val newer = area("new")
        val tried = mutableListOf<String>()
        assertEquals(route, routeWithOfflinePackages(listOf(old, newer), listOf(a, b)) {
            tried += it.single().manifest.label
            if (it.single() == newer) error("No route in this graph")
            route
        })
        assertEquals(listOf("new", "old"), tried)
    }

    @Test fun allWaypointsMustBelongToOneGraphAndIndependentGraphsAreNotStitched() = runTest {
        val outside = Coordinate(45.3, 8.0)
        try {
            routeWithOfflinePackages(listOf(area("east"), area("west", 7.0, 9.0)), listOf(a, outside, b)) {
                fail("No graph covers all waypoints"); route
            }
            fail()
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("intero percorso"))
        }
    }

    @Test fun cancellationDoesNotTryOtherGraphs() = runTest {
        var calls = 0
        try {
            routeWithOfflinePackages(listOf(area("old"), area("new")), listOf(a, b)) {
                calls++
                throw CancellationException()
            }
            fail()
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun everyCandidateCanFailWithoutFabricatingARoute() = runTest {
        var calls = 0
        try {
            routeWithOfflinePackages(listOf(area("old"), area("new")), listOf(a, b)) {
                calls++
                error("No route")
            }
            fail()
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("Percorso non disponibile"))
        }
        assertEquals(2, calls)
    }
}
