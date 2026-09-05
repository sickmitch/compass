package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate

data class NavigationPuckMotionConfig(
    val minimumAnimationMillis: Long = 300,
    val maximumAnimationMillis: Long = 1_300,
    val animationIntervalFraction: Double = 0.85,
    val stationarySpeedMetersPerSecond: Double = 1.2,
    val stationaryDeadbandMeters: Double = 3.0,
    val maximumInterpolatedDistanceMeters: Double = 250.0,
)

data class NavigationPuckPose(
    val coordinate: Coordinate,
    val bearingDegrees: Double,
)

enum class NavigationPuckTransitionMode {
    HOLD,
    SNAP,
    ANIMATE,
}

data class NavigationPuckTransition(
    val start: NavigationPuckPose?,
    val target: NavigationPuckPose,
    val mode: NavigationPuckTransitionMode,
    val durationMillis: Long,
    val distanceMeters: Double,
) {
    fun poseAt(fraction: Float): NavigationPuckPose {
        val origin = start ?: return target
        if (mode != NavigationPuckTransitionMode.ANIMATE) return target
        val progress = fraction.coerceIn(0f, 1f).toDouble()
        return NavigationPuckPose(
            coordinate = Coordinate(
                latitude = origin.coordinate.latitude +
                    (target.coordinate.latitude - origin.coordinate.latitude) * progress,
                longitude = origin.coordinate.longitude +
                    (target.coordinate.longitude - origin.coordinate.longitude) * progress,
            ),
            bearingDegrees = normalizeBearing(
                origin.bearingDegrees +
                    signedBearingDifference(origin.bearingDegrees, target.bearingDegrees) * progress,
            ),
        )
    }
}

/** Plans visual movement independently from Compose and MapLibre. */
fun planNavigationPuckTransition(
    displayedPose: NavigationPuckPose?,
    previousTargetTimestampEpochMillis: Long?,
    targetPosition: NavigationPosition,
    config: NavigationPuckMotionConfig = NavigationPuckMotionConfig(),
): NavigationPuckTransition {
    val target = NavigationPuckPose(
        coordinate = targetPosition.coordinate,
        bearingDegrees = targetPosition.bearingDegrees,
    )
    if (displayedPose == null) {
        return NavigationPuckTransition(null, target, NavigationPuckTransitionMode.SNAP, 0, 0.0)
    }
    val distance = distanceMeters(displayedPose.coordinate, target.coordinate)
    if (targetPosition.speedMetersPerSecond <= config.stationarySpeedMetersPerSecond &&
        distance <= config.stationaryDeadbandMeters
    ) {
        return NavigationPuckTransition(
            displayedPose,
            displayedPose,
            NavigationPuckTransitionMode.HOLD,
            0,
            distance,
        )
    }
    val intervalMillis = previousTargetTimestampEpochMillis?.let {
        targetPosition.timestampEpochMillis - it
    }
    if (distance > config.maximumInterpolatedDistanceMeters ||
        intervalMillis == null || intervalMillis <= 0
    ) {
        return NavigationPuckTransition(
            displayedPose,
            target,
            NavigationPuckTransitionMode.SNAP,
            0,
            distance,
        )
    }
    val duration = (intervalMillis * config.animationIntervalFraction).toLong().coerceIn(
        config.minimumAnimationMillis,
        config.maximumAnimationMillis,
    )
    return NavigationPuckTransition(
        displayedPose,
        target,
        NavigationPuckTransitionMode.ANIMATE,
        duration,
        distance,
    )
}
