package org.compass.cng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CompassLightColors = lightColorScheme(
    primary = Color(0xFF146C3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC1F1D0),
    onPrimaryContainer = Color(0xFF00210D),
    secondary = Color(0xFF4F6354),
    background = Color(0xFFF7FBF6),
    surface = Color(0xFFF7FBF6),
)

private val CompassDarkColors = darkColorScheme(
    primary = Color(0xFFA5D5B5),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF005229),
    onPrimaryContainer = Color(0xFFC1F1D0),
    secondary = Color(0xFFB6CCBA),
)

@Composable
fun CompassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) CompassDarkColors else CompassLightColors,
        content = content,
    )
}
