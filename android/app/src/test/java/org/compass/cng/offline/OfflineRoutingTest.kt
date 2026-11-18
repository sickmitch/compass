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
class OfflineRoutingTest {
    private val a = Coordinate(45.0, 11.0)
    private val b = Coordinate(45.01, 11.01)
    private fun leg(from: Coordinate = a, to: Coordinate = b) = RoutePreview(
        from, to, 1000.0, 100.0, listOf(from, to), emptyList(), OFFLINE_PROVIDER,
        allowsHighways = false,
    )
    private fun state(connectivity: NavigationConnectivity = NavigationConnectivity.OFFLINE) = NavigationState(
        phase = NavigationPhase.NAVIGATING, route = leg().copy(provider = "valhalla").toNavigationRoute(),
        connectivity = connectivity, offRouteStatus = OffRouteStatus.OFF_ROUTE,
        rawLocation = NavigationLocation(a, 5.0, 15.0, 90.0, 1000),
    )
    private fun online(action: suspend () -> RoutePreview) = object : RoutingRepository {
        override suspend fun previewRoute(origin: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?) = action()
        override suspend fun rankedCngStations(origin: Coordinate, destination: Coordinate, effectiveCngRangeKm: Double, maximumDetourMinutes: Double, departureAt: java.time.OffsetDateTime): RankedCngStations = error("not requested")
        override suspend fun routeWithCngStop(origin: Coordinate, destination: Coordinate, mimitStationId: String, originDirection: RouteOriginDirection?): RouteWithCngStop = error("not requested")
        override suspend fun predictiveCngStations(origin: Coordinate, destination: Coordinate, effectiveCngRangeKm: Double, estimatedRemainingCngRangeKm: Double, reserveCngRangeKm: Double, maximumDetourMinutes: Double, departureAt: java.time.OffsetDateTime, excludedMimitStationIds: Set<String>, estimatedRemainingGasolineRangeKm: Double?, reserveGasolineRangeKm: Double?, originDirection: RouteOriginDirection?): PredictiveCngSuggestion = error("not requested")
        override suspend fun routeWithCngItinerary(origin: Coordinate, destination: Coordinate, mimitStationIds: List<String>, effectiveCngRangeKm: Double, estimatedRemainingCngRangeKm: Double, reserveCngRangeKm: Double, originDirection: RouteOriginDirection?): RouteWithCngItinerary = error("not requested")
    }
    private class Local(val action: (List<Coordinate>, Boolean, RouteOriginDirection?) -> List<RoutePreview>) : OfflineRouter {
        override val available = true
        var calls = 0
        override suspend fun route(points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> {
            calls++
            return action(points, allowHighways, direction)
        }
    }

    @Test fun airplaneModeRoutesLocallyAndKeepsConstraints() = runTest {
        val local = Local { points, highways, heading ->
            assertEquals(listOf(a, b), points); assertFalse(highways)
            assertEquals(90.0, heading!!.headingDegrees, 0.0)
            listOf(leg())
        }
        val router = CompassNavigationRouteRecalculator(online { error("must not call server") }, offlineRouter = local)
        assertEquals(OFFLINE_PROVIDER, router.recalculate(state(), RouteUpdateReason.OFF_ROUTE).provider)
        assertEquals(1, local.calls)
        val controller = RouteUpdateController()
        assertNull(controller.nextUpdate(state(), 1000))
        assertEquals(RouteUpdateReason.OFF_ROUTE, controller.nextUpdate(state(), 1000, true))
        assertNull(controller.nextUpdate(state(), 2000, true))
    }

    @Test fun networkAndServerFailureOrTimeoutUseLocalButAuthAndCancellationDoNot() = runTest {
        for (failure in listOf(RoutePreviewFailure.NETWORK, RoutePreviewFailure.SERVER, RoutePreviewFailure.AUTHENTICATION)) {
            val local = Local { _, _, _ -> listOf(leg()) }
            val router = CompassNavigationRouteRecalculator(online { throw RoutePreviewException(failure) }, offlineRouter = local)
            if (failure == RoutePreviewFailure.AUTHENTICATION) {
                try { router.recalculate(state(NavigationConnectivity.ONLINE), RouteUpdateReason.OFF_ROUTE); fail() }
                catch (e: RoutePreviewException) { assertEquals(failure, e.failure) }
                assertEquals(0, local.calls)
            } else {
                assertEquals(OFFLINE_PROVIDER, router.recalculate(state(NavigationConnectivity.ONLINE), RouteUpdateReason.OFF_ROUTE).provider)
            }
        }
        val local = Local { _, _, _ -> listOf(leg()) }
        val timeout = CompassNavigationRouteRecalculator(online { delay(60_000); leg() }, offlineRouter = local)
        timeout.recalculate(state(NavigationConnectivity.ONLINE), RouteUpdateReason.OFF_ROUTE)
        assertEquals(8000L, testScheduler.currentTime)
        val cancelled = CompassNavigationRouteRecalculator(online { throw CancellationException() }, offlineRouter = local)
        try { cancelled.recalculate(state(NavigationConnectivity.ONLINE), RouteUpdateReason.OFF_ROUTE); fail() }
        catch (_: CancellationException) { assertEquals(1, local.calls) }
    }

