package org.compass.cng.domain.model

enum class PlaceKind {
    ADDRESS,
    LOCALITY,
    POI,
    COORDINATE,
    UNKNOWN,
}

enum class PlaceSearchSource {
    LIVE,
    CACHE,
}

data class PlaceSearchResult(
    val id: String,
    val displayName: String,
    val address: String?,
    val location: Coordinate,
    val kind: PlaceKind,
    val category: String?,
    val poiName: String?,
    val provider: String,
)

data class PlaceSearchResults(
    val query: String,
    val results: List<PlaceSearchResult>,
    val source: PlaceSearchSource = PlaceSearchSource.LIVE,
    val cachedAtEpochMillis: Long? = null,
)
