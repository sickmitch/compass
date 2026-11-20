package org.compass.cng

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowLocationPolicyTest {
    @Test
    fun followSubscribesToNetworkAndGpsWhenBothAreAvailable() {
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            enabledFollowLocationProviders(gpsEnabled = true, networkEnabled = true),
        )
    }

    @Test
    fun followCanAcquireAnApproximateNetworkFixWithoutGps() {
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER),
            enabledFollowLocationProviders(gpsEnabled = false, networkEnabled = true),
        )
    }
}
