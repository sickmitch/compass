package org.compass.cng.domain

import java.time.OffsetDateTime
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary

interface RoutingRepository {
    suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): RoutePreview

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
