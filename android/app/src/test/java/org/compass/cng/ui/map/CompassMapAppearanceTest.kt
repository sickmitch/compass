package org.compass.cng.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CompassMapAppearanceTest {
    @Test
    fun dayAndNightResolveIndependentDeploymentStyles() {
        val day = resolveCompassMapAppearance(
            theme = CompassMapTheme.DAY,
            dayStyleUrl = "https://maps.example/day.json",
            nightStyleUrl = "https://maps.example/night.json",
        )
        val night = resolveCompassMapAppearance(
            theme = CompassMapTheme.NIGHT,
            dayStyleUrl = "https://maps.example/day.json",
            nightStyleUrl = "https://maps.example/night.json",
        )

        assertEquals("https://maps.example/day.json", day.styleUrl)
        assertEquals("https://maps.example/night.json", night.styleUrl)
        assertEquals(0xFF009DFF.toInt(), night.palette.route)
        assertNotEquals(day.palette.route, night.palette.route)
        assertNotEquals(day.palette.markerStroke, night.palette.markerStroke)
        assertNotEquals(day.palette.cng, day.palette.selectedCng)
        assertNotEquals(night.palette.cng, night.palette.selectedCng)
    }

    @Test
    fun logClassificationDoesNotExposeRemoteStyleUrl() {
        assertEquals("bundled", mapStyleSource("asset://compass-day.json"))
        assertEquals("remote_https", mapStyleSource("https://secret.example/style?token=value"))
        assertEquals("custom", mapStyleSource("file:///tmp/style.json"))
    }

    @Test
    fun navigationPuckUsesTheDoubledIconScale() {
        assertEquals(1.10f, NAVIGATION_PUCK_ICON_SCALE)
    }
}
