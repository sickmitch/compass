package org.compass.cng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val CompassLightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8FF8C5),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE9DA),
    onSecondaryContainer = Color(0xFF092018),
    tertiary = Color(0xFF3D6472),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E9FA),
    onTertiaryContainer = Color(0xFF001F29),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C2),
    scrim = Color.Black,
)

internal val CompassDarkColors = darkColorScheme(
    primary = Color(0xFF91F0BC),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFFACFBD0),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF20352B),
    secondaryContainer = Color(0xFF364B40),
    onSecondaryContainer = Color(0xFFCFE9DA),
    tertiary = Color(0xFFA5CDDD),
    onTertiary = Color(0xFF073541),
    tertiaryContainer = Color(0xFF244C59),
    onTertiaryContainer = Color(0xFFC1E9FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1411),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0E1411),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF404943),
    scrim = Color.Black,
)

internal val CompassShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

internal val CompassTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

@Immutable
data class CompassSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val CompassLightSemanticColors = CompassSemanticColors(
    success = Color(0xFF006C4C),
    onSuccess = Color.White,
    successContainer = Color(0xFFB7F2D2),
    onSuccessContainer = Color(0xFF073B29),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF4A3510),
)

private val CompassDarkSemanticColors = CompassSemanticColors(
    success = Color(0xFF75D7A5),
    onSuccess = Color(0xFF003824),
    successContainer = Color(0xFF1B513A),
    onSuccessContainer = Color(0xFFB7F2D2),
    warningContainer = Color(0xFF5E461B),
    onWarningContainer = Color(0xFFFFDEA6),
)

private val LocalCompassSemanticColors = staticCompositionLocalOf {
    CompassLightSemanticColors
}

val MaterialTheme.compassSemanticColors: CompassSemanticColors
    @Composable get() = LocalCompassSemanticColors.current

@Composable
fun CompassTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalCompassSemanticColors provides if (darkTheme) {
            CompassDarkSemanticColors
        } else {
            CompassLightSemanticColors
        },
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) CompassDarkColors else CompassLightColors,
            typography = CompassTypography,
            shapes = CompassShapes,
            content = content,
        )
    }
}
