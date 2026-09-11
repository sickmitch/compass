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
import org.compass.cng.navigation.NavigationFuelStopVisit
import org.compass.cng.navigation.NavigationIntermediateStop
import org.compass.cng.navigation.NavigationIntermediateStopVisit
import org.compass.cng.navigation.NavigationLocationMode
import org.compass.cng.navigation.NavigationPosition
import org.compass.cng.navigation.NavigationProgressSnapshot
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
    fun roundTripPreservesActiveProgressAndRefuellingVisit() {
        val route = routeWithStop().toNavigationRoute()
        val visit = NavigationFuelStopVisit(
            stop = route.fuelStops.single(),
            arrivedAtEpochMillis = 2_000L,
            plannedCompletionAtEpochMillis = 1_202_000L,
            remainingDwellSeconds = 900.0,
        )
        val progress = NavigationProgressSnapshot(
            savedAtEpochMillis = 302_000L,
            navigationPosition = NavigationPosition(
                route.geometry[1], 1, 0.0, 90.0, 4.0, 302_000L,
            ),
            routeProgressFraction = 0.5,
            distanceRemainingMeters = 60_000.0,
            drivingDurationRemainingSeconds = 2_700.0,
            totalDurationRemainingSeconds = 3_600.0,
            estimatedArrivalAtEpochMillis = 3_902_000L,
            currentRoadName = "Verso la destinazione",
            currentManeuverIndex = 1,
            nextManeuverIndex = null,
            distanceToNextManeuverMeters = 500.0,
            completedFuelStopSequences = emptySet(),
            activeFuelStopVisit = visit,
            lastCompletedFuelStopSequence = null,
            lastSpokenInstruction = "Sei arrivato al rifornimento.",
            voiceGuidanceEnabled = false,
            lastSuccessfulRouteRefreshEpochMillis = 1_000L,
            locationMode = NavigationLocationMode.DEMO_REPLAY,
        )
        val cached = CachedNavigationRoute(route, 302_000L, true, progress)

        val restored = requireNotNull(codec.decode(codec.encode(cached)))

        assertEquals(cached, restored)
        assertEquals("43690", restored.progress?.activeFuelStopVisit?.stop?.mimitStationId)
        assertEquals(NavigationLocationMode.DEMO_REPLAY, restored.progress?.locationMode)
        assertEquals(900.0, restored.progress?.activeFuelStopVisit?.remainingDwellSeconds ?: -1.0, 0.0)
    }

    @Test
    fun roundTripPreservesIntermediateStopAndPausedVisit() {
        val stop = NavigationIntermediateStop(1, Coordinate(44.7, 10.3))
        val route = routeWithStop().toNavigationRoute().copy(
            fuelStops = emptyList(),
            intermediateStops = listOf(stop),
        )
        val visit = NavigationIntermediateStopVisit(stop, arrivedAtEpochMillis = 5_000L)
        val progress = NavigationProgressSnapshot(
            savedAtEpochMillis = 6_000L,
            navigationPosition = null,
            routeProgressFraction = 0.4,
            distanceRemainingMeters = 72_000.0,
            drivingDurationRemainingSeconds = 3_240.0,
            totalDurationRemainingSeconds = 3_240.0,
            estimatedArrivalAtEpochMillis = 3_246_000L,
            currentRoadName = null,
            currentManeuverIndex = 0,
            nextManeuverIndex = 1,
            distanceToNextManeuverMeters = 0.0,
            completedFuelStopSequences = emptySet(),
            activeFuelStopVisit = null,
            lastCompletedFuelStopSequence = null,
            lastSpokenInstruction = null,
            voiceGuidanceEnabled = true,
            lastSuccessfulRouteRefreshEpochMillis = null,
            activeIntermediateStopVisit = visit,
        )
        val cached = CachedNavigationRoute(route, 6_000L, true, progress)

        val restored = requireNotNull(codec.decode(codec.encode(cached)))

        assertEquals(cached, restored)
        assertEquals(stop, restored.route.intermediateStops.single())
        assertEquals(visit, restored.progress?.activeIntermediateStopVisit)
    }

    @Test
    fun cacheWrittenBeforeStructuredGuidanceRemainsReadable() {
        val cached = CachedNavigationRoute(
            routeWithStop().toNavigationRoute(),
            1_725_000_000_000,
            navigationWasActive = true,
        )
        val legacyDocument = Json.parseToJsonElement(
            codec.encode(cached).replace("\"schemaVersion\":3", "\"schemaVersion\":1"),
        )
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
