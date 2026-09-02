package org.compass.cng.navigation

import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.CngItineraryRouteLeg
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveRangeBasis
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngItinerary
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.SelectedCngStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRouteRecalculatorTest {
    @Test
    fun offRouteUsesRawFixWhileTrafficRefreshUsesSnappedPosition() = runTest {
        val repository = RecordingRepository()
        val recalculator = CompassNavigationRouteRecalculator(repository)
        val original = repository.route(
            Coordinate(45.0, 9.0),
            Coordinate(44.0, 11.0),
        ).toNavigationRoute()
        val raw = Coordinate(45.01, 9.02)
        val snapped = Coordinate(45.0, 9.01)
        val state = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = original,
            rawLocation = NavigationLocation(raw, 5.0, 10.0, 90.0, 1_000),
            snappedLocation = snapped,
        )

        recalculator.recalculate(state, RouteUpdateReason.OFF_ROUTE)
        assertEquals(raw, repository.lastOrigin)

        recalculator.recalculate(state, RouteUpdateReason.TRAFFIC_REFRESH)
        assertEquals(snapped, repository.lastOrigin)
        assertEquals(original.destination, repository.lastDestination)
    }

    @Test
    fun multiStopReroutePreservesRemainingStationsAndCurrentFuelEstimate() = runTest {
        val repository = RecordingRepository()
        val recalculator = CompassNavigationRouteRecalculator(repository)
        val origin = Coordinate(45.0, 9.0)
        val first = Coordinate(44.8, 9.5)
        val second = Coordinate(44.4, 10.2)
        val destination = Coordinate(44.0, 11.0)
        val stops = listOf(
            fuelStop(1, "first", first),
            fuelStop(2, "second", second),
        )
        val route = NavigationRoute(
            routeId = "multi",
            origin = origin,
            destination = destination,
            totalDistanceMeters = 30_000.0,
            drivingDurationSeconds = 1_800.0,
            totalTripDurationSeconds = 4_200.0,
            geometry = listOf(origin, first, second, destination),
            legs = listOf(
                navigationLeg(1, origin, first, 10_000.0, 65.0),
                navigationLeg(2, first, second, 10_000.0, 100.0),
                navigationLeg(3, second, destination, 10_000.0, 100.0),
            ),
            maneuvers = emptyList(),
            fuelStops = stops,
            fuelPlan = NavigationFuelPlan(100.0, 65.0, 30.0),
            timing = timing("multi", 1_800.0, 2),
            provider = "valhalla",
        )
        val state = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            snappedLocation = origin,
            routeProgressFraction = 0.1,
            nextFuelStop = NavigationFuelStopProgress(stops.first(), 7_000.0),
        )

        recalculator.recalculate(state, RouteUpdateReason.TRAFFIC_REFRESH)

        assertEquals(listOf("first", "second"), repository.lastItineraryIds)
        assertEquals(100.0, requireNotNull(repository.lastEffectiveRangeKm), 0.0)
        assertEquals(62.0, requireNotNull(repository.lastRemainingRangeKm), 0.0)
        assertEquals(30.0, requireNotNull(repository.lastReserveRangeKm), 0.0)
    }

    @Test
    fun unavailableFuelStopIsExcludedBeforeAReachableDirectRouteReplacesIt() = runTest {
        val repository = RecordingRepository()
        val recalculator = CompassNavigationRouteRecalculator(repository)
        val origin = Coordinate(45.0, 9.0)
        val stopLocation = Coordinate(44.5, 10.0)
        val destination = Coordinate(44.0, 11.0)
        val stop = fuelStop(1, "1001", stopLocation)
        val route = NavigationRoute(
            routeId = "fuel-route",
            origin = origin,
            destination = destination,
            totalDistanceMeters = 10_000.0,
            drivingDurationSeconds = 600.0,
            totalTripDurationSeconds = 1_800.0,
            geometry = listOf(origin, stopLocation, destination),
            legs = listOf(navigationLeg(1, origin, stopLocation, 10_000.0, 65.0)),
            maneuvers = emptyList(),
            fuelStops = listOf(stop),
            fuelPlan = NavigationFuelPlan(
                effectiveCngRangeKm = 100.0,
                initialRemainingCngRangeKm = 65.0,
                reserveCngRangeKm = 30.0,
                maximumDetourMinutes = 12.0,
                excludedMimitStationIds = setOf("999"),
            ),
            timing = timing("fuel-route", 600.0, 1),
            provider = "valhalla",
        )
        val state = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            snappedLocation = origin,
            routeProgressFraction = 0.1,
            nextFuelStop = NavigationFuelStopProgress(stop, 9_000.0),
        )

        val result = recalculator.replaceUnavailableFuelStop(state)

        assertTrue(result is FuelStopReplacementResult.Replaced)
        result as FuelStopReplacementResult.Replaced
        assertEquals(emptyList<NavigationFuelStop>(), result.route.fuelStops)
        assertEquals(setOf("999", "1001"), repository.lastExcludedIds)
        assertEquals(64.0, requireNotNull(repository.lastRemainingRangeKm), 0.0)
        assertEquals(12.0, requireNotNull(repository.lastMaximumDetourMinutes), 0.0)
    }

    @Test
    fun unavailableFuelStopWithoutPredictiveRangePlanKeepsDownloadedRoute() = runTest {
        val repository = RecordingRepository()
        val recalculator = CompassNavigationRouteRecalculator(repository)
        val route = repository.route(Coordinate(45.0, 9.0), Coordinate(44.0, 11.0))
            .toNavigationRoute()

        val result = recalculator.replaceUnavailableFuelStop(
            NavigationState(phase = NavigationPhase.NAVIGATING, route = route),
        )

        assertEquals(FuelStopReplacementResult.RangePlanRequired, result)
    }

    private fun fuelStop(sequence: Int, id: String, location: Coordinate) = NavigationFuelStop(
        sequence = sequence,
        mimitStationId = id,
        name = id,
        municipality = null,
        province = null,
        location = location,
        expectedArrivalAt = null,
        dwellTimeSeconds = 1_200,
    )

    private fun navigationLeg(
        sequence: Int,
        origin: Coordinate,
        destination: Coordinate,
        distanceMeters: Double,
        availableRangeKm: Double,
    ) = NavigationLeg(
        sequence = sequence,
        origin = origin,
        destination = destination,
        distanceMeters = distanceMeters,
        durationSeconds = 600.0,
        geometry = listOf(origin, destination),
        maneuvers = emptyList(),
        shapeIndexOffset = sequence - 1,
        availableRangeAtDepartureKm = availableRangeKm,
        estimatedRemainingRangeAtArrivalKm = availableRangeKm - 10.0,
        reserveMarginAtArrivalKm = availableRangeKm - 40.0,
    )

    private fun timing(routeId: String, durationSeconds: Double, stopCount: Int) = NavigationTiming(
        routeId = routeId,
        drivingDurationSeconds = durationSeconds,
        remainingDrivingDurationSeconds = durationSeconds,
        refuelingStopCount = stopCount,
        dwellSecondsPerRefuelingStop = 1_200,
        totalRefuelingDwellSeconds = stopCount * 1_200.0,
        totalTripDurationSeconds = durationSeconds + stopCount * 1_200.0,
        departureAt = null,
        drivingArrivalAt = null,
        tripArrivalAt = null,
    )

    private class RecordingRepository : RoutingRepository {
        var lastOrigin: Coordinate? = null
        var lastDestination: Coordinate? = null
        var lastItineraryIds: List<String>? = null
        var lastEffectiveRangeKm: Double? = null
        var lastRemainingRangeKm: Double? = null
        var lastReserveRangeKm: Double? = null
        var lastMaximumDetourMinutes: Double? = null
        var lastExcludedIds: Set<String>? = null

        override suspend fun previewRoute(
            origin: Coordinate,
            destination: Coordinate,
        ): RoutePreview {
            lastOrigin = origin
            lastDestination = destination
            return route(origin, destination)
        }

        fun route(origin: Coordinate, destination: Coordinate): RoutePreview = RoutePreview(
            origin = origin,
            destination = destination,
            distanceMeters = 1_000.0,
            durationSeconds = 100.0,
            geometry = listOf(origin, destination),
            maneuvers = emptyList(),
            provider = "valhalla",
        )

        override suspend fun rankedCngStations(
            origin: Coordinate,
            destination: Coordinate,
            effectiveCngRangeKm: Double,
            maximumDetourMinutes: Double,
            departureAt: OffsetDateTime,
        ): RankedCngStations = error("not expected")

        override suspend fun routeWithCngStop(
            origin: Coordinate,
            destination: Coordinate,
            mimitStationId: String,
        ): RouteWithCngStop {
            val stop = SelectedCngStop(
                mimitStationId = mimitStationId,
                name = null,
                municipality = null,
                province = null,
                location = Coordinate(
                    (origin.latitude + destination.latitude) / 2,
                    (origin.longitude + destination.longitude) / 2,
                ),
            )
            val legs = listOf(
                CngRouteLeg(CngRouteLegKind.ORIGIN_TO_CNG_STATION, route(origin, stop.location)),
                CngRouteLeg(CngRouteLegKind.CNG_STATION_TO_DESTINATION, route(stop.location, destination)),
            )
            return RouteWithCngStop(
                selectedStop = stop,
                distanceMeters = 2_000.0,
                durationSeconds = 200.0,
                legs = legs,
                provider = "valhalla",
            )
        }

        override suspend fun predictiveCngStations(
            origin: Coordinate,
            destination: Coordinate,
            effectiveCngRangeKm: Double,
            estimatedRemainingCngRangeKm: Double,
            reserveCngRangeKm: Double,
            maximumDetourMinutes: Double,
            departureAt: OffsetDateTime,
            excludedMimitStationIds: Set<String>,
        ): PredictiveCngSuggestion {
            lastOrigin = origin
            lastDestination = destination
            lastEffectiveRangeKm = effectiveCngRangeKm
            lastRemainingRangeKm = estimatedRemainingCngRangeKm
            lastReserveRangeKm = reserveCngRangeKm
            lastMaximumDetourMinutes = maximumDetourMinutes
            lastExcludedIds = excludedMimitStationIds
            return PredictiveCngSuggestion(
                state = PredictiveSuggestionState.NOT_NEEDED,
                departureAt = departureAt,
                maximumDetourMinutes = maximumDetourMinutes,
                baseRoute = route(origin, destination),
                rangeBasis = PredictiveRangeBasis(
                    effectiveCngRangeKm = effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = estimatedRemainingCngRangeKm,
                    reserveCngRangeKm = reserveCngRangeKm,
                    usableRangeBeforeReserveKm =
                        estimatedRemainingCngRangeKm - reserveCngRangeKm,
                    remainingRouteDistanceKm = 1.0,
                    rangeShortfallToDestinationKm = 0.0,
                    destinationReachableWithReserve = true,
                    consumptionModel = "caller_estimated_remaining_range",
                    trafficState = "not_configured",
                    trafficAdjusted = false,
                ),
                candidates = emptyList(),
                itinerary = null,
            )
        }

        override suspend fun routeWithCngItinerary(
            origin: Coordinate,
            destination: Coordinate,
            mimitStationIds: List<String>,
            effectiveCngRangeKm: Double,
            estimatedRemainingCngRangeKm: Double,
            reserveCngRangeKm: Double,
        ): RouteWithCngItinerary {
            lastItineraryIds = mimitStationIds
            lastEffectiveRangeKm = effectiveCngRangeKm
            lastRemainingRangeKm = estimatedRemainingCngRangeKm
            lastReserveRangeKm = reserveCngRangeKm
            val stops = mimitStationIds.mapIndexed { index, id ->
                SelectedCngStop(
                    mimitStationId = id,
                    name = id,
                    municipality = null,
                    province = null,
                    location = Coordinate(
                        origin.latitude + (destination.latitude - origin.latitude) * (index + 1) /
                            (mimitStationIds.size + 1),
                        origin.longitude + (destination.longitude - origin.longitude) * (index + 1) /
                            (mimitStationIds.size + 1),
                    ),
                )
            }
            val waypoints = listOf(origin) + stops.map(SelectedCngStop::location) + destination
            val legs = waypoints.zipWithNext().mapIndexed { index, (legOrigin, legDestination) ->
                CngItineraryRouteLeg(
                    sequence = index + 1,
                    kind = when (index) {
                        0 -> CngRouteLegKind.ORIGIN_TO_CNG_STATION
                        waypoints.size - 2 -> CngRouteLegKind.CNG_STATION_TO_DESTINATION
                        else -> CngRouteLegKind.CNG_STATION_TO_CNG_STATION
                    },
                    route = route(legOrigin, legDestination),
                    availableRangeAtDepartureKm = if (index == 0) {
                        estimatedRemainingCngRangeKm
                    } else {
                        effectiveCngRangeKm
                    },
                    estimatedRemainingRangeAtArrivalKm = reserveCngRangeKm + 1.0,
                    reserveMarginAtArrivalKm = 1.0,
                )
            }
            return RouteWithCngItinerary(
                selectedStops = stops,
                distanceMeters = legs.size * 1_000.0,
                durationSeconds = legs.size * 100.0,
                legs = legs,
                provider = "valhalla",
                rangeValidation = "all_legs_preserve_reserve",
            )
        }
    }
}
