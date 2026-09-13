package org.compass.cng.data.favorite

import org.compass.cng.domain.favorite.FavoritePlace
import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritePlaceDocumentCodecTest {
    private val codec = FavoritePlaceDocumentCodec()

    @Test
    fun documentRoundTripPreservesOnlyPrivateNameAndCoordinate() {
        val places = listOf(
            FavoritePlace("work", "Ufficio", Coordinate(45.4384, 10.9916)),
            FavoritePlace("home", "Casa", Coordinate(45.4521, 10.9843)),
        )

        val encoded = codec.encode(places)
        val decoded = codec.decode(encoded)

        assertEquals(listOf("Casa", "Ufficio"), decoded.map { it.name })
        assertEquals(Coordinate(45.4384, 10.9916), decoded.last().location)
        assertTrue("Google" !in encoded)
        assertTrue("address" !in encoded)
    }

    @Test
    fun corruptOrUnsupportedDocumentsAreIgnoredSafely() {
        assertTrue(codec.decode("not-json").isEmpty())
        assertTrue(codec.decode("""{"schemaVersion":2,"places":[]}""").isEmpty())
    }
}
