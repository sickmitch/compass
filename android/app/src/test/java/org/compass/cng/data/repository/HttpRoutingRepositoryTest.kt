package org.compass.cng.data.repository

import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.testing.predictiveResponseFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpRoutingRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpRoutingRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = HttpRoutingRepository(
            CompassApiClient(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = false },
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun mapsRankedCandidatesIntoDomainWithoutLosingExplainability() = runTest {
        enqueue("ranked-candidates-response.json")

        val result = repository.rankedCngStations(
            origin = MILAN,
            destination = BOLOGNA,
            effectiveCngRangeKm = 300.0,
            maximumDetourMinutes = 10.0,
            departureAt = OffsetDateTime.parse("2026-08-30T10:00:00+02:00"),
        )

        val station = result.candidates.single()
        assertEquals("43690", station.mimitStationId)
        assertEquals(OpeningState.OPEN, station.opening.state)
        assertEquals("24/7", station.opening.openingHours)
        assertEquals(PriceFreshness.FRESH, station.price?.freshness)
        assertEquals(1.599, station.price?.unitPrice ?: 0.0, 0.0)
        assertEquals(23_106.0, station.distanceFromPreviousWaypointMeters, 0.0)
        assertEquals(1, station.ranking.rank)
        assertTrue(result.baseRoute.geometry.size >= 2)
    }

    @Test
    fun mapsSelectedStopToTwoOrderedDomainLegsAndCombinedPreview() = runTest {
        enqueue("route-with-cng-stop-response.json")

        val result = repository.routeWithCngStop(
            origin = MILAN,
            destination = BOLOGNA,
            mimitStationId = "43690",
        )

        assertEquals("43690", result.selectedStop.mimitStationId)
        assertEquals(
            listOf(
                CngRouteLegKind.ORIGIN_TO_CNG_STATION,
                CngRouteLegKind.CNG_STATION_TO_DESTINATION,
            ),
            result.legs.map { it.kind },
        )
        assertEquals(2, result.asRoutePreview().maneuvers.size)
        assertEquals(MILAN, result.asRoutePreview().origin)
        assertEquals(BOLOGNA, result.asRoutePreview().destination)
    }

    @Test
    fun mapsPredictiveReachabilityWithoutLosingReserveMargins() = runTest {
        enqueueBody(predictiveResponseFixture(resource("ranked-candidates-response.json")))

        val result = repository.predictiveCngStations(
            origin = MILAN,
            destination = BOLOGNA,
            effectiveCngRangeKm = 300.0,
            estimatedRemainingCngRangeKm = 120.0,
            reserveCngRangeKm = 30.0,
            maximumDetourMinutes = 10.0,
            departureAt = OffsetDateTime.parse("2026-08-30T10:00:00+02:00"),
        )

        assertEquals(PredictiveSuggestionState.SUGGESTED, result.state)
        assertEquals(90.0, result.rangeBasis.usableRangeBeforeReserveKm, 0.0)
        assertEquals("caller_estimated_remaining_range", result.rangeBasis.consumptionModel)
        assertEquals("43690", result.candidates.single().station.mimitStationId)
        assertEquals(96.894, result.candidates.single().estimatedRemainingRangeAtArrivalKm, 0.0)
        assertEquals(66.894, result.candidates.single().reserveMarginAtArrivalKm, 0.0)
        assertEquals("43690", result.itinerary?.stops?.single()?.station?.mimitStationId)
        assertEquals(82.176, result.itinerary?.destinationLeg?.reserveMarginAtArrivalKm ?: 0.0, 0.0)
    }

    @Test
    fun mapsMultiStopRouteAndPreservesEveryRangeMargin() = runTest {
        enqueue("route-with-cng-itinerary-response.json")

        val result = repository.routeWithCngItinerary(
            origin = MILAN,
            destination = BOLOGNA,
            mimitStationIds = listOf("43690", "3473", "3618"),
            effectiveCngRangeKm = 100.0,
            estimatedRemainingCngRangeKm = 65.0,
            reserveCngRangeKm = 30.0,
        )

        assertEquals(listOf("43690", "3473", "3618"), result.selectedStops.map { it.mimitStationId })
        assertEquals(
            listOf(
                CngRouteLegKind.ORIGIN_TO_CNG_STATION,
                CngRouteLegKind.CNG_STATION_TO_CNG_STATION,
                CngRouteLegKind.CNG_STATION_TO_CNG_STATION,
                CngRouteLegKind.CNG_STATION_TO_DESTINATION,
            ),
            result.legs.map { it.kind },
        )
        assertEquals(listOf(15.0, 10.0, 10.0, 0.0), result.legs.map { it.reserveMarginAtArrivalKm })
        assertEquals(210_000.0, result.asRoutePreview().distanceMeters, 0.0)
    }

    @Test
    fun rejectsZeroPredictiveRemainingRangeBeforeNetworkRequest() = runTest {
        val error = runCatching {
            repository.predictiveCngStations(
                origin = MILAN,
                destination = BOLOGNA,
                effectiveCngRangeKm = 300.0,
                estimatedRemainingCngRangeKm = 0.0,
                reserveCngRangeKm = 0.0,
                maximumDetourMinutes = 10.0,
                departureAt = OffsetDateTime.parse("2026-08-30T10:00:00+02:00"),
            )
        }.exceptionOrNull()

        assertTrue(error is RoutePreviewException)
        assertEquals(RoutePreviewFailure.INVALID_RESPONSE, (error as RoutePreviewException).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsSelectedStopTotalsOutsideValhallaSourcePrecision() = runTest {
        val body = resource("route-with-cng-stop-response.json")
            .replace("\"distance_meters\": 210931.0", "\"distance_meters\": 210933.0")
        enqueueBody(body)

        val error = runCatching {
            repository.routeWithCngStop(
                origin = MILAN,
                destination = BOLOGNA,
                mimitStationId = "43690",
            )
        }.exceptionOrNull()

        assertTrue(error is RoutePreviewException)
        assertEquals(RoutePreviewFailure.INVALID_RESPONSE, (error as RoutePreviewException).failure)
    }

    private fun enqueue(resourceName: String) {
        enqueueBody(resource(resourceName))
    }

    private fun enqueueBody(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun resource(resourceName: String): String =
        requireNotNull(javaClass.classLoader?.getResource(resourceName)).readText()

    private companion object {
        val MILAN = Coordinate(45.4642, 9.19)
        val BOLOGNA = Coordinate(44.4949, 11.3426)
    }
}
