package org.compass.cng.ui.route

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.CngItineraryRouteLeg
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.PredictiveCngStation
import org.compass.cng.domain.model.PredictiveCngItinerary
import org.compass.cng.domain.model.PredictiveDestinationLeg
import org.compass.cng.domain.model.PredictiveItineraryStop
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveRangeBasis
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RankingBreakdown
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary
import org.compass.cng.domain.model.SelectedCngStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlannerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesLoadedBaseRouteAndOpensCngConfiguration() = runTest {
        val route = sampleRoute()
        val viewModel = RoutePlannerViewModel(FakeRoutingRepository(baseResult = Result.success(route)))

        assertEquals(route, viewModel.uiState.value.baseRoute)
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.operation)

        viewModel.openAddStop()

        assertEquals(PlannerStage.CONFIGURE_CNG, viewModel.uiState.value.stage)
        assertEquals("300", viewModel.uiState.value.effectiveRangeKmInput)
        assertEquals("10", viewModel.uiState.value.maximumDetourMinutesInput)
    }

    @Test
    fun mapsNetworkFailureToStableBaseRouteMessage() = runTest {
        val viewModel = RoutePlannerViewModel(
            FakeRoutingRepository(
                baseResult = Result.failure(RoutePreviewException(RoutePreviewFailure.NETWORK)),
            ),
        )

        assertEquals("Impossibile contattare il server Compass.", viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.baseRoute)
    }

    @Test
    fun validatesCngSearchInputsWithoutCallingRepository() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openAddStop()
        viewModel.updateMaximumDetour("999")

        viewModel.searchCngStations()

        assertEquals(0, repository.candidateCalls)
        assertEquals(
            "Inserisci un tempo massimo di deviazione tra 0 e 240 minuti.",
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun predictiveConfigurationRequiresCallerEstimatedRemainingRange() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(repository)

        viewModel.openPredictiveRange()

        assertEquals(PlannerStage.CONFIGURE_PREDICTIVE, viewModel.uiState.value.stage)
        assertEquals("", viewModel.uiState.value.estimatedRemainingRangeKmInput)
        assertEquals("30", viewModel.uiState.value.reserveRangeKmInput)

        viewModel.evaluatePredictiveRange()

        assertEquals(0, repository.predictiveCalls)
        assertEquals(
            "Inserisci l'autonomia CNG residua stimata, maggiore di 0 km.",
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun exposesOnlyReachablePredictiveSuggestionsAndRangeBasis() = runTest {
        val suggestion = samplePredictiveSuggestion(PredictiveSuggestionState.SUGGESTED)
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            predictiveResult = Result.success(suggestion),
        )
        val clock = Clock.fixed(Instant.parse("2026-08-30T08:00:00Z"), ZoneOffset.ofHours(2))
        val viewModel = RoutePlannerViewModel(repository, clock = clock)
        viewModel.openPredictiveRange()
        viewModel.updateEffectiveRange("300")
        viewModel.updateEstimatedRemainingRange("120,5")
        viewModel.updateReserveRange("30")
        viewModel.updateMaximumDetour("10")

        viewModel.evaluatePredictiveRange()

        assertEquals(1, repository.predictiveCalls)
        assertEquals(120.5, repository.lastRemainingRangeKm, 0.0)
        assertEquals(30.0, repository.lastReserveRangeKm, 0.0)
        assertEquals("2026-08-30T10:00+02:00", repository.lastDepartureAt.toString())
        assertEquals(PlannerStage.PREDICTIVE_ITINERARY, viewModel.uiState.value.stage)
        assertEquals(CngWorkflowMode.PREDICTIVE, viewModel.uiState.value.workflowMode)
        assertSame(suggestion, viewModel.uiState.value.predictiveSuggestion)
        assertNull(viewModel.uiState.value.rankedStations)
        assertEquals("43690", viewModel.uiState.value.predictiveSuggestion?.itinerary?.stops?.single()?.station?.mimitStationId)

        viewModel.navigateBack()
        assertEquals(PlannerStage.CONFIGURE_PREDICTIVE, viewModel.uiState.value.stage)
    }

    @Test
    fun calculatesCompleteThreeStopRouteForTheSixtyFiveKilometerEdgeCase() = runTest {
        val suggestion = sampleMultiStopPredictiveSuggestion()
        val routed = sampleMultiStopSelectedRoute()
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            predictiveResult = Result.success(suggestion),
            selectedItineraryResult = Result.success(routed),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openPredictiveRange()
        viewModel.updateEffectiveRange("100")
        viewModel.updateEstimatedRemainingRange("65")
        viewModel.updateReserveRange("30")

        viewModel.evaluatePredictiveRange()
        assertEquals(PlannerStage.PREDICTIVE_ITINERARY, viewModel.uiState.value.stage)
        assertEquals(
            listOf("43690", "3473", "3618"),
            viewModel.uiState.value.predictiveSuggestion
                ?.itinerary
                ?.stops
                ?.map { it.station.mimitStationId },
        )

        viewModel.acceptPredictiveItinerary()

        assertEquals(listOf("43690", "3473", "3618"), repository.lastItineraryStationIds)
        assertEquals(100.0, repository.lastItineraryEffectiveRangeKm, 0.0)
        assertEquals(65.0, repository.lastRemainingRangeKm, 0.0)
        assertEquals(30.0, repository.lastReserveRangeKm, 0.0)
        assertEquals(PlannerStage.SELECTED_ROUTE, viewModel.uiState.value.stage)
        assertSame(routed, viewModel.uiState.value.selectedItineraryRoute)
        assertNull(viewModel.uiState.value.selectedRoute)

        viewModel.navigateBack()
        assertEquals(PlannerStage.PREDICTIVE_ITINERARY, viewModel.uiState.value.stage)
    }

    @Test
    fun destinationReachableProducesExplicitNoSuggestionStatus() = runTest {
        val suggestion = samplePredictiveSuggestion(PredictiveSuggestionState.NOT_NEEDED)
        val viewModel = RoutePlannerViewModel(
            FakeRoutingRepository(
                baseResult = Result.success(sampleRoute()),
                predictiveResult = Result.success(suggestion),
            ),
        )
        viewModel.openPredictiveRange()
        viewModel.updateEstimatedRemainingRange("300")

        viewModel.evaluatePredictiveRange()

        assertEquals(PlannerStage.PREDICTIVE_STATUS, viewModel.uiState.value.stage)
        assertEquals(PredictiveSuggestionState.NOT_NEEDED, viewModel.uiState.value.predictiveSuggestion?.state)
        assertNull(viewModel.uiState.value.rankedStations)
    }

    @Test
    fun noReachableStationProducesSafetyStatusInsteadOfCandidates() = runTest {
        val suggestion = samplePredictiveSuggestion(PredictiveSuggestionState.NO_REACHABLE_STATION)
        val viewModel = RoutePlannerViewModel(
            FakeRoutingRepository(
                baseResult = Result.success(sampleRoute()),
                predictiveResult = Result.success(suggestion),
            ),
        )
        viewModel.openPredictiveRange()
        viewModel.updateEstimatedRemainingRange("40")

        viewModel.evaluatePredictiveRange()

        assertEquals(PlannerStage.PREDICTIVE_STATUS, viewModel.uiState.value.stage)
        assertEquals(
            PredictiveSuggestionState.NO_REACHABLE_STATION,
            viewModel.uiState.value.predictiveSuggestion?.state,
        )
        assertNull(viewModel.uiState.value.rankedStations)
    }

    @Test
    fun searchesRankedStationsWithDeviceDepartureOffset() = runTest {
        val ranked = sampleRankedStations()
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            rankedResult = Result.success(ranked),
        )
        val clock = Clock.fixed(Instant.parse("2026-08-30T08:00:00Z"), ZoneOffset.ofHours(2))
        val viewModel = RoutePlannerViewModel(repository, clock = clock)
        viewModel.openAddStop()
        viewModel.updateEffectiveRange("350,5")
        viewModel.updateMaximumDetour("12.5")

        viewModel.searchCngStations()

        assertEquals(PlannerStage.CNG_CANDIDATES, viewModel.uiState.value.stage)
        assertSame(ranked, viewModel.uiState.value.rankedStations)
        assertEquals(350.5, repository.lastRangeKm, 0.0)
        assertEquals(12.5, repository.lastDetourMinutes, 0.0)
        assertEquals("2026-08-30T10:00+02:00", repository.lastDepartureAt.toString())
    }

    @Test
    fun selectsOfficialStationIdAndExposesTwoLegRoute() = runTest {
        val ranked = sampleRankedStations()
        val selectedRoute = sampleSelectedRoute()
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            rankedResult = Result.success(ranked),
            selectedRouteResult = Result.success(selectedRoute),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openAddStop()
        viewModel.searchCngStations()

        viewModel.selectStation(ranked.candidates.single())

        assertEquals("43690", repository.lastSelectedStationId)
        assertEquals(PlannerStage.SELECTED_ROUTE, viewModel.uiState.value.stage)
        assertSame(selectedRoute, viewModel.uiState.value.selectedRoute)
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun selectedStationFailureKeepsCandidateResultsRetryable() = runTest {
        val ranked = sampleRankedStations()
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            rankedResult = Result.success(ranked),
            selectedRouteResult = Result.failure(
                RoutePreviewException(RoutePreviewFailure.STATION_UNAVAILABLE),
            ),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openAddStop()
        viewModel.searchCngStations()

        viewModel.selectStation(ranked.candidates.single())

        assertEquals(PlannerStage.CNG_CANDIDATES, viewModel.uiState.value.stage)
        assertEquals("La stazione selezionata non è raggiungibile.", viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.selectedRoute)
        assertNull(viewModel.uiState.value.pendingStation)
    }

    @Test
    fun backAndRemoveStopPreserveExplicitPlannerStages() = runTest {
        val ranked = sampleRankedStations()
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            rankedResult = Result.success(ranked),
            selectedRouteResult = Result.success(sampleSelectedRoute()),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openAddStop()
        viewModel.searchCngStations()
        viewModel.selectStation(ranked.candidates.single())

        viewModel.navigateBack()
        assertEquals(PlannerStage.CNG_CANDIDATES, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.selectedRoute)
        assertTrue(viewModel.uiState.value.rankedStations?.candidates?.isNotEmpty() == true)

        viewModel.removeCngStop()
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.rankedStations)
    }

    private class FakeRoutingRepository(
        private val baseResult: Result<RoutePreview>,
        private val rankedResult: Result<RankedCngStations> = Result.failure(
            AssertionError("rankedCngStations was not expected"),
        ),
        private val selectedRouteResult: Result<RouteWithCngStop> = Result.failure(
            AssertionError("routeWithCngStop was not expected"),
        ),
        private val predictiveResult: Result<PredictiveCngSuggestion> = Result.failure(
            AssertionError("predictiveCngStations was not expected"),
        ),
        private val selectedItineraryResult: Result<RouteWithCngItinerary> = Result.failure(
            AssertionError("routeWithCngItinerary was not expected"),
        ),
    ) : RoutingRepository {
        var candidateCalls = 0
        var predictiveCalls = 0
        var lastRangeKm = 0.0
        var lastRemainingRangeKm = 0.0
        var lastReserveRangeKm = 0.0
        var lastDetourMinutes = 0.0
        lateinit var lastDepartureAt: OffsetDateTime
        var lastSelectedStationId: String? = null
        var lastItineraryStationIds: List<String>? = null
        var lastItineraryEffectiveRangeKm = 0.0

        override suspend fun previewRoute(
            origin: Coordinate,
            destination: Coordinate,
        ): RoutePreview = baseResult.getOrThrow()

        override suspend fun rankedCngStations(
            origin: Coordinate,
            destination: Coordinate,
            effectiveCngRangeKm: Double,
            maximumDetourMinutes: Double,
            departureAt: OffsetDateTime,
        ): RankedCngStations {
            candidateCalls += 1
            lastRangeKm = effectiveCngRangeKm
            lastDetourMinutes = maximumDetourMinutes
            lastDepartureAt = departureAt
            return rankedResult.getOrThrow()
        }

        override suspend fun routeWithCngStop(
            origin: Coordinate,
            destination: Coordinate,
            mimitStationId: String,
        ): RouteWithCngStop {
            lastSelectedStationId = mimitStationId
            return selectedRouteResult.getOrThrow()
        }

        override suspend fun predictiveCngStations(
            origin: Coordinate,
            destination: Coordinate,
            effectiveCngRangeKm: Double,
            estimatedRemainingCngRangeKm: Double,
            reserveCngRangeKm: Double,
            maximumDetourMinutes: Double,
            departureAt: OffsetDateTime,
        ): PredictiveCngSuggestion {
            predictiveCalls += 1
            lastRangeKm = effectiveCngRangeKm
            lastRemainingRangeKm = estimatedRemainingCngRangeKm
            lastReserveRangeKm = reserveCngRangeKm
            lastDetourMinutes = maximumDetourMinutes
            lastDepartureAt = departureAt
            return predictiveResult.getOrThrow()
        }

        override suspend fun routeWithCngItinerary(
            origin: Coordinate,
            destination: Coordinate,
            mimitStationIds: List<String>,
            effectiveCngRangeKm: Double,
            estimatedRemainingCngRangeKm: Double,
            reserveCngRangeKm: Double,
        ): RouteWithCngItinerary {
            lastItineraryStationIds = mimitStationIds
            lastItineraryEffectiveRangeKm = effectiveCngRangeKm
            lastRemainingRangeKm = estimatedRemainingCngRangeKm
            lastReserveRangeKm = reserveCngRangeKm
            return selectedItineraryResult.getOrThrow()
        }
    }

    private fun sampleRoute(
        origin: Coordinate = RoutePlannerViewModel.MILAN,
        destination: Coordinate = RoutePlannerViewModel.BOLOGNA,
        distanceMeters: Double = 210_925.0,
        durationSeconds: Double = 6_773.406,
    ) = RoutePreview(
        origin = origin,
        destination = destination,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        geometry = listOf(origin, destination),
        maneuvers = listOf(sampleManeuver()),
        provider = "valhalla",
    )

    private fun sampleRankedStations(): RankedCngStations {
        val eta = OffsetDateTime.parse("2026-08-30T10:19:11+02:00")
        return RankedCngStations(
            departureAt = OffsetDateTime.parse("2026-08-30T10:00:00+02:00"),
            maximumDetourMinutes = 10.0,
            baseRoute = sampleRoute(),
            trafficState = "not_configured",
            candidates = listOf(
                RankedCngStation(
                    stationId = 716,
                    mimitStationId = "43690",
                    name = "S.ZENONE OVEST",
                    municipality = "SAN ZENONE AL LAMBRO",
                    province = "MI",
                    location = Coordinate(45.321004, 9.376063),
                    distanceFromPreviousWaypointMeters = 23_106.0,
                    detourMinutes = 1.08,
                    stationEta = eta,
                    destinationEta = OffsetDateTime.parse("2026-08-30T11:53:58+02:00"),
                    opening = OpeningAtEta(
                        state = OpeningState.OPEN,
                        validation = OpeningValidation.VALID,
                        openingHours = "24/7",
                        source = "osm",
                        sourceConfidence = 0.95,
                        evaluatedAt = eta,
                        timezone = "Europe/Rome",
                        nextChangeAt = null,
                        warnings = emptyList(),
                    ),
                    phone = "+39 02 1234567",
                    brand = "Enilive",
                    operator = null,
                    osmMatchConfidence = 0.95,
                    price = CngPrice(
                        unitPrice = 1.599,
                        currency = "EUR",
                        unit = "kg",
                        serviceMode = "served",
                        observedAt = OffsetDateTime.parse("2026-08-29T04:00:00Z"),
                        ingestedAt = OffsetDateTime.parse("2026-08-29T06:00:00Z"),
                        sourceName = "mimit",
                        ageSeconds = 100_000.0,
                        freshness = PriceFreshness.FRESH,
                    ),
                    ranking = RankingBreakdown(
                        rank = 1,
                        totalScore = 0.91,
                        detourScore = 0.89,
                        openingScore = 1.0,
                        priceScore = 0.8,
                        priceFreshnessScore = 0.9,
                    ),
                ),
            ),
        )
    }

    private fun sampleSelectedRoute(): RouteWithCngStop {
        val stop = Coordinate(45.321004, 9.376063)
        return RouteWithCngStop(
            selectedStop = SelectedCngStop(
                mimitStationId = "43690",
                name = "S.ZENONE OVEST",
                municipality = "SAN ZENONE AL LAMBRO",
                province = "MI",
                location = stop,
            ),
            distanceMeters = 210_930.0,
            durationSeconds = 6_839.0,
            legs = listOf(
                CngRouteLeg(
                    kind = CngRouteLegKind.ORIGIN_TO_CNG_STATION,
                    route = sampleRoute(
                        RoutePlannerViewModel.MILAN,
                        stop,
                        distanceMeters = 23_106.0,
                        durationSeconds = 1_151.0,
                    ),
                ),
                CngRouteLeg(
                    kind = CngRouteLegKind.CNG_STATION_TO_DESTINATION,
                    route = sampleRoute(
                        stop,
                        RoutePlannerViewModel.BOLOGNA,
                        distanceMeters = 187_824.0,
                        durationSeconds = 5_688.0,
                    ),
                ),
            ),
            provider = "valhalla",
        )
    }

    private fun samplePredictiveSuggestion(
        state: PredictiveSuggestionState,
    ): PredictiveCngSuggestion {
        val isNotNeeded = state == PredictiveSuggestionState.NOT_NEEDED
        val ranked = sampleRankedStations()
        val candidates = if (state == PredictiveSuggestionState.SUGGESTED) {
            listOf(
                PredictiveCngStation(
                    station = ranked.candidates.single(),
                    estimatedRemainingRangeAtArrivalKm = 96.9,
                    reserveMarginAtArrivalKm = 66.9,
                ),
            )
        } else {
            emptyList()
        }
        val itinerary = if (state == PredictiveSuggestionState.SUGGESTED) {
            PredictiveCngItinerary(
                stops = listOf(
                    samplePredictiveStop(
                        sequence = 1,
                        station = SelectedCngStop(
                            mimitStationId = "43690",
                            name = "S.ZENONE OVEST",
                            municipality = "SAN ZENONE AL LAMBRO",
                            province = "MI",
                            location = ranked.candidates.single().location,
                        ),
                        legDistanceMeters = 23_106.0,
                        legDurationSeconds = 1_151.0,
                        availableRangeKm = 120.0,
                        remainingRangeKm = 96.894,
                        reserveMarginKm = 66.894,
                    ),
                ),
                destinationLeg = PredictiveDestinationLeg(
                    distanceMeters = 187_824.0,
                    durationSeconds = 5_688.0,
                    availableRangeAtDepartureKm = 300.0,
                    estimatedRemainingRangeAtArrivalKm = 112.176,
                    reserveMarginAtArrivalKm = 82.176,
                    destinationEta = OffsetDateTime.parse("2026-08-30T11:53:59+02:00"),
                ),
                totalDistanceMeters = 210_930.0,
                totalDurationSeconds = 6_839.0,
                refuelAssumption = "full_effective_range_after_each_stop",
                distanceModel = "road_network",
            )
        } else {
            null
        }
        return PredictiveCngSuggestion(
            state = state,
            departureAt = ranked.departureAt,
            maximumDetourMinutes = ranked.maximumDetourMinutes,
            baseRoute = ranked.baseRoute,
            rangeBasis = PredictiveRangeBasis(
                effectiveCngRangeKm = 300.0,
                estimatedRemainingCngRangeKm = if (isNotNeeded) 300.0 else 120.0,
                reserveCngRangeKm = 30.0,
                usableRangeBeforeReserveKm = if (isNotNeeded) 270.0 else 90.0,
                remainingRouteDistanceKm = 210.925,
                rangeShortfallToDestinationKm = if (isNotNeeded) 0.0 else 120.925,
                destinationReachableWithReserve = isNotNeeded,
                consumptionModel = "caller_estimated_remaining_range",
                trafficState = "not_configured",
                trafficAdjusted = false,
            ),
            candidates = candidates,
            itinerary = itinerary,
        )
    }

    private fun sampleMultiStopPredictiveSuggestion(): PredictiveCngSuggestion {
        val firstRanked = sampleRankedStations().candidates.single().copy(
            distanceFromPreviousWaypointMeters = 20_000.0,
        )
        val stops = listOf(
            samplePredictiveStop(
                sequence = 1,
                station = SelectedCngStop(
                    "43690",
                    "S.ZENONE OVEST",
                    "SAN ZENONE AL LAMBRO",
                    "MI",
                    Coordinate(45.321004, 9.376063),
                ),
                legDistanceMeters = 20_000.0,
                legDurationSeconds = 600.0,
                availableRangeKm = 65.0,
                remainingRangeKm = 45.0,
                reserveMarginKm = 15.0,
            ),
            samplePredictiveStop(
                sequence = 2,
                station = SelectedCngStop(
                    "3473",
                    "SOMAGLIA OVEST",
                    "SOMAGLIA",
                    "LO",
                    Coordinate(45.14197, 9.634009),
                ),
                legDistanceMeters = 60_000.0,
                legDurationSeconds = 1_800.0,
                availableRangeKm = 100.0,
                remainingRangeKm = 40.0,
                reserveMarginKm = 10.0,
            ),
            samplePredictiveStop(
                sequence = 3,
                station = SelectedCngStop(
                    "3618",
                    "S.MARTINO OVEST",
                    "PARMA",
                    "PR",
                    Coordinate(44.825945, 10.37959),
                ),
                legDistanceMeters = 60_000.0,
                legDurationSeconds = 1_800.0,
                availableRangeKm = 100.0,
                remainingRangeKm = 40.0,
                reserveMarginKm = 10.0,
            ),
        )
        return PredictiveCngSuggestion(
            state = PredictiveSuggestionState.SUGGESTED,
            departureAt = OffsetDateTime.parse("2026-08-30T10:00:00+02:00"),
            maximumDetourMinutes = 10.0,
            baseRoute = sampleRoute(distanceMeters = 210_000.0, durationSeconds = 6_300.0),
            rangeBasis = PredictiveRangeBasis(
                effectiveCngRangeKm = 100.0,
                estimatedRemainingCngRangeKm = 65.0,
                reserveCngRangeKm = 30.0,
                usableRangeBeforeReserveKm = 35.0,
                remainingRouteDistanceKm = 210.0,
                rangeShortfallToDestinationKm = 175.0,
                destinationReachableWithReserve = false,
                consumptionModel = "caller_estimated_remaining_range",
                trafficState = "not_configured",
                trafficAdjusted = false,
            ),
            candidates = listOf(
                PredictiveCngStation(
                    station = firstRanked,
                    estimatedRemainingRangeAtArrivalKm = 45.0,
                    reserveMarginAtArrivalKm = 15.0,
                ),
            ),
            itinerary = PredictiveCngItinerary(
                stops = stops,
                destinationLeg = PredictiveDestinationLeg(
                    distanceMeters = 70_000.0,
                    durationSeconds = 2_100.0,
                    availableRangeAtDepartureKm = 100.0,
                    estimatedRemainingRangeAtArrivalKm = 30.0,
                    reserveMarginAtArrivalKm = 0.0,
                    destinationEta = OffsetDateTime.parse("2026-08-30T11:45:00+02:00"),
                ),
                totalDistanceMeters = 210_000.0,
                totalDurationSeconds = 6_300.0,
                refuelAssumption = "full_effective_range_after_each_stop",
                distanceModel = "road_network",
            ),
        )
    }

    private fun samplePredictiveStop(
        sequence: Int,
        station: SelectedCngStop,
        legDistanceMeters: Double,
        legDurationSeconds: Double,
        availableRangeKm: Double,
        remainingRangeKm: Double,
        reserveMarginKm: Double,
    ): PredictiveItineraryStop {
        val ranked = sampleRankedStations().candidates.single()
        return PredictiveItineraryStop(
            sequence = sequence,
            station = station,
            arrivalAt = OffsetDateTime.parse("2026-08-30T10:30:00+02:00")
                .plusMinutes((sequence - 1L) * 30),
            legDistanceMeters = legDistanceMeters,
            legDurationSeconds = legDurationSeconds,
            availableRangeAtDepartureKm = availableRangeKm,
            estimatedRemainingRangeAtArrivalKm = remainingRangeKm,
            reserveMarginAtArrivalKm = reserveMarginKm,
            opening = ranked.opening,
            phone = ranked.phone,
            brand = ranked.brand,
            operator = ranked.operator,
            osmMatchConfidence = ranked.osmMatchConfidence,
            price = ranked.price,
        )
    }

    private fun sampleMultiStopSelectedRoute(): RouteWithCngItinerary {
        val suggestion = sampleMultiStopPredictiveSuggestion()
        val stops = requireNotNull(suggestion.itinerary).stops.map { it.station }
        val points = listOf(RoutePlannerViewModel.MILAN) +
            stops.map(SelectedCngStop::location) + RoutePlannerViewModel.BOLOGNA
        val distances = listOf(20_000.0, 60_000.0, 60_000.0, 70_000.0)
        val durations = listOf(600.0, 1_800.0, 1_800.0, 2_100.0)
        val remaining = listOf(45.0, 40.0, 40.0, 30.0)
        val margins = listOf(15.0, 10.0, 10.0, 0.0)
        return RouteWithCngItinerary(
            selectedStops = stops,
            distanceMeters = distances.sum(),
            durationSeconds = durations.sum(),
            legs = distances.indices.map { index ->
                CngItineraryRouteLeg(
                    sequence = index + 1,
                    kind = when (index) {
                        0 -> CngRouteLegKind.ORIGIN_TO_CNG_STATION
                        distances.lastIndex -> CngRouteLegKind.CNG_STATION_TO_DESTINATION
                        else -> CngRouteLegKind.CNG_STATION_TO_CNG_STATION
                    },
                    route = sampleRoute(
                        origin = points[index],
                        destination = points[index + 1],
                        distanceMeters = distances[index],
                        durationSeconds = durations[index],
                    ),
                    availableRangeAtDepartureKm = if (index == 0) 65.0 else 100.0,
                    estimatedRemainingRangeAtArrivalKm = remaining[index],
                    reserveMarginAtArrivalKm = margins[index],
                )
            },
            provider = "valhalla",
            rangeValidation = "all_legs_preserve_reserve",
        )
    }

    private fun sampleManeuver() = Maneuver(
        type = 1,
        instruction = "Parti verso sud.",
        distanceMeters = 100.0,
        durationSeconds = 18.0,
        beginShapeIndex = 0,
        endShapeIndex = 1,
        streetNames = listOf("Via Roma"),
        travelMode = "drive",
        travelType = "car",
    )
}
