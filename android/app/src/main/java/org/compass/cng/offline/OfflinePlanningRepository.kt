package org.compass.cng.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.compass.cng.domain.*
import org.compass.cng.domain.model.*

/** Online-first A→B/ordinary-waypoint planning. CNG and search remain server operations. */
class OfflinePlanningRepository(
    private val remote: RoutingRepository,
    private val offline: OfflineRouter,
    private val availability: BackendAvailability,
) : RoutingRepository by remote {
    override suspend fun previewRoute(origin: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?): RoutePreview =
        previewRoute(origin, destination, originDirection, true, RouteTravelMode.DRIVING)

    override suspend fun previewRoute(origin: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?, allowHighways: Boolean): RoutePreview =
        previewRoute(origin, destination, originDirection, allowHighways, RouteTravelMode.DRIVING)

    override suspend fun previewRoute(origin: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?, allowHighways: Boolean, travelMode: RouteTravelMode): RoutePreview =
        route(travelMode, online = { remote.previewRoute(origin, destination, originDirection, allowHighways, travelMode) }) {
            val legs = local(listOf(origin, destination), allowHighways, originDirection)
            legs.single()
        }

    override suspend fun routeWithIntermediateStop(origin: Coordinate, intermediateStop: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?): RouteWithIntermediateStop =
        routeWithIntermediateStop(origin, intermediateStop, destination, originDirection, true, RouteTravelMode.DRIVING)

    override suspend fun routeWithIntermediateStop(origin: Coordinate, intermediateStop: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?, allowHighways: Boolean): RouteWithIntermediateStop =
        routeWithIntermediateStop(origin, intermediateStop, destination, originDirection, allowHighways, RouteTravelMode.DRIVING)

    override suspend fun routeWithIntermediateStop(origin: Coordinate, intermediateStop: Coordinate, destination: Coordinate, originDirection: RouteOriginDirection?, allowHighways: Boolean, travelMode: RouteTravelMode): RouteWithIntermediateStop =
        route(travelMode, online = { remote.routeWithIntermediateStop(origin, intermediateStop, destination, originDirection, allowHighways, travelMode) }) {
            val legs = local(listOf(origin, intermediateStop, destination), allowHighways, originDirection)
            val duration = legs.sumOf { it.durationSeconds }
            val timing = NavigationTiming.legacy(duration).copy(routeId = legs.first().navigation.routeId)
            RouteWithIntermediateStop(intermediateStop, legs.sumOf { it.distanceMeters },
                duration, legs, OFFLINE_PROVIDER, timing)
        }

    override suspend fun routeWithIntermediateStops(origin: Coordinate, intermediateStops: List<Coordinate>, destination: Coordinate, originDirection: RouteOriginDirection?): RouteWithIntermediateStops =
        routeWithIntermediateStops(origin, intermediateStops, destination, originDirection, true, RouteTravelMode.DRIVING)

    override suspend fun routeWithIntermediateStops(origin: Coordinate, intermediateStops: List<Coordinate>, destination: Coordinate, originDirection: RouteOriginDirection?, allowHighways: Boolean): RouteWithIntermediateStops =
        routeWithIntermediateStops(origin, intermediateStops, destination, originDirection, allowHighways, RouteTravelMode.DRIVING)

    override suspend fun routeWithIntermediateStops(origin: Coordinate, intermediateStops: List<Coordinate>, destination: Coordinate, originDirection: RouteOriginDirection?, allowHighways: Boolean, travelMode: RouteTravelMode): RouteWithIntermediateStops =
        route(travelMode, online = { remote.routeWithIntermediateStops(origin, intermediateStops, destination, originDirection, allowHighways, travelMode) }) {
            val legs = local(listOf(origin) + intermediateStops + destination, allowHighways, originDirection)
            val duration = legs.sumOf { it.durationSeconds }
            val timing = NavigationTiming.legacy(duration).copy(routeId = legs.first().navigation.routeId)
            RouteWithIntermediateStops(intermediateStops, legs.sumOf { it.distanceMeters },
                duration, legs, OFFLINE_PROVIDER, timing)
        }

    private suspend fun <T> route(mode: RouteTravelMode, online: suspend () -> T, local: suspend () -> T): T {
        if (!availability.available.value) {
            if (mode == RouteTravelMode.DRIVING && offline.available) return local()
            throw RoutePreviewException(RoutePreviewFailure.NETWORK)
        }
        val canFallback = mode == RouteTravelMode.DRIVING && offline.available
        try {
            val result = if (canFallback) withTimeoutOrNull(8_000) { online() } else online()
            if (result != null) {
                availability.serverResponded(true)
                return result
            }
        } catch (e: RoutePreviewException) {
            if (e.failure !in setOf(RoutePreviewFailure.NETWORK, RoutePreviewFailure.SERVER)) throw e
            availability.serverResponded(false)
            if (!canFallback) throw e
        }
        availability.serverResponded(false)
        return local()
    }

    private suspend fun local(points: List<Coordinate>, highways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> =
        try { offline.route(points, highways, direction) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw RoutePreviewException(RoutePreviewFailure.NO_ROUTE, e) }
        catch (e: LinkageError) { throw RoutePreviewException(RoutePreviewFailure.SERVER, e) }
}
