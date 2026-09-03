package org.compass.cng.data.search

import android.content.Context
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.PlaceKind
import org.compass.cng.domain.model.PlaceSearchResult
import org.compass.cng.domain.model.PlaceSearchResults
import org.compass.cng.domain.model.PlaceSearchSource

interface PlaceSearchCache {
    fun get(query: String): PlaceSearchResults?

    fun put(results: PlaceSearchResults)
}

object NoOpPlaceSearchCache : PlaceSearchCache {
    override fun get(query: String): PlaceSearchResults? = null

    override fun put(results: PlaceSearchResults) = Unit
}

class SharedPreferencesPlaceSearchCache internal constructor(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codec: PlaceSearchDocumentCodec = PlaceSearchDocumentCodec(),
) : PlaceSearchCache {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun get(query: String): PlaceSearchResults? = codec.get(
        preferences.getString(DOCUMENT_KEY, null),
        query,
    )

    override fun put(results: PlaceSearchResults) {
        if (results.source != PlaceSearchSource.LIVE || results.results.isEmpty()) return
        val updated = codec.put(
            document = preferences.getString(DOCUMENT_KEY, null),
            results = results,
            cachedAtEpochMillis = clock(),
            maximumEntries = MAX_ENTRIES,
        )
        preferences.edit().putString(DOCUMENT_KEY, updated).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_place_search_cache"
        const val DOCUMENT_KEY = "recent_searches_v1"
        const val MAX_ENTRIES = 10
    }
}

internal class PlaceSearchDocumentCodec(
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = true },
) {
    fun get(document: String?, query: String): PlaceSearchResults? = try {
        decode(document)
            .firstOrNull { it.normalizedQuery == normalize(query) }
            ?.toDomain()
    } catch (_: IllegalArgumentException) {
        null
    }

    fun put(
        document: String?,
        results: PlaceSearchResults,
        cachedAtEpochMillis: Long,
        maximumEntries: Int,
    ): String {
        require(maximumEntries > 0)
        val entry = StoredSearchEntry.fromDomain(results, cachedAtEpochMillis)
        val entries = decode(document)
            .filterNot { it.normalizedQuery == entry.normalizedQuery }
            .plus(entry)
            .sortedByDescending(StoredSearchEntry::cachedAtEpochMillis)
            .take(maximumEntries)
        return json.encodeToString(StoredSearchDocument(entries = entries))
    }

    private fun decode(document: String?): List<StoredSearchEntry> {
        if (document.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<StoredSearchDocument>(document).entries
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}

@Serializable
private data class StoredSearchDocument(
    val schemaVersion: Int = 1,
    val entries: List<StoredSearchEntry>,
) {
    init {
        require(schemaVersion == 1) { "unsupported place-search cache schema" }
    }
}

@Serializable
private data class StoredSearchEntry(
    val normalizedQuery: String,
    val query: String,
    val cachedAtEpochMillis: Long,
    val results: List<StoredSearchResult>,
) {
    init {
        require(normalizedQuery.isNotBlank() && cachedAtEpochMillis >= 0)
    }
    fun toDomain() = PlaceSearchResults(
        query = query,
        results = results.map(StoredSearchResult::toDomain),
        source = PlaceSearchSource.CACHE,
        cachedAtEpochMillis = cachedAtEpochMillis,
    )

    companion object {
        fun fromDomain(value: PlaceSearchResults, cachedAtEpochMillis: Long) = StoredSearchEntry(
            normalizedQuery = normalize(value.query),
            query = value.query,
            cachedAtEpochMillis = cachedAtEpochMillis,
            results = value.results.map(StoredSearchResult::fromDomain),
        )
    }
}

@Serializable
private data class StoredSearchResult(
    val id: String,
    val displayName: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val category: String?,
    val poiName: String?,
    val provider: String,
) {
    fun toDomain() = PlaceSearchResult(
        id, displayName, address, Coordinate(latitude, longitude),
        PlaceKind.valueOf(kind), category, poiName, provider,
    )

    companion object {
        fun fromDomain(value: PlaceSearchResult) = StoredSearchResult(
            value.id, value.displayName, value.address, value.location.latitude,
            value.location.longitude, value.kind.name, value.category, value.poiName, value.provider,
        )
    }
}

private fun normalize(query: String): String = query.trim().lowercase(Locale.ROOT)
