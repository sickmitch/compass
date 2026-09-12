package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowLocationPolicyTest {
    private val now = 1_000_000L

    @Test
    fun missingOrOldFixIsRepolledWhileFreshFixIsRetained() {
        assertTrue(FollowLocationPolicy.shouldRepoll(null, now))
        assertTrue(
            FollowLocationPolicy.shouldRepoll(location(now - 10_001L), now),
        )
        assertFalse(
            FollowLocationPolicy.shouldRepoll(location(now - 9_999L), now),
        )
    }

    @Test
    fun searchBiasRequiresARecentReasonablyAccurateGpsFix() {
        assertTrue(FollowLocationPolicy.canBiasSearch(location(now - 30_000L), now))
        assertFalse(FollowLocationPolicy.canBiasSearch(location(now - 120_001L), now))
        assertFalse(
            FollowLocationPolicy.canBiasSearch(
                location(now, accuracyMeters = 10_001.0),
                now,
            ),
        )
    }

    private fun location(
        timestampEpochMillis: Long,
        accuracyMeters: Double = 8.0,
    ) = NavigationLocation(
        coordinate = Coordinate(45.4384, 10.9916),
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = 0.0,
        bearingDegrees = null,
        timestampEpochMillis = timestampEpochMillis,
    )
}
