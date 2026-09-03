package org.compass.cng.domain.model

import java.time.OffsetDateTime
import kotlin.math.abs

enum class OpeningState {
    OPEN,
    CLOSED,
    UNKNOWN,
}

enum class OpeningValidation {
    VALID,
    MISSING,
    INVALID,
}

data class OpeningAtEta(
    val state: OpeningState,
    val validation: OpeningValidation,
    val openingHours: String?,
    val source: String?,
    val sourceConfidence: Double?,
    val evaluatedAt: OffsetDateTime,
    val timezone: String,
    val nextChangeAt: OffsetDateTime?,
    val warnings: List<String>,
)

enum class PriceFreshness {
    FRESH,
    STALE,
    FUTURE_OBSERVATION,
    UNKNOWN,
}

data class CngPrice(
    val unitPrice: Double,
    val currency: String,
    val unit: String,
    val serviceMode: String,
    val observedAt: OffsetDateTime,
    val ingestedAt: OffsetDateTime,
    val sourceName: String,
    val ageSeconds: Double?,
    val freshness: PriceFreshness,
)

data class RankingBreakdown(
    val rank: Int,
    val totalScore: Double,
    val detourScore: Double,
    val openingScore: Double,
    val priceScore: Double,
    val priceFreshnessScore: Double,
)

data class RankedCngStation(
    val stationId: Long,
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: Coordinate,
    val distanceFromPreviousWaypointMeters: Double,
    val detourMinutes: Double,
    val stationEta: OffsetDateTime,
    val destinationEta: OffsetDateTime,
    val opening: OpeningAtEta,
    val phone: String?,
    val brand: String?,
    val operator: String?,
    val osmMatchConfidence: Double?,
    val price: CngPrice?,
    val ranking: RankingBreakdown,
)

data class RankedCngStations(
    val departureAt: OffsetDateTime,
    val maximumDetourMinutes: Double,
    val baseRoute: RoutePreview,
    val trafficState: String,
    val candidates: List<RankedCngStation>,
)

enum class PredictiveSuggestionState {
    NOT_NEEDED,
    SUGGESTED,
    GASOLINE_FALLBACK,
    NO_REACHABLE_STATION,
    NO_ELIGIBLE_STATION,
    NO_COMPLETE_ITINERARY,
}

data class GasolineFallback(
    val estimatedRemainingGasolineRangeKm: Double,
    val reserveGasolineRangeKm: Double,
    val usableGasolineRangeKm: Double,
    val cngRangeUsedBeforeSwitchKm: Double,
    val requiredGasolineRangeKm: Double,
    val gasolineMarginAtDestinationKm: Double,
    val strategy: String,
) {
    init {
        require(estimatedRemainingGasolineRangeKm > 0)
        require(reserveGasolineRangeKm >= 0 && reserveGasolineRangeKm < estimatedRemainingGasolineRangeKm)
        require(
            abs(
                usableGasolineRangeKm -
                    (estimatedRemainingGasolineRangeKm - reserveGasolineRangeKm),
            ) <= 0.001,
        )
        require(usableGasolineRangeKm >= requiredGasolineRangeKm)
        require(cngRangeUsedBeforeSwitchKm >= 0 && gasolineMarginAtDestinationKm >= 0)
        require(
            abs(
                gasolineMarginAtDestinationKm -
                    (usableGasolineRangeKm - requiredGasolineRangeKm),
            ) <= 0.001,
        )
        require(strategy == "direct_after_cng_reserve")
    }
}

