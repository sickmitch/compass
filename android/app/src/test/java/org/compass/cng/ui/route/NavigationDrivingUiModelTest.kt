package org.compass.cng.ui.route

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.ManeuverSign
import org.compass.cng.domain.model.ManeuverSignElement
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.RouteSpeedLimit
import org.compass.cng.navigation.GpsStatus
import org.compass.cng.navigation.NavigationConnectivity
import org.compass.cng.navigation.NavigationFuelStop
import org.compass.cng.navigation.NavigationFuelStopLifecycle
import org.compass.cng.navigation.NavigationFuelStopProgress
import org.compass.cng.navigation.NavigationFuelStopVisit
import org.compass.cng.navigation.NavigationRoute
import org.compass.cng.navigation.NavigationPosition
import org.compass.cng.navigation.NavigationRouteSource
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.navigation.RouteUpdateFailure
import org.compass.cng.navigation.RouteUpdateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDrivingUiModelTest {
    @Test
    fun exposesGlanceableManeuverTripAndCngInformation() {
        val state = sampleState()

        val ui = state.toDrivingUiModel(ZoneOffset.UTC)

        assertEquals(ManeuverVisualFamily.TURN, ui.maneuverVisual.family)
        assertEquals(ManeuverDirection.RIGHT, ui.maneuverVisual.direction)
        assertEquals("320 m", ui.distanceToManeuver)
        assertEquals("Svolta a destra su Via Roma.", ui.primaryInstruction)
        assertEquals("Via Roma", ui.targetRoad)
        assertEquals("Poi mantieni la sinistra.", ui.followingInstruction)
        assertEquals(ManeuverVisualFamily.KEEP, ui.followingManeuverVisual?.family)
        assertEquals(ManeuverDirection.LEFT, ui.followingManeuverVisual?.direction)
        assertEquals("81,5 km", ui.remainingDistance)
        assertEquals("1 h 40 min", ui.remainingDuration)
        assertEquals("S. ZENONE OVEST", ui.nextCngStop?.name)
        assertEquals("22,5 km", ui.nextCngStop?.distance)
        assertEquals("20:45", ui.nextCngStop?.arrivalTime)
        assertEquals("Aperto all'arrivo", ui.nextCngStop?.availabilityLabel)
        assertEquals("1,599 EUR/kg", ui.nextCngStop?.price)
        assertEquals("In avvicinamento", ui.nextCngStop?.lifecycleLabel)
        assertEquals("20 min", ui.nextCngStop?.dwellDuration)
        assertEquals(1, ui.cngStops.size)
        assertEquals(0.25f, ui.progress)
    }

    @Test
    fun hidesDynamicCngEvidenceForCachedRoutes() {
        val ui = sampleState().copy(routeSource = NavigationRouteSource.CACHE).toDrivingUiModel()

        assertEquals(null, ui.nextCngStop?.availabilityLabel)
        assertEquals(null, ui.nextCngStop?.price)
        assertEquals("+39 02 123456", ui.nextCngStop?.phone)
    }

    @Test
    fun refuellingVisitBecomesTheCurrentIntermediateDestinationWithCountdown() {
        val base = sampleState()
        val stop = requireNotNull(base.nextFuelStop).stop
        val visit = NavigationFuelStopVisit(
            stop = stop,
            arrivedAtEpochMillis = 1_000,
            plannedCompletionAtEpochMillis = 601_000,
            remainingDwellSeconds = 600.0,
        )
        val progress = requireNotNull(base.nextFuelStop).copy(
            distanceRemainingMeters = 0.0,
            lifecycle = NavigationFuelStopLifecycle.REFUELING,
            estimatedArrivalAt = Instant.ofEpochMilli(1_000),
        )

        val ui = base.copy(
            activeFuelStopVisit = visit,
            nextFuelStop = progress,
            fuelStopProgress = listOf(progress),
        ).toDrivingUiModel(ZoneOffset.UTC)

        assertEquals(ManeuverVisualFamily.DESTINATION, ui.maneuverVisual.family)
        assertEquals("Sosta CNG", ui.distanceToManeuver)
        assertEquals("Rifornimento in corso", ui.primaryInstruction)
        assertEquals("S. ZENONE OVEST", ui.targetRoad)
        assertEquals("Conferma quando hai terminato", ui.followingInstruction)
        assertEquals("10 min", ui.nextCngStop?.refuelingRemainingDuration)
        assertEquals(false, ui.nextCngStop?.refuelingPlannedDurationElapsed)
    }

    @Test
    fun hidesStalePriceAndOpeningEvaluatedForADifferentArrival() {
        val state = sampleState()
        val route = requireNotNull(state.route)
        val originalStop = route.fuelStops.single()
        val changedStop = originalStop.copy(
            opening = requireNotNull(originalStop.opening).copy(
                evaluatedAt = originalStop.expectedArrivalAt!!.minusHours(2),
            ),
            price = requireNotNull(originalStop.price).copy(freshness = PriceFreshness.STALE),
        )
        val progress = requireNotNull(state.nextFuelStop).copy(stop = changedStop)

        val ui = state.copy(
            route = route.copy(fuelStops = listOf(changedStop)),
            nextFuelStop = progress,
            fuelStopProgress = listOf(progress),
        ).toDrivingUiModel()

        assertEquals(null, ui.nextCngStop?.availabilityLabel)
        assertEquals(null, ui.nextCngStop?.price)
    }

    @Test
    fun hidesNominallyFreshPriceWhenItsEvaluationBelongsToAnotherEta() {
        val state = sampleState()
        val route = requireNotNull(state.route)
        val originalStop = route.fuelStops.single()
        val changedStop = originalStop.copy(
            price = requireNotNull(originalStop.price).copy(ageSeconds = 0.0),
        )
        val progress = requireNotNull(state.nextFuelStop).copy(stop = changedStop)

        val ui = state.copy(
            route = route.copy(fuelStops = listOf(changedStop)),
            nextFuelStop = progress,
            fuelStopProgress = listOf(progress),
        ).toDrivingUiModel()

        assertEquals(null, ui.nextCngStop?.price)
    }

    @Test
    fun exposesStructuredJunctionSignsAndRoundaboutExitWithoutParsingInstruction() {
        val state = sampleState().let { sample ->
            val signed = requireNotNull(sample.currentManeuver).copy(
                instruction = "Testo localizzato senza dati da analizzare.",
                type = 26,
                sign = ManeuverSign(
                    exitNumberElements = listOf(ManeuverSignElement("2")),
                    exitBranchElements = listOf(
                        ManeuverSignElement("A1"),
                        ManeuverSignElement("E 35"),
                    ),
                    exitTowardElements = listOf(ManeuverSignElement("Bologna")),
                    exitNameElements = listOf(ManeuverSignElement("Casalecchio")),
                ),
                roundaboutExitCount = 2,
            )
            sample.copy(currentManeuver = signed)
        }

        val ui = state.toDrivingUiModel()

        assertEquals(2, ui.roundaboutExitCount)
        assertEquals("2", ui.junctionSign?.exitNumber)
        assertEquals("A1 / E 35", ui.junctionSign?.branches)
        assertEquals("Bologna", ui.junctionSign?.toward)
        assertEquals("Casalecchio", ui.junctionSign?.exitName)
    }

    @Test
    fun omitsManeuverRoadWhenTheJunctionSignAlreadyShowsIt() {
        val road = "Strada Statale 434 Transpolesana"
        val maneuver = requireNotNull(sampleState().currentManeuver).copy(
            type = 20,
            instruction = "Prendi l'uscita $road.",
            streetNames = listOf(road),
            sign = ManeuverSign(
                exitBranchElements = listOf(ManeuverSignElement(road)),
            ),
        )
        val state = sampleState().copy(
            currentManeuver = maneuver,
            currentRoadName = road,
        )

        val ui = state.toDrivingUiModel()

        assertEquals(null, ui.targetRoad)
        assertEquals(road, ui.junctionSign?.branches)
    }

    @Test
    fun retainsManeuverRoadWhenTheJunctionSignAddsDifferentInformation() {
        val maneuver = requireNotNull(sampleState().currentManeuver).copy(
            type = 20,
            sign = ManeuverSign(
                exitBranchElements = listOf(ManeuverSignElement("A14")),
                exitTowardElements = listOf(ManeuverSignElement("Bologna")),
            ),
        )
        val state = sampleState().copy(
            currentManeuver = maneuver,
            currentRoadName = "Autostrada del Sole",
        )

        assertEquals("Autostrada del Sole", state.toDrivingUiModel().targetRoad)
    }

    @Test
    fun exposesGraphSpeedLimitOnlyForTheCurrentlyMatchedShapeSegment() {
        val initial = sampleState()
        val route = requireNotNull(initial.route).copy(
            speedLimits = listOf(RouteSpeedLimit(0, 1, 50)),
            speedLimitSource = "valhalla_graph",
        )
        val matched = initial.copy(
            route = route,
            navigationPosition = NavigationPosition(
                coordinate = route.origin,
                routeSegmentIndex = 0,
                speedMetersPerSecond = 12.0,
                bearingDegrees = 90.0,
                horizontalAccuracyMeters = 4.0,
                timestampEpochMillis = 1_725_000_000_000,
            ),
        )

        assertEquals(50, matched.toDrivingUiModel().currentSpeedLimitKph)
        assertEquals(null, initial.toDrivingUiModel().currentSpeedLimitKph)
        assertEquals(
            null,
            matched.copy(
                navigationPosition = requireNotNull(matched.navigationPosition).copy(
                    routeSegmentIndex = 1,
                ),
            ).toDrivingUiModel().currentSpeedLimitKph,
        )
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
    fun exposesRouteRecalculationAsPrimaryDrivingFeedback() {
        val state = sampleState().copy(
            reroutingStatus = ReroutingStatus.IN_PROGRESS,
            routeUpdateReason = RouteUpdateReason.OFF_ROUTE,
        )

        val ui = state.toDrivingUiModel()

        assertTrue(ui.isRouteRecalculationInProgress)
        assertTrue(ui.statusMessages.any { "Aggiornamento del percorso" in it.text })
        assertEquals(
            false,
            sampleState().toDrivingUiModel().isRouteRecalculationInProgress,
        )
    }

    @Test
    fun exposesFreshTrafficDelayWithoutCallingGraphSpeedsLiveTraffic() {
        val observedAt = OffsetDateTime.of(2026, 9, 5, 7, 52, 0, 0, ZoneOffset.UTC)
        val state = sampleState().let { sample ->
            sample.copy(
                route = sample.route?.copy(
                    timing = requireNotNull(sample.route).timing.copy(
                        trafficDelaySeconds = 480.0,
                        trafficDelayState = "estimated",
                        trafficState = "fresh",
                        trafficAware = true,
                        trafficObservedAt = observedAt,
                    ),
                ),
            )
        }

        val message = state.toDrivingUiModel().statusMessages.single {
            "Traffico live incluso" in it.text
        }

        assertEquals(NavigationStatusLevel.POSITIVE, message.level)
        assertTrue("ritardo 8 min" in message.text)
        assertTrue("aggiornato" in message.text)
    }

    @Test
    fun convertsBackendUtcStopAndTrafficInstantsToTheDeviceZone() {
        val state = sampleState().let { sample ->
            sample.copy(
                route = requireNotNull(sample.route).copy(
                    timing = sample.route.timing.copy(
                        trafficDelaySeconds = 0.0,
                        trafficDelayState = "estimated",
                        trafficState = "fresh",
                        trafficAware = true,
                        trafficObservedAt = OffsetDateTime.parse("2026-09-03T06:10:00Z"),
                    ),
                ),
            )
        }

        val ui = state.toDrivingUiModel(ZoneId.of("Europe/Rome"))

        assertEquals("22:45", ui.nextCngStop?.arrivalTime)
        assertEquals("22:45", ui.arrivalTime)
        assertTrue(ui.statusMessages.any { "aggiornato 08:10" in it.text })
    }

    @Test
    fun reportsGraphSpeedFallbackEvenWhenTrafficFeedIsFresh() {
        val state = sampleState().let { sample ->
            sample.copy(
                route = sample.route?.copy(
                    timing = requireNotNull(sample.route).timing.copy(trafficState = "fresh"),
                ),
            )
        }

        assertTrue(
            state.toDrivingUiModel().statusMessages.any {
                it.text == "Traffico aggiornato, ma questa rotta usa velocità standard."
            },
        )
    }

    @Test
    fun mapsEveryPublishedValhallaTypeToAnExplicitPhaseSixVisual() {
        assertEquals((0..36).toList(), valhallaManeuverVisualCatalog.map { it.type })
        assertTrue(
            valhallaManeuverVisualCatalog.none {
                it.family == ManeuverVisualFamily.UNKNOWN || it.accessibilityLabel.isBlank()
            },
        )
    }

    @Test
    fun keepsTurnAnglesSidesAndRoadJunctionFamiliesDistinct() {
        val expected = mapOf(
            9 to (ManeuverVisualFamily.TURN to ManeuverDirection.SLIGHT_RIGHT),
            10 to (ManeuverVisualFamily.TURN to ManeuverDirection.RIGHT),
            11 to (ManeuverVisualFamily.TURN to ManeuverDirection.SHARP_RIGHT),
            12 to (ManeuverVisualFamily.U_TURN to ManeuverDirection.U_TURN_RIGHT),
            13 to (ManeuverVisualFamily.U_TURN to ManeuverDirection.U_TURN_LEFT),
            14 to (ManeuverVisualFamily.TURN to ManeuverDirection.SHARP_LEFT),
            15 to (ManeuverVisualFamily.TURN to ManeuverDirection.LEFT),
            16 to (ManeuverVisualFamily.TURN to ManeuverDirection.SLIGHT_LEFT),
            17 to (ManeuverVisualFamily.RAMP to ManeuverDirection.STRAIGHT),
            18 to (ManeuverVisualFamily.RAMP to ManeuverDirection.RIGHT),
            19 to (ManeuverVisualFamily.RAMP to ManeuverDirection.LEFT),
            20 to (ManeuverVisualFamily.EXIT to ManeuverDirection.RIGHT),
            21 to (ManeuverVisualFamily.EXIT to ManeuverDirection.LEFT),
            22 to (ManeuverVisualFamily.KEEP to ManeuverDirection.STRAIGHT),
            23 to (ManeuverVisualFamily.KEEP to ManeuverDirection.RIGHT),
            24 to (ManeuverVisualFamily.KEEP to ManeuverDirection.LEFT),
            25 to (ManeuverVisualFamily.MERGE to ManeuverDirection.STRAIGHT),
            26 to (ManeuverVisualFamily.ROUNDABOUT_ENTER to ManeuverDirection.NONE),
            27 to (ManeuverVisualFamily.ROUNDABOUT_EXIT to ManeuverDirection.NONE),
        )

        expected.forEach { (type, expectedVisual) ->
            val visual = maneuverVisual(type, null)
            assertEquals("family for type $type", expectedVisual.first, visual.family)
            assertEquals("direction for type $type", expectedVisual.second, visual.direction)
        }
    }

    @Test
    fun distinguishesDestinationFerryTransitAndConnectionFamilies() {
        assertEquals(ManeuverVisualFamily.DESTINATION, maneuverVisual(4, null).family)
        assertEquals(ManeuverVisualFamily.FERRY_ENTER, maneuverVisual(28, null).family)
        assertEquals(ManeuverVisualFamily.FERRY_EXIT, maneuverVisual(29, null).family)
        assertEquals(ManeuverVisualFamily.TRANSIT, maneuverVisual(30, null).family)
        assertEquals(ManeuverVisualFamily.TRANSIT_TRANSFER, maneuverVisual(31, null).family)
        assertEquals(ManeuverVisualFamily.TRANSIT_REMAIN, maneuverVisual(32, null).family)
        assertEquals(
            ManeuverVisualFamily.TRANSIT_CONNECTION_START,
            maneuverVisual(33, null).family,
        )
        assertEquals(
            ManeuverVisualFamily.TRANSIT_CONNECTION_TRANSFER,
            maneuverVisual(34, null).family,
        )
        assertEquals(
            ManeuverVisualFamily.TRANSIT_CONNECTION_DESTINATION,
            maneuverVisual(35, null).family,
        )
        assertEquals(
            ManeuverVisualFamily.POST_TRANSIT_CONNECTION_DESTINATION,
            maneuverVisual(36, null).family,
        )
    }

    @Test
    fun usesInstructionOnlyAsFallbackForUnknownFutureTypes() {
        val futureRight = maneuverVisual(99, "Svolta a destra sulla strada locale.")
        val absent = maneuverVisual(null, null)

        assertEquals(ManeuverVisualFamily.TURN, futureRight.family)
        assertEquals(ManeuverDirection.RIGHT, futureRight.direction)
        assertEquals(ManeuverVisualFamily.UNKNOWN, absent.family)
    }

    @Test
    fun branchingManeuversKeepTheSelectedLaneOnItsRequestedSide() {
        val right = branchingManeuverGeometry(ManeuverDirection.RIGHT, selectedReach = 0.31f)
        val left = branchingManeuverGeometry(ManeuverDirection.LEFT, selectedReach = 0.31f)

        assertTrue(right.trunkX > 0.50f)
        assertTrue(right.selectedTipX > right.junctionX)
        assertTrue(right.alternativeTipX < right.junctionX)
        assertTrue(left.trunkX < 0.50f)
        assertTrue(left.selectedTipX < left.junctionX)
        assertTrue(left.alternativeTipX > left.junctionX)
        assertEquals(1.0f, right.trunkX + left.trunkX, 0.0001f)
        assertEquals(1.0f, right.selectedTipX + left.selectedTipX, 0.0001f)
        assertEquals(1.0f, right.alternativeTipX + left.alternativeTipX, 0.0001f)
        assertTrue(right.selectedTerminalLength > 0.20f)
        assertTrue(left.selectedTerminalLength > 0.20f)

        val rightExit = branchingManeuverGeometry(
            ManeuverDirection.RIGHT,
            selectedReach = 0.34f,
            alternativeStraight = true,
        )
        assertEquals(0.50f, rightExit.alternativeTipX, 0.0001f)
        assertTrue(rightExit.selectedPreviousX > rightExit.junctionX)
        assertTrue(rightExit.selectedTerminalLength > 0.20f)
    }

    @Test
    fun slightTurnsShareACentredTrunkAndMeetTheirArrowheadOnAMirroredTangent() {
        val right = slightTurnGeometry(ManeuverDirection.SLIGHT_RIGHT)
        val left = slightTurnGeometry(ManeuverDirection.SLIGHT_LEFT)

        assertEquals(0.50f, right.startX, 0.0001f)
        assertEquals(right.startX, left.startX, 0.0001f)
        assertTrue(right.curveExitX > right.startX)
        assertTrue(right.tipX > right.curveExitX)
        assertTrue(left.curveExitX < left.startX)
        assertTrue(left.tipX < left.curveExitX)
        assertEquals(1.0f, right.curveExitX + left.curveExitX, 0.0001f)
        assertEquals(1.0f, right.tipX + left.tipX, 0.0001f)
        assertTrue(right.terminalLength > 0.20f)
        assertEquals(right.terminalLength, left.terminalLength, 0.0001f)
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
            opening = OpeningAtEta(
                state = OpeningState.OPEN,
                validation = OpeningValidation.VALID,
                openingHours = "24/7",
                source = "osm",
                sourceConfidence = 0.98,
                evaluatedAt = OffsetDateTime.of(
                    2026, 9, 3, 20, 45, 0, 0, ZoneOffset.UTC,
                ),
                timezone = "Europe/Rome",
                nextChangeAt = null,
                warnings = emptyList(),
            ),
            phone = "+39 02 123456",
            price = CngPrice(
                unitPrice = 1.599,
                currency = "EUR",
                unit = "kg",
                serviceMode = "self",
                observedAt = OffsetDateTime.of(
                    2026, 9, 3, 8, 0, 0, 0, ZoneOffset.UTC,
                ),
                ingestedAt = OffsetDateTime.of(
                    2026, 9, 3, 8, 5, 0, 0, ZoneOffset.UTC,
                ),
                sourceName = "mimit",
                ageSeconds = 45_900.0,
                freshness = PriceFreshness.FRESH,
            ),
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
            nextFuelStop = NavigationFuelStopProgress(
                stop,
                22_500.0,
                NavigationFuelStopLifecycle.APPROACHING,
            ),
            fuelStopProgress = listOf(
                NavigationFuelStopProgress(
                    stop,
                    22_500.0,
                    NavigationFuelStopLifecycle.APPROACHING,
                ),
            ),
        )
    }
}
