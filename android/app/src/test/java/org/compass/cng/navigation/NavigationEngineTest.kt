package org.compass.cng.navigation

import java.time.Instant
import java.time.OffsetDateTime
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {
    @Test
    fun filtersBadFixesAndSmoothsGpsNoise() {
        val filter = LocationFilter()
        val first = fix(latitude = 45.0, longitude = 9.0, timeMillis = 1_000)

        assertEquals(first.coordinate, filter.filter(first)?.coordinate)
        assertEquals(null, filter.filter(first.copy(accuracyMeters = 120.0, timestampEpochMillis = 2_000)))

        val noisy = filter.filter(
            fix(latitude = 45.00002, longitude = 9.00002, timeMillis = 2_000),
        )
        assertNotNull(noisy)
        assertTrue(requireNotNull(noisy).coordinate.latitude in 45.0..45.00002)
        assertTrue(noisy.coordinate.longitude in 9.0..9.00002)
    }

    @Test
    fun matcherProjectsToRouteAndResistsImplausibleBackwardJump() {
        val matcher = RouteMatcher(straightGeometry())
        val forward = matcher.match(
            fix(latitude = 45.00002, longitude = 9.0028, timeMillis = 1_000, bearing = 90.0),
        )
        val backwardNoise = matcher.match(
            fix(latitude = 45.0, longitude = 9.0002, timeMillis = 2_000, bearing = 90.0),
        )

        assertTrue(forward.distanceFromRouteMeters < 5.0)
        assertEquals(2, forward.segmentIndex)
        assertTrue(backwardNoise.distanceAlongGeometryMeters >= forward.distanceAlongGeometryMeters - 30.0)
    }

    @Test
    fun engineExposesOneMatchedNavigationPositionInsteadOfRawGpsAsVehicle() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        val raw = fix(45.00005, 9.0015, 1_000, bearing = 270.0)

        engine.updateLocation(raw, now = Instant.ofEpochMilli(1_000))

        val state = engine.state.value
        val position = requireNotNull(state.navigationPosition)
        assertEquals(raw, state.rawLocation)
        assertEquals(45.0, position.coordinate.latitude, 0.000_001)
        assertEquals(90.0, position.bearingDegrees, 0.5)
        assertEquals(position.coordinate, state.snappedLocation)
    }

    @Test
    fun forwardReplayUpdatesProgressManeuverAndArrivalLocally() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()

        replay("/navigation/basic-forward-replay.csv").forEach { location ->
            engine.updateLocation(
                location,
                now = Instant.ofEpochMilli(location.timestampEpochMillis),
            )
        }

        val state = engine.state.value
        assertEquals(NavigationPhase.ARRIVED, state.phase)
        assertEquals(GpsStatus.ACTIVE, state.gpsStatus)
        assertTrue(state.routeProgressFraction > 0.98)
        assertTrue(requireNotNull(state.distanceRemainingMeters) < 20.0)
        assertEquals("Arrivo a destinazione.", state.currentManeuver?.instruction)
    }

    @Test
    fun guidanceAdvancesAtTheManeuverBeginShapeInsteadOfItsEnd() {
        val base = route()
        val departure = requireNotNull(base.maneuvers.firstOrNull()).copy(
            instruction = "Parti verso est.",
            beginShapeIndex = 0,
            endShapeIndex = 2,
        )
        val exit = departure.copy(
            type = 20,
            instruction = "Prendi l'uscita Roverchiara Nord.",
            beginShapeIndex = 2,
            endShapeIndex = 3,
            streetNames = listOf("Roverchiara Nord"),
        )
        val turn = departure.copy(
            type = 15,
            instruction = "Svolta a sinistra su Via Cappafredda.",
            beginShapeIndex = 3,
            endShapeIndex = 4,
            streetNames = listOf("Via Cappafredda"),
        )
        val destination = requireNotNull(base.maneuvers.lastOrNull()).copy(
            beginShapeIndex = 4,
            endShapeIndex = 4,
        )
        val engine = testEngine()
        engine.preview(
            base.copy(maneuvers = listOf(departure, exit, turn, destination)),
        )
        engine.start()

        engine.updateLocation(fix(45.0, 9.0015, 1_000, 90.0))
        assertEquals("Prendi l'uscita Roverchiara Nord.", engine.state.value.currentManeuver?.instruction)

        engine.updateLocation(fix(45.0, 9.0025, 2_000, 90.0))
        val afterExit = engine.state.value
        assertEquals("Svolta a sinistra su Via Cappafredda.", afterExit.currentManeuver?.instruction)
        assertEquals("Arrivo a destinazione.", afterExit.nextManeuver?.instruction)
        assertTrue(requireNotNull(afterExit.distanceToNextManeuverMeters) in 35.0..45.0)
    }

    @Test
    fun temporaryDriftDoesNotConfirmOffRouteAndRecoveryClearsSuspicion() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.001, 1_000, 90.0))
        engine.updateLocation(fix(45.0006, 9.0015, 2_000, 90.0))
        engine.updateLocation(fix(45.0006, 9.0016, 3_000, 90.0))

        assertEquals(OffRouteStatus.SUSPECTED, engine.state.value.offRouteStatus)

        engine.updateLocation(fix(45.0, 9.0018, 4_000, 90.0))
        assertEquals(OffRouteStatus.ON_ROUTE, engine.state.value.offRouteStatus)
        assertTrue(engine.state.value.phase != NavigationPhase.GPS_LOST)
    }

    @Test
    fun threeBadFixesConfirmOffRouteWithoutAnyNetworkReroute() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.001, 1_000, 90.0))
        engine.updateLocation(fix(45.0007, 9.0015, 2_000, 90.0))
        engine.updateLocation(fix(45.0007, 9.0016, 3_000, 90.0))
        engine.updateLocation(fix(45.0007, 9.0017, 4_000, 90.0))

        assertEquals(OffRouteStatus.OFF_ROUTE, engine.state.value.offRouteStatus)
    }

    @Test
    fun nearbyWrongTurnConfirmsOffRouteFromHeadingAndRawRouteDistance() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.0010, 1_000, bearing = 90.0))

        engine.updateLocation(fix(45.00011, 9.0011, 2_000, bearing = 0.0))
        assertEquals(OffRouteStatus.SUSPECTED, engine.state.value.offRouteStatus)
        engine.updateLocation(fix(45.00015, 9.0011, 3_000, bearing = 0.0))
        engine.updateLocation(fix(45.00020, 9.0011, 4_000, bearing = 0.0))

        assertEquals(OffRouteStatus.OFF_ROUTE, engine.state.value.offRouteStatus)
    }

    @Test
    fun missingFixMovesSessionToGpsLostAndNextFixRecovers() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.001, 1_000, 90.0))

        engine.tick(17_000)
        assertEquals(NavigationPhase.GPS_LOST, engine.state.value.phase)
        assertEquals(GpsStatus.LOST, engine.state.value.gpsStatus)

        engine.updateLocation(fix(45.0, 9.0015, 18_000, 90.0))
        assertEquals(GpsStatus.ACTIVE, engine.state.value.gpsStatus)
        assertTrue(engine.state.value.phase != NavigationPhase.GPS_LOST)
    }

    @Test
    fun acquiringGpsWaitsForTimeoutBeforeReportingLoss() {
        val engine = testEngine()
        engine.preview(route())
        engine.start(nowEpochMillis = 1_000)

        engine.tick(15_000)
        assertEquals(NavigationPhase.NAVIGATING, engine.state.value.phase)
        assertEquals(GpsStatus.ACQUIRING, engine.state.value.gpsStatus)

        engine.tick(17_000)
        assertEquals(NavigationPhase.GPS_LOST, engine.state.value.phase)
    }

    @Test
    fun fuelStopApproachArrivalAndDwellAreFirstClassProgress() {
        val base = route()
        val fuelStop = NavigationFuelStop(
            sequence = 1,
            mimitStationId = "3618",
            name = "S.MARTINO OVEST",
            municipality = "Parma",
            province = "PR",
            location = Coordinate(45.0, 9.0020),
            expectedArrivalAt = OffsetDateTime.parse("2026-09-02T08:00:16+02:00"),
            dwellTimeSeconds = 1_200,
        )
        val route = base.copy(
            totalTripDurationSeconds = base.drivingDurationSeconds + 1_200,
            fuelStops = listOf(fuelStop),
            timing = base.timing.copy(
                refuelingStopCount = 1,
                totalRefuelingDwellSeconds = 1_200.0,
                totalTripDurationSeconds = base.drivingDurationSeconds + 1_200,
            ),
        )
        val engine = testEngine()
        engine.preview(route)
        engine.start()

        engine.updateLocation(fix(45.0, 9.0010, 1_000, 90.0))
        assertEquals(NavigationPhase.APPROACHING_FUEL_STOP, engine.state.value.phase)
        assertTrue(requireNotNull(engine.state.value.totalDurationRemainingSeconds) > 1_200.0)

        engine.updateLocation(fix(45.0, 9.0020, 2_000, 90.0))
        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)
        assertEquals("3618", engine.state.value.nextFuelStop?.stop?.mimitStationId)

        engine.updateLocation(fix(45.0, 9.0030, 3_000, 90.0))
        assertEquals(null, engine.state.value.nextFuelStop)
        assertTrue(requireNotNull(engine.state.value.totalDurationRemainingSeconds) < 20.0)
    }

    @Test
    fun successfulOffRouteReplacementRecordsTheRemainingDurationDifference() {
        val original = route()
        val engine = testEngine()
        engine.preview(original)
        engine.start()
        engine.updateLocation(fix(45.0, 9.0010, 1_000, bearing = 90.0))
        val previousDuration = requireNotNull(engine.state.value.totalDurationRemainingSeconds)
        val replacement = original.copy(
            routeId = "off-route-replacement",
            drivingDurationSeconds = previousDuration + 300.0,
            totalTripDurationSeconds = previousDuration + 300.0,
            timing = original.timing.copy(
                routeId = "off-route-replacement",
                drivingDurationSeconds = previousDuration + 300.0,
                remainingDrivingDurationSeconds = previousDuration + 300.0,
                totalTripDurationSeconds = previousDuration + 300.0,
            ),
        )

        engine.beginRouteUpdate(RouteUpdateReason.OFF_ROUTE)
        engine.replaceRoute(replacement, refreshedAtEpochMillis = 5_000, currentLocation = null)

        val notice = requireNotNull(engine.state.value.routeUpdateNotice)
        assertEquals("off-route-replacement", notice.routeId)
        assertEquals(previousDuration, notice.previousDurationSeconds, 0.0)
        assertEquals(previousDuration + 300.0, notice.updatedDurationSeconds, 0.0)
        assertEquals(300.0, notice.durationDeltaSeconds, 0.0)
        assertEquals(5_000L, notice.createdAtEpochMillis)
    }

    private fun testEngine() = NavigationEngine(
        locationFilter = LocationFilter(
            LocationFilterPolicy(
                maximumAccuracyMeters = 100.0,
                maximumPlausibleSpeedMetersPerSecond = 150.0,
                minimumPositionSmoothingAlpha = 1.0,
                maximumPositionSmoothingAlpha = 1.0,
                speedSmoothingAlpha = 1.0,
                bearingSmoothingAlpha = 1.0,
            ),
        ),
    )

    private fun route(): NavigationRoute {
        val geometry = straightGeometry()
        val preview = RoutePreview(
            origin = geometry.first(),
            destination = geometry.last(),
            distanceMeters = 314.0,
            durationSeconds = 31.4,
            geometry = geometry,
            maneuvers = listOf(
                Maneuver(
                    type = 1,
                    instruction = "Prosegui verso est.",
                    distanceMeters = 235.0,
                    durationSeconds = 23.5,
                    beginShapeIndex = 0,
                    endShapeIndex = 3,
                    streetNames = listOf("Via di prova"),
                    travelMode = "drive",
                    travelType = "car",
                ),
                Maneuver(
                    type = 4,
                    instruction = "Arrivo a destinazione.",
                    distanceMeters = 79.0,
                    durationSeconds = 7.9,
                    beginShapeIndex = 3,
                    endShapeIndex = 4,
                    streetNames = emptyList(),
                    travelMode = "drive",
                    travelType = "car",
                ),
            ),
            provider = "valhalla",
            navigation = NavigationTiming(
                routeId = "route_navigation_stage_2_fixture",
                drivingDurationSeconds = 31.4,
                remainingDrivingDurationSeconds = 31.4,
                refuelingStopCount = 0,
                dwellSecondsPerRefuelingStop = 1_200,
                totalRefuelingDwellSeconds = 0.0,
                totalTripDurationSeconds = 31.4,
                departureAt = OffsetDateTime.parse("2026-09-02T08:00:00+02:00"),
                drivingArrivalAt = OffsetDateTime.parse("2026-09-02T08:00:31.400+02:00"),
                tripArrivalAt = OffsetDateTime.parse("2026-09-02T08:00:31.400+02:00"),
            ),
        )
        return preview.toNavigationRoute()
    }

    private fun straightGeometry() = listOf(
        Coordinate(45.0, 9.0000),
        Coordinate(45.0, 9.0010),
        Coordinate(45.0, 9.0020),
        Coordinate(45.0, 9.0030),
        Coordinate(45.0, 9.0040),
    )

    private fun replay(resource: String): List<NavigationLocation> =
        requireNotNull(javaClass.getResourceAsStream(resource))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val values = line.split(',')
                fix(
                    latitude = values[1].toDouble(),
                    longitude = values[2].toDouble(),
                    timeMillis = values[0].toLong(),
                    bearing = values[5].toDouble(),
                    accuracy = values[3].toDouble(),
                    speed = values[4].toDouble(),
                )
            }

    private fun fix(
        latitude: Double,
        longitude: Double,
        timeMillis: Long,
        bearing: Double? = 90.0,
        accuracy: Double = 5.0,
        speed: Double = 10.0,
    ) = NavigationLocation(
        coordinate = Coordinate(latitude, longitude),
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        bearingDegrees = bearing,
        timestampEpochMillis = timeMillis,
    )
}