data class PredictiveRangeBasis(
    val effectiveCngRangeKm: Double,
    val estimatedRemainingCngRangeKm: Double,
    val reserveCngRangeKm: Double,
    val usableRangeBeforeReserveKm: Double,
    val remainingRouteDistanceKm: Double,
    val rangeShortfallToDestinationKm: Double,
    val destinationReachableWithReserve: Boolean,
    val consumptionModel: String,
    val trafficState: String,
    val trafficAdjusted: Boolean,
) {
    init {
        require(effectiveCngRangeKm > 0) { "effective range must be positive" }
        require(estimatedRemainingCngRangeKm > 0) { "remaining range must be positive" }
        require(estimatedRemainingCngRangeKm <= effectiveCngRangeKm) {
            "remaining range must not exceed effective range"
        }
        require(reserveCngRangeKm >= 0 && reserveCngRangeKm < estimatedRemainingCngRangeKm) {
            "reserve range must be below remaining range"
        }
        require(
            abs(
                usableRangeBeforeReserveKm -
                    (estimatedRemainingCngRangeKm - reserveCngRangeKm),
            ) <= RANGE_TOLERANCE_KM,
        ) { "usable range does not reconcile with remaining range and reserve" }
        require(remainingRouteDistanceKm >= 0 && rangeShortfallToDestinationKm >= 0) {
            "predictive route distances must not be negative"
        }
        require(
            destinationReachableWithReserve ==
                (remainingRouteDistanceKm <= usableRangeBeforeReserveKm + RANGE_TOLERANCE_KM),
        ) { "destination reachability does not match the road-distance range basis" }
    }

    private companion object {
        const val RANGE_TOLERANCE_KM = 0.001
    }
}

data class PredictiveCngStation(
    val station: RankedCngStation,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
) {
    init {
        require(estimatedRemainingRangeAtArrivalKm >= 0) {
            "remaining range at station must not be negative"
        }
        require(reserveMarginAtArrivalKm >= 0) {
            "reachable station must preserve the requested reserve"
        }
    }
}

data class PredictiveItineraryStop(
    val sequence: Int,
    val station: SelectedCngStop,
    val arrivalAt: OffsetDateTime,
    val legDistanceMeters: Double,
    val legDurationSeconds: Double,
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
    val opening: OpeningAtEta,
    val phone: String?,
    val brand: String?,
    val operator: String?,
    val osmMatchConfidence: Double?,
    val price: CngPrice?,
    val dwellTimeSeconds: Int = DEFAULT_CNG_REFUEL_DWELL_SECONDS,
) {
    init {
        require(sequence > 0) { "itinerary stop sequence must be positive" }
        require(legDistanceMeters >= 0 && legDurationSeconds >= 0) {
            "itinerary stop leg cost must not be negative"
        }
        require(availableRangeAtDepartureKm > 0) {
            "itinerary stop must have range available at departure"
        }
        require(estimatedRemainingRangeAtArrivalKm >= 0 && reserveMarginAtArrivalKm >= 0) {
            "itinerary stop must be reachable while preserving reserve"
        }
        require(dwellTimeSeconds >= 0) { "itinerary stop dwell must not be negative" }
    }
}

data class PredictiveDestinationLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
    val destinationEta: OffsetDateTime,
) {
    init {
        require(distanceMeters >= 0 && durationSeconds >= 0) {
            "destination leg cost must not be negative"
        }
        require(availableRangeAtDepartureKm > 0) {
            "destination leg must have range available at departure"
        }
        require(estimatedRemainingRangeAtArrivalKm >= 0 && reserveMarginAtArrivalKm >= 0) {
            "destination must be reachable while preserving reserve"
        }
    }
}

data class PredictiveCngItinerary(
    val stops: List<PredictiveItineraryStop>,
    val destinationLeg: PredictiveDestinationLeg,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val totalRefuelingDwellSeconds: Double = stops.sumOf {
        it.dwellTimeSeconds.toDouble()
    },
    val totalTripDurationSeconds: Double = totalDurationSeconds + totalRefuelingDwellSeconds,
    val refuelAssumption: String,
    val distanceModel: String,
) {
    init {
        require(stops.isNotEmpty()) { "predictive itinerary must contain at least one stop" }
        require(stops.size <= 32) { "predictive itinerary contains too many stops" }
        require(stops.map(PredictiveItineraryStop::sequence) == (1..stops.size).toList()) {
            "predictive itinerary stop sequence must be contiguous"
        }
        require(stops.map { it.station.mimitStationId }.distinct().size == stops.size) {
            "predictive itinerary must not repeat a station"
        }
        require(totalDistanceMeters >= 0 && totalDurationSeconds >= 0) {
            "predictive itinerary total cost must not be negative"
        }
        require(totalRefuelingDwellSeconds >= 0 && totalTripDurationSeconds >= 0) {
            "predictive itinerary dwell and trip duration must not be negative"
        }
        require(
            abs(stops.sumOf { it.dwellTimeSeconds.toDouble() } - totalRefuelingDwellSeconds) <=
                COST_SUM_TOLERANCE,
        ) { "predictive itinerary dwell does not reconcile with its stops" }
        require(
            abs(totalDurationSeconds + totalRefuelingDwellSeconds - totalTripDurationSeconds) <=
                COST_SUM_TOLERANCE,
        ) { "predictive itinerary total time must include driving and dwell" }
        require(
            abs(
                stops.sumOf(PredictiveItineraryStop::legDistanceMeters) +
                    destinationLeg.distanceMeters - totalDistanceMeters,
            ) <= COST_SUM_TOLERANCE,
        ) { "predictive itinerary distance does not reconcile with its legs" }
        require(
            abs(
                stops.sumOf(PredictiveItineraryStop::legDurationSeconds) +
                    destinationLeg.durationSeconds - totalDurationSeconds,
            ) <= COST_SUM_TOLERANCE,
        ) { "predictive itinerary duration does not reconcile with its legs" }
        require(refuelAssumption == "full_effective_range_after_each_stop") {
            "unsupported predictive refuel assumption"
        }
        require(distanceModel == "road_network") { "unsupported predictive distance model" }
    }

    private companion object {
        const val COST_SUM_TOLERANCE = 2.0
    }
}

