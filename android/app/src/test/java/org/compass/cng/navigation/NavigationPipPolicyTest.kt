package org.compass.cng.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPipPolicyTest {
    @Test fun guidanceAndTemporaryInterruptionsKeepPipAvailable() {
        listOf(
            NavigationPhase.NAVIGATING, NavigationPhase.APPROACHING_MANEUVER,
            NavigationPhase.APPROACHING_FUEL_STOP, NavigationPhase.AT_FUEL_STOP,
            NavigationPhase.AT_INTERMEDIATE_STOP, NavigationPhase.REROUTING,
            NavigationPhase.GPS_LOST,
        ).forEach { assertTrue(shouldOfferNavigationPip(true, it)) }
    }

    @Test fun previewsAndFinishedJourneysDoNotEnterPip() {
        listOf(NavigationPhase.IDLE, NavigationPhase.ROUTE_PREVIEW, NavigationPhase.ARRIVED)
            .forEach { assertFalse(shouldOfferNavigationPip(true, it)) }
        NavigationPhase.entries.forEach { assertFalse(shouldOfferNavigationPip(false, it)) }
    }
}
