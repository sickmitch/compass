package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCameraControllerTest {
    private val route = NavigationRoute(
        routeId = "camera-test-route",
        origin = Coordinate(45.0, 9.0),
        destination = Coordinate(45.0, 9.004),
        totalDistanceMeters = 320.0,
        drivingDurationSeconds = 40.0,
        totalTripDurationSeconds = 40.0,
        geometry = listOf(
            Coordinate(45.0, 9.0),
            Coordinate(45.0, 9.001),
            Coordinate(45.0, 9.002),
            Coordinate(45.0, 9.004),
        ),
        legs = emptyList(),
        maneuvers = emptyList(),
        fuelStops = emptyList(),
        timing = NavigationTiming.legacy(drivingDurationSeconds = 40.0),
        provider = "fixture",
    )

    @Test
    fun followTargetLooksAheadOfMatchedVehicleAlongRemainingRoute() {
        val controller = NavigationCameraController()
        val state = NavigationState(
            route = route,
            snappedLocation = route.origin,
            currentRouteSegmentIndex = 0,
            vehicleBearingDegrees = 90.0,
        )

        val instruction = controller.instruction(state)

        assertEquals(45.0, instruction.target.latitude, 0.000_001)
        assertTrue(instruction.target.longitude > route.origin.longitude)
        assertTrue(instruction.target.longitude < route.geometry[1].longitude)
        assertEquals(90.0, instruction.bearingDegrees, 0.5)
        assertTrue(instruction.pitchDegrees in 45.0..60.0)
    }

    @Test
    fun speedContinuouslyWidensViewAndExtendsLookAhead() {
        val controller = NavigationCameraController()
        val base = NavigationState(
            route = route,
            snappedLocation = route.origin,
            currentRouteSegmentIndex = 0,
            vehicleBearingDegrees = 90.0,
        )

        val urban = controller.instruction(base.copy(currentSpeedMetersPerSecond = 6.0))
        val motorway = controller.instruction(base.copy(currentSpeedMetersPerSecond = 30.0))

        assertTrue(urban.zoom > motorway.zoom)
        assertTrue(urban.pitchDegrees < motorway.pitchDegrees)
        assertTrue(motorway.target.longitude > urban.target.longitude)
    }

    @Test
    fun imminentTurnLimitsLookAheadAndAddsDetailWithoutLeavingConfiguredPitchRange() {
        val controller = NavigationCameraController()
        val turn = Maneuver(
            type = 10,
            instruction = "Svolta a destra.",
            distanceMeters = 20.0,
            durationSeconds = 3.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            streetNames = listOf("Via Roma"),
            verbalTransitionAlertInstruction = null,
            verbalPreTransitionInstruction = null,
            travelMode = "drive",
            travelType = "car",
        )
        val base = NavigationState(
            route = route,
            snappedLocation = route.origin,
            currentRouteSegmentIndex = 0,
            currentSpeedMetersPerSecond = 12.0,
            vehicleBearingDegrees = 450.0,
        )

        val cruising = controller.instruction(base)
        val approaching = controller.instruction(
            base.copy(currentManeuver = turn, distanceToNextManeuverMeters = 20.0),
        )

        assertTrue(approaching.target.longitude < cruising.target.longitude)
        assertTrue(approaching.zoom > cruising.zoom)
        assertEquals(90.0, approaching.bearingDegrees, 0.5)
        assertTrue(approaching.pitchDegrees in 45.0..60.0)
    }

    @Test
    fun matchedRouteHeadingWinsOverLaggingLocationBearingAfterRecenter() {
        val controller = NavigationCameraController()
        val instruction = controller.instruction(
            NavigationState(
                route = route,
                snappedLocation = route.origin,
                currentRouteSegmentIndex = 0,
                vehicleBearingDegrees = 315.0,
            ),
        )

        assertEquals(90.0, instruction.bearingDegrees, 0.5)
    }

    @Test
    fun consecutiveNearbyManeuversZoomInWhileSparseManeuversWidenTheView() {
        val controller = NavigationCameraController()
        val denseManeuver = maneuver(distanceMeters = 120.0)
        val sparseManeuver = maneuver(distanceMeters = 2_500.0)
        val base = NavigationState(
            route = route,
            snappedLocation = route.origin,
            currentRouteSegmentIndex = 0,
            currentSpeedMetersPerSecond = 12.0,
            distanceToNextManeuverMeters = 700.0,
        )

        val dense = controller.instruction(base.copy(nextManeuver = denseManeuver))
        val sparse = controller.instruction(base.copy(nextManeuver = sparseManeuver))

        assertTrue(dense.zoom > sparse.zoom)
        assertTrue(
            dense.zoom in controller.config.minimumFollowZoom..controller.config.maximumFollowZoom,
        )
        assertTrue(
            sparse.zoom in controller.config.minimumFollowZoom..controller.config.maximumFollowZoom,
        )
    }

    @Test
    fun drivingViewportAndManualCameraTimeoutHaveCentralizedSafeDefaults() {
        val config = NavigationCameraConfig()

        assertEquals(0.18, config.followTopPaddingFraction, 0.0)
        assertEquals(10_000L, config.freeModeAutoRecenterMillis)
    }

    private fun maneuver(distanceMeters: Double) = Maneuver(
        type = 10,
        instruction = "Svolta a destra.",
        distanceMeters = distanceMeters,
        durationSeconds = 10.0,
        beginShapeIndex = 0,
        endShapeIndex = 1,
        streetNames = listOf("Via Roma"),
        travelMode = "drive",
        travelType = "car",
    )
}
