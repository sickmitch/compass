package org.compass.cng.navigation

data class NavigationHeadingPolicy(
    val stationarySpeedMetersPerSecond: Double = 1.5,
    val smoothingAlpha: Double = 0.45,
    val maximumTurnPerFixDegrees: Double = 45.0,
)

/** Stabilizes route-matched heading and freezes it while the vehicle is effectively stationary. */
class NavigationHeadingController(
    private val policy: NavigationHeadingPolicy = NavigationHeadingPolicy(),
) {
    private var previousBearingDegrees: Double? = null

    fun reset() {
        previousBearingDegrees = null
    }

    fun update(routeBearingDegrees: Double, speedMetersPerSecond: Double): Double {
        val target = normalizeBearing(routeBearingDegrees)
        val previous = previousBearingDegrees
        if (previous == null) return target.also { previousBearingDegrees = it }
        if (speedMetersPerSecond <= policy.stationarySpeedMetersPerSecond) return previous

        val difference = signedBearingDifference(previous, target)
        val step = (difference * policy.smoothingAlpha).coerceIn(
            -policy.maximumTurnPerFixDegrees,
            policy.maximumTurnPerFixDegrees,
        )
        return normalizeBearing(previous + step).also { previousBearingDegrees = it }
    }
}

internal fun signedBearingDifference(fromDegrees: Double, toDegrees: Double): Double =
    ((normalizeBearing(toDegrees) - normalizeBearing(fromDegrees) + 540.0) % 360.0) - 180.0
