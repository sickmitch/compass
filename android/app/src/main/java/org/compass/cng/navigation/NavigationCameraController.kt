package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate

enum class NavigationCameraMode {
    FOLLOW,
    NORTH_UP,
    OVERVIEW,
    FREE,
}

val NavigationCameraMode.tracksVehiclePosition: Boolean
    get() = this == NavigationCameraMode.FOLLOW || this == NavigationCameraMode.NORTH_UP

data class NavigationMapControlPolicy(
    val showOrientationToggle: Boolean,
    val showOverview: Boolean,
    val showRecenter: Boolean,
)

fun NavigationCameraMode.mapControlPolicy() = NavigationMapControlPolicy(
    showOrientationToggle = tracksVehiclePosition,
    showOverview = tracksVehiclePosition,
    showRecenter = !tracksVehiclePosition,
)

fun NavigationCameraMode.toggleOrientation(): NavigationCameraMode = when (this) {
    NavigationCameraMode.FOLLOW -> NavigationCameraMode.NORTH_UP
    NavigationCameraMode.NORTH_UP -> NavigationCameraMode.FOLLOW
    NavigationCameraMode.OVERVIEW,
    NavigationCameraMode.FREE,
    -> NavigationCameraMode.FOLLOW
}

internal fun NavigationCameraMode.resolvedBearingDegrees(followBearingDegrees: Double): Double? =
    when (this) {
        NavigationCameraMode.FOLLOW -> normalizeBearing(followBearingDegrees)
        NavigationCameraMode.NORTH_UP,
        NavigationCameraMode.OVERVIEW,
        -> 0.0
        NavigationCameraMode.FREE -> null
    }

data class NavigationCameraInstruction(
    val target: Coordinate,
    val bearingDegrees: Double,
    val zoom: Double,
    val pitchDegrees: Double,
    val animationMillis: Int,
)

/** Centralized driving-camera policy; MapLibre only executes the returned instruction. */
data class NavigationCameraConfig(
    val urbanZoom: Double = 16.8,
    val motorwayZoom: Double = 14.3,
    val urbanPitchDegrees: Double = 52.0,
    val motorwayPitchDegrees: Double = 58.0,
    val urbanSpeedMetersPerSecond: Double = 6.0,
    val motorwaySpeedMetersPerSecond: Double = 30.0,
    val closeManeuverDistanceMeters: Double = 180.0,
    val maneuverAwarenessDistanceMeters: Double = 1_200.0,
    val closeManeuverZoomBoost: Double = 0.8,
    val approachingManeuverMinimumZoomBoost: Double = 0.7,
    val approachingManeuverMaximumZoomBoost: Double = 1.4,
    val complexManeuverZoomBoost: Double = 0.25,
    val denseManeuverSpacingMeters: Double = 300.0,
    val sparseManeuverSpacingMeters: Double = 1_800.0,
    val denseManeuverZoomBoost: Double = 1.1,
    val sparseManeuverZoomReduction: Double = 0.55,
    val minimumFollowZoom: Double = 13.2,
    val maximumFollowZoom: Double = 18.2,
    val headingLookAheadMeters: Double = 28.0,
    val followPuckVerticalFraction: Double = 0.75,
    val freeModeAutoRecenterMillis: Long = 10_000,
    val followAnimationMillis: Int = 900,
    val overviewAnimationMillis: Int = 800,
    val overviewEdgePaddingDp: Int = 56,
) {
    init {
        require(urbanZoom > motorwayZoom)
        require(urbanPitchDegrees in 45.0..60.0)
        require(motorwayPitchDegrees in 45.0..60.0)
        require(motorwaySpeedMetersPerSecond > urbanSpeedMetersPerSecond)
        require(closeManeuverDistanceMeters > 0.0)
        require(maneuverAwarenessDistanceMeters >= closeManeuverDistanceMeters)
        require(approachingManeuverMinimumZoomBoost >= 0.0)
        require(approachingManeuverMaximumZoomBoost >= approachingManeuverMinimumZoomBoost)
        require(denseManeuverSpacingMeters > 0.0)
        require(sparseManeuverSpacingMeters > denseManeuverSpacingMeters)
        require(denseManeuverZoomBoost >= 0.0)
        require(sparseManeuverZoomReduction >= 0.0)
        require(maximumFollowZoom > minimumFollowZoom)
        require(headingLookAheadMeters > 0.0)
        require(followPuckVerticalFraction in 0.5..0.9)
        require(freeModeAutoRecenterMillis > 0)
        require(followAnimationMillis > 0)
        require(overviewAnimationMillis > 0)
        require(overviewEdgePaddingDp >= 0)
    }
}

