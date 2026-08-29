package org.compass.cng.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Polyline6DecoderTest {
    @Test
    fun decodesKnownPolyline6Coordinates() {
        val coordinates = Polyline6Decoder.decode("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI")

        assertEquals(3, coordinates.size)
        assertEquals(38.5, coordinates[0].latitude, 0.000001)
        assertEquals(-120.2, coordinates[0].longitude, 0.000001)
        assertEquals(40.7, coordinates[1].latitude, 0.000001)
        assertEquals(-120.95, coordinates[1].longitude, 0.000001)
        assertEquals(43.252, coordinates[2].latitude, 0.000001)
        assertEquals(-126.453, coordinates[2].longitude, 0.000001)
    }

    @Test
    fun rejectsTruncatedPolyline() {
        assertThrows(IllegalArgumentException::class.java) {
            Polyline6Decoder.decode("_")
        }
    }
}
