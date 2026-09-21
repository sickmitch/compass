package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowLocationStabilizerTest {
    @Test
    fun rejectsRecentLessAccurateProviderFixAndAcceptsNextGpsFix() {
        val stabilizer = FollowLocationStabilizer()
        val gps = location(timestamp = 1_000, accuracy = 5.0, longitude = 10.0)
        val network = location(timestamp = 1_500, accuracy = 35.0, longitude = 10.001)
        val nextGps = location(timestamp = 2_000, accuracy = 4.0, longitude = 10.0001)

        assertEquals(gps.coordinate, requireNotNull(stabilizer.update(gps)).coordinate)
        assertNull(stabilizer.update(network))
        assertTrue(requireNotNull(stabilizer.update(nextGps)).coordinate.longitude < 10.0001)
    }

    @Test
    fun rejectsOutOfOrderFixEvenWhenItIsMoreAccurate() {
        val stabilizer = FollowLocationStabilizer()

        assertTrue(stabilizer.update(location(timestamp = 2_000, accuracy = 8.0)) != null)
        assertNull(stabilizer.update(location(timestamp = 1_999, accuracy = 2.0)))
    }

    @Test
    fun acceptsDegradedProviderAfterPreferredFixExpires() {
        val stabilizer = FollowLocationStabilizer()

        assertTrue(stabilizer.update(location(timestamp = 1_000, accuracy = 5.0)) != null)
        assertTrue(
            stabilizer.update(location(timestamp = 11_001, accuracy = 35.0)) != null,
        )
    }

    @Test
    fun freezesBearingForStationaryRouteFreeCamera() {
        val stabilizer = FollowLocationStabilizer()
        val first = requireNotNull(
            stabilizer.update(location(timestamp = 1_000, speed = 0.2, bearing = 280.0)),
        )
        val second = requireNotNull(
            stabilizer.update(location(timestamp = 2_000, speed = 0.3, bearing = 40.0)),
        )

        assertNull(first.bearingDegrees)
        assertNull(second.bearingDegrees)
    }

    private fun location(
        timestamp: Long,
        accuracy: Double = 5.0,
        longitude: Double = 10.0,
        speed: Double = 8.0,
        bearing: Double? = 90.0,
    ) = NavigationLocation(
        coordinate = Coordinate(45.0, longitude),
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        bearingDegrees = bearing,
        timestampEpochMillis = timestamp,
        receivedAtEpochMillis = timestamp,
    )
}
