package org.compass.cng.domain.geometry

import kotlin.math.roundToLong
import org.compass.cng.domain.model.Coordinate

object Polyline6Encoder {
    fun encode(coordinates: List<Coordinate>): String {
        require(coordinates.size >= 2) { "a route geometry needs at least two points" }
        val encoded = StringBuilder()
        var previousLatitude = 0L
        var previousLongitude = 0L
        coordinates.forEach { coordinate ->
            require(coordinate.latitude in -90.0..90.0)
            require(coordinate.longitude in -180.0..180.0)
            val latitude = (coordinate.latitude * 1_000_000).roundToLong()
            val longitude = (coordinate.longitude * 1_000_000).roundToLong()
            encoded.appendValue(latitude - previousLatitude)
            encoded.appendValue(longitude - previousLongitude)
            previousLatitude = latitude
            previousLongitude = longitude
        }
        return encoded.toString()
    }

    private fun StringBuilder.appendValue(delta: Long) {
        var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (value >= 0x20) {
            append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        append((value + 63).toInt().toChar())
    }
}
