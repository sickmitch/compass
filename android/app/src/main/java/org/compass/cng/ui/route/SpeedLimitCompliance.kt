package org.compass.cng.ui.route

import kotlin.math.roundToInt

internal enum class SpeedLimitComplianceStatus {
    UNAVAILABLE,
    WITHIN_LIMIT,
    OVER_LIMIT,
}

/** UI stability policy. These buffers are not legal or enforcement tolerances. */
internal data class SpeedLimitCompliancePolicy(
    val enterBufferKph: Double = 5.0,
    val exitBufferKph: Double = 2.0,
) {
    init {
        require(enterBufferKph > exitBufferKph)
        require(exitBufferKph >= 0.0)
    }
}

internal fun speedLimitComplianceStatus(
    previous: SpeedLimitComplianceStatus,
    speedMetersPerSecond: Double?,
    speedLimitKph: Int?,
    policy: SpeedLimitCompliancePolicy = SpeedLimitCompliancePolicy(),
): SpeedLimitComplianceStatus {
    val speedKph = speedMetersPerSecond
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.times(METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR)
        ?: return SpeedLimitComplianceStatus.UNAVAILABLE
    val limit = speedLimitKph
        ?.takeIf { it in 1..250 }
        ?.toDouble()
        ?: return SpeedLimitComplianceStatus.UNAVAILABLE

    return if (previous == SpeedLimitComplianceStatus.OVER_LIMIT) {
        if (speedKph <= limit + policy.exitBufferKph) {
            SpeedLimitComplianceStatus.WITHIN_LIMIT
        } else {
            SpeedLimitComplianceStatus.OVER_LIMIT
        }
    } else if (speedKph >= limit + policy.enterBufferKph) {
        SpeedLimitComplianceStatus.OVER_LIMIT
    } else {
        SpeedLimitComplianceStatus.WITHIN_LIMIT
    }
}

internal fun speedKphForDisplay(speedMetersPerSecond: Double?): Int? =
    speedMetersPerSecond
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.times(METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR)
        ?.roundToInt()

private const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6
