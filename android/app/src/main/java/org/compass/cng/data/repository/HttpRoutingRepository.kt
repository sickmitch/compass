package org.compass.cng.data.repository

import java.time.DateTimeException
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.data.api.ApiManeuver
import org.compass.cng.data.api.ApiOpeningEvaluation
import org.compass.cng.data.api.ApiCngPrice
import org.compass.cng.data.api.ApiRankedCandidate
import org.compass.cng.data.api.ApiRoute
import org.compass.cng.data.api.ApiNavigationTiming
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.geometry.Polyline6Decoder
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.CngItineraryRouteLeg
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngRouteLegKind
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.PredictiveCngStation
import org.compass.cng.domain.model.PredictiveCngItinerary
import org.compass.cng.domain.model.PredictiveDestinationLeg
import org.compass.cng.domain.model.PredictiveItineraryStop
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveRangeBasis
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RankingBreakdown
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary
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
        val candidates = response.candidates.map(ApiRankedCandidate::toRankedCngStation)
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

    override suspend fun predictiveCngStations(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: OffsetDateTime,
        excludedMimitStationIds: Set<String>,
    ): PredictiveCngSuggestion = mapFailures {
        require(effectiveCngRangeKm > 0) { "effective CNG range must be positive" }
        require(
            estimatedRemainingCngRangeKm > 0 &&
                estimatedRemainingCngRangeKm <= effectiveCngRangeKm,
        ) {
            "estimated remaining range is outside vehicle range"
        }
        require(reserveCngRangeKm >= 0 && reserveCngRangeKm < estimatedRemainingCngRangeKm) {
            "reserve range must be below remaining range"
        }
        require(maximumDetourMinutes >= 0) { "maximum detour must not be negative" }
        require(excludedMimitStationIds.size <= 32) {
            "at most 32 MIMIT station IDs may be excluded"
        }
        require(excludedMimitStationIds.all { id ->
            id.isNotEmpty() && id.length <= 32 && id.all { character ->
                character in '0'..'9'
            }
        }) { "excluded MIMIT station IDs must be numeric" }
        val response = apiClient.getPredictiveCngCandidates(
            origin = origin,
            destination = destination,
            effectiveCngRangeKm = effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = estimatedRemainingCngRangeKm,
            reserveCngRangeKm = reserveCngRangeKm,
            maximumDetourMinutes = maximumDetourMinutes,
            departureAt = departureAt.toString(),
            excludedMimitStationIds = excludedMimitStationIds,
        )
        require(response.excludedMimitStationIds.toSet() == excludedMimitStationIds) {
            "server did not acknowledge the excluded MIMIT station IDs"
        }
        val candidates = response.candidates.map { predictive ->
            PredictiveCngStation(
                station = predictive.candidate.toRankedCngStation(),
                estimatedRemainingRangeAtArrivalKm = (
                    predictive.estimatedRemainingRangeAtArrivalKm
                ),
                reserveMarginAtArrivalKm = predictive.reserveMarginAtArrivalKm,
            )
        }
        require(candidates.map { it.station.ranking.rank } == (1..candidates.size).toList()) {
            "predictive candidate ranking is not contiguous"
        }
        PredictiveCngSuggestion(
            state = response.suggestionState.toPredictiveSuggestionState(),
            departureAt = OffsetDateTime.parse(response.departureAt),
            maximumDetourMinutes = response.maximumDetourMinutes,
            baseRoute = response.baseRoute.toRoutePreview(origin, destination),
            rangeBasis = PredictiveRangeBasis(
                effectiveCngRangeKm = response.rangeBasis.effectiveCngRangeKm,
                estimatedRemainingCngRangeKm = (
                    response.rangeBasis.estimatedRemainingCngRangeKm
                ),
                reserveCngRangeKm = response.rangeBasis.reserveCngRangeKm,
                usableRangeBeforeReserveKm = response.rangeBasis.usableRangeBeforeReserveKm,
                remainingRouteDistanceKm = response.rangeBasis.remainingRouteDistanceKm,
                rangeShortfallToDestinationKm = response.rangeBasis.rangeShortfallToDestinationKm,
                destinationReachableWithReserve = (
                    response.rangeBasis.destinationReachableWithReserve
                ),
                consumptionModel = response.rangeBasis.consumptionModel,
                trafficState = response.rangeBasis.trafficState,
                trafficAdjusted = response.rangeBasis.trafficAdjusted,
            ),
            candidates = candidates,
            itinerary = response.itinerary?.let { itinerary ->
                PredictiveCngItinerary(
                    stops = itinerary.stops.map { stop ->
                        PredictiveItineraryStop(
                            sequence = stop.sequence,
                            station = SelectedCngStop(
                                mimitStationId = stop.mimitStationId,
                                name = stop.name,
                                municipality = stop.municipality,
                                province = stop.province,
                                location = Coordinate(stop.latitude, stop.longitude),
                            ),
                            arrivalAt = OffsetDateTime.parse(stop.arrivalAt),
                            legDistanceMeters = stop.legDistanceMeters,
                            legDurationSeconds = stop.legDurationSeconds,
                            availableRangeAtDepartureKm = stop.availableRangeAtDepartureKm,
                            estimatedRemainingRangeAtArrivalKm = (
                                stop.estimatedRemainingRangeAtArrivalKm
                            ),
                            reserveMarginAtArrivalKm = stop.reserveMarginAtArrivalKm,
                            opening = stop.opening.toOpeningAtEta(),
                            phone = stop.phone,
                            brand = stop.brand,
                            operator = stop.operator,
                            osmMatchConfidence = stop.osmMatchConfidence,
                            price = stop.price?.toCngPrice(),
                        )
                    },
                    destinationLeg = PredictiveDestinationLeg(
                        distanceMeters = itinerary.destinationLeg.distanceMeters,
                        durationSeconds = itinerary.destinationLeg.durationSeconds,
                        availableRangeAtDepartureKm = (
                            itinerary.destinationLeg.availableRangeAtDepartureKm
                        ),
                        estimatedRemainingRangeAtArrivalKm = (
                            itinerary.destinationLeg.estimatedRemainingRangeAtArrivalKm
                        ),
                        reserveMarginAtArrivalKm = (
                            itinerary.destinationLeg.reserveMarginAtArrivalKm
                        ),
                        destinationEta = OffsetDateTime.parse(
                            itinerary.destinationLeg.destinationEta,
                        ),
                    ),
                    totalDistanceMeters = itinerary.totalDistanceMeters,
                    totalDurationSeconds = itinerary.totalDurationSeconds,
                    refuelAssumption = itinerary.refuelAssumption,
                    distanceModel = itinerary.distanceModel,
                )
            },
        )
    }

    override suspend fun routeWithCngItinerary(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationIds: List<String>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
    ): RouteWithCngItinerary = mapFailures {
        require(mimitStationIds.isNotEmpty() && mimitStationIds.size <= 32) {
            "CNG itinerary must contain between 1 and 32 stops"
        }
        require(mimitStationIds.distinct().size == mimitStationIds.size) {
            "CNG itinerary must not repeat a station"
        }
        require(mimitStationIds.all { it.matches(Regex("^[0-9]{1,32}$")) }) {
            "invalid MIMIT station ID"
        }
        require(effectiveCngRangeKm > 0 && estimatedRemainingCngRangeKm > 0) {
            "CNG ranges must be positive"
        }
        require(estimatedRemainingCngRangeKm <= effectiveCngRangeKm) {
            "remaining range must not exceed effective range"
        }
        require(reserveCngRangeKm >= 0 && reserveCngRangeKm < estimatedRemainingCngRangeKm) {
            "reserve must be lower than remaining range"
        }
        val response = apiClient.getRouteWithCngItinerary(
            origin = origin,
            destination = destination,
            mimitStationIds = mimitStationIds,
            effectiveCngRangeKm = effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = estimatedRemainingCngRangeKm,
            reserveCngRangeKm = reserveCngRangeKm,
        )
        val selectedStops = response.selectedStops.map { stop ->
            SelectedCngStop(
                mimitStationId = stop.mimitStationId,
                name = stop.name,
                municipality = stop.municipality,
                province = stop.province,
                location = Coordinate(stop.latitude, stop.longitude),
                expectedArrivalAt = stop.expectedArrivalAt?.let(OffsetDateTime::parse),
                dwellTimeSeconds = stop.dwellTimeSeconds,
            )
        }
        require(selectedStops.map(SelectedCngStop::mimitStationId) == mimitStationIds) {
            "routed CNG stops do not match the requested itinerary"
        }
        RouteWithCngItinerary(
            selectedStops = selectedStops,
            distanceMeters = response.distanceMeters,
            durationSeconds = response.durationSeconds,
            legs = response.legs.map { leg ->
                CngItineraryRouteLeg(
                    sequence = leg.sequence,
                    kind = when (leg.kind) {
                        "origin_to_cng_station" -> CngRouteLegKind.ORIGIN_TO_CNG_STATION
                        "cng_station_to_cng_station" -> {
                            CngRouteLegKind.CNG_STATION_TO_CNG_STATION
                        }
                        "cng_station_to_destination" -> {
                            CngRouteLegKind.CNG_STATION_TO_DESTINATION
                        }
                        else -> error("unsupported CNG itinerary leg kind")
                    },
                    route = RoutePreview(
                        origin = Coordinate(leg.originLatitude, leg.originLongitude),
                        destination = Coordinate(
                            leg.destinationLatitude,
                            leg.destinationLongitude,
                        ),
                        distanceMeters = leg.distanceMeters,
                        durationSeconds = leg.durationSeconds,
                        geometry = Polyline6Decoder.decode(leg.encodedPolyline),
                        maneuvers = leg.maneuvers.map(ApiManeuver::toManeuver),
                        provider = response.provider,
                        navigation = response.navigation.toLegNavigationTiming(
                            sequence = leg.sequence,
                            durationSeconds = leg.durationSeconds,
                        ),
                    ),
                    availableRangeAtDepartureKm = leg.availableRangeAtDepartureKm,
                    estimatedRemainingRangeAtArrivalKm = (
                        leg.estimatedRemainingRangeAtArrivalKm
                    ),
                    reserveMarginAtArrivalKm = leg.reserveMarginAtArrivalKm,
                )
            },
            provider = response.provider,
            rangeValidation = response.rangeValidation,
            navigation = response.navigation.toNavigationTiming(),
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
                    navigation = response.navigation.toLegNavigationTiming(
                        sequence = response.legs.indexOf(leg) + 1,
                        durationSeconds = leg.durationSeconds,
                    ),
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
                expectedArrivalAt = response.selectedStop.expectedArrivalAt?.let(
                    OffsetDateTime::parse,
                ),
                dwellTimeSeconds = response.selectedStop.dwellTimeSeconds,
            ),
            distanceMeters = response.distanceMeters,
            durationSeconds = response.durationSeconds,
            legs = legs,
            provider = response.provider,
            navigation = response.navigation.toNavigationTiming(),
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
                "cng_itinerary_out_of_range" -> {
                    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE
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
    navigation = navigation.toNavigationTiming(),
)

private fun ApiManeuver.toManeuver(): Maneuver = Maneuver(
    type = type,
    instruction = instruction,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    beginShapeIndex = beginShapeIndex,
    endShapeIndex = endShapeIndex,
    streetNames = streetNames,
    verbalTransitionAlertInstruction = verbalTransitionAlertInstruction,
    verbalPreTransitionInstruction = verbalPreTransitionInstruction,
    verbalPostTransitionInstruction = verbalPostTransitionInstruction,
    bearingBefore = bearingBefore,
    bearingAfter = bearingAfter,
    travelMode = travelMode,
    travelType = travelType,
)

private fun ApiNavigationTiming.toNavigationTiming(): NavigationTiming = NavigationTiming(
    routeId = routeId,
    drivingDurationSeconds = drivingDurationSeconds,
    remainingDrivingDurationSeconds = remainingDrivingDurationSeconds,
    refuelingStopCount = refuelingStopCount,
    dwellSecondsPerRefuelingStop = dwellSecondsPerRefuelingStop,
    totalRefuelingDwellSeconds = totalRefuelingDwellSeconds,
    totalTripDurationSeconds = totalTripDurationSeconds,
    departureAt = departureAt?.let(OffsetDateTime::parse),
    drivingArrivalAt = drivingArrivalAt?.let(OffsetDateTime::parse),
    tripArrivalAt = tripArrivalAt?.let(OffsetDateTime::parse),
)

private fun ApiNavigationTiming.toLegNavigationTiming(
    sequence: Int,
    durationSeconds: Double,
): NavigationTiming = NavigationTiming(
    routeId = "${routeId}_leg_$sequence",
    drivingDurationSeconds = durationSeconds,
    remainingDrivingDurationSeconds = durationSeconds,
    refuelingStopCount = 0,
    dwellSecondsPerRefuelingStop = dwellSecondsPerRefuelingStop,
    totalRefuelingDwellSeconds = 0.0,
    totalTripDurationSeconds = durationSeconds,
    departureAt = null,
    drivingArrivalAt = null,
    tripArrivalAt = null,
)

private fun ApiRankedCandidate.toRankedCngStation(): RankedCngStation = RankedCngStation(
    stationId = stationId,
    mimitStationId = mimitStationId,
    name = name,
    municipality = municipality,
    province = province,
    location = Coordinate(latitude, longitude),
    distanceFromPreviousWaypointMeters = distanceFromPreviousWaypointMeters,
    detourMinutes = detourMinutes,
    stationEta = OffsetDateTime.parse(stationEta),
    destinationEta = OffsetDateTime.parse(destinationEta),
    opening = opening.toOpeningAtEta(),
    phone = phone,
    brand = brand,
    operator = operator,
    osmMatchConfidence = osmMatchConfidence,
    price = price?.toCngPrice(),
    ranking = RankingBreakdown(
        rank = ranking.rank,
        totalScore = ranking.totalScore,
        detourScore = ranking.detourScore,
        openingScore = ranking.openingScore,
        priceScore = ranking.priceScore,
        priceFreshnessScore = ranking.priceFreshnessScore,
    ),
)

private fun ApiOpeningEvaluation.toOpeningAtEta(): OpeningAtEta = OpeningAtEta(
    state = state.toOpeningState(),
    validation = validation.toOpeningValidation(),
    openingHours = openingHours,
    source = source,
    sourceConfidence = sourceConfidence,
    evaluatedAt = OffsetDateTime.parse(evaluatedAt),
    timezone = timezone,
    nextChangeAt = nextChangeAt?.let(OffsetDateTime::parse),
    warnings = warnings,
)

private fun ApiCngPrice.toCngPrice(): CngPrice = CngPrice(
    unitPrice = unitPrice,
    currency = currency,
    unit = unit,
    serviceMode = serviceMode,
    observedAt = OffsetDateTime.parse(observedAt),
    ingestedAt = OffsetDateTime.parse(ingestedAt),
    sourceName = sourceName,
    ageSeconds = ageSeconds,
    freshness = freshnessState.toPriceFreshness(),
)

private fun String.toPredictiveSuggestionState(): PredictiveSuggestionState = when (this) {
    "not_needed" -> PredictiveSuggestionState.NOT_NEEDED
    "suggested" -> PredictiveSuggestionState.SUGGESTED
    "no_reachable_station" -> PredictiveSuggestionState.NO_REACHABLE_STATION
    "no_eligible_station" -> PredictiveSuggestionState.NO_ELIGIBLE_STATION
    "no_complete_itinerary" -> PredictiveSuggestionState.NO_COMPLETE_ITINERARY
    else -> error("unsupported predictive suggestion state")
}

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