class NavigationCameraController(
    val config: NavigationCameraConfig = NavigationCameraConfig(),
) {
    fun instruction(state: NavigationState): NavigationCameraInstruction {
        val route = requireNotNull(state.route) { "navigation camera needs an active route" }
        val speed = state.currentSpeedMetersPerSecond.takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0) ?: 0.0
        val maneuverDistance = state.distanceToNextManeuverMeters
        val maneuverType = state.currentManeuver?.type
        val complexJunction = maneuverType in COMPLEX_MANEUVER_TYPES
        val speedFraction = (
            (speed - config.urbanSpeedMetersPerSecond) /
                (config.motorwaySpeedMetersPerSecond - config.urbanSpeedMetersPerSecond)
            ).coerceIn(0.0, 1.0)
        val maneuverZoomBoost = maneuverDistance
            ?.takeIf { it.isFinite() && it >= 0.0 && it <= config.maneuverAwarenessDistanceMeters }
            ?.let { distance ->
                val proximity = 1.0 - distance / config.maneuverAwarenessDistanceMeters
                val approachBoost = lerp(
                    config.approachingManeuverMinimumZoomBoost,
                    config.approachingManeuverMaximumZoomBoost,
                    proximity,
                )
                val closeBoost = if (distance <= config.closeManeuverDistanceMeters) {
                    config.closeManeuverZoomBoost *
                        (1.0 - distance / config.closeManeuverDistanceMeters)
                } else {
                    0.0
                }
                approachBoost + closeBoost +
                    if (complexJunction) config.complexManeuverZoomBoost * proximity else 0.0
            }
            ?: 0.0
        val maneuverSpacingMeters = state.nextManeuver?.distanceMeters
        val maneuverDensityZoomAdjustment = maneuverSpacingZoomAdjustment(maneuverSpacingMeters)
        val remaining = state.routePortions().remaining
        val position = state.snappedLocation ?: remaining.firstOrNull() ?: route.origin
        val headingTarget = coordinateAlong(
            remaining.ifEmpty { listOf(position) },
            config.headingLookAheadMeters,
        )
        val bearing = if (position != headingTarget) {
            bearingDegrees(position, headingTarget)
        } else {
            state.vehicleBearingDegrees
                ?.takeIf { it.isFinite() }
                ?.let(::normalizeBearing)
        }
            ?: 0.0
        return NavigationCameraInstruction(
            // Screen-space padding in NavigationMap places this exact coordinate on the driving
            // anchor. A geographic look-ahead target would move the puck as zoom changes.
            target = position,
            bearingDegrees = bearing,
            zoom = (
                lerp(config.urbanZoom, config.motorwayZoom, speedFraction) +
                    maneuverZoomBoost + maneuverDensityZoomAdjustment
                ).coerceIn(config.minimumFollowZoom, config.maximumFollowZoom),
            pitchDegrees = lerp(
                config.urbanPitchDegrees,
                config.motorwayPitchDegrees,
                speedFraction,
            ),
            animationMillis = config.followAnimationMillis,
        )
    }

    private fun coordinateAlong(points: List<Coordinate>, requestedDistanceMeters: Double): Coordinate {
        if (points.size < 2 || requestedDistanceMeters <= 0.0) return points.first()
        var distanceLeft = requestedDistanceMeters
        points.zipWithNext().forEach { (start, end) ->
            val segmentDistance = distanceMeters(start, end)
            if (segmentDistance <= 0.0) return@forEach
            if (distanceLeft <= segmentDistance) {
                val fraction = (distanceLeft / segmentDistance).coerceIn(0.0, 1.0)
                return Coordinate(
                    latitude = lerp(start.latitude, end.latitude, fraction),
                    longitude = lerp(start.longitude, end.longitude, fraction),
                )
            }
            distanceLeft -= segmentDistance
        }
        return points.last()
    }

    private fun maneuverSpacingZoomAdjustment(spacingMeters: Double?): Double {
        val spacing = spacingMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: return 0.0
        val spacingFraction = (
            (spacing - config.denseManeuverSpacingMeters) /
                (config.sparseManeuverSpacingMeters - config.denseManeuverSpacingMeters)
            ).coerceIn(0.0, 1.0)
        return lerp(
            config.denseManeuverZoomBoost,
            -config.sparseManeuverZoomReduction,
            spacingFraction,
        )
    }

    private companion object {
        val COMPLEX_MANEUVER_TYPES = setOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 26, 27)
    }
}

private fun lerp(start: Double, end: Double, fraction: Double): Double =
    start + (end - start) * fraction

internal fun followTopPaddingPixels(
    viewportHeightPixels: Int,
    puckVerticalFraction: Double,
    bottomObstructionPixels: Int = 0,
): Double {
    val visibleHeight = viewportHeightPixels -
        bottomObstructionPixels.coerceIn(0, viewportHeightPixels)
    return visibleHeight * (2.0 * puckVerticalFraction - 1.0)
}
