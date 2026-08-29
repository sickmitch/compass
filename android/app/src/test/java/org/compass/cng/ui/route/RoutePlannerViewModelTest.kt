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
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RankingBreakdown
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
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
    ) : RoutingRepository {
        var candidateCalls = 0
        var lastRangeKm = 0.0
        var lastDetourMinutes = 0.0
        lateinit var lastDepartureAt: OffsetDateTime
        var lastSelectedStationId: String? = null

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
