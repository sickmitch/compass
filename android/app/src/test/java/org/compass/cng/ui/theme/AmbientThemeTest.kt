package org.compass.cng.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientThemeTest {
    @Test
    fun darkAndBrightEnvironmentsSelectTheExpectedTheme() {
        assertEquals(true, ambientThemeTarget(0f))
        assertEquals(true, ambientThemeTarget(10f))
        assertEquals(false, ambientThemeTarget(50f))
        assertEquals(false, ambientThemeTarget(100_000f))
    }

    @Test
    fun IntermediateLightDoesNotToggleTheTheme() {
        assertNull(ambientThemeTarget(10.1f))
        assertNull(ambientThemeTarget(30f))
        assertNull(ambientThemeTarget(49.9f))
    }

    @Test
    fun invalidReadingsDoNotChangeTheTheme() {
        assertNull(ambientThemeTarget(-1f))
        assertNull(ambientThemeTarget(Float.NaN))
        assertNull(ambientThemeTarget(Float.POSITIVE_INFINITY))
    }
}
