package org.compass.cng.ui.route

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.navigation.GpsStatus
import org.compass.cng.navigation.NavigationConnectivity
import org.compass.cng.navigation.NavigationFuelStop
import org.compass.cng.navigation.NavigationRoute
import org.compass.cng.navigation.NavigationRouteSource
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.navigation.RouteUpdateFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDrivingUiModelTest {
    @Test
    fun exposesGlanceableManeuverTripAndCngInformation() {
        val state = sampleState()

        val ui = state.toDrivingUiModel()

        assertEquals("↱", ui.maneuverSymbol)
        assertEquals("320 m", ui.distanceToManeuver)
        assertEquals("Svolta a destra su Via Roma.", ui.primaryInstruction)
        assertEquals("Via Roma", ui.targetRoad)
        assertEquals("Poi mantieni la sinistra.", ui.followingInstruction)
        assertEquals("81,5 km", ui.remainingDistance)
        assertEquals("1 h 40 min", ui.remainingDuration)
        assertEquals("S. ZENONE OVEST", ui.nextCngStop?.name)
        assertEquals("22,5 km", ui.nextCngStop?.distance)
        assertEquals("20:45", ui.nextCngStop?.arrivalTime)
        assertEquals(0.25f, ui.progress)
    }

    @Test
    fun keepsDegradedDiagnosticsOutOfPrimaryValuesAndInDetailsMessages() {
        val state = sampleState().copy(
            routeSource = NavigationRouteSource.CACHE,
            connectivity = NavigationConnectivity.REROUTING_UNAVAILABLE,
            reroutingStatus = ReroutingStatus.FAILED,
            routeUpdateFailure = RouteUpdateFailure.NETWORK_OR_SERVER,
        )

        val ui = state.toDrivingUiModel()
        val messages = ui.statusMessages.map { it.text }

        assertEquals("81,5 km", ui.remainingDistance)
        assertTrue(messages.any { "rotta salvata" in it })
        assertTrue(messages.any { "Traffico live non disponibile" in it })
        assertTrue(messages.any { "ricalcolo non disponibile" in it })
        assertTrue(messages.any { "Dati CNG in cache" in it })
        assertTrue(messages.any { "continuo sulla rotta scaricata" in it })
    }

    @Test
    fun mapsValhallaManeuverFamiliesToStablePhaseOneSymbols() {
        assertEquals("↑", maneuverSymbol(8, null))
        assertEquals("↗", maneuverSymbol(9, null))
        assertEquals("↱", maneuverSymbol(10, null))
        assertEquals("↰", maneuverSymbol(15, null))
        assertEquals("↖", maneuverSymbol(16, null))
        assertEquals("⟳", maneuverSymbol(26, null))
        assertEquals("◆", maneuverSymbol(4, null))
    }

    private fun sampleState(): NavigationState {
        val origin = Coordinate(45.0, 9.0)
        val destination = Coordinate(44.5, 11.0)
        val first = Maneuver(
            type = 10,
            instruction = "Svolta a destra su Via Roma.",
            distanceMeters = 1_000.0,
            durationSeconds = 80.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            streetNames = listOf("Via Roma"),
            travelMode = "drive",
            travelType = "car",
        )
        val second = first.copy(type = 24, instruction = "Poi mantieni la sinistra.")
        val stop = NavigationFuelStop(
            sequence = 1,
            mimitStationId = "123",
            name = "S. ZENONE OVEST",
            municipality = "San Zenone al Lambro",
            province = "MI",
            location = Coordinate(44.9, 9.3),
            expectedArrivalAt = OffsetDateTime.of(2026, 9, 3, 20, 45, 0, 0, ZoneOffset.UTC),
            dwellTimeSeconds = 1_200,
        )
        val route = NavigationRoute(
            routeId = "route-ui",
            origin = origin,
            destination = destination,
            totalDistanceMeters = 100_000.0,
            drivingDurationSeconds = 6_000.0,
            totalTripDurationSeconds = 7_200.0,
            geometry = listOf(origin, destination),
            legs = emptyList(),
            maneuvers = listOf(first, second),
            fuelStops = listOf(stop),
            timing = NavigationTiming.legacy(6_000.0, refuelingStopCount = 1),
            provider = "valhalla",
        )
        return NavigationState(
            route = route,
            currentManeuver = first,
            nextManeuver = second,
            currentRoadName = "Via Roma",
            distanceToNextManeuverMeters = 320.0,
            distanceRemainingMeters = 81_500.0,
            totalDurationRemainingSeconds = 6_000.0,
            estimatedArrivalAt = Instant.parse("2026-09-03T20:45:00Z"),
            routeProgressFraction = 0.25,
            gpsStatus = GpsStatus.ACTIVE,
            nextFuelStop = org.compass.cng.navigation.NavigationFuelStopProgress(stop, 22_500.0),
        )
    }
}
