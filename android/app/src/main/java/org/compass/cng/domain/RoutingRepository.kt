package org.compass.cng.domain

import java.time.OffsetDateTime
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.DestinationSuggestRequest
import org.compass.cng.domain.model.DestinationSuggestions
import org.compass.cng.domain.model.DestinationSuggestion
import org.compass.cng.domain.model.ResolvedDestination
import org.compass.cng.domain.model.PlaceSearchResults
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithIntermediateStop
import org.compass.cng.domain.model.RouteWithIntermediateStops
import org.compass.cng.domain.model.asMultiple
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary

interface RoutingRepository {
    suspend fun suggestDestinations(request: DestinationSuggestRequest): DestinationSuggestions =
        throw UnsupportedOperationException("destination suggestions are unavailable")

    suspend fun resolveDestination(
        sessionId: String,
        revision: Int,
        suggestion: DestinationSuggestion,
    ): ResolvedDestination = throw UnsupportedOperationException(
        "destination resolution is unavailable",
    )

    suspend fun searchPlaces(
        query: String,
        limit: Int = 8,
    ): PlaceSearchResults = throw UnsupportedOperationException("place search is unavailable")

    suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): RoutePreview

    suspend fun routeWithIntermediateStop(
        origin: Coordinate,
        intermediateStop: Coordinate,
        destination: Coordinate,
    ): RouteWithIntermediateStop = throw UnsupportedOperationException(
        "intermediate-stop routing is unavailable",
    )

    suspend fun routeWithIntermediateStops(
        origin: Coordinate,
        intermediateStops: List<Coordinate>,
        destination: Coordinate,
    ): RouteWithIntermediateStops {
        require(intermediateStops.size == 1) {
            "multiple intermediate-stop routing is unavailable"
        }
        return routeWithIntermediateStop(origin, intermediateStops.single(), destination)
            .asMultiple()
    }

    suspend fun rankedCngStations(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
    ): RankedCngStations

    suspend fun routeWithCngStop(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationId: String,
    ): RouteWithCngStop

    suspend fun predictiveCngStations(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
        excludedMimitStationIds: Set<String> = emptySet(),
        estimatedRemainingGasolineRangeKm: Double? = null,
        reserveGasolineRangeKm: Double? = null,
    ): PredictiveCngSuggestion

    suspend fun routeWithCngItinerary(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationIds: List<String>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
    ): RouteWithCngItinerary
}

enum class RoutePreviewFailure {
    NETWORK,
    AUTHENTICATION,
    NO_ROUTE,
    STATION_NOT_FOUND,
    STATION_UNAVAILABLE,
    CNG_ITINERARY_OUT_OF_RANGE,
    SERVER,
    INVALID_RESPONSE,
}

class RoutePreviewException(
    val failure: RoutePreviewFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
