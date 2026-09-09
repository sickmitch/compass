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
    fun rapidPoorFixBurstDoesNotConfirmUntilMinimumDurationHasElapsed() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.0010, 1_000, bearing = 90.0))

        engine.updateLocation(fix(45.0007, 9.0011, 2_000, bearing = 0.0))
        engine.updateLocation(fix(45.0007, 9.0012, 2_400, bearing = 0.0))
        engine.updateLocation(fix(45.0007, 9.0013, 2_800, bearing = 0.0))

        assertEquals(OffRouteStatus.SUSPECTED, engine.state.value.offRouteStatus)
        assertEquals(800L, engine.state.value.offRouteDurationMillis)

        engine.updateLocation(fix(45.0007, 9.0014, 4_100, bearing = 0.0))

        assertEquals(OffRouteStatus.OFF_ROUTE, engine.state.value.offRouteStatus)
        assertTrue(requireNotNull(engine.state.value.routeMatchConfidence) < 0.5)
    }

    @Test
    fun moderateGpsDriftWhileExplicitlyStationaryDoesNotTriggerRerouting() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.0010, 1_000, speed = 0.0))

        engine.updateLocation(fix(45.0004, 9.0011, 2_000, speed = 0.0))
        engine.updateLocation(fix(45.0004, 9.0012, 3_000, speed = 0.0))
        engine.updateLocation(fix(45.0004, 9.0013, 4_000, speed = 0.0))

        assertEquals(OffRouteStatus.ON_ROUTE, engine.state.value.offRouteStatus)
        assertTrue(requireNotNull(engine.state.value.distanceFromRouteMeters) > 20.0)
    }

    @Test
    fun confirmedDeviationNeedsTwoReliableFixesToRecover() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.0010, 1_000))
        engine.updateLocation(fix(45.0007, 9.0011, 2_000))
        engine.updateLocation(fix(45.0007, 9.0012, 3_000))
        engine.updateLocation(fix(45.0007, 9.0013, 4_000))
        assertEquals(OffRouteStatus.OFF_ROUTE, engine.state.value.offRouteStatus)

        engine.updateLocation(fix(45.0, 9.0014, 5_000))
        assertEquals(OffRouteStatus.OFF_ROUTE, engine.state.value.offRouteStatus)

        engine.updateLocation(fix(45.0, 9.0015, 6_000))
        assertEquals(OffRouteStatus.ON_ROUTE, engine.state.value.offRouteStatus)
    }

    @Test
    fun doubtfulMatchesDoNotAdvanceOldRouteProgressOrFuelPlan() {
        val engine = testEngine()
        engine.preview(route())
        engine.start()
        engine.updateLocation(fix(45.0, 9.0010, 1_000))
        val reliable = engine.state.value

        engine.updateLocation(fix(45.0007, 9.0024, 2_000))
        val suspected = engine.state.value

        assertEquals(OffRouteStatus.SUSPECTED, suspected.offRouteStatus)
        assertEquals(reliable.routeProgressFraction, suspected.routeProgressFraction, 0.0)
        assertEquals(reliable.currentManeuver, suspected.currentManeuver)
        assertEquals(reliable.distanceRemainingMeters, suspected.distanceRemainingMeters)
        assertEquals(reliable.navigationPosition, suspected.navigationPosition)
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
    fun fuelStopVisitBlocksProgressCountsDownDwellAndResumesOnlyAfterConfirmation() {
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

        engine.updateLocation(
            fix(45.0, 9.0010, 1_000, 90.0),
            now = Instant.ofEpochMilli(1_000),
        )
        assertEquals(NavigationPhase.APPROACHING_FUEL_STOP, engine.state.value.phase)
        assertEquals(
            NavigationFuelStopLifecycle.APPROACHING,
            engine.state.value.fuelStopProgress.single().lifecycle,
        )
        assertTrue(requireNotNull(engine.state.value.totalDurationRemainingSeconds) > 1_200.0)

        engine.updateLocation(
            fix(45.0, 9.0020, 2_000, 90.0),
            now = Instant.ofEpochMilli(2_000),
        )
        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)
        assertEquals("3618", engine.state.value.nextFuelStop?.stop?.mimitStationId)
        assertEquals(
            NavigationFuelStopLifecycle.REFUELING,
            engine.state.value.nextFuelStop?.lifecycle,
        )
        assertEquals(
            1_200.0,
            requireNotNull(engine.state.value.activeFuelStopVisit).remainingDwellSeconds,
            0.0,
        )
        val progressAtStop = engine.state.value.routeProgressFraction
        val drivingAtStop = requireNotNull(engine.state.value.drivingDurationRemainingSeconds)

        engine.updateLocation(
            fix(45.0, 9.0030, 3_000, 90.0),
            now = Instant.ofEpochMilli(3_000),
        )
        assertEquals(progressAtStop, engine.state.value.routeProgressFraction, 0.0)
        assertEquals(
            drivingAtStop,
            requireNotNull(engine.state.value.drivingDurationRemainingSeconds),
            0.0,
        )
        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)

        val totalAtArrival = requireNotNull(engine.state.value.totalDurationRemainingSeconds)
        engine.tick(602_000)
        val waiting = engine.state.value
        assertEquals(
            600.0,
            requireNotNull(waiting.activeFuelStopVisit).remainingDwellSeconds,
            0.0,
        )
        assertEquals(NavigationPhase.AT_FUEL_STOP, waiting.phase)
        assertTrue(requireNotNull(waiting.totalDurationRemainingSeconds) < totalAtArrival - 590.0)
        assertEquals(false, requireNotNull(waiting.activeFuelStopVisit).plannedDurationElapsed)
        engine.beginRouteUpdate(RouteUpdateReason.TRAFFIC_REFRESH)
        assertEquals(ReroutingStatus.IDLE, engine.state.value.reroutingStatus)
        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)

        assertTrue(engine.completeFuelStop(nowEpochMillis = 602_000))
        assertEquals(null, engine.state.value.activeFuelStopVisit)
        assertEquals(null, engine.state.value.nextFuelStop)
        assertEquals(
            NavigationFuelStopLifecycle.COMPLETED,
            engine.state.value.fuelStopProgress.single().lifecycle,
        )
        assertEquals("3618", engine.state.value.lastCompletedFuelStop?.mimitStationId)
        assertEquals(
            drivingAtStop,
            requireNotNull(engine.state.value.totalDurationRemainingSeconds),
            0.0,
        )
        assertEquals(false, engine.completeFuelStop(nowEpochMillis = 603_000))
    }

    @Test
    fun refuellingDelayPushesFinalEtaAfterPlannedDwellUntilOperatorConfirms() {
        val base = route()
        val stop = NavigationFuelStop(
            sequence = 1,
            mimitStationId = "late-stop",
            name = "Tappa test",
            municipality = null,
            province = null,
            location = Coordinate(45.0, 9.0020),
            expectedArrivalAt = null,
            dwellTimeSeconds = 60,
        )
        val engine = testEngine()
        engine.preview(
            base.copy(
                fuelStops = listOf(stop),
                totalTripDurationSeconds = base.drivingDurationSeconds + 60.0,
            ),
        )
        engine.start(nowEpochMillis = 1_000)
        engine.updateLocation(
            fix(45.0, 9.0020, 2_000, 90.0),
            now = Instant.ofEpochMilli(2_000),
        )

        engine.tick(62_000)
        val onTimeEta = requireNotNull(engine.state.value.estimatedArrivalAt)
        assertTrue(requireNotNull(engine.state.value.activeFuelStopVisit).plannedDurationElapsed)

        engine.tick(122_000)
        val delayedEta = requireNotNull(engine.state.value.estimatedArrivalAt)
        assertEquals(60L, delayedEta.epochSecond - onTimeEta.epochSecond)
        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)
    }

    @Test
    fun sparseReliableFixesCaptureAStopCrossedBetweenSamples() {
        val base = route()
        val stop = NavigationFuelStop(
            sequence = 1,
            mimitStationId = "crossed-stop",
            name = "Tappa attraversata",
            municipality = null,
            province = null,
            location = Coordinate(45.0, 9.0020),
            expectedArrivalAt = null,
            dwellTimeSeconds = 1_200,
        )
        val engine = testEngine(
            policy = NavigationEnginePolicy(atFuelStopDistanceMeters = 10.0),
        )
        engine.preview(base.copy(fuelStops = listOf(stop)))
        engine.start(nowEpochMillis = 1_000)
        engine.updateLocation(
            fix(45.0, 9.0018, 2_000, 90.0),
            now = Instant.ofEpochMilli(2_000),
        )
        engine.updateLocation(
            fix(45.0, 9.0022, 3_000, 90.0),
            now = Instant.ofEpochMilli(3_000),
        )

        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)
        assertEquals("crossed-stop", engine.state.value.activeFuelStopVisit?.stop?.mimitStationId)
        assertEquals(stop.location, engine.state.value.navigationPosition?.coordinate)
        assertEquals(0.5, engine.state.value.routeProgressFraction, 0.02)
    }

    @Test
    fun completingOneVisitMakesTheFollowingCngStopAuthoritative() {
        val base = route()
        fun stop(sequence: Int, id: String, longitude: Double) = NavigationFuelStop(
            sequence = sequence,
            mimitStationId = id,
            name = id,
            municipality = null,
            province = null,
            location = Coordinate(45.0, longitude),
            expectedArrivalAt = null,
            dwellTimeSeconds = 60,
        )
        val first = stop(1, "first", 9.0010)
        val second = stop(2, "second", 9.0030)
        val engine = testEngine()
        engine.preview(base.copy(fuelStops = listOf(first, second)))
        engine.start(nowEpochMillis = 1_000)
        engine.updateLocation(
            fix(45.0, 9.0010, 2_000, 90.0),
            now = Instant.ofEpochMilli(2_000),
        )

        assertTrue(engine.completeFuelStop(nowEpochMillis = 3_000))

        assertEquals("second", engine.state.value.nextFuelStop?.stop?.mimitStationId)
        assertEquals(
            NavigationFuelStopLifecycle.COMPLETED,
            engine.state.value.fuelStopProgress.first().lifecycle,
        )
        assertEquals(
            NavigationFuelStopLifecycle.APPROACHING,
            engine.state.value.fuelStopProgress.last().lifecycle,
        )
    }

    @Test
    fun restoredRefuellingVisitKeepsProgressFrozenUntilExplicitCompletion() {
        val base = route()
        val stop = NavigationFuelStop(
            sequence = 1,
            mimitStationId = "restored-stop",
            name = "Tappa ripristinata",
            municipality = null,
            province = null,
            location = Coordinate(45.0, 9.0020),
            expectedArrivalAt = null,
            dwellTimeSeconds = 60,
        )
        val routed = base.copy(fuelStops = listOf(stop))
        val visit = NavigationFuelStopVisit(stop, 2_000L, 62_000L, 40.0)
        val engine = testEngine()

        engine.restore(
            route = routed,
            progress = NavigationProgressSnapshot(
                savedAtEpochMillis = 22_000L,
                navigationPosition = NavigationPosition(
                    stop.location, 1, 0.0, 90.0, 4.0, 22_000L,
                ),
                routeProgressFraction = 0.5,
                distanceRemainingMeters = 157.0,
                drivingDurationRemainingSeconds = 15.7,
                totalDurationRemainingSeconds = 55.7,
                estimatedArrivalAtEpochMillis = 77_700L,
                currentRoadName = "Via di prova",
                currentManeuverIndex = 0,
                nextManeuverIndex = 1,
                distanceToNextManeuverMeters = 78.0,
                completedFuelStopSequences = emptySet(),
                activeFuelStopVisit = visit,
                lastCompletedFuelStopSequence = null,
                lastSpokenInstruction = null,
                voiceGuidanceEnabled = true,
                lastSuccessfulRouteRefreshEpochMillis = null,
            ),
            cachedAtEpochMillis = 22_000L,
            nowEpochMillis = 42_000L,
        )

        assertEquals(NavigationPhase.AT_FUEL_STOP, engine.state.value.phase)
        assertEquals(20.0, requireNotNull(engine.state.value.activeFuelStopVisit).remainingDwellSeconds, 0.0)
        assertTrue(engine.completeFuelStop(43_000L))
        assertEquals(NavigationFuelStopLifecycle.COMPLETED, engine.state.value.fuelStopProgress.single().lifecycle)
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

    private fun testEngine(
        policy: NavigationEnginePolicy = NavigationEnginePolicy(),
    ) = NavigationEngine(
        policy = policy,
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
