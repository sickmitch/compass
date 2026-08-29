package org.compass.cng.data.repository

import kotlinx.coroutines.CancellationException
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.geometry.Polyline6Decoder
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.RoutePreview

class HttpRoutingRepository(
    private val apiClient: CompassApiClient,
) : RoutingRepository {
    override suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): RoutePreview {
        try {
            val route = apiClient.getRoute(origin, destination)
            return RoutePreview(
                origin = origin,
                destination = destination,
                distanceMeters = route.distanceMeters,
                durationSeconds = route.durationSeconds,
                geometry = Polyline6Decoder.decode(route.encodedPolyline),
                maneuvers = route.maneuvers.map { maneuver ->
                    Maneuver(
                        type = maneuver.type,
                        instruction = maneuver.instruction,
                        distanceMeters = maneuver.distanceMeters,
                        durationSeconds = maneuver.durationSeconds,
                        beginShapeIndex = maneuver.beginShapeIndex,
                        endShapeIndex = maneuver.endShapeIndex,
                        streetNames = maneuver.streetNames,
                        travelMode = maneuver.travelMode,
                        travelType = maneuver.travelType,
                    )
                },
                provider = route.provider,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiClientException.Http) {
            val failure = if (error.code == "route_not_found") {
                RoutePreviewFailure.NO_ROUTE
            } else {
                RoutePreviewFailure.SERVER
            }
            throw RoutePreviewException(failure, error)
        } catch (error: ApiClientException.Network) {
            throw RoutePreviewException(RoutePreviewFailure.NETWORK, error)
        } catch (error: ApiClientException.InvalidResponse) {
            throw RoutePreviewException(RoutePreviewFailure.INVALID_RESPONSE, error)
        } catch (error: IllegalArgumentException) {
            throw RoutePreviewException(RoutePreviewFailure.INVALID_RESPONSE, error)
        }
    }
}
