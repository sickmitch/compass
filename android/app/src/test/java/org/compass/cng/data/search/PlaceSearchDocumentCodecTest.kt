package org.compass.cng.data.search

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.PlaceKind
import org.compass.cng.domain.model.PlaceSearchResult
import org.compass.cng.domain.model.PlaceSearchResults
import org.compass.cng.domain.model.PlaceSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSearchDocumentCodecTest {
    private val codec = PlaceSearchDocumentCodec()

    @Test
    fun storesExactNormalizedQueryAndReturnsExplicitCacheProvenance() {
        val document = codec.put(null, result("Duomo di Milano", "duomo"), 1234, 10)

        val cached = requireNotNull(codec.get(document, "  DUOMO DI MILANO "))

        assertEquals(PlaceSearchSource.CACHE, cached.source)
        assertEquals(1234L, cached.cachedAtEpochMillis)
        assertEquals("duomo", cached.results.single().id)
        assertNull(codec.get(document, "Basilica di San Petronio"))
    }

    @Test
    fun enforcesBoundAndIgnoresCorruptDocuments() {
        val first = codec.put(null, result("uno", "1"), 1, 2)
        val second = codec.put(first, result("due", "2"), 2, 2)
        val third = codec.put(second, result("tre", "3"), 3, 2)

        assertNull(codec.get(third, "uno"))
        assertEquals("2", codec.get(third, "due")?.results?.single()?.id)
        assertNull(codec.get("not-json", "due"))
    }

    private fun result(query: String, id: String) = PlaceSearchResults(
        query = query,
        results = listOf(
            PlaceSearchResult(
                id, query, null, Coordinate(45.0, 9.0), PlaceKind.POI, null, query, "test",
            ),
        ),
    )
}
