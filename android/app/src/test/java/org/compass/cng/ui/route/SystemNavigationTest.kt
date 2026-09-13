package org.compass.cng.ui.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.compass.cng.navigation.NavigationPhase

class SystemNavigationTest {
    @Test
    fun backArrowIsHiddenOnlyWhenSystemUsesGestureNavigation() {
        assertFalse(shouldShowHeaderBackButton(canNavigateBack = true, systemUsesGestures = true))
        assertTrue(shouldShowHeaderBackButton(canNavigateBack = true, systemUsesGestures = false))
        assertFalse(shouldShowHeaderBackButton(canNavigateBack = false, systemUsesGestures = false))
    }

    @Test
    fun closingOptionsDoesNotStopAnActiveNavigationSession() {
        assertFalse(
            shouldStopNavigationOnBack(PlannerStage.OPTIONS, NavigationPhase.NAVIGATING),
        )
        assertTrue(
            shouldStopNavigationOnBack(
                PlannerStage.NAVIGATION_PREVIEW,
                NavigationPhase.NAVIGATING,
            ),
        )
    }
}
