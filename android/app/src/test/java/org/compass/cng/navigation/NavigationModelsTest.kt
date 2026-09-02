package org.compass.cng.navigation

import java.time.OffsetDateTime
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.SelectedCngStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NavigationModelsTest {
    @Test
    fun selectedStopBecomesFirstClassNavigationEventWithTwentyMinuteDwell() {
        val route = selectedStopRoute()

        val navigation = route.toNavigationRoute()

        assertEquals("route_1234567890abcdef1234567890abcdef", navigation.routeId)
        assertEquals(2, navigation.legs.size)
        assertEquals(1, navigation.fuelStops.size)
        assertEquals(1_200, navigation.fuelStops.single().dwellTimeSeconds)
        assertEquals(2_400.0, navigation.drivingDurationSeconds, 0.0)
        assertEquals(3_600.0, navigation.totalTripDurationSeconds, 0.0)
    }

    @Test
    fun concatenatedLegManeuversUseGlobalShapeIndexesWithoutDuplicatingWaypoint() {
        val navigation = selectedStopRoute().toNavigationRoute()

        assertEquals(5, navigation.geometry.size)
        assertEquals(listOf(0, 2), navigation.maneuvers.map { it.beginShapeIndex })
        assertEquals(listOf(2, 4), navigation.maneuvers.map { it.endShapeIndex })
        assertNotEquals(
            navigation.geometry[1],
            navigation.geometry[3],
        )
    }

    @Test
    fun sessionPublishesExplicitPreviewStateAndCanReturnToIdle() {
        val session = NavigationSession()

        session.preview(selectedStopRoute().toNavigationRoute())
        assertEquals(NavigationPhase.ROUTE_PREVIEW, session.state.value.phase)
        assertEquals(1, session.state.value.route?.fuelStops?.size)

        session.clear()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        assertEquals(null, session.state.value.route)
    }

    private fun selectedStopRoute(): RouteWithCngStop {
        val origin = Coordinate(45.0, 9.0)
        val stop = Coordinate(44.5, 10.0)
        val destination = Coordinate(44.0, 11.0)
        val timing = NavigationTiming(
            routeId = "route_1234567890abcdef1234567890abcdef",
            drivingDurationSeconds = 2_400.0,
            remainingDrivingDurationSeconds = 2_400.0,
            refuelingStopCount = 1,
            dwellSecondsPerRefuelingStop = 1_200,
            totalRefuelingDwellSeconds = 1_200.0,
            totalTripDurationSeconds = 3_600.0,
            departureAt = OffsetDateTime.parse("2026-09-02T08:00:00+02:00"),
            drivingArrivalAt = OffsetDateTime.parse("2026-09-02T08:40:00+02:00"),
            tripArrivalAt = OffsetDateTime.parse("2026-09-02T09:00:00+02:00"),
        )
        val first = routeLeg(origin, stop, 1_000.0, 0.0)
        val second = routeLeg(stop, destination, 1_400.0, 1.0)
        return RouteWithCngStop(
            selectedStop = SelectedCngStop(
                mimitStationId = "3618",
                name = "S.MARTINO OVEST",
                municipality = "Parma",
                province = "PR",
                location = stop,
                expectedArrivalAt = OffsetDateTime.parse("2026-09-02T08:16:40+02:00"),
                dwellTimeSeconds = 1_200,
            ),
            distanceMeters = 24_000.0,
            durationSeconds = 2_400.0,
            legs = listOf(
                CngRouteLeg(CngRouteLegKind.ORIGIN_TO_CNG_STATION, first),
                CngRouteLeg(CngRouteLegKind.CNG_STATION_TO_DESTINATION, second),
            ),
            provider = "valhalla",
            navigation = timing,
        )
    }

    private fun routeLeg(
        origin: Coordinate,
        destination: Coordinate,
        durationSeconds: Double,
        latitudeOffset: Double,
    ): RoutePreview {
        val middle = Coordinate(
            (origin.latitude + destination.latitude) / 2 + latitudeOffset / 100,
            (origin.longitude + destination.longitude) / 2,
        )
        return RoutePreview(
            origin = origin,
            destination = destination,
            distanceMeters = 12_000.0,
            durationSeconds = durationSeconds,
            geometry = listOf(origin, middle, destination),
            maneuvers = listOf(
                Maneuver(
                    type = 1,
                    instruction = "Prosegui.",
                    distanceMeters = 12_000.0,
                    durationSeconds = durationSeconds,
                    beginShapeIndex = 0,
                    endShapeIndex = 2,
                    streetNames = emptyList(),
                    travelMode = "drive",
                    travelType = "car",
                ),
            ),
            provider = "valhalla",
        )
    }
}