data class PredictiveCngSuggestion(
    val state: PredictiveSuggestionState,
    val departureAt: OffsetDateTime,
    val maximumDetourMinutes: Double,
    val baseRoute: RoutePreview,
    val rangeBasis: PredictiveRangeBasis,
    val candidates: List<PredictiveCngStation>,
    val itinerary: PredictiveCngItinerary?,
    val gasolineFallback: GasolineFallback? = null,
) {
    init {
        require((state == PredictiveSuggestionState.SUGGESTED) == (itinerary != null)) {
            "only a suggested predictive result may contain an itinerary"
        }
        require((state == PredictiveSuggestionState.SUGGESTED) == (candidates.size == 1)) {
            "a suggested predictive result must expose its selected first stop"
        }
        require(
            (state == PredictiveSuggestionState.GASOLINE_FALLBACK) ==
                (gasolineFallback != null),
        ) { "only a gasoline fallback result may contain gasoline metrics" }
        require(
            state != PredictiveSuggestionState.NOT_NEEDED ||
                rangeBasis.destinationReachableWithReserve,
        ) {
            "not-needed result must prove destination reachability"
        }
        candidates.forEach { candidate ->
            require(
                candidate.station.distanceFromPreviousWaypointMeters <=
                    rangeBasis.usableRangeBeforeReserveKm * 1_000 + 1,
            ) { "predictive station is beyond usable road range" }
        }
        itinerary?.let { plan ->
            require(
                plan.stops.first().station.mimitStationId ==
                    candidates.single().station.mimitStationId,
            ) { "predictive first candidate must match the itinerary first stop" }
        }
    }

    fun asRankedStations(): RankedCngStations = RankedCngStations(
        departureAt = departureAt,
        maximumDetourMinutes = maximumDetourMinutes,
        baseRoute = baseRoute,
        trafficState = rangeBasis.trafficState,
        candidates = candidates.map(PredictiveCngStation::station),
    )
}

data class SelectedCngStop(
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: Coordinate,
    val expectedArrivalAt: OffsetDateTime? = null,
    val dwellTimeSeconds: Int = DEFAULT_CNG_REFUEL_DWELL_SECONDS,
)

enum class CngRouteLegKind {
    ORIGIN_TO_CNG_STATION,
    CNG_STATION_TO_CNG_STATION,
    CNG_STATION_TO_DESTINATION,
}

data class CngRouteLeg(
    val kind: CngRouteLegKind,
    val route: RoutePreview,
)

