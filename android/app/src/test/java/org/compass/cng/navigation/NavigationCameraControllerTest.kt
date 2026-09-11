package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun followTargetIsTheMatchedVehicleAndBearingLooksAlongTheRemainingRoute() {
        val controller = NavigationCameraController()
        val state = NavigationState(
            route = route,
            navigationPosition = position(route.origin, bearing = 90.0),
        )

        val instruction = controller.instruction(state)

        assertEquals(45.0, instruction.target.latitude, 0.000_001)
        assertEquals(route.origin.longitude, instruction.target.longitude, 0.000_001)
        assertEquals(90.0, instruction.bearingDegrees, 0.5)
        assertTrue(instruction.pitchDegrees in 45.0..60.0)
    }

    @Test
    fun speedContinuouslyWidensViewWithoutMovingTheVehicleAnchor() {
        val controller = NavigationCameraController()
        val base = NavigationState(
            route = route,
            navigationPosition = position(route.origin, bearing = 90.0),
        )

        val urban = controller.instruction(base.withSpeed(6.0))
        val motorway = controller.instruction(base.withSpeed(30.0))

        assertTrue(urban.zoom > motorway.zoom)
        assertTrue(urban.pitchDegrees < motorway.pitchDegrees)
        assertEquals(urban.target, motorway.target)
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
            navigationPosition = position(route.origin, speed = 12.0, bearing = 450.0),
        )

        val cruising = controller.instruction(base)
        val approaching = controller.instruction(
            base.copy(currentManeuver = turn, distanceToNextManeuverMeters = 20.0),
        )

        assertEquals(cruising.target, approaching.target)
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
                navigationPosition = position(route.origin, bearing = 315.0),
            ),
        )

        assertEquals(90.0, instruction.bearingDegrees, 0.5)
    }

    @Test
    fun followTargetStaysOnLocalHeadingCentrelineBeforeAConnectedTurn() {
        val bentRoute = route.copy(
            geometry = listOf(
                Coordinate(45.0, 9.0),
                Coordinate(45.0, 9.0005),
                Coordinate(45.001, 9.0005),
            ),
        )
        val instruction = NavigationCameraController().instruction(
            NavigationState(
                route = bentRoute,
                navigationPosition = position(bentRoute.origin, speed = 30.0),
            ),
        )

        assertEquals(90.0, instruction.bearingDegrees, 0.5)
        assertEquals(bentRoute.origin.latitude, instruction.target.latitude, 0.000_001)
        assertEquals(bentRoute.origin.longitude, instruction.target.longitude, 0.000_001)
    }

    @Test
    fun consecutiveNearbyManeuversZoomInWhileSparseManeuversWidenTheView() {
        val controller = NavigationCameraController()
        val denseManeuver = maneuver(distanceMeters = 120.0)
        val sparseManeuver = maneuver(distanceMeters = 2_500.0)
        val base = NavigationState(
            route = route,
            navigationPosition = position(route.origin, speed = 12.0),
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
    fun nearbyUrbanTurnIsMateriallyCloserThanSparseGuidanceAtTheSameSpeed() {
        val controller = NavigationCameraController()
        val turn = maneuver(distanceMeters = 100.0)
        val base = NavigationState(
            route = route,
            navigationPosition = position(route.origin, speed = 22.0),
            currentManeuver = turn,
        )

        val nearby = controller.instruction(
            base.copy(
                distanceToNextManeuverMeters = 46.0,
                nextManeuver = maneuver(distanceMeters = 120.0),
            ),
        )
        val sparse = controller.instruction(
            base.copy(
                distanceToNextManeuverMeters = 2_000.0,
                nextManeuver = maneuver(distanceMeters = 2_500.0),
            ),
        )

        assertTrue(nearby.zoom - sparse.zoom >= 2.0)
    }

    @Test
    fun drivingViewportAndManualCameraTimeoutHaveCentralizedSafeDefaults() {
        val config = NavigationCameraConfig()

        assertEquals(0.75, config.followPuckVerticalFraction, 0.0)
        assertEquals(10_000L, config.freeModeAutoRecenterMillis)
    }

    @Test
    fun followPuckAnchorRejectsPositionsOutsideTheLowerDrivingViewport() {
        assertThrows(IllegalArgumentException::class.java) {
            NavigationCameraConfig(followPuckVerticalFraction = 0.49)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationCameraConfig(followPuckVerticalFraction = 0.91)
        }
    }

    @Test
    fun followPaddingPlacesTheCameraTargetOnTheConfiguredScreenFraction() {
        assertEquals(500.0, followTopPaddingPixels(1_000, 0.75), 0.0)
        assertEquals(0.0, followTopPaddingPixels(1_000, 0.5), 0.0)
        assertEquals(400.0, followTopPaddingPixels(1_000, 0.75, 200), 0.0)
    }

    @Test
    fun orientationToggleKeepsTrackingAndRecenterAlwaysReturnsToHeadingUp() {
        assertEquals(
            NavigationCameraMode.NORTH_UP,
            NavigationCameraMode.FOLLOW.toggleOrientation(),
        )
        assertEquals(
            NavigationCameraMode.FOLLOW,
            NavigationCameraMode.NORTH_UP.toggleOrientation(),
        )
        assertEquals(
            NavigationCameraMode.FOLLOW,
            NavigationCameraMode.OVERVIEW.toggleOrientation(),
        )
        assertTrue(NavigationCameraMode.FOLLOW.tracksVehiclePosition)
        assertTrue(NavigationCameraMode.NORTH_UP.tracksVehiclePosition)
    }

    @Test
    fun orientationPolicyUsesHeadingUpForFollowAndNorthUpForOverview() {
        assertEquals(87.0, NavigationCameraMode.FOLLOW.resolvedBearingDegrees(447.0)!!, 0.0)
        assertEquals(0.0, NavigationCameraMode.NORTH_UP.resolvedBearingDegrees(87.0)!!, 0.0)
        assertEquals(0.0, NavigationCameraMode.OVERVIEW.resolvedBearingDegrees(87.0)!!, 0.0)
        assertEquals(null, NavigationCameraMode.FREE.resolvedBearingDegrees(87.0))
    }

    @Test
    fun mapControlsAvoidCompetingActionsOutsidePositionTracking() {
        val follow = NavigationCameraMode.FOLLOW.mapControlPolicy()
        assertTrue(follow.showOrientationToggle)
        assertTrue(follow.showOverview)
        assertEquals(false, follow.showRecenter)

        val overview = NavigationCameraMode.OVERVIEW.mapControlPolicy()
        assertEquals(false, overview.showOrientationToggle)
        assertEquals(false, overview.showOverview)
        assertTrue(overview.showRecenter)

        val free = NavigationCameraMode.FREE.mapControlPolicy()
        assertEquals(false, free.showOrientationToggle)
        assertEquals(false, free.showOverview)
        assertTrue(free.showRecenter)
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

    private fun position(
        coordinate: Coordinate,
        segment: Int = 0,
        speed: Double = 0.0,
        bearing: Double = 90.0,
    ) = NavigationPosition(coordinate, segment, speed, bearing, 5.0, 1_000)

    private fun NavigationState.withSpeed(speed: Double) = copy(
        navigationPosition = requireNotNull(navigationPosition).copy(speedMetersPerSecond = speed),
    )
}
