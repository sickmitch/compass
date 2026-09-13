package org.compass.cng.domain.favorite

import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FavoritePlaceRepositoryTest {
    @Test
    fun placesAreStoredByIdentityAndSortedByPrivateName() {
        val repository = InMemoryFavoritePlaceRepository()
        val work = FavoritePlace("work", "Lavoro", Coordinate(45.42, 10.98))
        val home = FavoritePlace("home", "Casa", Coordinate(45.44, 10.99))

        repository.save(work)
        repository.save(home)
        repository.save(work.copy(name = "Ufficio"))

        assertEquals(listOf("Casa", "Ufficio"), repository.load().map { it.name })
        assertEquals(2, repository.load().size)
        assertEquals(listOf("Ufficio"), repository.delete("home").map { it.name })
    }

    @Test
    fun duplicateNamesAreRejectedCaseInsensitively() {
        val repository = InMemoryFavoritePlaceRepository(
            listOf(FavoritePlace("home", "Casa", Coordinate(45.44, 10.99))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            repository.save(FavoritePlace("other", " casa ", Coordinate(45.45, 11.0)))
        }
    }
}
