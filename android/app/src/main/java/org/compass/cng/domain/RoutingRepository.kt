package org.compass.cng.domain

import java.time.OffsetDateTime
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop

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
}

enum class RoutePreviewFailure {
    NETWORK,
    NO_ROUTE,
    STATION_NOT_FOUND,
    STATION_UNAVAILABLE,
    SERVER,
    INVALID_RESPONSE,
}

class RoutePreviewException(
    val failure: RoutePreviewFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
