package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationSessionCacheTest {
    @Test
    fun processRestartRestoresActiveGuidanceProgressAndExplicitStopClearsIt() {
        val store = MemoryRouteStore()
        var now = 1_000L
        val first = NavigationSession(routeStore = store, clock = { now })
        first.preview(route())
        first.start()
        first.setLocationMode(NavigationLocationMode.DEMO_REPLAY)
        now = 7_000L
        first.updateLocation(
            NavigationLocation(
                Coordinate(45.05, 9.05), 4.0, 12.0, 38.0, 7_000L,
            ),
        )

        now = 8_000L
        val restored = NavigationSession(routeStore = store, clock = { now })

        assertEquals(NavigationPhase.GPS_LOST, restored.state.value.phase)
        assertEquals(true, restored.restoredNavigationWasActive)
        assertEquals(NavigationRouteSource.CACHE, restored.state.value.routeSource)
        assertEquals(NavigationConnectivity.REROUTING_UNAVAILABLE, restored.state.value.connectivity)
        assertEquals(NavigationLocationMode.DEMO_REPLAY, restored.state.value.locationMode)
        assertEquals("offline-route", restored.state.value.route?.routeId)
        assertEquals(0.5, restored.state.value.routeProgressFraction, 0.03)
        assertEquals(45.05, requireNotNull(restored.state.value.snappedLocation).latitude, 1e-9)
        assertEquals(9.05, requireNotNull(restored.state.value.snappedLocation).longitude, 1e-9)
        restored.stopToPreview()
        assertNull(store.cached)
    }

    @Test
    fun restoredPreviewReportsThatNavigationWasNotActive() {
        val store = MemoryRouteStore()
        NavigationSession(routeStore = store).preview(route())

        val restored = NavigationSession(routeStore = store)

        assertEquals(false, restored.restoredNavigationWasActive)
        assertEquals(NavigationPhase.ROUTE_PREVIEW, restored.state.value.phase)
    }

    @Test
    fun failedRefreshKeepsRouteAndMarksReroutingUnavailableUntilReplacement() {
        val session = NavigationSession()
        session.preview(route())
        session.start()
        session.beginRouteUpdate(RouteUpdateReason.MANUAL_DEBUG)
        session.failRouteUpdate()

        assertEquals("offline-route", session.state.value.route?.routeId)
        assertEquals(NavigationConnectivity.REROUTING_UNAVAILABLE, session.state.value.connectivity)

        session.replaceRoute(route("fresh-route"), 1234, null)
        assertEquals(NavigationConnectivity.ONLINE, session.state.value.connectivity)
        assertEquals(NavigationRouteSource.LIVE, session.state.value.routeSource)
    }

    @Test
    fun networkLossRetainsLocalStateAndRecoveryNeedsASuccessfulReplacement() {
        val session = NavigationSession()
        session.preview(route())
        session.start()
        session.setLocationMode(NavigationLocationMode.DEMO_REPLAY)

        session.networkLost()
        assertEquals(NavigationConnectivity.OFFLINE, session.state.value.connectivity)
        assertEquals("offline-route", session.state.value.route?.routeId)

        session.networkRestored()
        assertEquals(NavigationConnectivity.RECOVERING, session.state.value.connectivity)
        assertEquals("offline-route", session.state.value.route?.routeId)

        session.beginRouteUpdate(RouteUpdateReason.CONNECTIVITY_RECOVERY)
        session.failRouteUpdate()
        assertEquals(NavigationConnectivity.RECOVERING, session.state.value.connectivity)

        session.replaceRoute(route("recovered-route"), 5_000L, null)
        assertEquals(NavigationConnectivity.ONLINE, session.state.value.connectivity)
        assertEquals(NavigationLocationMode.DEMO_REPLAY, session.state.value.locationMode)
        assertEquals("recovered-route", session.state.value.route?.routeId)
    }

    private fun route(id: String = "offline-route") = RoutePreview(
        origin = Coordinate(45.0, 9.0),
        destination = Coordinate(45.1, 9.1),
        distanceMeters = 10_000.0,
        durationSeconds = 600.0,
        geometry = listOf(Coordinate(45.0, 9.0), Coordinate(45.1, 9.1)),
        maneuvers = listOf(
            Maneuver(1, "Prosegui.", 10_000.0, 600.0, 0, 1, emptyList(), travelMode = "drive", travelType = "car"),
        ),
        provider = "valhalla",
        navigation = org.compass.cng.domain.model.NavigationTiming.legacy(600.0).copy(routeId = id),
    ).toNavigationRoute()

    private class MemoryRouteStore : NavigationRouteStore {
        var cached: CachedNavigationRoute? = null

        override fun load(): CachedNavigationRoute? = cached

        override fun save(
            route: NavigationRoute,
            navigationWasActive: Boolean,
            progress: NavigationProgressSnapshot?,
        ) {
            cached = CachedNavigationRoute(route, 1000, navigationWasActive, progress)
        }

        override fun clear() {
            cached = null
        }
    }
}
