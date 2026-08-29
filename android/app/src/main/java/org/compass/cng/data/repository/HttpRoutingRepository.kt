package org.compass.cng.data.repository

import java.time.DateTimeException
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.data.api.ApiManeuver
import org.compass.cng.data.api.ApiRoute
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.geometry.Polyline6Decoder
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RankingBreakdown
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.SelectedCngStop

class HttpRoutingRepository(
    private val apiClient: CompassApiClient,
) : RoutingRepository {
    override suspend fun previewRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): RoutePreview = mapFailures {
        apiClient.getRoute(origin, destination).toRoutePreview(origin, destination)
    }

    override suspend fun rankedCngStations(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
    ): RankedCngStations = mapFailures {
        require(effectiveCngRangeKm > 0) { "effective CNG range must be positive" }
        require(maximumDetourMinutes >= 0) { "maximum detour must not be negative" }
        val response = apiClient.getRankedCngCandidates(
            origin = origin,
            destination = destination,
            effectiveCngRangeKm = effectiveCngRangeKm,
            maximumDetourMinutes = maximumDetourMinutes,
            departureAt = departureAt.toString(),
        )
        val candidates = response.candidates.map { candidate ->
            RankedCngStation(
                stationId = candidate.stationId,
                mimitStationId = candidate.mimitStationId,
                name = candidate.name,
                municipality = candidate.municipality,
                province = candidate.province,
                location = Coordinate(candidate.latitude, candidate.longitude),
                distanceFromPreviousWaypointMeters = candidate.distanceFromPreviousWaypointMeters,
                detourMinutes = candidate.detourMinutes,
                stationEta = OffsetDateTime.parse(candidate.stationEta),
                destinationEta = OffsetDateTime.parse(candidate.destinationEta),
                opening = OpeningAtEta(
                    state = candidate.opening.state.toOpeningState(),
                    validation = candidate.opening.validation.toOpeningValidation(),
                    openingHours = candidate.opening.openingHours,
                    source = candidate.opening.source,
                    sourceConfidence = candidate.opening.sourceConfidence,
                    evaluatedAt = OffsetDateTime.parse(candidate.opening.evaluatedAt),
                    timezone = candidate.opening.timezone,
                    nextChangeAt = candidate.opening.nextChangeAt?.let(OffsetDateTime::parse),
                    warnings = candidate.opening.warnings,
                ),
                phone = candidate.phone,
                brand = candidate.brand,
                operator = candidate.operator,
                osmMatchConfidence = candidate.osmMatchConfidence,
                price = candidate.price?.let { price ->
                    CngPrice(
                        unitPrice = price.unitPrice,
                        currency = price.currency,
                        unit = price.unit,
                        serviceMode = price.serviceMode,
                        observedAt = OffsetDateTime.parse(price.observedAt),
                        ingestedAt = OffsetDateTime.parse(price.ingestedAt),
                        sourceName = price.sourceName,
                        ageSeconds = price.ageSeconds,
                        freshness = price.freshnessState.toPriceFreshness(),
                    )
                },
                ranking = RankingBreakdown(
                    rank = candidate.ranking.rank,
                    totalScore = candidate.ranking.totalScore,
                    detourScore = candidate.ranking.detourScore,
                    openingScore = candidate.ranking.openingScore,
                    priceScore = candidate.ranking.priceScore,
                    priceFreshnessScore = candidate.ranking.priceFreshnessScore,
                ),
            )
        }
        require(candidates.map { it.ranking.rank } == (1..candidates.size).toList()) {
            "candidate ranking is not contiguous"
        }
        RankedCngStations(
            departureAt = OffsetDateTime.parse(response.departureAt),
            maximumDetourMinutes = response.maximumDetourMinutes,
            baseRoute = response.baseRoute.toRoutePreview(origin, destination),
            trafficState = response.trafficState,
            candidates = candidates,
        )
    }

    override suspend fun routeWithCngStop(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationId: String,
    ): RouteWithCngStop = mapFailures {
        require(mimitStationId.matches(Regex("^[0-9]{1,32}$"))) { "invalid MIMIT station ID" }
        val response = apiClient.getRouteWithCngStop(origin, destination, mimitStationId)
        val legs = response.legs.map { leg ->
            CngRouteLeg(
                kind = when (leg.kind) {
                    "origin_to_cng_station" -> CngRouteLegKind.ORIGIN_TO_CNG_STATION
                    "cng_station_to_destination" -> CngRouteLegKind.CNG_STATION_TO_DESTINATION
                    else -> error("unsupported selected-stop leg kind")
                },
                route = RoutePreview(
                    origin = Coordinate(leg.originLatitude, leg.originLongitude),
                    destination = Coordinate(leg.destinationLatitude, leg.destinationLongitude),
                    distanceMeters = leg.distanceMeters,
                    durationSeconds = leg.durationSeconds,
                    geometry = Polyline6Decoder.decode(leg.encodedPolyline),
                    maneuvers = leg.maneuvers.map(ApiManeuver::toManeuver),
                    provider = response.provider,
                ),
            )
        }
        RouteWithCngStop(
            selectedStop = SelectedCngStop(
                mimitStationId = response.selectedStop.mimitStationId,
                name = response.selectedStop.name,
                municipality = response.selectedStop.municipality,
                province = response.selectedStop.province,
                location = Coordinate(
                    response.selectedStop.latitude,
                    response.selectedStop.longitude,
                ),
            ),
            distanceMeters = response.distanceMeters,
            durationSeconds = response.durationSeconds,
            legs = legs,
            provider = response.provider,
        )
    }

    private suspend fun <Result> mapFailures(block: suspend () -> Result): Result {
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiClientException.Http) {
            val failure = when (error.code) {
                "route_not_found" -> RoutePreviewFailure.NO_ROUTE
                "station_not_found" -> RoutePreviewFailure.STATION_NOT_FOUND
                "station_inactive", "station_location_unavailable" -> {
                    RoutePreviewFailure.STATION_UNAVAILABLE
                }
                else -> RoutePreviewFailure.SERVER
            }
            throw RoutePreviewException(failure, error)
        } catch (error: ApiClientException.Network) {
            throw RoutePreviewException(RoutePreviewFailure.NETWORK, error)
        } catch (error: ApiClientException.InvalidResponse) {
            throw RoutePreviewException(RoutePreviewFailure.INVALID_RESPONSE, error)
        } catch (error: IllegalArgumentException) {
            throw RoutePreviewException(RoutePreviewFailure.INVALID_RESPONSE, error)
        } catch (error: IllegalStateException) {
            throw RoutePreviewException(RoutePreviewFailure.INVALID_RESPONSE, error)
        } catch (error: DateTimeException) {
            throw RoutePreviewException(RoutePreviewFailure.INVALID_RESPONSE, error)
        }
    }
}

