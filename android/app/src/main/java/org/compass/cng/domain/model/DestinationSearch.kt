package org.compass.cng.domain.model

enum class DestinationKind {
    BUSINESS,
    ADDRESS,
    LOCALITY,
    UNKNOWN,
}

data class DestinationSearchContext(
    val location: Coordinate? = null,
    val biasRadiusMeters: Double? = null,
    val routeBounds: DestinationSearchBounds? = null,
)

data class DestinationSearchBounds(
    val southWest: Coordinate,
    val northEast: Coordinate,
)

data class DestinationSuggestRequest(
    val query: String,
    val sessionId: String,
    val revision: Int,
    val context: DestinationSearchContext = DestinationSearchContext(),
)

data class DestinationSuggestion(
    val id: String,
    val provider: String,
    val providerRef: String,
    val kind: DestinationKind,
    val title: String,
    val subtitle: String?,
    val addressPreview: String?,
    val distanceMeters: Int?,
    val providerRank: Int,
    val attribution: String,
)

data class DestinationSuggestions(
    val sessionId: String,
    val revision: Int,
    val results: List<DestinationSuggestion>,
)

data class AddressComponent(
    val longText: String,
    val shortText: String?,
    val types: List<String>,
)

data class NormalizedAddress(
    val street: String?,
    val streetNumber: String?,
    val locality: String?,
    val province: String?,
    val region: String?,
    val postalCode: String?,
    val country: String?,
)

data class ResolvedDestinationSelection(
    val provider: String,
    val providerRef: String,
    val formattedAddress: String?,
    val addressComponents: List<AddressComponent>,
    val normalizedAddress: NormalizedAddress,
    val location: Coordinate,
    val kind: DestinationKind,
    val attribution: List<String>,
    val fieldSources: Map<String, String>,
)

/** Deliberately excludes Google title/address before entering a MapLibre surface. */
data class NavigationTarget(
    val location: Coordinate,
    val providerRef: String?,
    val mapLabel: String,
)

data class ResolvedDestination(
    val sessionId: String,
    val revision: Int,
    val selection: ResolvedDestinationSelection,
    val navigationTarget: NavigationTarget,
)
