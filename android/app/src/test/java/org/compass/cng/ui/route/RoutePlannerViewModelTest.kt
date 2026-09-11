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
import org.compass.cng.domain.model.DestinationKind
import org.compass.cng.domain.model.DestinationSuggestRequest
import org.compass.cng.domain.model.DestinationSuggestion
import org.compass.cng.domain.model.DestinationSuggestions
import org.compass.cng.domain.model.NavigationTarget
import org.compass.cng.domain.model.NormalizedAddress
import org.compass.cng.domain.model.ResolvedDestination
import org.compass.cng.domain.model.ResolvedDestinationSelection
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PlaceKind
import org.compass.cng.domain.model.PlaceSearchResult
import org.compass.cng.domain.model.PlaceSearchResults
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
import org.compass.cng.domain.model.RouteWithIntermediateStop
import org.compass.cng.domain.model.RouteWithIntermediateStops
import org.compass.cng.domain.model.asMultiple
import org.compass.cng.domain.model.SelectedCngStop
import org.compass.cng.domain.server.InMemoryServerConnectionRepository
import org.compass.cng.domain.server.ServerConnection
import org.compass.cng.navigation.NavigationPhase
import org.compass.cng.navigation.NavigationLocation
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
    fun productionStartModeFollowsGpsWithoutLoadingADefaultRoute() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )
        val location = NavigationLocation(
            coordinate = Coordinate(45.4642, 9.19),
            accuracyMeters = 4.0,
            speedMetersPerSecond = 8.0,
            bearingDegrees = 25.0,
            timestampEpochMillis = 1_000L,
        )

        assertEquals(PlannerStage.FOLLOW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.baseRoute)
        assertEquals("", viewModel.uiState.value.originLatitudeInput)
        assertEquals("", viewModel.uiState.value.destinationLatitudeInput)
        assertEquals(0, repository.previewCalls)

        viewModel.updateFollowLocation(location)

        assertEquals(location, viewModel.uiState.value.followLocation)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun applicationFactoryEnablesRouteFreeFollowAtStartup() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel.Factory(
            routingRepository = repository,
            navigationSession = NavigationSession(),
            vehicleProfileRepository = InMemoryVehicleProfileRepository(),
            serverConnectionRepository = configuredServerRepository(),
        ).create(RoutePlannerViewModel::class.java)

        assertEquals(PlannerStage.FOLLOW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.baseRoute)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun productionStartupOpensServerConfigurationWhenCredentialsAreMissing() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = InMemoryServerConnectionRepository(),
            startInFollowMode = true,
        )

        assertEquals(PlannerStage.SERVER_CONNECTION, viewModel.uiState.value.stage)
        assertEquals("Configura il server Compass per continuare.", viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.baseRoute)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun productionStartupDiscardsAnInactiveCachedPreview() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val session = NavigationSession().apply { preview(sampleRoute().toNavigationRoute()) }

        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            navigationSession = session,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )

        assertEquals(PlannerStage.FOLLOW, viewModel.uiState.value.stage)
        assertNull(viewModel.navigationState.value.route)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun savingServerProfileInFollowModeDoesNotCreateATrip() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            startInFollowMode = true,
        )

        viewModel.updateServerBaseUrl("https://compass.example.test/")
        viewModel.updateServerUsername("driver")
        viewModel.updateServerPassword("secret")
        viewModel.saveServerConnection()

        assertEquals(PlannerStage.FOLLOW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.baseRoute)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun tripSelectorSupportsSearchAndCurrentLocationForEitherEndpoint() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )
        val searchedOrigin = PlaceSearchResult(
            id = "origin",
            displayName = "Verona Porta Nuova",
            address = "Verona",
            location = Coordinate(45.429, 10.982),
            kind = PlaceKind.POI,
            category = "railway_station",
            poiName = "Verona Porta Nuova",
            provider = "fixture",
        )
        val currentDestination = Coordinate(45.0703, 7.6869)

        viewModel.openRouteConfiguration()
        viewModel.openPlaceSearch(RouteEndpoint.ORIGIN)
        viewModel.selectPlace(searchedOrigin)
        viewModel.currentLocationRequested(RouteEndpoint.DESTINATION)
        assertEquals(
            CurrentLocationAcquisitionStatus.ACQUIRING,
            viewModel.uiState.value.destinationCurrentLocationStatus,
        )
        viewModel.useCurrentLocation(currentDestination)

        val configured = viewModel.uiState.value
        assertEquals(PlannerStage.CONFIGURE_ROUTE, configured.stage)
        assertEquals(RouteLocationMethod.SEARCH, configured.originLocationMethod)
        assertEquals("Verona Porta Nuova", configured.originDisplayName)
        assertEquals(
            RouteLocationMethod.CURRENT_LOCATION,
            configured.destinationLocationMethod,
        )
        assertEquals("Posizione attuale", configured.destinationDisplayName)
        assertEquals(
            CurrentLocationAcquisitionStatus.SUCCESS,
            configured.destinationCurrentLocationStatus,
        )
        assertNull(configured.message)
        assertEquals(0, repository.previewCalls)

        viewModel.applyRouteInputs()

        assertEquals(1, repository.previewCalls)
        assertEquals(searchedOrigin.location, repository.lastPreviewOrigin)
        assertEquals(currentDestination, repository.lastPreviewDestination)
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.routeInputsDirty)
    }

    @Test
    fun tripActionsStayLockedUntilEditedInputsAreRecalculated() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openRouteConfiguration()

        viewModel.updateDestinationLongitude("12.5")
        assertTrue(viewModel.uiState.value.routeInputsDirty)

        viewModel.openAddStop()
        viewModel.openPredictiveRange()
        viewModel.openNavigationPreview()
        assertEquals(PlannerStage.CONFIGURE_ROUTE, viewModel.uiState.value.stage)

        viewModel.applyRouteInputs()
        assertFalse(viewModel.uiState.value.routeInputsDirty)

        viewModel.openPredictiveRange()
        assertEquals(PlannerStage.CONFIGURE_PREDICTIVE, viewModel.uiState.value.stage)
    }

    @Test
    fun intermediateStopDefaultsToThirtyPercentAndUsesWaypointRouting() = runTest {
        val direct = sampleRoute(distanceMeters = 100_000.0)
        val stop = Coordinate(45.0, 10.0)
        val via = RouteWithIntermediateStop(
            stop = stop,
            distanceMeters = 125_000.0,
            durationSeconds = 4_000.0,
            legs = listOf(
                sampleRoute(
                    origin = direct.origin,
                    destination = stop,
                    distanceMeters = 50_000.0,
                    durationSeconds = 1_600.0,
                ),
                sampleRoute(
                    origin = stop,
                    destination = direct.destination,
                    distanceMeters = 75_000.0,
                    durationSeconds = 2_400.0,
                ),
            ),
            provider = "valhalla",
            navigation = direct.navigation.copy(
                routeId = "route_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                drivingDurationSeconds = 4_000.0,
                remainingDrivingDurationSeconds = 4_000.0,
                totalTripDurationSeconds = 4_000.0,
            ),
        )
        val repository = FakeRoutingRepository(
            baseResult = Result.success(direct),
            intermediateRouteResult = Result.success(via),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openRouteConfiguration()

        viewModel.addIntermediateStop()
        assertEquals("30,0", viewModel.uiState.value.intermediateStopMaximumDeviationKmInput)
        viewModel.openMapPointPicker(RouteEndpoint.INTERMEDIATE_STOP)
        viewModel.updateMapPickerCoordinate(stop)
        viewModel.confirmMapPointPicker()
        viewModel.chooseIntermediateStop()

        assertEquals(1, repository.intermediateRouteCalls)
        assertEquals(stop, repository.lastIntermediateStop)
        assertEquals(via.asMultiple(), viewModel.uiState.value.intermediateStopsRoute)
        assertEquals(listOf(stop), viewModel.uiState.value.plannedIntermediateStops.map { it.location })
        assertFalse(viewModel.uiState.value.routeInputsDirty)
    }

    @Test
    fun intermediateStopSearchUsesRouteBoundsWithoutPointBias() = runTest {
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute(distanceMeters = 100_000.0)),
        )
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
        )
        viewModel.openRouteConfiguration()
        viewModel.addIntermediateStop()
        viewModel.openPlaceSearch(RouteEndpoint.INTERMEDIATE_STOP)
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 1, emptyList()),
        )

        viewModel.updatePlaceSearchQuery("farmacia")
        viewModel.searchDestinations()

        val context = requireNotNull(repository.lastDestinationSuggestRequest).context
        assertNull(context.biasRadiusMeters)
        val bounds = requireNotNull(context.routeBounds)
        assertTrue(bounds.southWest.latitude < sampleRoute().geometry.minOf { it.latitude })
        assertTrue(bounds.northEast.longitude > sampleRoute().geometry.maxOf { it.longitude })
    }

    @Test
    fun searchedIntermediateStopIsPreviewedOnMapBeforeExplicitChoice() = runTest {
        val direct = sampleRoute(distanceMeters = 100_000.0)
        val suggestion = destinationSuggestion()
        val resolved = resolvedDestination(suggestion)
        val stop = resolved.navigationTarget.location
        val via = RouteWithIntermediateStop(
            stop = stop,
            distanceMeters = 112_000.0,
            durationSeconds = 4_200.0,
            legs = listOf(
                sampleRoute(
                    origin = direct.origin,
                    destination = stop,
                    distanceMeters = 38_000.0,
                    durationSeconds = 1_400.0,
                ),
                sampleRoute(
                    origin = stop,
                    destination = direct.destination,
                    distanceMeters = 74_000.0,
                    durationSeconds = 2_800.0,
                ),
            ),
            provider = "valhalla",
            navigation = direct.navigation.copy(
                routeId = "route_cccccccccccccccccccccccccccccccc",
                drivingDurationSeconds = 4_200.0,
                remainingDrivingDurationSeconds = 4_200.0,
                totalTripDurationSeconds = 4_200.0,
            ),
        )
        val repository = FakeRoutingRepository(
            baseResult = Result.success(direct),
            intermediateRouteResult = Result.success(via),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openRouteConfiguration()
        viewModel.addIntermediateStop()
        viewModel.openPlaceSearch(RouteEndpoint.INTERMEDIATE_STOP)
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 1, listOf(suggestion)),
        )
        repository.resolvedDestinationResult = Result.success(resolved.copy(sessionId = session))
        viewModel.updatePlaceSearchQuery("Duomo di Milano")
        viewModel.searchDestinations()

        viewModel.selectDestinationSuggestion(suggestion)

        val preview = viewModel.uiState.value
        assertEquals(PlannerStage.INTERMEDIATE_STOP_PREVIEW, preview.stage)
        assertEquals(via.asMultiple(), preview.pendingIntermediateStopsRoute)
        assertEquals("Non selezionata", preview.intermediateStopDisplayName)
        assertTrue(preview.destinationSuggestions.isEmpty())

        viewModel.chooseIntermediateStop()

        val chosen = viewModel.uiState.value
        assertEquals(PlannerStage.INTERMEDIATE_STOPS, chosen.stage)
        assertEquals(listOf(stop), chosen.plannedIntermediateStops.map { it.location })
        assertEquals(via.asMultiple(), chosen.intermediateStopsRoute)
        assertEquals(direct, chosen.baseRoute)
        assertFalse(chosen.routeInputsDirty)
        assertNull(chosen.pendingIntermediateStopsRoute)
    }

    @Test
    fun intermediateStopOverExactRoadDeviationIsRejected() = runTest {
        val direct = sampleRoute(distanceMeters = 100_000.0)
        val stop = Coordinate(45.0, 10.0)
        val via = RouteWithIntermediateStop(
            stop = stop,
            distanceMeters = 140_000.0,
            durationSeconds = 4_000.0,
            legs = listOf(
                sampleRoute(
                    origin = direct.origin,
                    destination = stop,
                    distanceMeters = 70_000.0,
                ),
                sampleRoute(
                    origin = stop,
                    destination = direct.destination,
                    distanceMeters = 70_000.0,
                ),
            ),
            provider = "valhalla",
            navigation = direct.navigation.copy(
                routeId = "route_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                drivingDurationSeconds = 4_000.0,
                remainingDrivingDurationSeconds = 4_000.0,
                totalTripDurationSeconds = 4_000.0,
            ),
        )
        val viewModel = RoutePlannerViewModel(
            FakeRoutingRepository(
                baseResult = Result.success(direct),
                intermediateRouteResult = Result.success(via),
            ),
        )
        viewModel.openRouteConfiguration()
        viewModel.addIntermediateStop()
        viewModel.openMapPointPicker(RouteEndpoint.INTERMEDIATE_STOP)
        viewModel.updateMapPickerCoordinate(stop)
        viewModel.confirmMapPointPicker()

        assertNull(viewModel.uiState.value.intermediateStopsRoute)
        assertTrue(viewModel.uiState.value.plannedIntermediateStops.isEmpty())
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("40,0 km"))
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("30,0 km"))
    }

    @Test
    fun multipleIntermediateStopsCanBeReorderedBeforeTheCommonSummary() = runTest {
        val direct = sampleRoute(distanceMeters = 100_000.0)
        val first = Coordinate(45.1, 10.1)
        val second = Coordinate(45.2, 10.2)
        val repository = FakeRoutingRepository(
            baseResult = Result.success(direct),
            multipleIntermediateRouteFactory = { stops ->
                val points = listOf(direct.origin) + stops + direct.destination
                RouteWithIntermediateStops(
                    stops = stops,
                    distanceMeters = 110_000.0 + stops.size * 1_000.0,
                    durationSeconds = 4_000.0 + stops.size * 60.0,
                    legs = points.zipWithNext().map { (origin, destination) ->
                        sampleRoute(origin = origin, destination = destination)
                    },
                    provider = "valhalla",
                    navigation = direct.navigation,
                )
            },
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.addIntermediateStop()

        listOf(first, second).forEach { coordinate ->
            viewModel.openMapPointPicker(RouteEndpoint.INTERMEDIATE_STOP)
            viewModel.updateMapPickerCoordinate(coordinate)
            viewModel.confirmMapPointPicker()
            viewModel.chooseIntermediateStop()
        }
        viewModel.moveIntermediateStop(1, 0)
        viewModel.calculateIntermediateStopsRoute()

        assertEquals(listOf(second, first), repository.lastIntermediateStops)
        assertEquals(PlannerStage.NAVIGATION_PREVIEW, viewModel.uiState.value.stage)
        assertEquals(
            listOf(second, first),
            viewModel.navigationState.value.route?.intermediateStops?.map { it.location },
        )
    }

    @Test
    fun emptyTripEndpointsDoNotSubmitOrShowAValidationError() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )

        viewModel.openRouteConfiguration()
        viewModel.applyRouteInputs()

        assertEquals(0, repository.previewCalls)
        assertNull(viewModel.uiState.value.message)
        assertEquals(PlannerStage.CONFIGURE_ROUTE, viewModel.uiState.value.stage)
    }

    @Test
    fun backingOutOfANewTripReturnsToRouteFreeFollow() = runTest {
        val viewModel = RoutePlannerViewModel(
            routingRepository = FakeRoutingRepository(baseResult = Result.success(sampleRoute())),
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )

        viewModel.openRouteConfiguration()
        viewModel.navigateBack()

        assertEquals(PlannerStage.FOLLOW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.baseRoute)
    }

    @Test
    fun terminatingNavigationReturnsToRouteFreeFollow() = runTest {
        val viewModel = RoutePlannerViewModel(
            routingRepository = FakeRoutingRepository(baseResult = Result.success(sampleRoute())),
        )
        viewModel.openNavigationPreview()
        viewModel.startNavigation()

        viewModel.stopNavigation()

        assertEquals(PlannerStage.FOLLOW, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.baseRoute)
        assertEquals(NavigationPhase.IDLE, viewModel.navigationState.value.phase)
        assertNull(viewModel.navigationState.value.route)
    }

    @Test
    fun defaultUiStateIsRouteFreeAndHasNoPreselectedEndpoints() {
        val state = RoutePlannerUiState()

        assertEquals(PlannerStage.FOLLOW, state.stage)
        assertNull(state.operation)
        assertEquals("", state.originLatitudeInput)
        assertEquals("", state.destinationLatitudeInput)
        assertEquals("Non selezionata", state.originDisplayName)
        assertEquals("Non selezionata", state.destinationDisplayName)
    }

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
        assertEquals(PlannerStage.SERVER_CONNECTION, viewModel.uiState.value.stage)
    }

    @Test
    fun savesPersistentServerConnectionAndReturnsWithoutInventingAReroute() = runTest {
        val routing = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val connections = InMemoryServerConnectionRepository(
            ServerConnection.create(
                baseUrl = "https://old.example.it/",
                username = "old-user",
                password = "old-password",
            ),
        )
        val viewModel = RoutePlannerViewModel(
            routingRepository = routing,
            serverConnectionRepository = connections,
        )

        viewModel.openServerConnection()
        assertEquals(PlannerStage.SERVER_CONNECTION, viewModel.uiState.value.stage)
        assertEquals("https://old.example.it/", viewModel.uiState.value.serverBaseUrlInput)
        viewModel.updateServerBaseUrl("https://new.example.it/compass")
        viewModel.updateServerUsername("road-user")
        viewModel.updateServerPassword("road-password")
        viewModel.saveServerConnection()

        assertEquals("https://new.example.it/compass/", connections.load().baseUrl)
        assertEquals("road-user", connections.load().username)
        assertEquals("road-password", connections.load().password)
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertEquals(1, routing.previewCalls)
    }

    @Test
    fun refusesInsecureServerUntilHttpFallbackIsAccepted() = runTest {
        val connections = InMemoryServerConnectionRepository()
        val viewModel = RoutePlannerViewModel(
            routingRepository = FakeRoutingRepository(baseResult = Result.success(sampleRoute())),
            serverConnectionRepository = connections,
        )
        viewModel.openServerConnection()
        viewModel.updateServerBaseUrl("http://192.0.2.1:8000/")
        viewModel.updateServerUsername("road-user")
        viewModel.updateServerPassword("road-password")

        viewModel.saveServerConnection()

        assertEquals(PlannerStage.SERVER_CONNECTION, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("accettare esplicitamente"))
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
        assertFalse(viewModel.uiState.value.routeInputsDirty)
        assertNull(viewModel.uiState.value.rankedStations)
        assertNull(viewModel.uiState.value.selectedRoute)
        assertNull(viewModel.uiState.value.predictiveSuggestion)
    }

    @Test
    fun searchesAndResolvesGoogleDestinationWithoutRoutingByText() = runTest {
        val suggestion = destinationSuggestion()
        val resolved = resolvedDestination(suggestion)
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
            destinationSuggestionsResult = Result.success(
                DestinationSuggestions("9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da", 1, listOf(suggestion)),
            ),
            resolvedDestinationResult = Result.success(resolved),
        )
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )

        viewModel.openRouteConfiguration()
        viewModel.openDestinationSearch()
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 1, listOf(suggestion)),
        )
        repository.resolvedDestinationResult = Result.success(resolved.copy(sessionId = session))
        viewModel.updatePlaceSearchQuery("Duomo di Milano")
        viewModel.searchDestinations()

        assertEquals(listOf(suggestion), viewModel.uiState.value.destinationSuggestions)
        viewModel.selectDestinationSuggestion(suggestion)

        assertEquals(1, repository.destinationSuggestCalls)
        assertEquals(1, repository.destinationResolveCalls)
        assertEquals(0, repository.previewCalls)
        assertEquals("45.464100", viewModel.uiState.value.destinationLatitudeInput)
        assertEquals("9.191900", viewModel.uiState.value.destinationLongitudeInput)
        assertEquals(resolved.selection.formattedAddress, viewModel.uiState.value.destinationDisplayName)
        assertEquals(PlannerStage.CONFIGURE_ROUTE, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.routeInputsDirty)
    }

    @Test
    fun duplicateTapDoesNotResolveSelectionTwice() = runTest {
        val suggestion = destinationSuggestion()
        val repository = FakeRoutingRepository(
            baseResult = Result.success(sampleRoute()),
        )
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openRouteConfiguration()
        viewModel.openDestinationSearch()
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 1, listOf(suggestion)),
        )
        repository.resolvedDestinationResult = Result.success(
            resolvedDestination(suggestion).copy(sessionId = session),
        )
        viewModel.updatePlaceSearchQuery("Duomo di Milano")
        viewModel.searchDestinations()

        viewModel.selectDestinationSuggestion(suggestion)
        viewModel.selectDestinationSuggestion(suggestion)

        assertEquals(1, repository.destinationResolveCalls)
    }

    @Test
    fun ignoresSuggestionResponseForAnOlderRevision() = runTest {
        val suggestion = destinationSuggestion()
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )
        viewModel.openRouteConfiguration()
        viewModel.openDestinationSearch()
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 0, listOf(suggestion)),
        )

        viewModel.updatePlaceSearchQuery("Verona")
        viewModel.searchDestinations()

        assertTrue(viewModel.uiState.value.destinationSuggestions.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun searchWithoutGpsDoesNotInventABiasCoordinate() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(
            routingRepository = repository,
            serverConnectionRepository = configuredServerRepository(),
            startInFollowMode = true,
        )
        viewModel.openRouteConfiguration()
        viewModel.openDestinationSearch()
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 1, emptyList()),
        )

        viewModel.updatePlaceSearchQuery("Roma")
        viewModel.searchDestinations()

        assertNull(repository.lastDestinationSuggestRequest?.context?.location)
        assertNull(repository.lastDestinationSuggestRequest?.context?.biasRadiusMeters)
    }

    @Test
    fun mapStagesReplaceGoogleAddressWithNeutralDestinationLabel() = runTest {
        val suggestion = destinationSuggestion()
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(repository)
        viewModel.openRouteConfiguration()
        viewModel.openDestinationSearch()
        val session = requireNotNull(viewModel.uiState.value.destinationSearchSessionId)
        repository.destinationSuggestionsResult = Result.success(
            DestinationSuggestions(session, 1, listOf(suggestion)),
        )
        repository.resolvedDestinationResult = Result.success(
            resolvedDestination(suggestion).copy(sessionId = session),
        )
        viewModel.updateEffectiveRange("240")
        viewModel.updateReserveRange("35")
        viewModel.updateMaximumDetour("12")
        viewModel.updatePlaceSearchQuery("Duomo di Milano")
        viewModel.searchDestinations()
        viewModel.selectDestinationSuggestion(suggestion)
        assertEquals("Piazza del Duomo, Milano", viewModel.uiState.value.destinationDisplayName)

        viewModel.applyRouteInputs()
        viewModel.openNavigationPreview()

        assertEquals("Destinazione selezionata", viewModel.uiState.value.destinationDisplayName)
        assertEquals(resolvedDestination(suggestion).navigationTarget.location, repository.lastPreviewDestination)
        assertEquals("240", viewModel.uiState.value.effectiveRangeKmInput)
        assertEquals("35", viewModel.uiState.value.reserveRangeKmInput)
        assertEquals("12", viewModel.uiState.value.maximumDetourMinutesInput)
    }

    @Test
    fun currentDeviceLocationPrefillsVisibleOriginBeforeRouteCalculation() = runTest {
        val repository = FakeRoutingRepository(baseResult = Result.success(sampleRoute()))
        val viewModel = RoutePlannerViewModel(repository)
        val current = Coordinate(45.5, 9.2)

        viewModel.currentLocationRequested()
        assertEquals(
            CurrentLocationAcquisitionStatus.ACQUIRING,
            viewModel.uiState.value.originCurrentLocationStatus,
        )
        assertNull(viewModel.uiState.value.message)
        viewModel.useCurrentLocationAsOrigin(current)

        assertEquals(1, repository.previewCalls)
        assertEquals(PlannerStage.CONFIGURE_ROUTE, viewModel.uiState.value.stage)
        assertEquals("45.500000", viewModel.uiState.value.originLatitudeInput)
        assertEquals("9.200000", viewModel.uiState.value.originLongitudeInput)
        assertEquals(RoutePlannerViewModel.MILAN, repository.lastPreviewOrigin)
        assertEquals("Posizione attuale", viewModel.uiState.value.originDisplayName)
        assertEquals(
            CurrentLocationAcquisitionStatus.SUCCESS,
            viewModel.uiState.value.originCurrentLocationStatus,
        )
        assertNull(viewModel.uiState.value.message)
        assertEquals(RoutePlannerViewModel.MILAN, viewModel.uiState.value.baseRoute?.origin)

        viewModel.applyRouteInputs()

        assertEquals(2, repository.previewCalls)
        assertEquals(current, repository.lastPreviewOrigin)
        assertEquals(current, viewModel.uiState.value.baseRoute?.origin)
        assertEquals("Posizione attuale", viewModel.uiState.value.originDisplayName)
        assertEquals(PlannerStage.PREVIEW, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.routeInputsDirty)
    }

    @Test
    fun currentLocationFailureIsScopedToTheSelectedEndpointWithoutErrorMessage() = runTest {
        val viewModel = RoutePlannerViewModel(
            FakeRoutingRepository(baseResult = Result.success(sampleRoute())),
        )
        viewModel.openRouteConfiguration()

        viewModel.currentLocationRequested(RouteEndpoint.DESTINATION)
        viewModel.currentLocationUnavailable()

        val state = viewModel.uiState.value
        assertEquals(
            CurrentLocationAcquisitionStatus.FAILURE,
            state.destinationCurrentLocationStatus,
        )
        assertEquals(
            CurrentLocationAcquisitionStatus.IDLE,
            state.originCurrentLocationStatus,
        )
        assertEquals(RouteLocationMethod.CURRENT_LOCATION, state.destinationLocationMethod)
        assertNull(state.message)
    }

    @Test
    fun explainsWhichNavigationPermissionIsMissing() = runTest {
        val viewModel = RoutePlannerViewModel(
            FakeRoutingRepository(baseResult = Result.success(sampleRoute())),
        )

        viewModel.navigationPermissionDenied(
            locationGranted = true,
            notificationsGranted = false,
        )

        assertEquals(
            "Autorizza le notifiche per mantenere visibile la navigazione in background.",
            viewModel.uiState.value.message,
        )
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
    fun extendedPlanningCanClearASelectedVehicleAndKeepEditableValues() = runTest {
        val profiles = InMemoryVehicleProfileRepository().apply {
            save(VehicleProfile("panda", "Panda", 240.0, 25.0, 520.0, 50.0))
        }
        val viewModel = RoutePlannerViewModel(
            routingRepository = FakeRoutingRepository(Result.success(sampleRoute())),
            vehicleProfileRepository = profiles,
        )

        viewModel.openPredictiveRange()
        viewModel.clearVehicleProfileSelection()

        assertNull(viewModel.uiState.value.vehicleProfiles.selectedProfile)
        assertNull(profiles.load().selectedProfileId)
        assertEquals("240", viewModel.uiState.value.effectiveRangeKmInput)
        assertEquals("25", viewModel.uiState.value.reserveRangeKmInput)
        assertEquals("", viewModel.uiState.value.estimatedRemainingRangeKmInput)
        assertEquals("10", viewModel.uiState.value.maximumDetourMinutesInput)
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
        assertEquals(PlannerStage.NAVIGATION_PREVIEW, viewModel.uiState.value.stage)
        assertEquals(NavigationPhase.ROUTE_PREVIEW, viewModel.navigationState.value.phase)
        assertEquals(routed.selectedStops.map { it.mimitStationId }, viewModel.uiState.value
            .selectedItineraryRoute?.selectedStops?.map { it.mimitStationId })
        assertEquals(
            suggestion.itinerary?.stops?.first()?.price,
            viewModel.uiState.value.selectedItineraryRoute?.selectedStops?.first()?.price,
        )
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
        assertEquals(PlannerStage.NAVIGATION_PREVIEW, viewModel.uiState.value.stage)
        assertEquals(NavigationPhase.ROUTE_PREVIEW, viewModel.navigationState.value.phase)
        assertEquals(selectedRoute.selectedStop.mimitStationId, viewModel.uiState.value
            .selectedRoute?.selectedStop?.mimitStationId)
        assertEquals(
            ranked.candidates.single().opening,
            viewModel.uiState.value.selectedRoute?.selectedStop?.opening,
        )
        assertEquals(
            ranked.candidates.single().price,
            viewModel.uiState.value.selectedRoute?.selectedStop?.price,
        )
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

    private fun configuredServerRepository() = InMemoryServerConnectionRepository(
        ServerConnection.create(
            baseUrl = "https://compass.example.test/",
            username = "driver",
            password = "secret",
        ),
    )

    private fun destinationSuggestion() = DestinationSuggestion(
        id = "google_places_new:place-a",
        provider = "google_places_new",
        providerRef = "place-a",
        kind = DestinationKind.BUSINESS,
        title = "Duomo di Milano",
        subtitle = "Piazza del Duomo, Milano",
        addressPreview = "Piazza del Duomo, Milano",
        distanceMeters = 250,
        providerRank = 0,
        attribution = "Google Maps",
    )

    private fun resolvedDestination(suggestion: DestinationSuggestion) = ResolvedDestination(
        sessionId = "9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
        revision = 1,
        selection = ResolvedDestinationSelection(
            provider = suggestion.provider,
            providerRef = suggestion.providerRef,
            formattedAddress = "Piazza del Duomo, Milano",
            addressComponents = emptyList(),
            normalizedAddress = NormalizedAddress(
                street = null,
                streetNumber = null,
                locality = "Milano",
                province = null,
                region = null,
                postalCode = null,
                country = "Italia",
            ),
            location = Coordinate(45.4641, 9.1919),
            kind = suggestion.kind,
            attribution = listOf("Google Maps"),
            fieldSources = mapOf(
                "formatted_address" to "google_places_new",
                "address_components" to "google_places_new",
                "location" to "google_places_new",
            ),
        ),
        navigationTarget = NavigationTarget(
            location = Coordinate(45.4641, 9.1919),
            providerRef = suggestion.providerRef,
            mapLabel = "Destinazione selezionata",
        ),
    )

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
        private val intermediateRouteResult: Result<RouteWithIntermediateStop> = Result.failure(
            AssertionError("routeWithIntermediateStop was not expected"),
        ),
        private val multipleIntermediateRouteFactory:
            ((List<Coordinate>) -> RouteWithIntermediateStops)? = null,
        private val placeSearchResult: Result<PlaceSearchResults> = Result.failure(
            AssertionError("searchPlaces was not expected"),
        ),
        var destinationSuggestionsResult: Result<DestinationSuggestions> = Result.failure(
            AssertionError("suggestDestinations was not expected"),
        ),
        var resolvedDestinationResult: Result<ResolvedDestination> = Result.failure(
            AssertionError("resolveDestination was not expected"),
        ),
    ) : RoutingRepository {
        var previewCalls = 0
        var destinationSuggestCalls = 0
        var destinationResolveCalls = 0
        var intermediateRouteCalls = 0
        var lastIntermediateStop: Coordinate? = null
        var lastIntermediateStops: List<Coordinate>? = null
        var lastDestinationSuggestRequest: DestinationSuggestRequest? = null
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

        override suspend fun searchPlaces(query: String, limit: Int): PlaceSearchResults =
            placeSearchResult.getOrThrow()

        override suspend fun suggestDestinations(
            request: DestinationSuggestRequest,
        ): DestinationSuggestions {
            destinationSuggestCalls += 1
            lastDestinationSuggestRequest = request
            return destinationSuggestionsResult.getOrThrow()
        }

        override suspend fun resolveDestination(
            sessionId: String,
            revision: Int,
            suggestion: DestinationSuggestion,
        ): ResolvedDestination {
            destinationResolveCalls += 1
            return resolvedDestinationResult.getOrThrow()
        }

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

        override suspend fun routeWithIntermediateStop(
            origin: Coordinate,
            intermediateStop: Coordinate,
            destination: Coordinate,
        ): RouteWithIntermediateStop {
            intermediateRouteCalls += 1
            lastIntermediateStop = intermediateStop
            return intermediateRouteResult.getOrThrow()
        }

        override suspend fun routeWithIntermediateStops(
            origin: Coordinate,
            intermediateStops: List<Coordinate>,
            destination: Coordinate,
        ): RouteWithIntermediateStops {
            lastIntermediateStops = intermediateStops
            lastIntermediateStop = intermediateStops.singleOrNull()
            intermediateRouteCalls += 1
            return multipleIntermediateRouteFactory?.invoke(intermediateStops)
                ?: intermediateRouteResult.getOrThrow().asMultiple()
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
