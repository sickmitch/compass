package org.compass.cng.data.navigation

import java.time.OffsetDateTime
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.SelectedCngStop
import org.compass.cng.navigation.CachedNavigationRoute
import org.compass.cng.navigation.toNavigationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRouteDocumentCodecTest {
    private val codec = NavigationRouteDocumentCodec()

    @Test
    fun roundTripPreservesDownloadedGeometryManeuversStationAndTiming() {
        val route = routeWithStop().toNavigationRoute()
        val cached = CachedNavigationRoute(route, 1_725_000_000_000, navigationWasActive = true)

        val restored = requireNotNull(codec.decode(codec.encode(cached)))

        assertEquals(cached, restored)
        assertEquals("43690", restored.route.fuelStops.single().mimitStationId)
        assertEquals(1_200, restored.route.timing.totalRefuelingDwellSeconds.toInt())
    }

    @Test
    fun invalidOrUnknownCacheDocumentsAreIgnored() {
        assertNull(codec.decode("not-json"))
        assertNull(codec.decode("""{"schemaVersion":99,"cachedAtEpochMillis":1,"navigationWasActive":true,"route":{}}"""))
    }

    private fun routeWithStop(): RouteWithCngStop {
        val origin = Coordinate(45.0, 9.0)
        val stop = Coordinate(44.5, 10.0)
        val destination = Coordinate(44.0, 11.0)
        val first = leg(origin, stop, "Verso il rifornimento")
        val second = leg(stop, destination, "Verso la destinazione")
        return RouteWithCngStop(
            selectedStop = SelectedCngStop(
                "43690", "S.ZENONE OVEST", "San Zenone", "MI", stop,
                OffsetDateTime.parse("2026-09-03T09:00:00+02:00"), 1_200,
            ),
            distanceMeters = 120_000.0,
            durationSeconds = 5_400.0,
            legs = listOf(
                CngRouteLeg(CngRouteLegKind.ORIGIN_TO_CNG_STATION, first),
                CngRouteLeg(CngRouteLegKind.CNG_STATION_TO_DESTINATION, second),
            ),
            provider = "valhalla",
            navigation = NavigationTiming(
                "cached-route", 5_400.0, 5_400.0, 1, 1_200, 1_200.0, 6_600.0,
                OffsetDateTime.parse("2026-09-03T08:00:00+02:00"),
                OffsetDateTime.parse("2026-09-03T09:30:00+02:00"),
                OffsetDateTime.parse("2026-09-03T09:50:00+02:00"),
            ),
        )
    }

    private fun leg(origin: Coordinate, destination: Coordinate, instruction: String) = RoutePreview(
        origin = origin,
        destination = destination,
        distanceMeters = 60_000.0,
        durationSeconds = 2_700.0,
        geometry = listOf(origin, destination),
        maneuvers = listOf(
            Maneuver(1, instruction, 60_000.0, 2_700.0, 0, 1, emptyList(), travelMode = "drive", travelType = "car"),
        ),
        provider = "valhalla",
    )
}
