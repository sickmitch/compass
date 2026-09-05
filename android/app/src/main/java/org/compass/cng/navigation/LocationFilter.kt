package org.compass.cng.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.compass.cng.domain.model.Coordinate

data class LocationFilterPolicy(
    val maximumAccuracyMeters: Double = 75.0,
    val maximumPlausibleSpeedMetersPerSecond: Double = 100.0,
    val minimumPositionSmoothingAlpha: Double = 0.25,
    val maximumPositionSmoothingAlpha: Double = 0.75,
    val speedSmoothingAlpha: Double = 0.35,
    val bearingSmoothingAlpha: Double = 0.3,
    val stationarySpeedMetersPerSecond: Double = 1.2,
    val stationaryDeadbandMeters: Double = 3.0,
    val minimumBearingSpeedMetersPerSecond: Double = 2.0,
    val maximumDeliveryDelayMillis: Long = 10_000,
)

/** Rejects unusable fixes and smooths accepted position, speed and circular bearing. */
class LocationFilter(
    private val policy: LocationFilterPolicy = LocationFilterPolicy(),
) {
    private var previous: NavigationLocation? = null

    fun reset() {
        previous = null
    }

    fun filter(location: NavigationLocation): NavigationLocation? {
        val deliveryDelay = location.receivedAtEpochMillis?.minus(location.timestampEpochMillis)
        if (deliveryDelay != null && deliveryDelay > policy.maximumDeliveryDelayMillis) return null
        if (!location.coordinate.latitude.isFinite() ||
            location.coordinate.latitude !in -90.0..90.0 ||
            !location.coordinate.longitude.isFinite() ||
            location.coordinate.longitude !in -180.0..180.0 ||
            !location.accuracyMeters.isFinite() ||
            location.accuracyMeters <= 0.0 ||
            location.accuracyMeters > policy.maximumAccuracyMeters
        ) {
            return null
        }
        val last = previous
        if (last == null) {
            return location.normalized().also { previous = it }
        }
        val elapsedSeconds = (location.timestampEpochMillis - last.timestampEpochMillis) / 1_000.0
        if (elapsedSeconds <= 0.0) return null
        val displacement = distanceMeters(last.coordinate, location.coordinate)
        val tolerance = last.accuracyMeters + location.accuracyMeters
        if (displacement > tolerance &&
            (displacement - tolerance) / elapsedSeconds > policy.maximumPlausibleSpeedMetersPerSecond
        ) {
            return null
        }

        val accuracyWeight = 1.0 - location.accuracyMeters / policy.maximumAccuracyMeters
        val positionAlpha = accuracyWeight.coerceIn(
            policy.minimumPositionSmoothingAlpha,
            policy.maximumPositionSmoothingAlpha,
        )
        val speed = smoothNullable(
            last.speedMetersPerSecond,
            location.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 },
            policy.speedSmoothingAlpha,
        )
        val stationary = (last.speedMetersPerSecond ?: 0.0) <= policy.stationarySpeedMetersPerSecond &&
            (speed ?: 0.0) <= policy.stationarySpeedMetersPerSecond &&
            displacement <= policy.stationaryDeadbandMeters
        val coordinate = if (stationary) {
            last.coordinate
        } else {
            Coordinate(
                latitude = lerp(last.coordinate.latitude, location.coordinate.latitude, positionAlpha),
                longitude = lerp(last.coordinate.longitude, location.coordinate.longitude, positionAlpha),
            )
        }
        val bearing = if ((speed ?: 0.0) < policy.minimumBearingSpeedMetersPerSecond) {
            last.bearingDegrees
        } else {
            smoothBearing(last.bearingDegrees, location.bearingDegrees)
        }
        return location.copy(
            coordinate = coordinate,
            speedMetersPerSecond = speed,
            bearingDegrees = bearing,
        ).also { previous = it }
    }

    private fun NavigationLocation.normalized() = copy(
        speedMetersPerSecond = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 },
        bearingDegrees = bearingDegrees
            ?.takeIf {
                it.isFinite() &&
                    (speedMetersPerSecond ?: 0.0) >= policy.minimumBearingSpeedMetersPerSecond
            }
            ?.let(::normalizeBearing),
    )

    private fun smoothBearing(previous: Double?, current: Double?): Double? {
        if (current == null || !current.isFinite()) return previous
        if (previous == null) return normalizeBearing(current)
        val alpha = policy.bearingSmoothingAlpha
        val previousRadians = previous.toRadians()
        val currentRadians = current.toRadians()
        val x = (1 - alpha) * cos(previousRadians) + alpha * cos(currentRadians)
        val y = (1 - alpha) * sin(previousRadians) + alpha * sin(currentRadians)
        return normalizeBearing(atan2(y, x) * 180.0 / Math.PI)
    }
}

private fun lerp(start: Double, end: Double, alpha: Double): Double = start + (end - start) * alpha

private fun smoothNullable(previous: Double?, current: Double?, alpha: Double): Double? = when {
    current == null -> previous
    previous == null -> current
    else -> lerp(previous, current, alpha)
}
