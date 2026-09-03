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
import org.compass.cng.domain.model.GasolineFallback
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
import org.compass.cng.navigation.NavigationPhase
import org.compass.cng.navigation.NavigationSession
import org.compass.cng.navigation.toNavigationRoute
import org.compass.cng.domain.vehicle.InMemoryVehicleProfileRepository
import org.compass.cng.domain.vehicle.VehicleProfile
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
    fun startNavigationCreatesExplicitRoutePreviewSession() = runTest {
        val route = sampleRoute()
        val viewModel = RoutePlannerViewModel(FakeRoutingRepository(baseResult = Result.success(route)))

        viewModel.openNavigationPreview()

        assertEquals(PlannerStage.NAVIGATION_PREVIEW, viewModel.uiState.value.stage)
        assertEquals(NavigationPhase.ROUTE_PREVIEW, viewModel.navigationState.value.phase)
        assertEquals(route.navigation.routeId, viewModel.navigationState.value.route?.routeId)

        viewModel.navigateBack()
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertEquals(NavigationPhase.IDLE, viewModel.navigationState.value.phase)
    }

    @Test
    fun recreatedViewModelReattachesToApplicationNavigationSession() = runTest {
        val route = sampleRoute()
        val session = NavigationSession().apply {
            preview(route.toNavigationRoute())
            start()
        }
        val repository = FakeRoutingRepository(baseResult = Result.success(route))

        val recreated = RoutePlannerViewModel(
            routingRepository = repository,
            navigationSession = session,
        )

        assertEquals(PlannerStage.NAVIGATION_PREVIEW, recreated.uiState.value.stage)
        assertEquals(route.navigation.routeId, recreated.navigationState.value.route?.routeId)
        assertEquals(NavigationPhase.NAVIGATING, recreated.navigationState.value.phase)
        assertEquals(0, repository.previewCalls)
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
    fun editsRouteCoordinatesAndClearsRouteDependentState() = runTest {
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            rankedResult = Result.success(sampleRankedStations()),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openAddStop()
        viewModel.searchCngStations()
        assertEquals(PlannerStage.CNG_CANDIDATES, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.rankedStations?.candidates?.isNotEmpty() == true)

        viewModel.openRouteConfiguration()
        viewModel.updateDestinationLongitude("200")
        viewModel.applyRouteInputs()

        assertEquals(1, repository.previewCalls)
        assertEquals(
            "Inserisci una longitudine valida per la destinazione, tra -180 e 180.",
            viewModel.uiState.value.message,
        )
        assertEquals(PlannerStage.CONFIGURE_ROUTE, viewModel.uiState.value.stage)

        val rome = Coordinate(latitude = 41.9028, longitude = 12.4964)
        val florence = Coordinate(latitude = 43.7696, longitude = 11.2558)
        viewModel.updateOriginLatitude("41.9028")
        viewModel.updateOriginLongitude("12.4964")
        viewModel.updateDestinationLatitude("43.7696")
        viewModel.updateDestinationLongitude("11.2558")
        viewModel.applyRouteInputs()

        assertEquals(2, repository.previewCalls)
        assertEquals(rome, repository.lastPreviewOrigin)
        assertEquals(florence, repository.lastPreviewDestination)
        assertEquals(rome, viewModel.uiState.value.activeOrigin)
        assertEquals(florence, viewModel.uiState.value.activeDestination)
        assertEquals(rome, viewModel.uiState.value.baseRoute?.origin)
        assertEquals(florence, viewModel.uiState.value.baseRoute?.destination)
        assertEquals("41.902800", viewModel.uiState.value.originLatitudeInput)
        assertEquals("11.255800", viewModel.uiState.value.destinationLongitudeInput)
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.rankedStations)
        assertNull(viewModel.uiState.value.selectedRoute)
        assertNull(viewModel.uiState.value.predictiveSuggestion)
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
    fun selectedVehicleProfilePrefillsCngAndGasolinePolicyAcrossViewModels() = runTest {
        val profiles = InMemoryVehicleProfileRepository()
        val first = RoutePlannerViewModel(
            routingRepository = FakeRoutingRepository(Result.success(sampleRoute())),
            vehicleProfileRepository = profiles,
        )
        first.openVehicleProfiles()
        first.updateVehicleProfileName("Panda Natural Power")
        first.updateVehicleProfileCngRange("240")
        first.updateVehicleProfileCngReserve("25")
        first.updateVehicleProfileGasolineRange("520")
        first.updateVehicleProfileGasolineReserve("50")

        first.saveVehicleProfile()

        assertEquals("Panda Natural Power", first.uiState.value.vehicleProfiles.selectedProfile?.name)
        assertEquals("240", first.uiState.value.effectiveRangeKmInput)
        assertEquals("25", first.uiState.value.reserveRangeKmInput)
        assertEquals("520", first.uiState.value.effectiveGasolineRangeKmInput)
        assertEquals("50", first.uiState.value.gasolineReserveRangeKmInput)

        val recreated = RoutePlannerViewModel(
            routingRepository = FakeRoutingRepository(Result.success(sampleRoute())),
            vehicleProfileRepository = profiles,
        )
        assertEquals("Panda Natural Power", recreated.uiState.value.vehicleProfiles.selectedProfile?.name)
        assertEquals("240", recreated.uiState.value.effectiveRangeKmInput)
        assertEquals("50", recreated.uiState.value.gasolineReserveRangeKmInput)
    }

    @Test
    fun gasolineFallbackUsesProfileReserveAndStaysVisibleInNavigation() = runTest {
        val fallback = GasolineFallback(
            estimatedRemainingGasolineRangeKm = 220.0,
            reserveGasolineRangeKm = 30.0,
            usableGasolineRangeKm = 190.0,
            cngRangeUsedBeforeSwitchKm = 35.0,
            requiredGasolineRangeKm = 175.0,
            gasolineMarginAtDestinationKm = 15.0,
            strategy = "direct_after_cng_reserve",
        )
        val suggestion = samplePredictiveSuggestion(
            PredictiveSuggestionState.NO_COMPLETE_ITINERARY,
        ).copy(
            state = PredictiveSuggestionState.GASOLINE_FALLBACK,
            gasolineFallback = fallback,
        )
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            predictiveResult = Result.success(suggestion),
        )
        val profiles = InMemoryVehicleProfileRepository().apply {
            save(VehicleProfile("panda", "Panda", 300.0, 30.0, 500.0, 30.0))
        }
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            vehicleProfileRepository = profiles,
        )
        viewModel.openPredictiveRange()
        viewModel.updateEstimatedRemainingRange("120")
        viewModel.updateEstimatedRemainingGasolineRange("220")

        viewModel.evaluatePredictiveRange()

        assertEquals(220.0, requireNotNull(repository.lastRemainingGasolineRangeKm), 0.0)
        assertEquals(30.0, requireNotNull(repository.lastGasolineReserveRangeKm), 0.0)
        assertEquals(PlannerStage.PREDICTIVE_STATUS, viewModel.uiState.value.stage)
        viewModel.openGasolineFallbackNavigation()
        assertEquals(fallback, viewModel.navigationState.value.route?.gasolineFallback)
        assertEquals(PlannerStage.NAVIGATION_PREVIEW, viewModel.uiState.value.stage)
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
        assertEquals(sampleRoute().origin, repository.lastPredictiveOrigin)
        assertEquals(sampleRoute().destination, repository.lastPredictiveDestination)
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
        assertEquals(sampleRoute().origin, repository.lastCandidateOrigin)
        assertEquals(sampleRoute().destination, repository.lastCandidateDestination)
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
        assertEquals(sampleRoute().origin, repository.lastSelectedOrigin)
        assertEquals(sampleRoute().destination, repository.lastSelectedDestination)
        assertEquals(PlannerStage.SELECTED_ROUTE, viewModel.uiState.value.stage)
        assertSame(selectedRoute, viewModel.uiState.value.selectedRoute)
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun downstreamRequestsUseEditedRouteCoordinates() = runTest {
        val customOrigin = Coordinate(latitude = 41.9028, longitude = 12.4964)
        val customDestination = Coordinate(latitude = 43.7696, longitude = 11.2558)
        val ranked = sampleRankedStations()
        val suggestion = samplePredictiveSuggestion(PredictiveSuggestionState.SUGGESTED)
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            rankedResult = Result.success(ranked),
            selectedRouteResult = Result.success(sampleSelectedRoute()),
            predictiveResult = Result.success(suggestion),
            selectedItineraryResult = Result.success(sampleMultiStopSelectedRoute()),
        )
        val viewModel = RoutePlannerViewModel(repository)

        viewModel.openRouteConfiguration()
        viewModel.updateOriginLatitude("41.9028")
        viewModel.updateOriginLongitude("12.4964")
        viewModel.updateDestinationLatitude("43.7696")
        viewModel.updateDestinationLongitude("11.2558")
        viewModel.applyRouteInputs()

        viewModel.openAddStop()
        viewModel.searchCngStations()
        assertEquals(customOrigin, repository.lastCandidateOrigin)
        assertEquals(customDestination, repository.lastCandidateDestination)

        viewModel.selectStation(ranked.candidates.single())
        assertEquals(customOrigin, repository.lastSelectedOrigin)
        assertEquals(customDestination, repository.lastSelectedDestination)

        viewModel.removeCngStop()
        viewModel.openPredictiveRange()
        viewModel.updateEstimatedRemainingRange("120")
        viewModel.evaluatePredictiveRange()
        assertEquals(customOrigin, repository.lastPredictiveOrigin)
        assertEquals(customDestination, repository.lastPredictiveDestination)

        viewModel.acceptPredictiveItinerary()
        assertEquals(customOrigin, repository.lastItineraryOrigin)
        assertEquals(customDestination, repository.lastItineraryDestination)
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
        var previewCalls = 0
        var candidateCalls = 0
        var predictiveCalls = 0
        var lastRangeKm = 0.0
        var lastRemainingRangeKm = 0.0
        var lastReserveRangeKm = 0.0
        var lastDetourMinutes = 0.0
        var lastPreviewOrigin: Coordinate? = null
        var lastPreviewDestination: Coordinate? = null
        var lastCandidateOrigin: Coordinate? = null
        var lastCandidateDestination: Coordinate? = null
        var lastPredictiveOrigin: Coordinate? = null
        var lastPredictiveDestination: Coordinate? = null
        var lastSelectedOrigin: Coordinate? = null
        var lastSelectedDestination: Coordinate? = null
        var lastItineraryOrigin: Coordinate? = null
        var lastItineraryDestination: Coordinate? = null
        lateinit var lastDepartureAt: OffsetDateTime
        var lastSelectedStationId: String? = null
        var lastItineraryStationIds: List<String>? = null
        var lastItineraryEffectiveRangeKm = 0.0
        var lastRemainingGasolineRangeKm: Double? = null
        var lastGasolineReserveRangeKm: Double? = null

        override suspend fun previewRoute(
            origin: Coordinate,
            destination: Coordinate,
        ): RoutePreview {
            previewCalls += 1
            lastPreviewOrigin = origin
            lastPreviewDestination = destination
            val route = baseResult.getOrThrow()
            return if (route.origin == origin && route.destination == destination) {
                route
            } else {
                route.copy(
                    origin = origin,
                    destination = destination,
                    geometry = listOf(origin, destination),
                )
            }
        }

        override suspend fun rankedCngStations(
            origin: Coordinate,
            destination: Coordinate,
            effectiveCngRangeKm: Double,
            maximumDetourMinutes: Double,
            departureAt: OffsetDateTime,
        ): RankedCngStations {
            candidateCalls += 1
            lastCandidateOrigin = origin
            lastCandidateDestination = destination
            lastRangeKm = effectiveCngRangeKm
            lastDetourMinutes = maximumDetourMinutes
            lastDepartureAt = departureAt
            val ranked = rankedResult.getOrThrow()
            val route = ranked.baseRoute
            return if (route.origin == origin && route.destination == destination) {
                ranked
            } else {
                ranked.copy(
                    baseRoute = route.copy(
                        origin = origin,
                        destination = destination,
                        geometry = listOf(origin, destination),
                    ),
                )
            }
        }

        override suspend fun routeWithCngStop(
            origin: Coordinate,
            destination: Coordinate,
            mimitStationId: String,
        ): RouteWithCngStop {
            lastSelectedOrigin = origin
            lastSelectedDestination = destination
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
            excludedMimitStationIds: Set<String>,
            estimatedRemainingGasolineRangeKm: Double?,
            reserveGasolineRangeKm: Double?,
        ): PredictiveCngSuggestion {
            predictiveCalls += 1
            lastPredictiveOrigin = origin
            lastPredictiveDestination = destination
            lastRangeKm = effectiveCngRangeKm
            lastRemainingRangeKm = estimatedRemainingCngRangeKm
            lastReserveRangeKm = reserveCngRangeKm
            lastDetourMinutes = maximumDetourMinutes
            lastDepartureAt = departureAt
            lastRemainingGasolineRangeKm = estimatedRemainingGasolineRangeKm
            lastGasolineReserveRangeKm = reserveGasolineRangeKm
            val suggestion = predictiveResult.getOrThrow()
            val route = suggestion.baseRoute
            return if (route.origin == origin && route.destination == destination) {
                suggestion
            } else {
                suggestion.copy(
                    baseRoute = route.copy(
                        origin = origin,
                        destination = destination,
                        geometry = listOf(origin, destination),
                    ),
                )
            }
        }

        override suspend fun routeWithCngItinerary(
            origin: Coordinate,
            destination: Coordinate,
            mimitStationIds: List<String>,
            effectiveCngRangeKm: Double,
            estimatedRemainingCngRangeKm: Double,
            reserveCngRangeKm: Double,
        ): RouteWithCngItinerary {
            lastItineraryOrigin = origin
            lastItineraryDestination = destination
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
