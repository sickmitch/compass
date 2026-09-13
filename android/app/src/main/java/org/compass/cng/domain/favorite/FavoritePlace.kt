package org.compass.cng.domain.favorite

import org.compass.cng.domain.model.Coordinate

data class FavoritePlace(
    val id: String,
    val name: String,
    val location: Coordinate,
) {
    init {
        require(id.isNotBlank()) { "favorite place id must not be blank" }
        require(name.isNotBlank() && name.length <= MAX_FAVORITE_PLACE_NAME_LENGTH) {
            "favorite place name must contain 1 to $MAX_FAVORITE_PLACE_NAME_LENGTH characters"
        }
    }
}

interface FavoritePlaceRepository {
    fun load(): List<FavoritePlace>

    fun save(place: FavoritePlace): List<FavoritePlace>

    fun delete(placeId: String): List<FavoritePlace>
}

class InMemoryFavoritePlaceRepository(
    initial: List<FavoritePlace> = emptyList(),
) : FavoritePlaceRepository {
    private var value = validateAndSort(initial)

    override fun load(): List<FavoritePlace> = value

    override fun save(place: FavoritePlace): List<FavoritePlace> {
        val updated = value.filterNot { it.id == place.id } + place
        value = validateAndSort(updated)
        return value
    }

    override fun delete(placeId: String): List<FavoritePlace> {
        value = value.filterNot { it.id == placeId }
        return value
    }
}

internal fun validateAndSort(places: List<FavoritePlace>): List<FavoritePlace> {
    require(places.size <= MAX_FAVORITE_PLACES) {
        "at most $MAX_FAVORITE_PLACES favorite places may be stored"
    }
    require(places.map(FavoritePlace::id).distinct().size == places.size) {
        "favorite place ids must be unique"
    }
    require(places.map { it.name.trim().lowercase() }.distinct().size == places.size) {
        "favorite place names must be unique"
    }
    return places.sortedBy { it.name.lowercase() }
}

const val MAX_FAVORITE_PLACES = 50
const val MAX_FAVORITE_PLACE_NAME_LENGTH = 60
