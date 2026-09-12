package org.compass.cng.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CompassThemeTest {
    @Test
    fun brandedMaterialSchemesKeepNavigationContrastInBothThemes() {
        assertEquals(Color(0xFF006C4C), CompassLightColors.primary)
        assertEquals(Color(0xFF91F0BC), CompassDarkColors.primary)
        assertEquals(Color.Black, CompassDarkColors.background)
        assertEquals(Color.Black, CompassDarkColors.surface)
        assertEquals(Color.Black, CompassDarkColors.surfaceContainerLowest)
        assertEquals(Color.Transparent, CompassDarkColors.surfaceTint)
        assertNotEquals(CompassLightColors.primary, CompassLightColors.surface)
        assertNotEquals(CompassDarkColors.primary, CompassDarkColors.surface)
        assertNotEquals(CompassLightColors.outline, CompassLightColors.background)
        assertNotEquals(CompassDarkColors.outline, CompassDarkColors.background)
    }

    @Test
    fun controlsUseAutomotiveFriendlyMaterialTouchTargets() {
        assertEquals(56.dp, CompassControlMinimumHeight)
        assertEquals(48.dp, CompassCompactControlMinimumHeight)
    }
}
