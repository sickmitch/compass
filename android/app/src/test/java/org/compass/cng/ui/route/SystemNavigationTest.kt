package org.compass.cng.ui.route

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    @Test
    fun expandedTripPanelAlignsLeftAndRightNavigationControls() {
        assertEquals(0.dp, navigationLeftControlBottomPadding(tripSummaryVisible = true))
        assertEquals(44.dp, navigationLeftControlBottomPadding(tripSummaryVisible = false))
    }

    @Test
    fun navigationCardsShareTheMoreTransparentGlassOpacity() {
        assertEquals(0.70f, NAVIGATION_GLASS_PANEL_ALPHA)
    }

    @Test
    fun closestPlannedStopOwnsThePrimaryGuidancePosition() {
        assertTrue(
            shouldPrioritizeIntermediateStop(
                intermediateDistanceMeters = 1_000.0,
                cngDistanceMeters = 2_000.0,
            ),
        )
        assertFalse(
            shouldPrioritizeIntermediateStop(
                intermediateDistanceMeters = 3_000.0,
                cngDistanceMeters = 2_000.0,
            ),
        )
        assertTrue(
            shouldPrioritizeIntermediateStop(
                intermediateDistanceMeters = 1_000.0,
                cngDistanceMeters = null,
            ),
        )
        assertFalse(
            shouldPrioritizeIntermediateStop(
                intermediateDistanceMeters = null,
                cngDistanceMeters = 2_000.0,
            ),
        )
    }

    @Test
    fun removeNextStopIsVisibleOnlyForAnUpcomingNonActiveStop() {
        assertFalse(
            shouldShowRemoveNextStop(
                hasNextFuelStop = false,
                hasNextIntermediateStop = false,
                fuelStopVisitActive = false,
                intermediateStopVisitActive = false,
            ),
        )
        assertTrue(
            shouldShowRemoveNextStop(
                hasNextFuelStop = true,
                hasNextIntermediateStop = false,
                fuelStopVisitActive = false,
                intermediateStopVisitActive = false,
            ),
        )
        assertFalse(
            shouldShowRemoveNextStop(
                hasNextFuelStop = true,
                hasNextIntermediateStop = false,
                fuelStopVisitActive = true,
                intermediateStopVisitActive = false,
            ),
        )
    }
}
