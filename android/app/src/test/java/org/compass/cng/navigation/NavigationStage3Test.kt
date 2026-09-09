package org.compass.cng.navigation

import java.time.OffsetDateTime
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStage3Test {
    @Test
    fun voiceGuidanceCanBeDisabledAndReenabledDuringNavigation() {
        val engine = NavigationEngine()
        engine.preview(route("route_voice_toggle"))
        engine.start(nowEpochMillis = 1_000)

        engine.setVoiceGuidanceEnabled(false)
        assertEquals(false, engine.state.value.voiceGuidanceEnabled)

        engine.setVoiceGuidanceEnabled(true)
        assertTrue(engine.state.value.voiceGuidanceEnabled)
    }

    @Test
    fun maneuverAnnouncementsAdvanceByTimeAndDistanceWithoutDuplicates() {
        val route = route("route_stage_3_voice")
        val controller = ManeuverController()
        val base = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            currentManeuver = route.maneuvers.first(),
            navigationPosition = position(route.origin, speed = 10.0),
        )

        assertEquals(
            AnnouncementStage.EARLY,
            controller.nextAnnouncement(base.copy(distanceToNextManeuverMeters = 600.0))?.stage,
        )
        assertNull(controller.nextAnnouncement(base.copy(distanceToNextManeuverMeters = 590.0)))
        assertEquals(
            AnnouncementStage.PREPARE,
            controller.nextAnnouncement(base.copy(distanceToNextManeuverMeters = 150.0))?.stage,
        )
        assertEquals(
            AnnouncementStage.NOW,
            controller.nextAnnouncement(base.copy(distanceToNextManeuverMeters = 20.0))?.stage,
        )
        assertNull(controller.nextAnnouncement(base.copy(distanceToNextManeuverMeters = 15.0)))
    }

    @Test
    fun arrivalAndFuelStopAnnouncementsAreEachSpokenOnce() {
        val route = route("route_stage_3_events")
        val stop = NavigationFuelStop(
            sequence = 1,
            mimitStationId = "3618",
            name = "S.MARTINO OVEST",
            municipality = "Parma",
            province = "PR",
            location = route.geometry[1],
            expectedArrivalAt = null,
            dwellTimeSeconds = 1_200,
        )
        val fuel = NavigationFuelStopProgress(stop, 20.0)
        val controller = ManeuverController()
        val atFuel = NavigationState(
            phase = NavigationPhase.AT_FUEL_STOP,
            route = route.copy(fuelStops = listOf(stop)),
            nextFuelStop = fuel,
        )

        assertEquals(AnnouncementKind.FUEL_STOP, controller.nextAnnouncement(atFuel)?.kind)
        assertNull(controller.nextAnnouncement(atFuel))

        val refuellingCompleted = atFuel.copy(
            phase = NavigationPhase.NAVIGATING,
            nextFuelStop = null,
            lastCompletedFuelStop = stop,
        )
        assertEquals(
            "Rifornimento completato. Riprendi il percorso.",
            controller.nextAnnouncement(refuellingCompleted)?.text,
        )
        assertNull(controller.nextAnnouncement(refuellingCompleted))

        val arrived = atFuel.copy(phase = NavigationPhase.ARRIVED, nextFuelStop = null)
        assertEquals(AnnouncementKind.ARRIVAL, controller.nextAnnouncement(arrived)?.kind)
        assertNull(controller.nextAnnouncement(arrived))
    }

    @Test
    fun updateControllerRefreshesAtFiveMinutesAndDeduplicatesOffRouteEpisode() {
        val controller = RouteUpdateController()
        val route = route("route_stage_3_refresh")
        val onRoute = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            navigationPosition = position(route.origin),
        )
        controller.navigationStarted(1_000)

        assertNull(controller.nextUpdate(onRoute, 300_999))
        assertEquals(RouteUpdateReason.TRAFFIC_REFRESH, controller.nextUpdate(onRoute, 301_000))
        controller.attemptStarted(301_000)
        controller.updateSucceeded(302_000)

        val offRoute = onRoute.copy(offRouteStatus = OffRouteStatus.OFF_ROUTE)
        assertEquals(RouteUpdateReason.OFF_ROUTE, controller.nextUpdate(offRoute, 303_000))
        controller.attemptStarted(303_000)
        controller.updateFailed()
        assertNull(controller.nextUpdate(offRoute, 362_000))
        assertEquals(RouteUpdateReason.OFF_ROUTE, controller.nextUpdate(offRoute, 364_000))
    }

    @Test
    fun suspectedDeviationDefersTrafficRefreshUntilPositionIsReliable() {
        val controller = RouteUpdateController()
        val route = route("route_stage_3_suspected")
        val base = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            navigationPosition = position(route.origin),
        )
        controller.navigationStarted(1_000)

        assertNull(
            controller.nextUpdate(
                base.copy(offRouteStatus = OffRouteStatus.SUSPECTED),
                301_000,
            ),
        )
        assertEquals(RouteUpdateReason.TRAFFIC_REFRESH, controller.nextUpdate(base, 301_000))
    }

    @Test
    fun offlineStateSuppressesServerUpdatesAndRecoveryRetriesWithBoundedBackoff() {
        val controller = RouteUpdateController()
        val route = route("route_connectivity_recovery")
        val localState = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            navigationPosition = position(route.origin),
            connectivity = NavigationConnectivity.OFFLINE,
        )
        controller.navigationStarted(1_000L)

        assertNull(controller.nextUpdate(localState, 901_000L))

        controller.connectivityRestored()
        val recovering = localState.copy(connectivity = NavigationConnectivity.RECOVERING)
        assertEquals(
            RouteUpdateReason.CONNECTIVITY_RECOVERY,
            controller.nextUpdate(recovering, 901_000L),
        )
        controller.attemptStarted(901_000L)
        assertNull(controller.nextUpdate(recovering, 902_000L))
        controller.updateFailed(retryConnectivityRecovery = true)
        assertNull(controller.nextUpdate(recovering, 905_999L))
        assertEquals(
            RouteUpdateReason.CONNECTIVITY_RECOVERY,
            controller.nextUpdate(recovering, 906_000L),
        )
        controller.attemptStarted(906_000L)
        controller.updateFailed(retryConnectivityRecovery = true)
        assertNull(controller.nextUpdate(recovering, 920_999L))
        assertEquals(
            RouteUpdateReason.CONNECTIVITY_RECOVERY,
            controller.nextUpdate(recovering, 921_000L),
        )
        controller.attemptStarted(921_000L)
        controller.updateFailed(retryConnectivityRecovery = true)
        assertNull(controller.nextUpdate(recovering, 980_999L))
        assertEquals(
            RouteUpdateReason.CONNECTIVITY_RECOVERY,
            controller.nextUpdate(recovering, 981_000L),
        )
        controller.attemptStarted(981_000L)
        controller.updateFailed(retryConnectivityRecovery = true)
        assertNull(controller.nextUpdate(recovering, 1_040_999L))
        assertEquals(
            RouteUpdateReason.CONNECTIVITY_RECOVERY,
            controller.nextUpdate(recovering, 1_041_000L),
        )
    }

    @Test
    fun failedRerouteKeepsDownloadedRouteAndSuccessfulReplacementStaysActive() {
        val original = route("route_stage_3_original")
        val replacement = route("route_stage_3_replacement")
        val engine = NavigationEngine(
            locationFilter = LocationFilter(
                LocationFilterPolicy(
                    minimumPositionSmoothingAlpha = 1.0,
                    maximumPositionSmoothingAlpha = 1.0,
                ),
            ),
        )
        engine.preview(original)
        engine.start(nowEpochMillis = 1_000)
        val fix = NavigationLocation(original.geometry[1], 4.0, 10.0, 90.0, 2_000)
        engine.updateLocation(fix)
        val progressBefore = engine.state.value.routeProgressFraction

        engine.beginRouteUpdate(RouteUpdateReason.OFF_ROUTE)
        assertEquals(NavigationPhase.REROUTING, engine.state.value.phase)
        engine.failRouteUpdate()
        assertSame(original, engine.state.value.route)
        assertEquals(ReroutingStatus.FAILED, engine.state.value.reroutingStatus)
        assertEquals(progressBefore, engine.state.value.routeProgressFraction, 0.0)

        engine.beginRouteUpdate(RouteUpdateReason.TRAFFIC_REFRESH)
        engine.replaceRoute(replacement, refreshedAtEpochMillis = 3_000, currentLocation = fix)
        assertEquals(replacement.routeId, engine.state.value.route?.routeId)
        assertEquals(ReroutingStatus.IDLE, engine.state.value.reroutingStatus)
        assertTrue(engine.state.value.phase != NavigationPhase.ROUTE_PREVIEW)
        assertEquals(3_000L, engine.state.value.lastSuccessfulRouteRefreshEpochMillis)
        assertNull(engine.state.value.routeUpdateNotice)
    }

    @Test
    fun failedRerouteReturnsToGuidanceEvenWhenMoreOffRouteFixesArriveInFlight() {
        val original = route("route_stage_3_inflight_failure")
        val engine = NavigationEngine(
            locationFilter = LocationFilter(
                LocationFilterPolicy(
                    minimumPositionSmoothingAlpha = 1.0,
                    maximumPositionSmoothingAlpha = 1.0,
                ),
            ),
        )
        engine.preview(original)
        engine.start(nowEpochMillis = 1_000)
        engine.updateLocation(NavigationLocation(original.geometry[1], 4.0, 10.0, 90.0, 2_000))

        engine.beginRouteUpdate(RouteUpdateReason.OFF_ROUTE)
        engine.updateLocation(
            NavigationLocation(
                coordinate = Coordinate(45.0007, 9.0011),
                accuracyMeters = 4.0,
                speedMetersPerSecond = 10.0,
                bearingDegrees = 0.0,
                timestampEpochMillis = 3_000,
            ),
        )
        engine.failRouteUpdate()

        assertEquals(ReroutingStatus.FAILED, engine.state.value.reroutingStatus)
        assertTrue(engine.state.value.phase != NavigationPhase.REROUTING)
        assertSame(original, engine.state.value.route)
    }

    @Test
    fun cameraZoomsForUrbanTurnAndLooksFurtherAheadAtMotorwaySpeed() {
        val route = route("route_stage_3_camera")
        val controller = NavigationCameraController()
        val urban = controller.instruction(
            NavigationState(
                route = route,
                currentManeuver = route.maneuvers.first(),
                distanceToNextManeuverMeters = 90.0,
                navigationPosition = position(route.origin, speed = 8.0, bearing = 92.0),
            ),
        )
        val motorway = controller.instruction(
            NavigationState(
                route = route,
                distanceToNextManeuverMeters = 5_000.0,
                navigationPosition = position(route.origin, speed = 32.0, bearing = 90.0),
            ),
        )

        assertTrue(urban.zoom > motorway.zoom)
        assertTrue(urban.pitchDegrees < motorway.pitchDegrees)
        assertEquals(90.0, urban.bearingDegrees, 0.5)
    }

    @Test
    fun routeOverviewStartsAtSnappedPositionAndExcludesTravelledGeometry() {
        val route = route("route_stage_3_remaining_overview")
        val snapped = Coordinate(45.0, 9.0015)
        val portions = NavigationState(
            route = route,
            navigationPosition = position(snapped, segment = 1),
        ).routePortions()

        assertEquals(listOf(route.geometry[0], route.geometry[1], snapped), portions.travelled)
        assertEquals(listOf(snapped, route.geometry[2]), portions.remaining)
        assertTrue(route.geometry[0] !in portions.remaining)
    }

    private fun route(routeId: String): NavigationRoute {
        val geometry = listOf(
            Coordinate(45.0, 9.0000),
            Coordinate(45.0, 9.0010),
            Coordinate(45.0, 9.0020),
        )
        val maneuvers = listOf(
            Maneuver(
                type = 10,
                instruction = "Svolta a destra in Via Roma.",
                distanceMeters = 100.0,
                durationSeconds = 10.0,
                beginShapeIndex = 0,
                endShapeIndex = 1,
                streetNames = listOf("Via Roma"),
                verbalTransitionAlertInstruction = "Tra 500 metri svolta a destra.",
                verbalPreTransitionInstruction = "Svolta a destra in Via Roma.",
                travelMode = "drive",
                travelType = "car",
            ),
            Maneuver(
                type = 4,
                instruction = "Arrivo a destinazione.",
                distanceMeters = 100.0,
                durationSeconds = 10.0,
                beginShapeIndex = 1,
                endShapeIndex = 2,
                streetNames = emptyList(),
                travelMode = "drive",
                travelType = "car",
            ),
        )
        return RoutePreview(
            origin = geometry.first(),
            destination = geometry.last(),
            distanceMeters = 200.0,
            durationSeconds = 20.0,
            geometry = geometry,
            maneuvers = maneuvers,
            provider = "valhalla",
            navigation = NavigationTiming(
                routeId = routeId,
                drivingDurationSeconds = 20.0,
                remainingDrivingDurationSeconds = 20.0,
                refuelingStopCount = 0,
                dwellSecondsPerRefuelingStop = 1_200,
                totalRefuelingDwellSeconds = 0.0,
                totalTripDurationSeconds = 20.0,
                departureAt = OffsetDateTime.parse("2026-09-02T08:00:00+02:00"),
                drivingArrivalAt = OffsetDateTime.parse("2026-09-02T08:00:20+02:00"),
                tripArrivalAt = OffsetDateTime.parse("2026-09-02T08:00:20+02:00"),
            ),
        ).toNavigationRoute()
    }

    private fun position(
        coordinate: Coordinate,
        segment: Int = 0,
        speed: Double = 0.0,
        bearing: Double = 90.0,
    ) = NavigationPosition(coordinate, segment, speed, bearing, 5.0, 1_000)
}
