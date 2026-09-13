package org.compass.cng.data.favorite

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.compass.cng.domain.favorite.FavoritePlace
import org.compass.cng.domain.favorite.FavoritePlaceRepository
import org.compass.cng.domain.favorite.validateAndSort
import org.compass.cng.domain.model.Coordinate

class SharedPreferencesFavoritePlaceRepository internal constructor(
    context: Context,
    private val codec: FavoritePlaceDocumentCodec = FavoritePlaceDocumentCodec(),
) : FavoritePlaceRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): List<FavoritePlace> = codec.decode(preferences.getString(DOCUMENT_KEY, null))

    override fun save(place: FavoritePlace): List<FavoritePlace> {
        val updated = validateAndSort(load().filterNot { it.id == place.id } + place)
        return persist(updated)
    }

    override fun delete(placeId: String): List<FavoritePlace> = persist(
        load().filterNot { it.id == placeId },
    )

    private fun persist(places: List<FavoritePlace>): List<FavoritePlace> {
        check(preferences.edit().putString(DOCUMENT_KEY, codec.encode(places)).commit()) {
            "favorite places could not be persisted"
        }
        return places
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_favorite_places"
        const val DOCUMENT_KEY = "favorite_places_v1"
    }
}

internal class FavoritePlaceDocumentCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    },
) {
    fun encode(places: List<FavoritePlace>): String = json.encodeToString(
        StoredFavoritePlaces(places = places.map(StoredFavoritePlace::fromDomain)),
    )

    fun decode(value: String?): List<FavoritePlace> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            validateAndSort(
                json.decodeFromString<StoredFavoritePlaces>(value)
                    .places
                    .map(StoredFavoritePlace::toDomain),
            )
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}

@Serializable
private data class StoredFavoritePlaces(
    val schemaVersion: Int = 1,
    val places: List<StoredFavoritePlace>,
) {
    init {
        require(schemaVersion == 1) { "unsupported favorite-place schema" }
    }
}

@Serializable
private data class StoredFavoritePlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    fun toDomain(): FavoritePlace = FavoritePlace(
        id = id,
        name = name,
        location = Coordinate(latitude, longitude),
    )

    companion object {
        fun fromDomain(place: FavoritePlace): StoredFavoritePlace = StoredFavoritePlace(
            id = place.id,
            name = place.name,
            latitude = place.location.latitude,
            longitude = place.location.longitude,
        )
    }
}
