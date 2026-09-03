package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationSessionCacheTest {
    @Test
    fun processRestartRestoresRouteAsCachedPreviewAndExplicitStopClearsIt() {
        val store = MemoryRouteStore()
        val first = NavigationSession(routeStore = store)
        first.preview(route())
        first.start()

        val restored = NavigationSession(routeStore = store)

        assertEquals(NavigationPhase.ROUTE_PREVIEW, restored.state.value.phase)
        assertEquals(NavigationRouteSource.CACHE, restored.state.value.routeSource)
        assertEquals("offline-route", restored.state.value.route?.routeId)
        restored.stopToPreview()
        assertNull(store.cached)
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

        override fun save(route: NavigationRoute, navigationWasActive: Boolean) {
            cached = CachedNavigationRoute(route, 1000, navigationWasActive)
        }

        override fun clear() {
            cached = null
        }
    }
}
