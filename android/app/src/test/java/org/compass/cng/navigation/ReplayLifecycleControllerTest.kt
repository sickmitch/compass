package org.compass.cng.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayLifecycleControllerTest {
    @Test
    fun resumesReplayExactlyOnceAfterSimulatedOffRouteUpdate() {
        val controller = ReplayLifecycleController()
        controller.navigationStarted(replay = true)

        controller.simulatedOffRouteStarted()

        assertFalse(controller.isReplayActive)
        assertTrue(controller.routeUpdateFinished())
        assertTrue(controller.isReplayActive)
        assertFalse(controller.routeUpdateFinished())
    }

    @Test
    fun realLocationNavigationDoesNotBecomeReplayAfterRouteUpdate() {
        val controller = ReplayLifecycleController()
        controller.navigationStarted(replay = false)

        controller.simulatedOffRouteStarted()

        assertFalse(controller.routeUpdateFinished())
        assertFalse(controller.isReplayActive)
    }
}
