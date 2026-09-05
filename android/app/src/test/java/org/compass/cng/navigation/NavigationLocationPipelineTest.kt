package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLocationPipelineTest {
    @Test
    fun stationaryNoiseKeepsPositionAndDoesNotAdoptUnstableGpsBearing() {
        val filter = LocationFilter()
        val first = location(
            coordinate = Coordinate(45.0, 9.0),
            speed = 0.2,
            bearing = 270.0,
            timestamp = 1_000,
        )
        val noisy = location(
            coordinate = Coordinate(45.000_005, 9.000_005),
            speed = 0.3,
            bearing = 45.0,
            timestamp = 2_000,
        )

        val acceptedFirst = requireNotNull(filter.filter(first))
        val acceptedNoisy = requireNotNull(filter.filter(noisy))

        assertEquals(first.coordinate, acceptedNoisy.coordinate)
        assertNull(acceptedFirst.bearingDegrees)
        assertNull(acceptedNoisy.bearingDegrees)
    }

    @Test
    fun delayedAndImplausibleFixesAreRejectedBeforeMapMatching() {
        val filter = LocationFilter()
        assertNull(
            filter.filter(
                location(
                    coordinate = Coordinate(45.0, 9.0),
                    timestamp = 1_000,
                    receivedAt = 20_000,
                ),
            ),
        )

        assertTrue(filter.filter(location(Coordinate(45.0, 9.0), timestamp = 20_000)) != null)
        assertNull(
            filter.filter(
                location(Coordinate(46.0, 10.0), timestamp = 21_000),
            ),
        )
    }

    @Test
    fun routeMatchedHeadingUsesShortestRotationAndFreezesAtLowSpeed() {
        val controller = NavigationHeadingController(
            NavigationHeadingPolicy(smoothingAlpha = 1.0, maximumTurnPerFixDegrees = 45.0),
        )

        assertEquals(350.0, controller.update(350.0, 12.0), 0.0)
        assertEquals(10.0, controller.update(10.0, 12.0), 0.0)
        assertEquals(10.0, controller.update(180.0, 0.3), 0.0)
    }

    @Test
    fun puckMotionInterpolatesCoordinateAndBearingAcrossNorth() {
        val start = NavigationPuckPose(Coordinate(45.0, 9.0), 350.0)
        val target = NavigationPosition(
            coordinate = Coordinate(45.0, 9.001),
            routeSegmentIndex = 1,
            speedMetersPerSecond = 12.0,
            bearingDegrees = 10.0,
            horizontalAccuracyMeters = 4.0,
            timestampEpochMillis = 2_000,
        )

        val transition = planNavigationPuckTransition(start, 1_000, target)
        val halfway = transition.poseAt(0.5f)

        assertEquals(NavigationPuckTransitionMode.ANIMATE, transition.mode)
        assertEquals(850L, transition.durationMillis)
        assertEquals(9.0005, halfway.coordinate.longitude, 0.000_001)
        assertTrue(halfway.bearingDegrees < 0.1 || halfway.bearingDegrees > 359.9)
    }

    @Test
    fun stationaryPuckHoldsAndLargeDiscontinuitySnaps() {
        val start = NavigationPuckPose(Coordinate(45.0, 9.0), 90.0)
        val held = planNavigationPuckTransition(
            start,
            1_000,
            NavigationPosition(Coordinate(45.000_005, 9.000_005), 0, 0.2, 180.0, 5.0, 2_000),
        )
        val snapped = planNavigationPuckTransition(
            start,
            1_000,
            NavigationPosition(Coordinate(45.01, 9.01), 0, 20.0, 90.0, 5.0, 2_000),
        )

        assertEquals(NavigationPuckTransitionMode.HOLD, held.mode)
        assertEquals(start, held.target)
        assertEquals(NavigationPuckTransitionMode.SNAP, snapped.mode)
    }

    private fun location(
        coordinate: Coordinate,
        speed: Double = 10.0,
        bearing: Double? = 90.0,
        timestamp: Long,
        receivedAt: Long? = null,
    ) = NavigationLocation(
        coordinate = coordinate,
        accuracyMeters = 5.0,
        speedMetersPerSecond = speed,
        bearingDegrees = bearing,
        timestampEpochMillis = timestamp,
        receivedAtEpochMillis = receivedAt,
    )
}
