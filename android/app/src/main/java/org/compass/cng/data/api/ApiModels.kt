package org.compass.cng.data.api

data class ApiPlaceSearchResults(
    val query: String,
    val results: List<ApiPlaceSearchResult>,
)

data class ApiPlaceSearchResult(
    val id: String,
    val displayName: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val category: String?,
    val poiName: String?,
    val provider: String,
)

data class ApiRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String,
    val maneuvers: List<ApiManeuver>,
    val provider: String,
    val navigation: ApiNavigationTiming,
)

data class ApiNavigationTiming(
    val routeId: String,
    val drivingDurationSeconds: Double,
    val remainingDrivingDurationSeconds: Double,
    val refuelingStopCount: Int,
    val dwellSecondsPerRefuelingStop: Int,
    val totalRefuelingDwellSeconds: Double,
    val totalTripDurationSeconds: Double,
    val departureAt: String?,
    val drivingArrivalAt: String?,
    val tripArrivalAt: String?,
    val trafficDelaySeconds: Double? = null,
    val trafficDelayState: String = "unavailable",
)

data class ApiManeuver(
    val type: Int,
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val streetNames: List<String>,
    val verbalTransitionAlertInstruction: String?,
    val verbalPreTransitionInstruction: String?,
    val verbalPostTransitionInstruction: String?,
    val bearingBefore: Int?,
    val bearingAfter: Int?,
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
    val excludedMimitStationIds: List<String>,
    val baseRoute: ApiRoute,
    val trafficState: String,
    val rangeBasis: ApiPredictiveRangeBasis,
    val candidates: List<ApiPredictiveRankedCandidate>,
    val itinerary: ApiPredictiveItinerary?,
    val gasolineFallback: ApiGasolineFallback? = null,
)

data class ApiGasolineFallback(
    val estimatedRemainingGasolineRangeKm: Double,
    val reserveGasolineRangeKm: Double,
    val usableGasolineRangeKm: Double,
    val cngRangeUsedBeforeSwitchKm: Double,
    val requiredGasolineRangeKm: Double,
    val gasolineMarginAtDestinationKm: Double,
    val strategy: String,
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
    val dwellTimeSeconds: Int,
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
    val totalRefuelingDwellSeconds: Double,
    val totalTripDurationSeconds: Double,
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
    val navigation: ApiNavigationTiming,
)

data class ApiRouteWithCngItinerary(
    val selectedStops: List<ApiSelectedCngStop>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<ApiCngItineraryRouteLeg>,
    val provider: String,
    val rangeValidation: String,
    val navigation: ApiNavigationTiming,
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
    val expectedArrivalAt: String?,
    val dwellTimeSeconds: Int,
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
