package org.compass.cng.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.compass.cng.domain.model.Coordinate
import org.junit.After
import org.junit.Assert.assertEquals
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
