package org.compass.cng.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.compass.cng.domain.*
import org.compass.cng.domain.model.*
import org.compass.cng.navigation.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflinePlanningRepositoryTest {
    private val a = Coordinate(45.0, 11.0)
    private val b = Coordinate(45.01, 11.01)
    private val c = Coordinate(45.02, 11.02)
    private val d = Coordinate(45.03, 11.03)
    private fun leg(from: Coordinate = a, to: Coordinate = b) = RoutePreview(
        from, to, 1000.0, 100.0, listOf(from, to), emptyList(), OFFLINE_PROVIDER,
    )
    private val available = BackendAvailability()
    private var points = emptyList<Coordinate>()
    private var highways = true
    private var heading: RouteOriginDirection? = null
    private var localCalls = 0
    private val local = object : OfflineRouter {
        override var available = true
        override suspend fun route(points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> {
            localCalls++
            this@OfflinePlanningRepositoryTest.points = points
            highways = allowHighways
            heading = direction
            return points.zipWithNext { from, to -> leg(from, to).copy(allowsHighways = allowHighways) }
        }
    }
    private fun online(action: suspend () -> RoutePreview) = object : RoutingRepository {
        override suspend fun previewRoute(origin: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?) = action()
        override suspend fun rankedCngStations(origin: Coordinate, destination: Coordinate, effectiveCngRangeKm: Double, maximumDetourMinutes: Double, departureAt: java.time.OffsetDateTime): RankedCngStations = error("not requested")
        override suspend fun routeWithCngStop(origin: Coordinate, destination: Coordinate, mimitStationId: String, originDirection: RouteOriginDirection?): RouteWithCngStop = error("not requested")
        override suspend fun predictiveCngStations(origin: Coordinate, destination: Coordinate, effectiveCngRangeKm: Double, estimatedRemainingCngRangeKm: Double, reserveCngRangeKm: Double, maximumDetourMinutes: Double, departureAt: java.time.OffsetDateTime, excludedMimitStationIds: Set<String>, estimatedRemainingGasolineRangeKm: Double?, reserveGasolineRangeKm: Double?, originDirection: RouteOriginDirection?): PredictiveCngSuggestion = error("not requested")
        override suspend fun routeWithCngItinerary(origin: Coordinate, destination: Coordinate, mimitStationIds: List<String>, effectiveCngRangeKm: Double, estimatedRemainingCngRangeKm: Double, reserveCngRangeKm: Double, originDirection: RouteOriginDirection?): RouteWithCngItinerary = error("not requested")
    }

    @Test fun initialOfflineRouteKeepsHighwayAndHeadingConstraints() = runTest {
        available.networkChanged(false)
        val repo = OfflinePlanningRepository(online { error("must not request network") }, local, available)
        val direction = RouteOriginDirection(80.0, 30)
        val result = repo.previewRoute(a, b, direction, false, RouteTravelMode.DRIVING)
        assertEquals(listOf(a, b), points)
        assertFalse(highways)
        assertEquals(direction, heading)
        assertEquals(OFFLINE_PROVIDER, result.provider)
        val session = NavigationSession()
        session.preview(result.toNavigationRoute())
        session.start()
        assertEquals(NavigationConnectivity.REROUTING_UNAVAILABLE, session.state.value.connectivity)
    }

    @Test fun normalStopsKeepOrderTotalsAndLocalProvenanceWithoutServer() = runTest {
        available.serverResponded(false)
        val repo = OfflinePlanningRepository(online { error("network") }, local, available)
        val single = repo.routeWithIntermediateStop(a, b, c)
        assertEquals(listOf(a, b, c), points)
        assertEquals(200.0, single.durationSeconds, 0.0)
        assertEquals(OFFLINE_PROVIDER, single.provider)
        val multiple = repo.routeWithIntermediateStops(a, listOf(b, c), d, allowHighways = false)
        assertEquals(listOf(a, b, c, d), points)
        assertEquals(listOf(b, c), multiple.stops)
        assertEquals(3000.0, multiple.distanceMeters, 0.0)
        assertEquals(300.0, multiple.navigation.totalTripDurationSeconds, 0.0)
        assertFalse(highways)
        val route = multiple.toNavigationRoute(listOf(NavigationOrderedStop("Uno"), NavigationOrderedStop("Due")))
        assertEquals(listOf(b, c), route.intermediateStops.map { it.location })
        assertEquals(OFFLINE_PROVIDER, route.provider)
    }

    @Test fun networkFailureDisablesCngAndUsesLocalUntilRecovery() = runTest {
        var requests = 0
        val repo = OfflinePlanningRepository(online {
            requests++
            if (requests == 1) throw RoutePreviewException(RoutePreviewFailure.NETWORK)
            leg().copy(provider = "valhalla")
        }, local, available)
        assertEquals(OFFLINE_PROVIDER, repo.previewRoute(a, b).provider)
        assertFalse(available.available.value)
        repo.previewRoute(a, b)
        assertEquals(1, requests)
        available.serverResponded(true)
        assertEquals("valhalla", repo.previewRoute(a, b).provider)
        assertEquals(2, requests)
        assertEquals(2, localCalls)
    }

    @Test fun timeoutCancelsRemoteAfterEightSeconds() = runTest {
        var cancelled = false
        val repo = OfflinePlanningRepository(online {
            try { delay(60_000); leg() } finally { cancelled = true }
        }, local, available)
        assertEquals(OFFLINE_PROVIDER, repo.previewRoute(a, b).provider)
        assertEquals(8000L, testScheduler.currentTime)
        assertTrue(cancelled)
        assertFalse(available.available.value)
    }

    @Test fun serverFailureFallsBackButApplicationFailuresDoNot() = runTest {
        for (failure in listOf(RoutePreviewFailure.AUTHENTICATION, RoutePreviewFailure.NO_ROUTE,
                RoutePreviewFailure.INVALID_RESPONSE, RoutePreviewFailure.RATE_LIMITED)) {
            val repo = OfflinePlanningRepository(online { throw RoutePreviewException(failure) }, local, available)
            try { repo.previewRoute(a, b); fail() }
            catch (e: RoutePreviewException) { assertEquals(failure, e.failure) }
            assertEquals(0, localCalls)
        }
        val repo = OfflinePlanningRepository(online { throw RoutePreviewException(RoutePreviewFailure.SERVER) }, local, available)
        assertEquals(OFFLINE_PROVIDER, repo.previewRoute(a, b).provider)
    }

    @Test fun cancellationNeverStartsNativeCalculation() = runTest {
        val repo = OfflinePlanningRepository(online { throw CancellationException() }, local, available)
        try { repo.previewRoute(a, b); fail() } catch (_: CancellationException) { }
        assertEquals(0, localCalls)
        assertTrue(available.available.value)
    }

    @Test fun absentPackageAndNonCarModesDoNotUseNativeEngine() = runTest {
        available.networkChanged(false)
        val repo = OfflinePlanningRepository(online { error("network") }, local, available)
        for (mode in listOf(RouteTravelMode.WALKING, RouteTravelMode.CYCLING)) {
            try { repo.previewRoute(a, b, allowHighways = true, travelMode = mode); fail() }
            catch (e: RoutePreviewException) { assertEquals(RoutePreviewFailure.NETWORK, e.failure) }
        }
        local.available = false
        try { repo.previewRoute(a, b); fail() }
        catch (e: RoutePreviewException) { assertEquals(RoutePreviewFailure.NETWORK, e.failure) }
        assertEquals(0, localCalls)
    }

    @Test fun localCoverageFailureIsReportedWithoutInventingARoute() = runTest {
        available.networkChanged(false)
        val unsupported = object : OfflineRouter {
            override val available = true
            override suspend fun route(points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> = error("outside coverage")
        }
        val repo = OfflinePlanningRepository(online { error("network") }, unsupported, available)
        try { repo.previewRoute(a, b); fail() }
        catch (e: RoutePreviewException) { assertEquals(RoutePreviewFailure.NO_ROUTE, e.failure) }
    }

    @Test fun connectivityReturningDoesNotProveServerReadiness() {
        available.networkChanged(false)
        available.serverResponded(true) // Ignore a late HTTP completion after network loss.
        assertFalse(available.available.value)
        available.networkChanged(true)
        assertFalse(available.available.value)
        available.serverResponded(true)
        assertTrue(available.available.value)
        available.serverResponded(false)
        available.networkChanged(true)
        assertFalse(available.available.value)
    }
}
