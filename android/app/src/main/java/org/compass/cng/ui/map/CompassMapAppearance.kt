package org.compass.cng.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import org.compass.cng.BuildConfig
import org.compass.cng.R

internal enum class CompassMapTheme {
    DAY,
    NIGHT,
}

internal data class CompassMapPalette(
    val route: Int,
    val travelledRoute: Int,
    val origin: Int,
    val destination: Int,
    val cng: Int,
    val selectedCng: Int,
    val markerStroke: Int,
    val markerText: Int,
)

internal data class CompassMapAppearance(
    val theme: CompassMapTheme,
    val styleUrl: String,
    val palette: CompassMapPalette,
)

@Composable
internal fun compassMapAppearance(): CompassMapAppearance = resolveCompassMapAppearance(
    theme = if (isSystemInDarkTheme()) CompassMapTheme.NIGHT else CompassMapTheme.DAY,
    dayStyleUrl = BuildConfig.COMPASS_MAP_DAY_STYLE_URL,
    nightStyleUrl = BuildConfig.COMPASS_MAP_NIGHT_STYLE_URL,
)

internal fun resolveCompassMapAppearance(
    theme: CompassMapTheme,
    dayStyleUrl: String,
    nightStyleUrl: String,
): CompassMapAppearance = when (theme) {
    CompassMapTheme.DAY -> CompassMapAppearance(
        theme = theme,
        styleUrl = dayStyleUrl,
        palette = CompassMapPalette(
            route = 0xFF08783E.toInt(),
            travelledRoute = 0xFF69716C.toInt(),
            origin = 0xFF08783E.toInt(),
            destination = 0xFFB73024.toInt(),
            cng = 0xFF006D5B.toInt(),
            selectedCng = 0xFFE28719.toInt(),
            markerStroke = 0xFFFFFFFF.toInt(),
            markerText = 0xFFFFFFFF.toInt(),
        ),
    )
    CompassMapTheme.NIGHT -> CompassMapAppearance(
        theme = theme,
        styleUrl = nightStyleUrl,
        palette = CompassMapPalette(
            route = 0xFF009DFF.toInt(),
            travelledRoute = 0xFF718078.toInt(),
            origin = 0xFF50E28B.toInt(),
            destination = 0xFFFF7769.toInt(),
            cng = 0xFF35CDA0.toInt(),
            selectedCng = 0xFFFFC857.toInt(),
            markerStroke = 0xFF071A12.toInt(),
            markerText = 0xFF071A12.toInt(),
        ),
    )
}

internal fun mapStyleSource(styleUrl: String): String = when {
    styleUrl.startsWith("asset://") -> "bundled"
    styleUrl.startsWith("https://") -> "remote_https"
    else -> "custom"
}

internal fun CompassMapAppearance.navigationPuckDrawableRes(): Int = when (theme) {
    CompassMapTheme.DAY -> R.drawable.ic_navigation_vehicle
    CompassMapTheme.NIGHT -> R.drawable.ic_navigation_vehicle_night
}
