package org.compass.cng.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.compass.cng.testing.predictiveResponseFixture
import org.compass.cng.domain.model.Coordinate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompassApiClientTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postsStrictBaseRouteRequestAndMapsResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(SUCCESS_RESPONSE),
        )
        val client = CompassApiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            json = json,
        )

        val route = client.getRoute(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
        )

        assertEquals(210_925.0, route.distanceMeters, 0.0)
        assertEquals(6_773.406, route.durationSeconds, 0.0)
        assertEquals("valhalla", route.provider)
        assertEquals("Parti verso sud.", route.maneuvers.single().instruction)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/routes", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("auto", requestJson.getValue("costing").jsonPrimitive.content)
        assertEquals("it-IT", requestJson.getValue("language").jsonPrimitive.content)
        assertEquals(
            "45.4642",
            requestJson.getValue("origin").jsonObject.getValue("latitude").jsonPrimitive.content,
        )
    }

    @Test
    fun preservesMachineReadableHttpError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"route_not_found","message":"No route."}"""),
        )
        val client = CompassApiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            json = json,
        )

        val error = try {
            client.getRoute(
                origin = Coordinate(45.4642, 9.19),
                destination = Coordinate(44.4949, 11.3426),
            )
            error("expected ApiClientException.Http")
        } catch (error: ApiClientException.Http) {
            error
        }

        assertEquals(422, error.statusCode)
        assertEquals("route_not_found", error.code)
    }

    @Test
    fun postsRankedCandidatePolicyAndMapsArrivalAwareStation() = runTest {
        server.enqueue(successResponse(resource("ranked-candidates-response.json")))
        val client = client()

        val result = client.getRankedCngCandidates(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
            effectiveCngRangeKm = 300.0,
            maximumDetourMinutes = 10.0,
            departureAt = "2026-08-30T10:00:00+02:00",
        )

        assertEquals("not_configured", result.trafficState)
        assertEquals(1, result.candidates.size)
        val station = result.candidates.single()
        assertEquals("43690", station.mimitStationId)
        assertEquals("open", station.opening.state)
        assertEquals(1.599, station.price?.unitPrice ?: 0.0, 0.0)
        assertEquals(1, station.ranking.rank)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/cng/ranked-candidates", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(
            "300.0",
            requestJson.getValue("effective_cng_range_km").jsonPrimitive.content,
        )
        assertEquals(
            "10.0",
            requestJson.getValue("maximum_detour_minutes").jsonPrimitive.content,
        )
        assertEquals(
            "2026-08-30T10:00:00+02:00",
            requestJson.getValue("departure_at").jsonPrimitive.content,
        )
        assertFalse(requestJson.getValue("include_closed").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun postsOfficialMimitIdAndMapsOrderedSelectedRouteLegs() = runTest {
        server.enqueue(successResponse(resource("route-with-cng-stop-response.json")))
        val client = client()

        val result = client.getRouteWithCngStop(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
            mimitStationId = "43690",
        )

        assertEquals("43690", result.selectedStop.mimitStationId)
        assertEquals(2, result.legs.size)
        assertEquals("origin_to_cng_station", result.legs.first().kind)
        assertEquals("cng_station_to_destination", result.legs.last().kind)
        assertEquals(210_931.0, result.distanceMeters, 0.0)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/routes/with-cng-stop", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("43690", requestJson.getValue("mimit_station_id").jsonPrimitive.content)
    }

    @Test
    fun postsPredictiveRangeStateAndMapsOnlyReachableCandidates() = runTest {
        server.enqueue(
            successResponse(
                predictiveResponseFixture(resource("ranked-candidates-response.json")),
            ),
        )
        val client = client()

        val result = client.getPredictiveCngCandidates(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
            effectiveCngRangeKm = 300.0,
            estimatedRemainingCngRangeKm = 120.0,
            reserveCngRangeKm = 30.0,
            maximumDetourMinutes = 10.0,
            departureAt = "2026-08-30T10:00:00+02:00",
        )

        assertEquals("suggested", result.suggestionState)
        assertEquals(90.0, result.rangeBasis.usableRangeBeforeReserveKm, 0.0)
        assertFalse(result.rangeBasis.trafficAdjusted)
        assertEquals("43690", result.candidates.single().candidate.mimitStationId)
        assertEquals(96.894, result.candidates.single().estimatedRemainingRangeAtArrivalKm, 0.0)
        assertEquals(66.894, result.candidates.single().reserveMarginAtArrivalKm, 0.0)
        assertEquals("43690", result.itinerary?.stops?.single()?.mimitStationId)
        assertEquals("road_network", result.itinerary?.distanceModel)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/cng/predictive-candidates", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(
            "120.0",
            requestJson.getValue("estimated_remaining_cng_range_km").jsonPrimitive.content,
        )
        assertEquals(
            "30.0",
            requestJson.getValue("reserve_cng_range_km").jsonPrimitive.content,
        )
        assertFalse(requestJson.getValue("include_closed").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun postsOrderedMultiStopRangeRequestAndMapsValidatedLegs() = runTest {
        server.enqueue(successResponse(resource("route-with-cng-itinerary-response.json")))
        val client = client()

        val result = client.getRouteWithCngItinerary(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
            mimitStationIds = listOf("43690", "3473", "3618"),
            effectiveCngRangeKm = 100.0,
            estimatedRemainingCngRangeKm = 65.0,
            reserveCngRangeKm = 30.0,
        )

        assertEquals(3, result.selectedStops.size)
        assertEquals(4, result.legs.size)
        assertEquals("cng_station_to_cng_station", result.legs[1].kind)
        assertEquals(0.0, result.legs.last().reserveMarginAtArrivalKm, 0.0)
        assertEquals("all_legs_preserve_reserve", result.rangeValidation)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/routes/with-cng-itinerary", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(
            listOf("43690", "3473", "3618"),
            requestJson.getValue("mimit_station_ids").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("100.0", requestJson.getValue("effective_cng_range_km").jsonPrimitive.content)
        assertEquals(
            "65.0",
            requestJson.getValue("estimated_remaining_cng_range_km").jsonPrimitive.content,
        )
        assertEquals("30.0", requestJson.getValue("reserve_cng_range_km").jsonPrimitive.content)
    }

    @Test
    fun mapsDestinationReachablePredictiveResponseWithoutCandidates() = runTest {
        server.enqueue(
            successResponse(
                predictiveResponseFixture(
                    resource("ranked-candidates-response.json"),
                    suggestionState = "not_needed",
                ),
            ),
        )

        val result = client().getPredictiveCngCandidates(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
            effectiveCngRangeKm = 300.0,
            estimatedRemainingCngRangeKm = 300.0,
            reserveCngRangeKm = 30.0,
            maximumDetourMinutes = 10.0,
            departureAt = "2026-08-30T10:00:00+02:00",
        )

        assertEquals("not_needed", result.suggestionState)
        assertEquals(true, result.rangeBasis.destinationReachableWithReserve)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun rejectsPredictiveCandidateBeyondUsableRoadRange() = runTest {
        val response = predictiveResponseFixture(resource("ranked-candidates-response.json"))
            .replace("\"distance_from_previous_waypoint_meters\":23106.0", "\"distance_from_previous_waypoint_meters\":93000.0")
        server.enqueue(successResponse(response))

        val error = runCatching {
            client().getPredictiveCngCandidates(
                origin = Coordinate(45.4642, 9.19),
                destination = Coordinate(44.4949, 11.3426),
                effectiveCngRangeKm = 300.0,
                estimatedRemainingCngRangeKm = 120.0,
                reserveCngRangeKm = 30.0,
                maximumDetourMinutes = 10.0,
                departureAt = "2026-08-30T10:00:00+02:00",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsUnknownFieldsInPredictiveResponse() = runTest {
        val response = predictiveResponseFixture(resource("ranked-candidates-response.json"))
            .replaceFirst("{", "{\"unexpected\":true,")
        server.enqueue(successResponse(response))

        val error = runCatching {
            client().getPredictiveCngCandidates(
                origin = Coordinate(45.4642, 9.19),
                destination = Coordinate(44.4949, 11.3426),
                effectiveCngRangeKm = 300.0,
                estimatedRemainingCngRangeKm = 120.0,
                reserveCngRangeKm = 30.0,
                maximumDetourMinutes = 10.0,
                departureAt = "2026-08-30T10:00:00+02:00",
            )
        }.exceptionOrNull()

        assertEquals(ApiClientException.InvalidResponse::class.java, error?.javaClass)
    }

    @Test
    fun rejectsUnknownFieldsInRankedResponse() = runTest {
        val response = resource("ranked-candidates-response.json")
            .replaceFirst("{", "{\"unexpected\":true,")
        server.enqueue(successResponse(response))
        val client = client()

        val error = runCatching {
            client.getRankedCngCandidates(
                origin = Coordinate(45.4642, 9.19),
                destination = Coordinate(44.4949, 11.3426),
                effectiveCngRangeKm = 300.0,
                maximumDetourMinutes = 10.0,
                departureAt = "2026-08-30T10:00:00+02:00",
            )
        }.exceptionOrNull()

        assertEquals(ApiClientException.InvalidResponse::class.java, error?.javaClass)
        assertNull((error as? ApiClientException.Http)?.code)
    }

    private fun client() = CompassApiClient(
        baseUrl = server.url("/").toString(),
        httpClient = OkHttpClient(),
        json = json,
    )

    private fun successResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun resource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource(name),
    ).readText()

    private companion object {
        val SUCCESS_RESPONSE = """
            {
              "distance_meters": 210925.0,
              "duration_seconds": 6773.406,
              "geometry": {
                "format": "polyline6",
                "encoded_polyline": "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"
              },
              "maneuvers": [
                {
                  "type": 1,
                  "instruction": "Parti verso sud.",
                  "distance_meters": 100.0,
                  "duration_seconds": 18.0,
                  "begin_shape_index": 0,
                  "end_shape_index": 2,
                  "street_names": ["Via Roma"],
                  "verbal_transition_alert_instruction": null,
                  "verbal_pre_transition_instruction": null,
                  "verbal_post_transition_instruction": null,
                  "bearing_before": null,
                  "bearing_after": 181,
                  "travel_mode": "drive",
                  "travel_type": "car"
                }
              ],
              "provider": "valhalla"
            }
        """.trimIndent()
    }
}
