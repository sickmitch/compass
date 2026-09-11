package org.compass.cng.ui.map

import org.compass.cng.R
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
        assertEquals(0.55f, NAVIGATION_PUCK_MIN_ICON_SCALE)
        assertEquals(NAVIGATION_PUCK_ICON_SCALE * 0.5f, NAVIGATION_PUCK_MIN_ICON_SCALE)
        assert(NAVIGATION_PUCK_MIN_SCALE_ZOOM < NAVIGATION_PUCK_FULL_SCALE_ZOOM)
    }

    @Test
    fun navigationPuckUsesMaterialDayAndNightAssets() {
        val day = resolveCompassMapAppearance(
            theme = CompassMapTheme.DAY,
            dayStyleUrl = "asset://day.json",
            nightStyleUrl = "asset://night.json",
        )
        val night = resolveCompassMapAppearance(
            theme = CompassMapTheme.NIGHT,
            dayStyleUrl = "asset://day.json",
            nightStyleUrl = "asset://night.json",
        )

        assertEquals(R.drawable.ic_navigation_vehicle, day.navigationPuckDrawableRes())
        assertEquals(R.drawable.ic_navigation_vehicle_night, night.navigationPuckDrawableRes())
        assertNotEquals(day.navigationPuckDrawableRes(), night.navigationPuckDrawableRes())
    }
}
