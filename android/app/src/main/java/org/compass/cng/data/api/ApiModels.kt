package org.compass.cng.data.api

data class ApiRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String,
    val maneuvers: List<ApiManeuver>,
    val provider: String,
)

data class ApiManeuver(
    val type: Int,
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val streetNames: List<String>,
    val travelMode: String?,
    val travelType: String?,
)

data class ApiRankedCandidates(
    val departureAt: String,
    val maximumDetourMinutes: Double,
    val baseRoute: ApiRoute,
    val trafficState: String,
    val candidates: List<ApiRankedCandidate>,
)

data class ApiPredictiveCandidates(
    val suggestionState: String,
    val departureAt: String,
    val maximumDetourMinutes: Double,
    val baseRoute: ApiRoute,
    val trafficState: String,
    val rangeBasis: ApiPredictiveRangeBasis,
    val candidates: List<ApiPredictiveRankedCandidate>,
    val itinerary: ApiPredictiveItinerary?,
)

data class ApiPredictiveRangeBasis(
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
)

data class ApiPredictiveRankedCandidate(
    val candidate: ApiRankedCandidate,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
)

data class ApiPredictiveItineraryStop(
    val sequence: Int,
    val stationId: Long,
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val latitude: Double,
    val longitude: Double,
    val arrivalAt: String,
    val legDistanceMeters: Double,
    val legDurationSeconds: Double,
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
    val opening: ApiOpeningEvaluation,
    val phone: String?,
    val brand: String?,
    val operator: String?,
    val osmMatchConfidence: Double?,
    val price: ApiCngPrice?,
)

data class ApiPredictiveDestinationLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
    val destinationEta: String,
)

data class ApiPredictiveItinerary(
    val stops: List<ApiPredictiveItineraryStop>,
    val destinationLeg: ApiPredictiveDestinationLeg,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val refuelAssumption: String,
    val distanceModel: String,
)

data class ApiRankedCandidate(
    val stationId: Long,
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceFromPreviousWaypointMeters: Double,
    val detourMinutes: Double,
    val stationEta: String,
    val destinationEta: String,
    val opening: ApiOpeningEvaluation,
    val phone: String?,
    val brand: String?,
    val operator: String?,
    val osmMatchConfidence: Double?,
    val price: ApiCngPrice?,
    val ranking: ApiRankingBreakdown,
)

data class ApiOpeningEvaluation(
    val state: String,
    val validation: String,
    val openingHours: String?,
    val source: String?,
    val sourceConfidence: Double?,
    val evaluatedAt: String,
    val timezone: String,
    val nextChangeAt: String?,
    val warnings: List<String>,
)

data class ApiCngPrice(
    val unitPrice: Double,
    val currency: String,
    val unit: String,
    val serviceMode: String,
    val observedAt: String,
    val ingestedAt: String,
    val sourceName: String,
    val ageSeconds: Double?,
    val freshnessState: String,
)

data class ApiRankingBreakdown(
    val rank: Int,
    val totalScore: Double,
    val detourScore: Double,
    val openingScore: Double,
    val priceScore: Double,
    val priceFreshnessScore: Double,
)

data class ApiRouteWithCngStop(
    val selectedStop: ApiSelectedCngStop,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<ApiRouteLeg>,
    val provider: String,
)

data class ApiRouteWithCngItinerary(
    val selectedStops: List<ApiSelectedCngStop>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<ApiCngItineraryRouteLeg>,
    val provider: String,
    val rangeValidation: String,
)

data class ApiCngItineraryRouteLeg(
    val sequence: Int,
    val kind: String,
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String,
    val maneuvers: List<ApiManeuver>,
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
)

data class ApiSelectedCngStop(
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val latitude: Double,
    val longitude: Double,
)

data class ApiRouteLeg(
    val kind: String,
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String,
    val maneuvers: List<ApiManeuver>,
)
