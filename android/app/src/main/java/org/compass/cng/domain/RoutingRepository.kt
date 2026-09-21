package org.compass.cng.domain

import java.time.OffsetDateTime
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.AlongRouteSearchRequest
import org.compass.cng.domain.model.AlongRouteSearchResults
import org.compass.cng.domain.model.DestinationSuggestRequest
import org.compass.cng.domain.model.DestinationSuggestions
import org.compass.cng.domain.model.DestinationSuggestion
import org.compass.cng.domain.model.ResolvedDestination
import org.compass.cng.domain.model.PlaceSearchResults
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteTravelMode
import org.compass.cng.domain.model.RouteWithIntermediateStop
import org.compass.cng.domain.model.RouteWithIntermediateStops
import org.compass.cng.domain.model.asMultiple
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary

data class RouteOriginDirection(
    val headingDegrees: Double,
    val headingToleranceDegrees: Int,
) {
    init {
        require(headingDegrees.isFinite() && headingDegrees >= 0.0 && headingDegrees < 360.0)
        require(headingToleranceDegrees in 0..180)
    }
}

interface RoutingRepository {
    suspend fun searchAlongRoute(request: AlongRouteSearchRequest): AlongRouteSearchResults =
        throw UnsupportedOperationException("along-route search is unavailable")

