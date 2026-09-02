package org.compass.cng.navigation

enum class NavigationCameraMode {
    FOLLOW,
    OVERVIEW,
}

data class NavigationCameraInstruction(
    val bearingDegrees: Double,
    val zoom: Double,
    val pitchDegrees: Double,
    val animationMillis: Int,
)

/** Pure camera policy. MapLibre only executes the returned instruction. */
class NavigationCameraController {
    fun instruction(state: NavigationState): NavigationCameraInstruction {
        val speed = state.currentSpeedMetersPerSecond
        val maneuverDistance = state.distanceToNextManeuverMeters
        val maneuverType = state.currentManeuver?.type
        val complexJunction = maneuverType in COMPLEX_MANEUVER_TYPES
        val (zoom, pitch) = when {
            maneuverDistance != null && maneuverDistance <= 180.0 && speed < 18.0 -> 17.2 to 48.0
            complexJunction && maneuverDistance != null && maneuverDistance <= 800.0 -> 15.4 to 50.0
            speed >= 27.0 -> 14.0 to 60.0
            speed >= 15.0 -> 14.8 to 58.0
            else -> 16.0 to 55.0
        }
        return NavigationCameraInstruction(
            bearingDegrees = state.vehicleBearingDegrees ?: 0.0,
            zoom = zoom,
            pitchDegrees = pitch,
            animationMillis = 750,
        )
    }

    private companion object {
        val COMPLEX_MANEUVER_TYPES = setOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 26, 27)
    }
}