data class RouteWithCngStop(
    val selectedStop: SelectedCngStop,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<CngRouteLeg>,
    val provider: String,
    val navigation: NavigationTiming = NavigationTiming.legacy(durationSeconds, 1),
) {
    init {
        require(legs.map(CngRouteLeg::kind) == EXPECTED_CNG_ROUTE_LEGS) {
            "selected-stop route must have two ordered legs"
        }
        require(legs.first().route.destination == selectedStop.location) {
            "first leg must end at the selected CNG stop"
        }
        require(legs.last().route.origin == selectedStop.location) {
            "second leg must start at the selected CNG stop"
        }
        require(
            abs(legs.sumOf { it.route.distanceMeters } - distanceMeters) <=
                DISTANCE_SUM_TOLERANCE_METERS,
        ) {
            "selected-stop distance does not reconcile with leg distances"
        }
        require(
            abs(legs.sumOf { it.route.durationSeconds } - durationSeconds) <=
                DURATION_SUM_TOLERANCE_SECONDS,
        ) {
            "selected-stop duration does not reconcile with leg durations"
        }
    }

    fun asRoutePreview(): RoutePreview = RoutePreview(
        origin = legs.first().route.origin,
        destination = legs.last().route.destination,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        geometry = legs.flatMap { it.route.geometry },
        maneuvers = legs.flatMap { it.route.maneuvers },
        provider = provider,
        navigation = navigation,
    )

    private companion object {
        // Valhalla rounds the trip summary and each leg summary independently at its
        // serialized precision. Two legs can therefore differ from the trip total by
        // up to roughly 1.5 metres/seconds without representing a routing mismatch.
        const val DISTANCE_SUM_TOLERANCE_METERS = 2.0
        const val DURATION_SUM_TOLERANCE_SECONDS = 2.0
        val EXPECTED_CNG_ROUTE_LEGS = listOf(
            CngRouteLegKind.ORIGIN_TO_CNG_STATION,
            CngRouteLegKind.CNG_STATION_TO_DESTINATION,
        )
    }
}

data class CngItineraryRouteLeg(
    val sequence: Int,
    val kind: CngRouteLegKind,
    val route: RoutePreview,
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
) {
    init {
        require(sequence > 0) { "route leg sequence must be positive" }
        require(availableRangeAtDepartureKm > 0) { "route leg range must be positive" }
        require(estimatedRemainingRangeAtArrivalKm >= 0 && reserveMarginAtArrivalKm >= 0) {
            "route leg must preserve the requested reserve"
        }
    }
}

data class RouteWithCngItinerary(
    val selectedStops: List<SelectedCngStop>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<CngItineraryRouteLeg>,
    val provider: String,
    val rangeValidation: String,
    val navigation: NavigationTiming = NavigationTiming.legacy(
        durationSeconds,
        selectedStops.size,
    ),
) {
    init {
        require(selectedStops.isNotEmpty()) { "CNG itinerary route needs at least one stop" }
        require(selectedStops.size <= 32) { "CNG itinerary route contains too many stops" }
        require(selectedStops.map { it.mimitStationId }.distinct().size == selectedStops.size) {
            "CNG itinerary route must not repeat a station"
        }
        require(legs.size == selectedStops.size + 1) {
            "CNG itinerary route must contain one more leg than stops"
        }
        require(legs.map(CngItineraryRouteLeg::sequence) == (1..legs.size).toList()) {
            "CNG itinerary route leg sequence must be contiguous"
        }
        require(legs.first().kind == CngRouteLegKind.ORIGIN_TO_CNG_STATION) {
            "first itinerary route leg must reach a CNG station"
        }
        require(legs.last().kind == CngRouteLegKind.CNG_STATION_TO_DESTINATION) {
            "last itinerary route leg must reach the destination"
        }
        require(
            legs.drop(1).dropLast(1).all {
                it.kind == CngRouteLegKind.CNG_STATION_TO_CNG_STATION
            },
        ) { "intermediate itinerary legs must connect CNG stations" }
        selectedStops.forEachIndexed { index, stop ->
            require(legs[index].route.destination == stop.location) {
                "route leg must end at its selected CNG stop"
            }
            require(legs[index + 1].route.origin == stop.location) {
                "following route leg must start at its selected CNG stop"
            }
        }
        require(abs(legs.sumOf { it.route.distanceMeters } - distanceMeters) <= 2.0) {
            "CNG itinerary route distance does not reconcile with its legs"
        }
        require(abs(legs.sumOf { it.route.durationSeconds } - durationSeconds) <= 2.0) {
            "CNG itinerary route duration does not reconcile with its legs"
        }
        require(rangeValidation == "all_legs_preserve_reserve") {
            "CNG itinerary route was not range-validated"
        }
    }

    fun asRoutePreview(): RoutePreview = RoutePreview(
        origin = legs.first().route.origin,
        destination = legs.last().route.destination,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        geometry = legs.flatMap { it.route.geometry },
        maneuvers = legs.flatMap { it.route.maneuvers },
        provider = provider,
        navigation = navigation,
    )
}