private fun ApiRoute.toRoutePreview(
    origin: Coordinate,
    destination: Coordinate,
): RoutePreview = RoutePreview(
    origin = origin,
    destination = destination,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    geometry = Polyline6Decoder.decode(encodedPolyline),
    maneuvers = maneuvers.map(ApiManeuver::toManeuver),
    provider = provider,
)

private fun ApiManeuver.toManeuver(): Maneuver = Maneuver(
    type = type,
    instruction = instruction,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    beginShapeIndex = beginShapeIndex,
    endShapeIndex = endShapeIndex,
    streetNames = streetNames,
    travelMode = travelMode,
    travelType = travelType,
)

private fun String.toOpeningState(): OpeningState = when (this) {
    "open" -> OpeningState.OPEN
    "closed" -> OpeningState.CLOSED
    "unknown" -> OpeningState.UNKNOWN
    else -> error("unsupported opening state")
}

private fun String.toOpeningValidation(): OpeningValidation = when (this) {
    "valid" -> OpeningValidation.VALID
    "missing" -> OpeningValidation.MISSING
    "invalid" -> OpeningValidation.INVALID
    else -> error("unsupported opening validation")
}

private fun String.toPriceFreshness(): PriceFreshness = when (this) {
    "fresh" -> PriceFreshness.FRESH
    "stale" -> PriceFreshness.STALE
    "future_observation" -> PriceFreshness.FUTURE_OBSERVATION
    "unknown" -> PriceFreshness.UNKNOWN
    else -> error("unsupported price freshness state")
}
