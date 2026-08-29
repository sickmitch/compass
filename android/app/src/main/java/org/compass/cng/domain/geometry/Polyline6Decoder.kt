package org.compass.cng.domain.geometry

import org.compass.cng.domain.model.Coordinate

object Polyline6Decoder {
    private const val PRECISION = 1_000_000.0

    fun decode(encoded: String): List<Coordinate> {
        require(encoded.isNotEmpty()) { "encoded polyline must not be empty" }

        var index = 0
        var latitude = 0L
        var longitude = 0L
        val coordinates = mutableListOf<Coordinate>()

        while (index < encoded.length) {
            val latitudeChunk = decodeChunk(encoded, index)
            index = latitudeChunk.nextIndex
            val longitudeChunk = decodeChunk(encoded, index)
            index = longitudeChunk.nextIndex

            latitude += latitudeChunk.delta
            longitude += longitudeChunk.delta
            coordinates += Coordinate(
                latitude = latitude / PRECISION,
                longitude = longitude / PRECISION,
            )
        }

        return coordinates
    }

    private fun decodeChunk(encoded: String, startIndex: Int): DecodedChunk {
        var index = startIndex
        var result = 0L
        var shift = 0

        while (true) {
            require(index < encoded.length) { "truncated encoded polyline" }
            val value = encoded[index++].code - 63
            require(value in 0..63) { "invalid encoded polyline character" }
            result = result or ((value and 0x1f).toLong() shl shift)
            if (value < 0x20) {
                break
            }
            shift += 5
            require(shift <= 60) { "encoded polyline coordinate is too large" }
        }

        val delta = if ((result and 1L) == 1L) -(result shr 1) - 1 else result shr 1
        return DecodedChunk(delta = delta, nextIndex = index)
    }

    private data class DecodedChunk(
        val delta: Long,
        val nextIndex: Int,
    )
}
