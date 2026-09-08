package org.compass.cng.data.navigation

import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
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
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteSpeedLimit
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.SelectedCngStop
import org.compass.cng.navigation.CachedNavigationRoute
import org.compass.cng.navigation.toNavigationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRouteDocumentCodecTest {
    private val codec = NavigationRouteDocumentCodec()

    @Test
    fun roundTripPreservesDownloadedGeometryManeuversStationAndTiming() {
        val route = routeWithStop().toNavigationRoute()
        val cached = CachedNavigationRoute(route, 1_725_000_000_000, navigationWasActive = true)

        val restored = requireNotNull(codec.decode(codec.encode(cached)))

        assertEquals(cached, restored)
        assertEquals("43690", restored.route.fuelStops.single().mimitStationId)
        assertEquals(OpeningState.OPEN, restored.route.fuelStops.single().opening?.state)
        assertEquals(1.599, restored.route.fuelStops.single().price?.unitPrice)
        assertEquals("+39 02 123456", restored.route.fuelStops.single().phone)
        assertEquals(1_200, restored.route.timing.totalRefuelingDwellSeconds.toInt())
        assertEquals(50, restored.route.speedLimits.first().speedLimitKph)
        assertEquals("valhalla_graph", restored.route.speedLimitSource)
        assertEquals(listOf(0, 1), restored.route.speedLimits.map { it.beginShapeIndex })
        assertEquals(listOf(1, 2), restored.route.speedLimits.map { it.endShapeIndex })
    }

    @Test
    fun invalidOrUnknownCacheDocumentsAreIgnored() {
        assertNull(codec.decode("not-json"))
        assertNull(codec.decode("""{"schemaVersion":99,"cachedAtEpochMillis":1,"navigationWasActive":true,"route":{}}"""))
    }

    @Test
    fun cacheWrittenBeforeStructuredGuidanceRemainsReadable() {
        val cached = CachedNavigationRoute(
            routeWithStop().toNavigationRoute(),
            1_725_000_000_000,
            navigationWasActive = true,
        )
        val legacyDocument = Json.parseToJsonElement(codec.encode(cached))
            .withoutStructuredGuidance()
            .withoutSpeedLimitContext()
            .withoutCngNavigationDetails()
            .toString()

        val restored = requireNotNull(codec.decode(legacyDocument))

        assertEquals(cached.route.routeId, restored.route.routeId)
        assertEquals(2, restored.route.maneuvers.size)
        assertEquals(true, restored.route.maneuvers.all { it.sign == null })
        assertEquals(true, restored.route.maneuvers.all { it.roundaboutExitCount == null })
        assertEquals(emptyList<RouteSpeedLimit>(), restored.route.speedLimits)
        assertNull(restored.route.speedLimitSource)
        assertNull(restored.route.fuelStops.single().opening)
        assertNull(restored.route.fuelStops.single().price)
    }

    private fun routeWithStop(): RouteWithCngStop {
        val origin = Coordinate(45.0, 9.0)
        val stop = Coordinate(44.5, 10.0)
        val destination = Coordinate(44.0, 11.0)
        val first = leg(origin, stop, "Verso il rifornimento")
        val second = leg(stop, destination, "Verso la destinazione")
        return RouteWithCngStop(
            selectedStop = SelectedCngStop(
                "43690", "S.ZENONE OVEST", "San Zenone", "MI", stop,
                OffsetDateTime.parse("2026-09-03T09:00:00+02:00"),
                1_200,
                opening = OpeningAtEta(
                    OpeningState.OPEN,
                    OpeningValidation.VALID,
                    "24/7",
                    "osm",
                    0.99,
                    OffsetDateTime.parse("2026-09-03T09:00:00+02:00"),
                    "Europe/Rome",
                    null,
                    emptyList(),
                ),
                phone = "+39 02 123456",
                price = CngPrice(
                    1.599,
                    "EUR",
                    "kg",
                    "self",
                    OffsetDateTime.parse("2026-09-03T08:00:00+02:00"),
                    OffsetDateTime.parse("2026-09-03T08:05:00+02:00"),
                    "mimit",
                    3_600.0,
                    PriceFreshness.FRESH,
                ),
            ),
            distanceMeters = 120_000.0,
            durationSeconds = 5_400.0,
            legs = listOf(
                CngRouteLeg(CngRouteLegKind.ORIGIN_TO_CNG_STATION, first),
                CngRouteLeg(CngRouteLegKind.CNG_STATION_TO_DESTINATION, second),
            ),
            provider = "valhalla",
            navigation = NavigationTiming(
                "cached-route", 5_400.0, 5_400.0, 1, 1_200, 1_200.0, 6_600.0,
                OffsetDateTime.parse("2026-09-03T08:00:00+02:00"),
                OffsetDateTime.parse("2026-09-03T09:30:00+02:00"),
                OffsetDateTime.parse("2026-09-03T09:50:00+02:00"),
                trafficDelaySeconds = 180.0,
                trafficDelayState = "estimated",
                trafficState = "fresh",
                trafficAware = true,
                trafficObservedAt = OffsetDateTime.parse("2026-09-03T07:59:30+02:00"),
            ),
        )
    }

    private fun leg(origin: Coordinate, destination: Coordinate, instruction: String) = RoutePreview(
        origin = origin,
        destination = destination,
        distanceMeters = 60_000.0,
        durationSeconds = 2_700.0,
        geometry = listOf(origin, destination),
        maneuvers = listOf(
            Maneuver(
                1, instruction, 60_000.0, 2_700.0, 0, 1, emptyList(),
                travelMode = "drive",
                travelType = "car",
                sign = ManeuverSign(
                    exitNumberElements = listOf(ManeuverSignElement("1", 2)),
                    exitBranchElements = listOf(ManeuverSignElement("A1")),
                ),
                roundaboutExitCount = 2,
            ),
        ),
        provider = "valhalla",
        speedLimits = listOf(RouteSpeedLimit(0, 1, 50)),
        speedLimitSource = "valhalla_graph",
    )
}

private fun JsonElement.withoutStructuredGuidance(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.mapNotNull { (key, value) ->
            if (key == "sign" || key == "roundaboutExitCount") {
                null
            } else {
                key to value.withoutStructuredGuidance()
            }
        }.toMap(),
    )
    is JsonArray -> JsonArray(map(JsonElement::withoutStructuredGuidance))
    else -> this
}


private fun JsonElement.withoutSpeedLimitContext(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.mapNotNull { (key, value) ->
            if (key == "speedLimits" || key == "speedLimitSource") {
                null
            } else {
                key to value.withoutSpeedLimitContext()
            }
        }.toMap(),
    )
    is JsonArray -> JsonArray(map(JsonElement::withoutSpeedLimitContext))
    else -> this
}

private fun JsonElement.withoutCngNavigationDetails(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.mapNotNull { (key, value) ->
            if (key in setOf("opening", "phone", "brand", "operator", "price")) {
                null
            } else {
                key to value.withoutCngNavigationDetails()
            }
        }.toMap(),
    )
    is JsonArray -> JsonArray(map(JsonElement::withoutCngNavigationDetails))
    else -> this
}
