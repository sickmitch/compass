package org.compass.cng.domain.geometry

import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class Polyline6EncoderTest {
    @Test
    fun roundTripsCoordinatesWithoutSwappingLatitudeAndLongitude() {
        val coordinates = listOf(
            Coordinate(45.000001, 10.000002),
            Coordinate(45.100003, 10.200004),
            Coordinate(45.300005, 10.500006),
        )

        val encoded = Polyline6Encoder.encode(coordinates)

        assertEquals(coordinates, Polyline6Decoder.decode(encoded))
    }
}