    @Test fun offlineFailureLeavesOriginalRouteAndSuccessfulOnlineDoesNotUseLocal() = runTest {
        val original = state()
        val local = Local { _, _, _ -> error("no coverage") }
        val router = CompassNavigationRouteRecalculator(online { leg().copy(provider = "valhalla") }, offlineRouter = local)
        try { router.recalculate(original, RouteUpdateReason.OFF_ROUTE); fail() }
        catch (e: RoutePreviewException) { assertEquals(RoutePreviewFailure.NO_ROUTE, e.failure) }
        assertEquals("valhalla", original.route!!.provider)
        assertEquals("valhalla", router.recalculate(state(NavigationConnectivity.ONLINE), RouteUpdateReason.OFF_ROUTE).provider)
        assertEquals(1, local.calls)
    }

    @Test fun rejectsOfflineRouteThatWouldConsumeTheCngReserve() = runTest {
        val original = state().let { it.copy(route = it.route!!.copy(
            fuelPlan = NavigationFuelPlan(100.0, 20.0, 10.0),
        )) }
        val local = Local { _, _, _ -> listOf(leg().copy(distanceMeters = 15_000.0)) }
        val router = CompassNavigationRouteRecalculator(online { error("online") }, offlineRouter = local)
        try { router.recalculate(original, RouteUpdateReason.OFF_ROUTE); fail() }
        catch (e: RoutePreviewException) {
            assertEquals(RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE, e.failure)
        }
        assertEquals("valhalla", original.route!!.provider)
    }

    @Test fun packageDoesNotEnableOfflineReroutingForPedestrians() {
        val walking = state().let { it.copy(route = it.route!!.copy(travelMode = RouteTravelMode.WALKING)) }
        assertNull(RouteUpdateController().nextUpdate(walking, 1000, true))
    }

    @Test fun orderedCngAndOrdinaryStopsAndDwellSurviveOfflineRerouting() = runTest {
        val c = Coordinate(45.02, 11.02)
        val d = Coordinate(45.03, 11.03)
        val points = listOf(a, b, c, d)
        val legs = points.zipWithNext { from, to -> leg(from, to) }
        val stop = SelectedCngStop("123", "Metano", null, null, c, dwellTimeSeconds = 600)
        val route = RouteWithIntermediateStops(listOf(b, c), 3000.0, 300.0, legs,
            "valhalla", NavigationTiming.legacy(300.0)).toNavigationRoute(listOf(
            NavigationOrderedStop("Tappa"), NavigationOrderedStop("Metano", stop),
        )).copy(fuelPlan = NavigationFuelPlan(100.0, 80.0, 10.0))
        val session = NavigationSession()
        session.preview(route); session.start(); session.networkLost()
        val local = Local { received, _, _ -> assertEquals(points, received); legs }
        val result = CompassNavigationRouteRecalculator(online { error("online") }, offlineRouter = local)
            .recalculate(session.state.value, RouteUpdateReason.OFF_ROUTE)
        assertEquals(listOf(b), result.intermediateStops.map { it.location })
        assertEquals(listOf("123"), result.fuelStops.map { it.mimitStationId })
        assertEquals(2, result.fuelStops.single().sequence)
        assertEquals(900.0, result.totalTripDurationSeconds, 0.0)
        assertEquals(100.0, result.legs.last().availableRangeAtDepartureKm!!, 0.0)
        assertNotNull(result.fuelPlan)
        session.replaceRoute(result, 2000, null)
        assertEquals(NavigationConnectivity.OFFLINE, session.state.value.connectivity)
    }
}
