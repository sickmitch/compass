package org.compass.cng.navigation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.compass.cng.domain.model.Coordinate

internal const val EARTH_RADIUS_METERS = 6_371_008.8

internal fun distanceMeters(first: Coordinate, second: Coordinate): Double {
    val latitude1 = first.latitude.toRadians()
    val latitude2 = second.latitude.toRadians()
    val latitudeDelta = (second.latitude - first.latitude).toRadians()
    val longitudeDelta = (second.longitude - first.longitude).toRadians()
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(latitude1) * cos(latitude2) *
        sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
}

internal fun bearingDegrees(first: Coordinate, second: Coordinate): Double {
    val latitude1 = first.latitude.toRadians()
    val latitude2 = second.latitude.toRadians()
    val longitudeDelta = (second.longitude - first.longitude).toRadians()
    val y = sin(longitudeDelta) * cos(latitude2)
    val x = cos(latitude1) * sin(latitude2) -
        sin(latitude1) * cos(latitude2) * cos(longitudeDelta)
    return normalizeBearing(atan2(y, x) * 180.0 / PI)
}

internal fun normalizeBearing(value: Double): Double = (value % 360.0 + 360.0) % 360.0

internal fun bearingDifference(first: Double, second: Double): Double {
    val difference = kotlin.math.abs(normalizeBearing(first) - normalizeBearing(second))
    return minOf(difference, 360.0 - difference)
}

internal fun Double.toRadians(): Double = this * PI / 180.0