    suspend fun resolveAlongRoute(
        search: AlongRouteSearchResults,
        suggestion: DestinationSuggestion,
        currentRoute: org.compass.cng.domain.model.AlongRouteContext,
    ): ResolvedDestination = throw UnsupportedOperationException(
        "along-route selection resolution is unavailable",
    )

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
        originDirection: RouteOriginDirection? = null,
    ): RoutePreview

    suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): RoutePreview = previewRoute(origin, destination, originDirection)

    suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
        travelMode: RouteTravelMode,
    ): RoutePreview = previewRoute(origin, destination, originDirection, allowHighways).copy(
        travelMode = travelMode,
    )

    suspend fun routeWithIntermediateStop(
        origin: Coordinate,
        intermediateStop: Coordinate,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
    ): RouteWithIntermediateStop = throw UnsupportedOperationException(
        "intermediate-stop routing is unavailable",
    )

    suspend fun routeWithIntermediateStop(
        origin: Coordinate,
        intermediateStop: Coordinate,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): RouteWithIntermediateStop = routeWithIntermediateStop(
        origin, intermediateStop, destination, originDirection,
    )

    suspend fun routeWithIntermediateStop(
        origin: Coordinate,
        intermediateStop: Coordinate,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
        travelMode: RouteTravelMode,
    ): RouteWithIntermediateStop = routeWithIntermediateStop(
        origin, intermediateStop, destination, originDirection, allowHighways,
    ).let { route ->
        route.copy(legs = route.legs.map { it.copy(travelMode = travelMode) })
    }

    suspend fun routeWithIntermediateStops(
        origin: Coordinate,
        intermediateStops: List<Coordinate>,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
    ): RouteWithIntermediateStops {
        require(intermediateStops.size == 1) {
            "multiple intermediate-stop routing is unavailable"
        }
        return routeWithIntermediateStop(
            origin,
            intermediateStops.single(),
            destination,
            originDirection,
        )
            .asMultiple()
    }

    suspend fun routeWithIntermediateStops(
        origin: Coordinate,
        intermediateStops: List<Coordinate>,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): RouteWithIntermediateStops = routeWithIntermediateStops(
        origin, intermediateStops, destination, originDirection,
    )

    suspend fun routeWithIntermediateStops(
        origin: Coordinate,
        intermediateStops: List<Coordinate>,
        destination: Coordinate,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
        travelMode: RouteTravelMode,
    ): RouteWithIntermediateStops = routeWithIntermediateStops(
        origin, intermediateStops, destination, originDirection, allowHighways,
    ).let { route ->
        route.copy(legs = route.legs.map { it.copy(travelMode = travelMode) })
    }

    suspend fun rankedCngStations(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
    ): RankedCngStations

    suspend fun rankedCngStations(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
        allowHighways: Boolean,
    ): RankedCngStations = rankedCngStations(
        origin, destination, effectiveCngRangeKm, maximumDetourMinutes, departureAt,
    )

    suspend fun rankedCngStationsAlongItinerary(
        origin: Coordinate,
        destination: Coordinate,
        intermediateStops: List<Coordinate>,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
    ): RankedCngStations = rankedCngStations(
        origin = origin,
        destination = destination,
        effectiveCngRangeKm = effectiveCngRangeKm,
        maximumDetourMinutes = maximumDetourMinutes,
        departureAt = departureAt,
    )


    suspend fun rankedCngStationsAlongItinerary(
        origin: Coordinate,
        destination: Coordinate,
        intermediateStops: List<Coordinate>,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
        allowHighways: Boolean,
    ): RankedCngStations = rankedCngStationsAlongItinerary(
        origin, destination, intermediateStops, effectiveCngRangeKm,
        maximumDetourMinutes, departureAt,
    )

    suspend fun routeWithCngStop(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationId: String,
        originDirection: RouteOriginDirection? = null,
    ): RouteWithCngStop

    suspend fun routeWithCngStop(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationId: String,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): RouteWithCngStop = routeWithCngStop(
        origin, destination, mimitStationId, originDirection,
    )

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
        originDirection: RouteOriginDirection? = null,
    ): PredictiveCngSuggestion

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
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): PredictiveCngSuggestion = predictiveCngStations(
        origin, destination, effectiveCngRangeKm, estimatedRemainingCngRangeKm,
        reserveCngRangeKm, maximumDetourMinutes, departureAt, excludedMimitStationIds,
        estimatedRemainingGasolineRangeKm, reserveGasolineRangeKm, originDirection,
    )

    suspend fun predictiveCngStationsAlongItinerary(
        origin: Coordinate,
        destination: Coordinate,
        intermediateStops: List<Coordinate>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
        excludedMimitStationIds: Set<String> = emptySet(),
        estimatedRemainingGasolineRangeKm: Double? = null,
        reserveGasolineRangeKm: Double? = null,
        originDirection: RouteOriginDirection? = null,
    ): PredictiveCngSuggestion = predictiveCngStations(
        origin = origin,
        destination = destination,
        effectiveCngRangeKm = effectiveCngRangeKm,
        estimatedRemainingCngRangeKm = estimatedRemainingCngRangeKm,
        reserveCngRangeKm = reserveCngRangeKm,
        maximumDetourMinutes = maximumDetourMinutes,
        departureAt = departureAt,
        excludedMimitStationIds = excludedMimitStationIds,
        estimatedRemainingGasolineRangeKm = estimatedRemainingGasolineRangeKm,
        reserveGasolineRangeKm = reserveGasolineRangeKm,
        originDirection = originDirection,
    )

    suspend fun predictiveCngStationsAlongItinerary(
        origin: Coordinate,
        destination: Coordinate,
        intermediateStops: List<Coordinate>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
        excludedMimitStationIds: Set<String> = emptySet(),
        estimatedRemainingGasolineRangeKm: Double? = null,
        reserveGasolineRangeKm: Double? = null,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): PredictiveCngSuggestion = predictiveCngStationsAlongItinerary(
        origin, destination, intermediateStops, effectiveCngRangeKm,
        estimatedRemainingCngRangeKm, reserveCngRangeKm, maximumDetourMinutes,
        departureAt, excludedMimitStationIds, estimatedRemainingGasolineRangeKm,
        reserveGasolineRangeKm, originDirection,
    )

    suspend fun routeWithCngItinerary(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationIds: List<String>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        originDirection: RouteOriginDirection? = null,
    ): RouteWithCngItinerary

    suspend fun routeWithCngItinerary(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationIds: List<String>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        originDirection: RouteOriginDirection? = null,
        allowHighways: Boolean,
    ): RouteWithCngItinerary = routeWithCngItinerary(
        origin, destination, mimitStationIds, effectiveCngRangeKm,
        estimatedRemainingCngRangeKm, reserveCngRangeKm, originDirection,
    )
}

enum class RoutePreviewFailure {
    NETWORK,
    AUTHENTICATION,
    NO_ROUTE,
    STATION_NOT_FOUND,
    STATION_UNAVAILABLE,
    CNG_ITINERARY_OUT_OF_RANGE,
    ROUTE_REQUIRED,
    STALE_SEARCH_CONTEXT,
    RATE_LIMITED,
    SERVER,
    INVALID_RESPONSE,
}

class RoutePreviewException(
    val failure: RoutePreviewFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
