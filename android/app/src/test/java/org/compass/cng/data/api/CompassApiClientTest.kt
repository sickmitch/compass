package org.compass.cng.data.api

import java.net.SocketTimeoutException
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
import org.compass.cng.domain.model.DestinationSearchContext
import org.compass.cng.domain.model.DestinationSearchBounds
import org.compass.cng.domain.server.ServerConnection
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
        assertEquals("route_1234567890abcdef1234567890abcdef", route.navigation.routeId)
        assertEquals(6_773.406, route.navigation.totalTripDurationSeconds, 0.0)
        assertEquals(420.0, route.navigation.trafficDelaySeconds)
        assertEquals("fresh", route.navigation.trafficState)
        assertTrue(route.navigation.trafficAware)
        assertEquals(181, route.maneuvers.single().bearingAfter)
        assertEquals("1", route.maneuvers.single().sign?.exitNumberElements?.single()?.text)
        assertEquals(2, route.maneuvers.single().roundaboutExitCount)
        assertEquals(50, route.speedLimits.single().speedLimitKph)
        assertEquals("valhalla_graph", route.speedLimitSource)

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
    fun postsIntermediateStopRouteAndKeepsZeroFuelDwell() = runTest {
        server.enqueue(successResponse(INTERMEDIATE_STOP_RESPONSE))
        val client = client()
        val origin = Coordinate(45.0, 9.0)
        val stop = Coordinate(45.1, 9.2)
        val destination = Coordinate(45.2, 9.4)

        val route = client.getRouteWithIntermediateStop(origin, stop, destination)

        assertEquals(stop, route.intermediateStop)
        assertEquals(2, route.legs.size)
        assertEquals(200.0, route.durationSeconds, 0.0)
        assertEquals(0, route.navigation.refuelingStopCount)
        assertEquals(200.0, route.navigation.totalTripDurationSeconds, 0.0)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/routes/with-intermediate-stop", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(
            "45.1",
            requestJson.getValue("intermediate_stop").jsonObject
                .getValue("latitude").jsonPrimitive.content,
        )
    }

    @Test
    fun postsOrderedIntermediateStopsAndMapsEveryLeg() = runTest {
        server.enqueue(successResponse(INTERMEDIATE_STOPS_RESPONSE))
        val client = client()
        val origin = Coordinate(45.0, 9.0)
        val stops = listOf(Coordinate(45.1, 9.2), Coordinate(45.15, 9.3))
        val destination = Coordinate(45.2, 9.4)

        val route = client.getRouteWithIntermediateStops(origin, stops, destination)

        assertEquals(stops, route.intermediateStops)
        assertEquals(3, route.legs.size)
        assertEquals(0, route.navigation.refuelingStopCount)
        assertEquals(300.0, route.navigation.totalTripDurationSeconds, 0.0)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/routes/with-intermediate-stops", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(2, requestJson.getValue("intermediate_stops").jsonArray.size)
    }

    @Test
    fun searchesPlacesWithGetAndMapsPoiMetadata() = runTest {
        server.enqueue(
            successResponse(
                """
                {
                  "query":"Duomo di Milano",
                  "cacheable":false,
                  "results":[{
                    "result_id":"nominatim:node:123",
                    "display_name":"Duomo di Milano, Milano, Italia",
                    "address":"Piazza del Duomo, Milano",
                    "location":{"latitude":45.4641,"longitude":9.1919},
                    "kind":"poi",
                    "category":"place_of_worship",
                    "poi_name":"Duomo di Milano",
                    "provider":"nominatim",
                    "provider_place_id":"node:123"
                  }]
                }
                """.trimIndent(),
            ),
        )

        val result = client().searchPlaces("Duomo di Milano")

        assertEquals("Duomo di Milano", result.query)
        assertFalse(result.cacheable)
        assertEquals("poi", result.results.single().kind)
        assertEquals(45.4641, result.results.single().latitude, 0.0)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("Duomo di Milano", recorded.requestUrl?.queryParameter("q"))
        assertEquals("it", recorded.requestUrl?.queryParameter("language"))
    }

    @Test
    fun suggestsThenResolvesDestinationWithSeparateMapSafeTarget() = runTest {
        server.enqueue(
            successResponse(
                """
                {
                  "session_id":"9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
                  "revision":4,
                  "provider":"google_places_new",
                  "maximum_results":5,
                  "results":[{
                    "id":"google_places_new:place-a",
                    "provider":"google_places_new",
                    "provider_ref":"place-a",
                    "kind":"business",
                    "title":"Libreria Verona",
                    "subtitle":"Via Roma 12, Verona",
                    "address_preview":"Via Roma 12, Verona",
                    "distance_meters":321,
                    "provider_rank":0,
                    "requires_resolution":true,
                    "attribution":"Google Maps"
                  }]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            successResponse(
                """
                {
                  "session_id":"9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
                  "revision":4,
                  "selection":{
                    "provider":"google_places_new",
                    "provider_ref":"place-a",
                    "formatted_address":"Via Roma 12, 37100 Verona VR, Italia",
                    "address_components":[{
                      "long_text":"12","short_text":"12","types":["street_number"]
                    }],
                    "normalized_address":{
                      "street":"Via Roma","street_number":"12","locality":"Verona",
                      "province":"Verona","region":"Veneto","postal_code":"37100",
                      "country":"Italia"
                    },
                    "location":{"latitude":45.44,"longitude":10.99},
                    "kind":"business",
                    "resolution_status":"resolved",
                    "attribution":["Google Maps"],
                    "field_sources":{
                      "formatted_address":"google_places_new",
                      "address_components":"google_places_new",
                      "location":"google_places_new"
                    }
                  },
                  "navigation_target":{
                    "location":{"latitude":45.44,"longitude":10.99},
                    "provider_ref":"place-a",
                    "map_label":"Destinazione selezionata"
                  }
                }
                """.trimIndent(),
            ),
        )
        val request = ApiDestinationSuggestRequest(
            query = "Libreria Verona",
            sessionId = "9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
            revision = 4,
            context = DestinationSearchContext(Coordinate(45.4384, 10.9916), 25_000.0),
        )

        val suggestions = client().suggestDestinations(request)
        val resolved = client().resolveDestination(
            request.sessionId,
            request.revision,
            suggestions.results.single().provider,
            suggestions.results.single().providerRef,
        )

        assertEquals("Libreria Verona", suggestions.results.single().title)
        assertEquals(321, suggestions.results.single().distanceMeters)
        assertEquals("Via Roma 12, 37100 Verona VR, Italia", resolved.formattedAddress)
        assertEquals("12", resolved.normalizedAddress.streetNumber)
        assertEquals(Coordinate(45.44, 10.99), resolved.navigationCoordinate)
        assertEquals("Destinazione selezionata", resolved.mapLabel)

        val suggestRequest = server.takeRequest()
        assertEquals("/api/v1/destinations/suggest", suggestRequest.path)
        val suggestJson = json.parseToJsonElement(suggestRequest.body.readUtf8()).jsonObject
        assertEquals("4", suggestJson.getValue("revision").jsonPrimitive.content)
        assertEquals(
            "25000.0",
            suggestJson.getValue("context").jsonObject
                .getValue("bias_radius_meters").jsonPrimitive.content,
        )
        val resolveRequest = server.takeRequest()
        assertEquals("/api/v1/destinations/resolve", resolveRequest.path)
        val resolveJson = json.parseToJsonElement(resolveRequest.body.readUtf8()).jsonObject
        assertEquals("place-a", resolveJson.getValue("provider_ref").jsonPrimitive.content)
    }

    @Test
    fun sendsRouteBoundsForAlongRouteDestinationSearch() = runTest {
        server.enqueue(
            successResponse(
                """
                {
                  "session_id":"9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
                  "revision":1,
                  "provider":"google_places_new",
                  "maximum_results":5,
                  "results":[]
                }
                """.trimIndent(),
            ),
        )
        client().suggestDestinations(
            ApiDestinationSuggestRequest(
                query = "ristorante",
                sessionId = "9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
                revision = 1,
                context = DestinationSearchContext(
                    location = Coordinate(45.1, 9.2),
                    routeBounds = DestinationSearchBounds(
                        southWest = Coordinate(44.9, 8.8),
                        northEast = Coordinate(45.3, 9.6),
                    ),
                ),
            ),
        )

        val recorded = server.takeRequest()
        val context = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            .getValue("context").jsonObject
        assertFalse(context.containsKey("bias_radius_meters"))
        val bounds = context.getValue("route_bounds").jsonObject
        assertEquals(
            "44.9",
            bounds.getValue("south_west").jsonObject
                .getValue("latitude").jsonPrimitive.content,
        )
        assertEquals(
            "9.6",
            bounds.getValue("north_east").jsonObject
                .getValue("longitude").jsonPrimitive.content,
        )
    }

    @Test
    fun rejectsDestinationResponseThatLeaksGoogleTextIntoMapTarget() = runTest {
        server.enqueue(
            successResponse(
                """
                {
                  "session_id":"9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
                  "revision":4,
                  "selection":{
                    "provider":"google_places_new","provider_ref":"place-a",
                    "formatted_address":"Via Roma 12","address_components":[],
                    "normalized_address":{
                      "street":"Via Roma","street_number":"12","locality":null,
                      "province":null,"region":null,"postal_code":null,"country":null
                    },
                    "location":{"latitude":45.44,"longitude":10.99},
                    "kind":"address","resolution_status":"resolved",
                    "attribution":["Google Maps"],
                    "field_sources":{
                      "formatted_address":"google_places_new",
                      "address_components":"google_places_new",
                      "location":"google_places_new"
                    }
                  },
                  "navigation_target":{
                    "location":{"latitude":45.44,"longitude":10.99},
                    "provider_ref":"place-a","map_label":"Via Roma 12"
                  }
                }
                """.trimIndent(),
            ),
        )

        val failure = runCatching {
            client().resolveDestination(
                "9b6c53a0-3e77-4c73-92cb-1cbb2fbd67da",
                4,
                "google_places_new",
                "place-a",
            )
        }.exceptionOrNull()

        assertTrue(failure is ApiClientException.InvalidResponse)
    }

    @Test
    fun readsEndpointAndBasicCredentialsForEveryRequest() = runTest {
        val secondServer = MockWebServer()
        secondServer.start()
        try {
            server.enqueue(successResponse(SUCCESS_RESPONSE))
            secondServer.enqueue(successResponse(SUCCESS_RESPONSE))
            var connection = ServerConnection.create(
                baseUrl = server.url("/").toString(),
                username = "first-user",
                password = "first-password",
                allowInsecureHttp = true,
                requireCredentials = true,
            )
            val client = CompassApiClient(
                connectionProvider = { connection },
                httpClient = OkHttpClient(),
                json = json,
            )

            client.getRoute(Coordinate(45.4642, 9.19), Coordinate(44.4949, 11.3426))
            val firstRequest = server.takeRequest()
            assertEquals(
                okhttp3.Credentials.basic("first-user", "first-password", Charsets.UTF_8),
                firstRequest.headers["Authorization"],
            )

            connection = ServerConnection.create(
                baseUrl = secondServer.url("proxy/").toString(),
                username = "second-user",
                password = "second-password",
                allowInsecureHttp = true,
                requireCredentials = true,
            )
            client.getRoute(Coordinate(45.4642, 9.19), Coordinate(44.4949, 11.3426))
            val secondRequest = secondServer.takeRequest()
            assertEquals("/proxy/api/v1/routes", secondRequest.path)
            assertEquals(
                okhttp3.Credentials.basic("second-user", "second-password", Charsets.UTF_8),
                secondRequest.headers["Authorization"],
            )
        } finally {
            secondServer.shutdown()
        }
    }

    @Test
    fun logsBoundedRequestOutcomeWithoutPayload() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(SUCCESS_RESPONSE),
        )
        val events = mutableListOf<String>()
        val timestamps = listOf(1_000_000_000L, 1_125_000_000L).iterator()
        val client = CompassApiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            json = json,
            eventLogger = events::add,
            monotonicNanos = { timestamps.next() },
        )

        client.getRoute(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
        )

        assertEquals(
            listOf(
                "request started: method=POST endpoint=/api/v1/routes " +
                    "call_timeout_ms=0 read_timeout_ms=10000",
                "request completed: method=POST endpoint=/api/v1/routes status=200 " +
                    "duration_ms=125",
                "route decoded: distance_meters=210925 duration_seconds=6773 maneuvers=1 " +
                    "traffic_state=fresh traffic_aware=true traffic_delay_seconds=420",
            ),
            events,
        )
        assertFalse(events.any { "45.4642" in it || "encoded_polyline" in it })
    }

    @Test
    fun logsPlaceSearchGetSoDeviceGateCanRetainEvidence() = runTest {
        server.enqueue(
            successResponse(
                """
                {"query":"Bologna","results":[]}
                """.trimIndent(),
            ),
        )
        val events = mutableListOf<String>()
        val timestamps = listOf(3_000_000_000L, 3_010_000_000L).iterator()
        val client = CompassApiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            json = json,
            eventLogger = events::add,
            monotonicNanos = { timestamps.next() },
        )

        client.searchPlaces("Bologna")

        assertEquals(
            listOf(
                "request started: method=GET endpoint=/api/v1/places/search",
                "request completed: method=GET endpoint=/api/v1/places/search status=200 " +
                    "duration_ms=10",
            ),
            events,
        )
    }

    @Test
    fun rejectsZeroCostRouteResponseInsteadOfPresentingDegradedNavigation() = runTest {
        server.enqueue(
            successResponse(
                SUCCESS_RESPONSE
                    .replace("\"distance_meters\": 210925.0", "\"distance_meters\": 0.0")
                    .replace("\"duration_seconds\": 6773.406", "\"duration_seconds\": 0.0"),
            ),
        )

        val error = runCatching {
            client().getRoute(
                origin = Coordinate(45.4642, 9.19),
                destination = Coordinate(44.4949, 11.3426),
            )
        }.exceptionOrNull()

        assertTrue(error is ApiClientException.InvalidResponse)
    }

    @Test
    fun logsNetworkExceptionClassAndDuration() = runTest {
        val events = mutableListOf<String>()
        val timestamps = listOf(2_000_000_000L, 2_250_000_000L).iterator()
        val client = CompassApiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient.Builder()
                .addInterceptor { throw SocketTimeoutException("test timeout") }
                .build(),
            json = json,
            eventLogger = events::add,
            monotonicNanos = { timestamps.next() },
        )

        val error = runCatching {
            client.getRoute(
                origin = Coordinate(45.4642, 9.19),
                destination = Coordinate(44.4949, 11.3426),
            )
        }.exceptionOrNull()

        assertTrue(error is ApiClientException.Network)
        assertEquals(
            "request failed: method=POST endpoint=/api/v1/routes kind=network " +
                "duration_ms=250 cause=SocketTimeoutException",
            events.last(),
        )
        assertFalse(events.any { "test timeout" in it })
    }

    @Test
    fun givesOnlyPredictiveEvaluationAnExtendedTimeout() = runTest {
        server.enqueue(successResponse(predictiveResponseFixture(resource("ranked-candidates-response.json"))))
        val events = mutableListOf<String>()
        val client = CompassApiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            json = json,
            eventLogger = events::add,
        )

        client.getPredictiveCngCandidates(
            origin = Coordinate(45.4642, 9.19),
            destination = Coordinate(44.4949, 11.3426),
            effectiveCngRangeKm = 300.0,
            estimatedRemainingCngRangeKm = 120.0,
            reserveCngRangeKm = 30.0,
            maximumDetourMinutes = 10.0,
            departureAt = "2026-08-30T10:00:00+02:00",
        )

        assertEquals(
            "request started: method=POST endpoint=/api/v1/cng/predictive-candidates " +
                "call_timeout_ms=240000 read_timeout_ms=240000",
            events.first(),
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
        assertEquals(50, result.legs.first().speedLimits.single().speedLimitKph)
        assertEquals("valhalla_graph", result.legs.first().speedLimitSource)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/routes/with-cng-stop", recorded.path)
        val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("43690", requestJson.getValue("mimit_station_id").jsonPrimitive.content)
    }

    @Test
    fun postsPredictiveRangeStateAndMapsOnlyReachableCandidates() = runTest {
        server.enqueue(
            successResponse(
                predictiveResponseFixture(
                    resource("ranked-candidates-response.json"),
                    excludedMimitStationIds = listOf("1001"),
                ),
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
            excludedMimitStationIds = setOf("1001"),
        )

        assertEquals("suggested", result.suggestionState)
        assertEquals(listOf("1001"), result.excludedMimitStationIds)
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
        assertEquals(
            "1001",
            requestJson.getValue("excluded_mimit_station_ids")
                .jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun postsAndMapsExplicitGasolineFallback() = runTest {
        server.enqueue(
            successResponse(
                predictiveResponseFixture(
                    resource("ranked-candidates-response.json"),
                    suggestionState = "gasoline_fallback",
                ),
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
            estimatedRemainingGasolineRangeKm = 200.0,
            reserveGasolineRangeKm = 30.0,
        )

        assertEquals("gasoline_fallback", result.suggestionState)
        assertEquals(120.925, result.gasolineFallback?.requiredGasolineRangeKm ?: -1.0, 0.0)
        assertEquals("direct_after_cng_reserve", result.gasolineFallback?.strategy)
        val request = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "200.0",
            request.getValue("estimated_remaining_gasoline_range_km").jsonPrimitive.content,
        )
        assertEquals(
            "30.0",
            request.getValue("reserve_gasoline_range_km").jsonPrimitive.content,
        )
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
        assertEquals(listOf(50, 90, 110, 130), result.legs.map {
            it.speedLimits.single().speedLimitKph
        })

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
                  "travel_type": "car",
                  "sign": {
                    "exit_number_elements": [{"text":"1","consecutive_count":2}],
                    "exit_branch_elements": [{"text":"A1","consecutive_count":null}],
                    "exit_toward_elements": [{"text":"Bologna","consecutive_count":null}],
                    "exit_name_elements": []
                  },
                  "roundabout_exit_count": 2
                }
              ],
              "speed_limits": [
                {
                  "begin_shape_index": 0,
                  "end_shape_index": 2,
                  "speed_limit_kph": 50
                }
              ],
              "speed_limit_source": "valhalla_graph",
              "provider": "valhalla",
              "navigation": {
                "route_id": "route_1234567890abcdef1234567890abcdef",
                "driving_duration_seconds": 6773.406,
                "remaining_driving_duration_seconds": 6773.406,
                "refueling_stop_count": 0,
                "dwell_seconds_per_refueling_stop": 1200,
                "total_refueling_dwell_seconds": 0.0,
                "total_trip_duration_seconds": 6773.406,
                "departure_at": "2026-09-02T08:00:00+02:00",
                "driving_arrival_at": "2026-09-02T09:52:53.406+02:00",
                "trip_arrival_at": "2026-09-02T09:52:53.406+02:00",
                "traffic_delay_seconds": 420.0,
                "traffic_delay_state": "estimated",
                "traffic_state": "fresh",
                "traffic_aware": true,
                "traffic_observed_at": "2026-09-02T07:59:30+02:00"
              }
            }
        """.trimIndent()

        val INTERMEDIATE_STOP_RESPONSE = """
            {
              "intermediate_stop":{"latitude":45.1,"longitude":9.2},
              "distance_meters":2000.0,
              "duration_seconds":200.0,
              "legs":[
                {
                  "kind":"origin_to_intermediate_stop",
                  "origin":{"latitude":45.0,"longitude":9.0},
                  "destination":{"latitude":45.1,"longitude":9.2},
                  "distance_meters":1000.0,"duration_seconds":100.0,
                  "geometry":{"format":"polyline6","encoded_polyline":"_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"},
                  "maneuvers":[],"speed_limits":[],"speed_limit_source":"valhalla_graph"
                },
                {
                  "kind":"intermediate_stop_to_destination",
                  "origin":{"latitude":45.1,"longitude":9.2},
                  "destination":{"latitude":45.2,"longitude":9.4},
                  "distance_meters":1000.0,"duration_seconds":100.0,
                  "geometry":{"format":"polyline6","encoded_polyline":"_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"},
                  "maneuvers":[],"speed_limits":[],"speed_limit_source":"valhalla_graph"
                }
              ],
              "provider":"valhalla",
              "navigation":{
                "route_id":"route_abcdef1234567890abcdef1234567890",
                "driving_duration_seconds":200.0,
                "remaining_driving_duration_seconds":200.0,
                "refueling_stop_count":0,
                "dwell_seconds_per_refueling_stop":1200,
                "total_refueling_dwell_seconds":0.0,
                "total_trip_duration_seconds":200.0,
                "departure_at":null,"driving_arrival_at":null,"trip_arrival_at":null,
                "traffic_delay_seconds":null,"traffic_delay_state":"unavailable",
                "traffic_state":"not_configured","traffic_aware":false,
                "traffic_observed_at":null
              }
            }
        """.trimIndent()

        val INTERMEDIATE_STOPS_RESPONSE = """
            {
              "intermediate_stops":[
                {"latitude":45.1,"longitude":9.2},
                {"latitude":45.15,"longitude":9.3}
              ],
              "distance_meters":3000.0,
              "duration_seconds":300.0,
              "legs":[
                {
                  "sequence":1,"kind":"origin_to_intermediate_stop",
                  "origin":{"latitude":45.0,"longitude":9.0},
                  "destination":{"latitude":45.1,"longitude":9.2},
                  "distance_meters":1000.0,"duration_seconds":100.0,
                  "geometry":{"format":"polyline6","encoded_polyline":"_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"},
                  "maneuvers":[],"speed_limits":[],"speed_limit_source":"valhalla_graph"
                },
                {
                  "sequence":2,"kind":"intermediate_stop_to_intermediate_stop",
                  "origin":{"latitude":45.1,"longitude":9.2},
                  "destination":{"latitude":45.15,"longitude":9.3},
                  "distance_meters":1000.0,"duration_seconds":100.0,
                  "geometry":{"format":"polyline6","encoded_polyline":"_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"},
                  "maneuvers":[],"speed_limits":[],"speed_limit_source":"valhalla_graph"
                },
                {
                  "sequence":3,"kind":"intermediate_stop_to_destination",
                  "origin":{"latitude":45.15,"longitude":9.3},
                  "destination":{"latitude":45.2,"longitude":9.4},
                  "distance_meters":1000.0,"duration_seconds":100.0,
                  "geometry":{"format":"polyline6","encoded_polyline":"_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"},
                  "maneuvers":[],"speed_limits":[],"speed_limit_source":"valhalla_graph"
                }
              ],
              "provider":"valhalla",
              "navigation":{
                "route_id":"route_1234567890abcdef1234567890abcdef",
                "driving_duration_seconds":300.0,
                "remaining_driving_duration_seconds":300.0,
                "refueling_stop_count":0,
                "dwell_seconds_per_refueling_stop":1200,
                "total_refueling_dwell_seconds":0.0,
                "total_trip_duration_seconds":300.0,
                "departure_at":null,"driving_arrival_at":null,"trip_arrival_at":null,
                "traffic_delay_seconds":null,"traffic_delay_state":"unavailable",
                "traffic_state":"not_configured","traffic_aware":false,
                "traffic_observed_at":null
              }
            }
        """.trimIndent()
    }
}
