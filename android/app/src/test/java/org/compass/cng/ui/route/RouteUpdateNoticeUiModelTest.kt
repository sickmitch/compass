package org.compass.cng.ui.route

import org.compass.cng.navigation.NavigationRouteUpdateNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteUpdateNoticeUiModelTest {
    @Test
    fun formatsAddedSavedAndUnchangedDuration() {
        assertEquals("+8 min", formatRouteDurationDifference(480.0))
        assertEquals("−1 h 5 min", formatRouteDurationDifference(-3_900.0))
        assertEquals("0 min", formatRouteDurationDifference(29.0))
        assertTrue(notice(previous = 600.0, updated = 630.0).toUiModel().addsTime)
    }

    @Test
    fun exposesTheThreeValuesAndAnAccessibilityDescription() {
        val ui = notice(previous = 3_600.0, updated = 4_080.0).toUiModel()

        assertEquals("1 h 0 min", ui.previousDuration)
        assertEquals("1 h 8 min", ui.updatedDuration)
        assertEquals("+8 min", ui.durationDifference)
        assertTrue(ui.addsTime)
        assertTrue("Durata precedente 1 h 0 min" in ui.accessibilityDescription)
        assertTrue("differenza +8 min" in ui.accessibilityDescription)
    }

    @Test
    fun tenSecondWindowSurvivesRecompositionWithoutRestarting() {
        val notice = notice(previous = 600.0, updated = 720.0, createdAt = 5_000L)

        assertEquals(10_000L, routeUpdateNoticeRemainingMillis(notice, 5_000L))
        assertEquals(4_000L, routeUpdateNoticeRemainingMillis(notice, 11_000L))
        assertEquals(0L, routeUpdateNoticeRemainingMillis(notice, 15_000L))
        assertFalse(notice.toUiModel().routeId.isBlank())
    }

    private fun notice(
        previous: Double,
        updated: Double,
        createdAt: Long = 5_000L,
    ) = NavigationRouteUpdateNotice(
        routeId = "replacement-route",
        previousDurationSeconds = previous,
        updatedDurationSeconds = updated,
        createdAtEpochMillis = createdAt,
    )
}
