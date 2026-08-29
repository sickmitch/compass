package org.compass.cng.domain

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RoutePreview

interface RoutingRepository {
    suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): RoutePreview
}

enum class RoutePreviewFailure {
    NETWORK,
    NO_ROUTE,
    SERVER,
    INVALID_RESPONSE,
}

class RoutePreviewException(
    val failure: RoutePreviewFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
