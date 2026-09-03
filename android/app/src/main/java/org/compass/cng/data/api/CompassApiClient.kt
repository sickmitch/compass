package org.compass.cng.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.compass.cng.domain.model.Coordinate

class CompassApiClient(
    baseUrl: String,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val eventLogger: (String) -> Unit = {},
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val apiBaseUrl = baseUrl.toHttpUrl()
    private val routeUrl = resolve("api/v1/routes")
    private val rankedCandidatesUrl = resolve("api/v1/cng/ranked-candidates")
    private val predictiveCandidatesUrl = resolve("api/v1/cng/predictive-candidates")
    private val routeWithCngStopUrl = resolve("api/v1/routes/with-cng-stop")
    private val routeWithCngItineraryUrl = resolve("api/v1/routes/with-cng-itinerary")
    private val predictiveHttpClient = httpClient.newBuilder()
        .readTimeout(PREDICTIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PREDICTIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun getRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): ApiRoute {
        val payload = RouteRequestDto(
            origin = origin.toDto(),
            destination = destination.toDto(),
            costing = "auto",
            language = "it-IT",
        )
        return post<RouteResponseDto>(routeUrl, json.encodeToString(payload)).toApiRoute()
    }

    suspend fun getRankedCngCandidates(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: String,
    ): ApiRankedCandidates {
        val payload = RankedCandidatesRequestDto(
            origin = origin.toDto(),
            destination = destination.toDto(),
            costing = "auto",
            language = "it-IT",
            effectiveCngRangeKm = effectiveCngRangeKm,
            maximumDetourMinutes = maximumDetourMinutes,
            departureAt = departureAt,
            includeClosed = false,
        )
        return post<RankedCandidatesResponseDto>(
            rankedCandidatesUrl,
            json.encodeToString(payload),
        ).toApiRankedCandidates()
    }

    suspend fun getRouteWithCngStop(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationId: String,
    ): ApiRouteWithCngStop {
        val payload = RouteWithCngStopRequestDto(
            origin = origin.toDto(),
            destination = destination.toDto(),
            costing = "auto",
            language = "it-IT",
            mimitStationId = mimitStationId,
        )
        return post<RouteWithCngStopResponseDto>(
            routeWithCngStopUrl,
            json.encodeToString(payload),
        ).toApiRouteWithCngStop()
    }

    suspend fun getPredictiveCngCandidates(
        origin: Coordinate,
        destination: Coordinate,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
        maximumDetourMinutes: Double,
        departureAt: String,
        excludedMimitStationIds: Set<String> = emptySet(),
        estimatedRemainingGasolineRangeKm: Double? = null,
        reserveGasolineRangeKm: Double? = null,
    ): ApiPredictiveCandidates {
        val payload = PredictiveCandidatesRequestDto(
            origin = origin.toDto(),
            destination = destination.toDto(),
            costing = "auto",
            language = "it-IT",
            effectiveCngRangeKm = effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = estimatedRemainingCngRangeKm,
            reserveCngRangeKm = reserveCngRangeKm,
            estimatedRemainingGasolineRangeKm = estimatedRemainingGasolineRangeKm,
            reserveGasolineRangeKm = reserveGasolineRangeKm,
            maximumDetourMinutes = maximumDetourMinutes,
            departureAt = departureAt,
            includeClosed = false,
            excludedMimitStationIds = excludedMimitStationIds.sorted(),
        )
        return post<PredictiveCandidatesResponseDto>(
            predictiveCandidatesUrl,
            json.encodeToString(payload),
            client = predictiveHttpClient,
        ).toApiPredictiveCandidates()
    }

    suspend fun getRouteWithCngItinerary(
        origin: Coordinate,
        destination: Coordinate,
        mimitStationIds: List<String>,
        effectiveCngRangeKm: Double,
        estimatedRemainingCngRangeKm: Double,
        reserveCngRangeKm: Double,
    ): ApiRouteWithCngItinerary {
        val payload = RouteWithCngItineraryRequestDto(
            origin = origin.toDto(),
            destination = destination.toDto(),
            costing = "auto",
            language = "it-IT",
            mimitStationIds = mimitStationIds,
            effectiveCngRangeKm = effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = estimatedRemainingCngRangeKm,
            reserveCngRangeKm = reserveCngRangeKm,
        )
        return post<RouteWithCngItineraryResponseDto>(
            routeWithCngItineraryUrl,
            json.encodeToString(payload),
        ).toApiRouteWithCngItinerary()
    }

    private fun resolve(path: String): HttpUrl = apiBaseUrl.resolve(path)
        ?: error("COMPASS_API_BASE_URL cannot resolve $path")

    private suspend inline fun <reified ResponseDto> post(
        url: HttpUrl,
        requestJson: String,
        client: OkHttpClient = httpClient,
    ): ResponseDto = withContext(Dispatchers.IO) {
        val endpoint = url.encodedPath
        val startedAtNanos = monotonicNanos()
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        eventLogger(
            "request started: method=POST endpoint=$endpoint " +
                "call_timeout_ms=${client.callTimeoutMillis} " +
                "read_timeout_ms=${client.readTimeoutMillis}",
        )
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                eventLogger(
                    "request completed: method=POST endpoint=$endpoint status=${response.code} " +
                        "duration_ms=${elapsedMillis(startedAtNanos)}",
                )
                if (!response.isSuccessful) {
                    val error = runCatching { json.decodeFromString<ErrorResponseDto>(body) }
                        .getOrNull()
                    throw ApiClientException.Http(
                        statusCode = response.code,
                        code = error?.code ?: "http_${response.code}",
                    )
                }
                try {
                    json.decodeFromString<ResponseDto>(body)
                } catch (error: SerializationException) {
                    throw ApiClientException.InvalidResponse(error)
                }
            }
        } catch (error: CancellationException) {
            eventLogger(
                "request cancelled: method=POST endpoint=$endpoint " +
                    "duration_ms=${elapsedMillis(startedAtNanos)}",
            )
            throw error
        } catch (error: ApiClientException.Http) {
            throw error
        } catch (error: ApiClientException.InvalidResponse) {
            eventLogger(
                "request failed: method=POST endpoint=$endpoint kind=invalid_response " +
                    "duration_ms=${elapsedMillis(startedAtNanos)} " +
                    "cause=${error.cause.causesForLog()}",
            )
            throw error
        } catch (error: IOException) {
            eventLogger(
                "request failed: method=POST endpoint=$endpoint kind=network " +
                    "duration_ms=${elapsedMillis(startedAtNanos)} cause=${error.causesForLog()}",
            )
            throw ApiClientException.Network(error)
        } catch (error: IllegalArgumentException) {
            eventLogger(
                "request failed: method=POST endpoint=$endpoint kind=invalid_response " +
                    "duration_ms=${elapsedMillis(startedAtNanos)} cause=${error.causesForLog()}",
            )
            throw ApiClientException.InvalidResponse(error)
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((monotonicNanos() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PREDICTIVE_TIMEOUT_SECONDS = 240L
    }
}

private fun Throwable?.causesForLog(): String {
    if (this == null) return "unknown"
    val names = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && names.size < 4) {
        names += current::class.java.simpleName.ifEmpty { current::class.java.name }
        current = current.cause
    }
    return names.joinToString(">")
}

sealed class ApiClientException(cause: Throwable? = null) : Exception(cause) {
    class Network(cause: Throwable) : ApiClientException(cause)
    class InvalidResponse(cause: Throwable) : ApiClientException(cause)
    class Http(
        val statusCode: Int,
        val code: String,
    ) : ApiClientException()
}

@Serializable
private data class CoordinateDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
private data class RouteRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
)

@Serializable
private data class RankedCandidatesRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
    @SerialName("effective_cng_range_km") val effectiveCngRangeKm: Double,
    @SerialName("maximum_detour_minutes") val maximumDetourMinutes: Double,
    @SerialName("departure_at") val departureAt: String,
    @SerialName("include_closed") val includeClosed: Boolean,
)

@Serializable
private data class PredictiveCandidatesRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
    @SerialName("effective_cng_range_km") val effectiveCngRangeKm: Double,
    @SerialName("estimated_remaining_cng_range_km")
    val estimatedRemainingCngRangeKm: Double,
    @SerialName("reserve_cng_range_km") val reserveCngRangeKm: Double,
    @SerialName("estimated_remaining_gasoline_range_km")
    val estimatedRemainingGasolineRangeKm: Double?,
    @SerialName("reserve_gasoline_range_km") val reserveGasolineRangeKm: Double?,
    @SerialName("maximum_detour_minutes") val maximumDetourMinutes: Double,
    @SerialName("departure_at") val departureAt: String,
    @SerialName("include_closed") val includeClosed: Boolean,
    @SerialName("excluded_mimit_station_ids") val excludedMimitStationIds: List<String>,
)

@Serializable
private data class RouteWithCngStopRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
    @SerialName("mimit_station_id") val mimitStationId: String,
)

@Serializable
private data class RouteWithCngItineraryRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
    @SerialName("mimit_station_ids") val mimitStationIds: List<String>,
    @SerialName("effective_cng_range_km") val effectiveCngRangeKm: Double,
    @SerialName("estimated_remaining_cng_range_km")
    val estimatedRemainingCngRangeKm: Double,
    @SerialName("reserve_cng_range_km") val reserveCngRangeKm: Double,
)

@Serializable
private data class RouteGeometryDto(
    val format: String,
    @SerialName("encoded_polyline") val encodedPolyline: String,
)

@Serializable
private data class ManeuverDto(
    val type: Int,
    val instruction: String,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("begin_shape_index") val beginShapeIndex: Int,
    @SerialName("end_shape_index") val endShapeIndex: Int,
    @SerialName("street_names") val streetNames: List<String>,
    @SerialName("verbal_transition_alert_instruction")
    val verbalTransitionAlertInstruction: String?,
    @SerialName("verbal_pre_transition_instruction")
    val verbalPreTransitionInstruction: String?,
    @SerialName("verbal_post_transition_instruction")
    val verbalPostTransitionInstruction: String?,
    @SerialName("bearing_before") val bearingBefore: Int?,
    @SerialName("bearing_after") val bearingAfter: Int?,
    @SerialName("travel_mode") val travelMode: String?,
    @SerialName("travel_type") val travelType: String?,
)

@Serializable
private data class RouteResponseDto(
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val geometry: RouteGeometryDto,
    val maneuvers: List<ManeuverDto>,
    val provider: String,
    val navigation: NavigationTimingDto? = null,
)

@Serializable
private data class NavigationTimingDto(
    @SerialName("route_id") val routeId: String,
    @SerialName("driving_duration_seconds") val drivingDurationSeconds: Double,
    @SerialName("remaining_driving_duration_seconds")
    val remainingDrivingDurationSeconds: Double,
    @SerialName("refueling_stop_count") val refuelingStopCount: Int,
    @SerialName("dwell_seconds_per_refueling_stop")
    val dwellSecondsPerRefuelingStop: Int,
    @SerialName("total_refueling_dwell_seconds") val totalRefuelingDwellSeconds: Double,
    @SerialName("total_trip_duration_seconds") val totalTripDurationSeconds: Double,
    @SerialName("departure_at") val departureAt: String?,
    @SerialName("driving_arrival_at") val drivingArrivalAt: String?,
    @SerialName("trip_arrival_at") val tripArrivalAt: String?,
)

@Serializable
private data class CorridorPolicyDto(
    @SerialName("effective_cng_range_km") val effectiveCngRangeKm: Double,
    @SerialName("range_fraction") val rangeFraction: Double,
    @SerialName("uncapped_radius_km") val uncappedRadiusKm: Double,
    @SerialName("radius_km") val radiusKm: Double,
    @SerialName("cap_applied") val capApplied: String,
)

@Serializable
private data class SpatialPruningMetricsDto(
    @SerialName("active_station_count") val activeStationCount: Int,
    @SerialName("active_station_with_location_count") val activeStationWithLocationCount: Int,
    @SerialName("excluded_missing_location_count") val excludedMissingLocationCount: Int,
    @SerialName("corridor_candidate_count") val corridorCandidateCount: Int,
    @SerialName("returned_candidate_count") val returnedCandidateCount: Int,
    @SerialName("pruned_with_location_count") val prunedWithLocationCount: Int,
    @SerialName("reduction_ratio") val reductionRatio: Double,
    @SerialName("candidate_limit_applied") val candidateLimitApplied: Boolean,
    @SerialName("routing_calls") val routingCalls: Int,
)

@Serializable
private data class NetworkCostBasisDto(
    val provider: String,
    @SerialName("traffic_state") val trafficState: String,
    @SerialName("traffic_aware") val trafficAware: Boolean,
    @SerialName("duration_model") val durationModel: String,
    @SerialName("distance_model") val distanceModel: String,
)

@Serializable
private data class NetworkEvaluationMetricsDto(
    @SerialName("spatial_candidate_count") val spatialCandidateCount: Int,
    @SerialName("matrix_candidate_count") val matrixCandidateCount: Int,
    @SerialName("reachable_candidate_count") val reachableCandidateCount: Int,
    @SerialName("unreachable_candidate_count") val unreachableCandidateCount: Int,
    @SerialName("eligible_candidate_count") val eligibleCandidateCount: Int,
    @SerialName("excluded_by_detour_count") val excludedByDetourCount: Int,
    @SerialName("matrix_batch_size") val matrixBatchSize: Int,
    @SerialName("matrix_calls") val matrixCalls: Int,
    @SerialName("matrix_fallback_splits") val matrixFallbackSplits: Int,
    @SerialName("matrix_location_failures") val matrixLocationFailures: Int,
    @SerialName("base_route_calls") val baseRouteCalls: Int,
    @SerialName("per_candidate_route_calls") val perCandidateRouteCalls: Int,
)

@Serializable
private data class RankingPolicyDto(
    @SerialName("detour_weight") val detourWeight: Double,
    @SerialName("opening_weight") val openingWeight: Double,
    @SerialName("price_weight") val priceWeight: Double,
    @SerialName("price_freshness_weight") val priceFreshnessWeight: Double,
    @SerialName("unknown_opening_score") val unknownOpeningScore: Double,
    @SerialName("closed_score_multiplier") val closedScoreMultiplier: Double,
    @SerialName("price_freshness_hours") val priceFreshnessHours: Double,
    @SerialName("opening_hours_timezone") val openingHoursTimezone: String,
    @SerialName("opening_hours_country") val openingHoursCountry: String,
    @SerialName("price_selection") val priceSelection: String,
    @SerialName("closed_candidate_policy") val closedCandidatePolicy: String,
)

@Serializable
private data class RankingEvaluationMetricsDto(
    @SerialName("detour_eligible_candidate_count") val detourEligibleCandidateCount: Int,
    @SerialName("opening_open_count") val openingOpenCount: Int,
    @SerialName("opening_closed_count") val openingClosedCount: Int,
    @SerialName("opening_unknown_count") val openingUnknownCount: Int,
    @SerialName("opening_valid_count") val openingValidCount: Int,
    @SerialName("opening_missing_count") val openingMissingCount: Int,
    @SerialName("opening_invalid_count") val openingInvalidCount: Int,
    @SerialName("excluded_closed_count") val excludedClosedCount: Int,
    @SerialName("price_available_count") val priceAvailableCount: Int,
    @SerialName("price_missing_count") val priceMissingCount: Int,
    @SerialName("ranked_candidate_count") val rankedCandidateCount: Int,
    @SerialName("enrichment_queries") val enrichmentQueries: Int,
)

@Serializable
private data class OpeningEvaluationDto(
    val state: String,
    val validation: String,
    @SerialName("opening_hours") val openingHours: String?,
    val source: String?,
    @SerialName("source_confidence") val sourceConfidence: Double?,
    @SerialName("evaluated_at") val evaluatedAt: String,
    val timezone: String,
    @SerialName("next_change_at") val nextChangeAt: String?,
    val comment: String?,
    val warnings: List<String>,
)

@Serializable
private data class CngPriceDto(
    @SerialName("unit_price") val unitPrice: Double,
    val currency: String,
    val unit: String,
    @SerialName("service_mode") val serviceMode: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("ingested_at") val ingestedAt: String,
    @SerialName("source_name") val sourceName: String,
    @SerialName("age_seconds") val ageSeconds: Double?,
    @SerialName("freshness_state") val freshnessState: String,
)

@Serializable
private data class RankingBreakdownDto(
    val rank: Int,
    @SerialName("total_score") val totalScore: Double,
    @SerialName("detour_score") val detourScore: Double,
    @SerialName("opening_score") val openingScore: Double,
    @SerialName("price_score") val priceScore: Double,
    @SerialName("price_freshness_score") val priceFreshnessScore: Double,
    @SerialName("detour_contribution") val detourContribution: Double,
    @SerialName("opening_contribution") val openingContribution: Double,
    @SerialName("price_contribution") val priceContribution: Double,
    @SerialName("price_freshness_contribution") val priceFreshnessContribution: Double,
    @SerialName("availability_multiplier") val availabilityMultiplier: Double,
)

@Serializable
private data class RankedCandidateDto(
    @SerialName("station_id") val stationId: Long,
    @SerialName("mimit_station_id") val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val latitude: Double,
    val longitude: Double,
    @SerialName("straight_line_distance_to_route_meters")
    val straightLineDistanceToRouteMeters: Double,
    @SerialName("route_fraction") val routeFraction: Double,
    @SerialName("distance_from_previous_waypoint_meters")
    val distanceFromPreviousWaypointMeters: Double,
    @SerialName("duration_from_previous_waypoint_seconds")
    val durationFromPreviousWaypointSeconds: Double,
    @SerialName("station_to_destination_distance_meters")
    val stationToDestinationDistanceMeters: Double,
    @SerialName("station_to_destination_duration_seconds")
    val stationToDestinationDurationSeconds: Double,
    @SerialName("route_via_station_distance_meters") val routeViaStationDistanceMeters: Double,
    @SerialName("route_via_station_duration_seconds") val routeViaStationDurationSeconds: Double,
    @SerialName("extra_distance_meters") val extraDistanceMeters: Double,
    @SerialName("detour_duration_seconds") val detourDurationSeconds: Double,
    @SerialName("detour_minutes") val detourMinutes: Double,
    @SerialName("station_eta") val stationEta: String,
    @SerialName("destination_eta") val destinationEta: String,
    val opening: OpeningEvaluationDto,
    val phone: String?,
    val brand: String?,
    val operator: String?,
    @SerialName("osm_match_confidence") val osmMatchConfidence: Double?,
    val price: CngPriceDto?,
    val ranking: RankingBreakdownDto,
)

@Serializable
private data class RankedCandidatesResponseDto(
    val stage: String,
    @SerialName("departure_at") val departureAt: String,
    @SerialName("maximum_detour_minutes") val maximumDetourMinutes: Double,
    @SerialName("base_route") val baseRoute: RouteResponseDto,
    val corridor: CorridorPolicyDto,
    @SerialName("spatial_pruning") val spatialPruning: SpatialPruningMetricsDto,
    @SerialName("cost_basis") val costBasis: NetworkCostBasisDto,
    @SerialName("network_evaluation") val networkEvaluation: NetworkEvaluationMetricsDto,
    @SerialName("ranking_policy") val rankingPolicy: RankingPolicyDto,
    @SerialName("ranking_evaluation") val rankingEvaluation: RankingEvaluationMetricsDto,
    val candidates: List<RankedCandidateDto>,
)

@Serializable
private data class PredictiveRangeBasisDto(
    @SerialName("effective_cng_range_km") val effectiveCngRangeKm: Double,
    @SerialName("estimated_remaining_cng_range_km")
    val estimatedRemainingCngRangeKm: Double,
    @SerialName("reserve_cng_range_km") val reserveCngRangeKm: Double,
    @SerialName("usable_range_before_reserve_km") val usableRangeBeforeReserveKm: Double,
    @SerialName("remaining_route_distance_km") val remainingRouteDistanceKm: Double,
    @SerialName("range_shortfall_to_destination_km") val rangeShortfallToDestinationKm: Double,
    @SerialName("destination_reachable_with_reserve")
    val destinationReachableWithReserve: Boolean,
    @SerialName("remaining_route_origin") val remainingRouteOrigin: String,
    @SerialName("consumption_model") val consumptionModel: String,
    @SerialName("traffic_state") val trafficState: String,
    @SerialName("traffic_adjusted") val trafficAdjusted: Boolean,
)

@Serializable
private data class PredictiveReachabilityMetricsDto(
    @SerialName("detour_eligible_candidate_count") val detourEligibleCandidateCount: Int,
    @SerialName("reachable_before_reserve_count") val reachableBeforeReserveCount: Int,
    @SerialName("excluded_unreachable_before_reserve_count")
    val excludedUnreachableBeforeReserveCount: Int,
    @SerialName("ranked_reachable_candidate_count") val rankedReachableCandidateCount: Int,
    @SerialName("furthest_reachable_route_fraction") val furthestReachableRouteFraction: Double?,
    @SerialName("evaluation_skipped_destination_reachable")
    val evaluationSkippedDestinationReachable: Boolean,
    @SerialName("pairwise_matrix_calls") val pairwiseMatrixCalls: Int,
    @SerialName("pairwise_matrix_fallback_splits") val pairwiseMatrixFallbackSplits: Int,
    @SerialName("pairwise_matrix_location_failures") val pairwiseMatrixLocationFailures: Int,
    @SerialName("itinerary_search_labels") val itinerarySearchLabels: Int,
)

@Serializable
private data class PredictiveRankedCandidateDto(
    val candidate: RankedCandidateDto,
    @SerialName("estimated_remaining_range_at_arrival_km")
    val estimatedRemainingRangeAtArrivalKm: Double,
    @SerialName("reserve_margin_at_arrival_km") val reserveMarginAtArrivalKm: Double,
)

@Serializable
private data class PredictiveItineraryStopDto(
    val sequence: Int,
    @SerialName("station_id") val stationId: Long,
    @SerialName("mimit_station_id") val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: CoordinateDto,
    @SerialName("arrival_at") val arrivalAt: String,
    @SerialName("leg_distance_meters") val legDistanceMeters: Double,
    @SerialName("leg_duration_seconds") val legDurationSeconds: Double,
    @SerialName("available_range_at_departure_km") val availableRangeAtDepartureKm: Double,
    @SerialName("estimated_remaining_range_at_arrival_km")
    val estimatedRemainingRangeAtArrivalKm: Double,
    @SerialName("reserve_margin_at_arrival_km") val reserveMarginAtArrivalKm: Double,
    val opening: OpeningEvaluationDto,
    val phone: String?,
    val brand: String?,
    val operator: String?,
    @SerialName("osm_match_confidence") val osmMatchConfidence: Double?,
    val price: CngPriceDto?,
)

@Serializable
private data class PredictiveDestinationLegDto(
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("available_range_at_departure_km") val availableRangeAtDepartureKm: Double,
    @SerialName("estimated_remaining_range_at_arrival_km")
    val estimatedRemainingRangeAtArrivalKm: Double,
    @SerialName("reserve_margin_at_arrival_km") val reserveMarginAtArrivalKm: Double,
    @SerialName("destination_eta") val destinationEta: String,
)

@Serializable
private data class PredictiveItineraryDto(
    val stops: List<PredictiveItineraryStopDto>,
    @SerialName("destination_leg") val destinationLeg: PredictiveDestinationLegDto,
    @SerialName("total_distance_meters") val totalDistanceMeters: Double,
    @SerialName("total_duration_seconds") val totalDurationSeconds: Double,
    @SerialName("refuel_assumption") val refuelAssumption: String,
    @SerialName("distance_model") val distanceModel: String,
)

@Serializable
private data class PredictiveCandidatesResponseDto(
    val stage: String,
    @SerialName("suggestion_state") val suggestionState: String,
    @SerialName("departure_at") val departureAt: String,
    @SerialName("maximum_detour_minutes") val maximumDetourMinutes: Double,
    @SerialName("excluded_mimit_station_ids")
    val excludedMimitStationIds: List<String> = emptyList(),
    @SerialName("base_route") val baseRoute: RouteResponseDto,
    val corridor: CorridorPolicyDto,
    @SerialName("spatial_pruning") val spatialPruning: SpatialPruningMetricsDto,
    @SerialName("cost_basis") val costBasis: NetworkCostBasisDto,
    @SerialName("network_evaluation") val networkEvaluation: NetworkEvaluationMetricsDto,
    @SerialName("range_basis") val rangeBasis: PredictiveRangeBasisDto,
    @SerialName("reachability_evaluation")
    val reachabilityEvaluation: PredictiveReachabilityMetricsDto,
    @SerialName("ranking_policy") val rankingPolicy: RankingPolicyDto,
    @SerialName("ranking_evaluation") val rankingEvaluation: RankingEvaluationMetricsDto,
    val candidates: List<PredictiveRankedCandidateDto>,
    val itinerary: PredictiveItineraryDto?,
    @SerialName("gasoline_fallback") val gasolineFallback: GasolineFallbackDto? = null,
)

@Serializable
private data class GasolineFallbackDto(
    @SerialName("estimated_remaining_gasoline_range_km")
    val estimatedRemainingGasolineRangeKm: Double,
    @SerialName("reserve_gasoline_range_km") val reserveGasolineRangeKm: Double,
    @SerialName("usable_gasoline_range_km") val usableGasolineRangeKm: Double,
    @SerialName("cng_range_used_before_switch_km") val cngRangeUsedBeforeSwitchKm: Double,
    @SerialName("required_gasoline_range_km") val requiredGasolineRangeKm: Double,
    @SerialName("gasoline_margin_at_destination_km")
    val gasolineMarginAtDestinationKm: Double,
    val strategy: String,
)

@Serializable
private data class SelectedCngStopDto(
    @SerialName("mimit_station_id") val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: CoordinateDto,
    @SerialName("expected_arrival_at") val expectedArrivalAt: String? = null,
    @SerialName("dwell_time_seconds") val dwellTimeSeconds: Int = 20 * 60,
)

@Serializable
private data class RouteLegDto(
    val kind: String,
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val geometry: RouteGeometryDto,
    val maneuvers: List<ManeuverDto>,
)

@Serializable
private data class RouteWithCngStopResponseDto(
    @SerialName("selected_stop") val selectedStop: SelectedCngStopDto,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val legs: List<RouteLegDto>,
    val provider: String,
    val navigation: NavigationTimingDto? = null,
)

@Serializable
private data class CngItineraryStopDto(
    val sequence: Int,
    @SerialName("mimit_station_id") val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: CoordinateDto,
    @SerialName("expected_arrival_at") val expectedArrivalAt: String? = null,
    @SerialName("dwell_time_seconds") val dwellTimeSeconds: Int = 20 * 60,
)

@Serializable
private data class CngItineraryRouteLegDto(
    val sequence: Int,
    val kind: String,
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val geometry: RouteGeometryDto,
    val maneuvers: List<ManeuverDto>,
    @SerialName("available_range_at_departure_km") val availableRangeAtDepartureKm: Double,
    @SerialName("estimated_remaining_range_at_arrival_km")
    val estimatedRemainingRangeAtArrivalKm: Double,
    @SerialName("reserve_margin_at_arrival_km") val reserveMarginAtArrivalKm: Double,
)

@Serializable
private data class RouteWithCngItineraryResponseDto(
    @SerialName("selected_stops") val selectedStops: List<CngItineraryStopDto>,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val legs: List<CngItineraryRouteLegDto>,
    val provider: String,
    @SerialName("range_validation") val rangeValidation: String,
    val navigation: NavigationTimingDto? = null,
)

@Serializable
private data class ErrorResponseDto(
    val code: String,
    val message: String,
)

private fun Coordinate.toDto(): CoordinateDto = CoordinateDto(
    latitude = latitude,
    longitude = longitude,
)

private fun RouteResponseDto.toApiRoute(): ApiRoute {
    require(geometry.format == "polyline6") { "unsupported route geometry format" }
    require(distanceMeters >= 0 && durationSeconds >= 0) { "negative route cost" }
    return ApiRoute(
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        encodedPolyline = geometry.encodedPolyline,
        maneuvers = maneuvers.map(ManeuverDto::toApiManeuver),
        provider = provider,
        navigation = navigation.toApiNavigationTiming(durationSeconds, refuelingStopCount = 0),
    )
}

private fun ManeuverDto.toApiManeuver(): ApiManeuver = ApiManeuver(
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

private fun RankedCandidatesResponseDto.toApiRankedCandidates(): ApiRankedCandidates {
    require(stage == "ranking") { "unsupported candidate response stage" }
    require(costBasis.provider == "valhalla") { "unsupported routing provider" }
    require(candidates.size == rankingEvaluation.rankedCandidateCount) {
        "candidate count does not match ranking metrics"
    }
    return ApiRankedCandidates(
        departureAt = departureAt,
        maximumDetourMinutes = maximumDetourMinutes,
        baseRoute = baseRoute.toApiRoute(),
        trafficState = costBasis.trafficState,
        candidates = candidates.map(RankedCandidateDto::toApiRankedCandidate),
    )
}

private fun PredictiveCandidatesResponseDto.toApiPredictiveCandidates(): ApiPredictiveCandidates {
    require(stage == "predictive_ranking") { "unsupported predictive response stage" }
    require(costBasis.provider == "valhalla") { "unsupported routing provider" }
    require(costBasis.trafficAware == rangeBasis.trafficAdjusted) {
        "predictive traffic flags disagree"
    }
    require(rangeBasis.remainingRouteOrigin == "request_origin") {
        "unsupported remaining-route origin"
    }
    require(rangeBasis.consumptionModel == "caller_estimated_remaining_range") {
        "unsupported predictive consumption model"
    }
    require(candidates.size == reachabilityEvaluation.rankedReachableCandidateCount) {
        "candidate count does not match reachability metrics"
    }
    require(candidates.size == rankingEvaluation.rankedCandidateCount) {
        "candidate count does not match ranking metrics"
    }
    require(
        suggestionState in setOf(
            "not_needed",
            "suggested",
            "gasoline_fallback",
            "no_reachable_station",
            "no_eligible_station",
            "no_complete_itinerary",
        ),
    ) { "unsupported predictive suggestion state" }
    require(suggestionState != "suggested" || candidates.size == 1) {
        "suggested response must contain its selected first stop"
    }
    require(suggestionState == "suggested" || candidates.isEmpty()) {
        "non-suggestion response must not contain candidates"
    }
    require(
        suggestionState != "not_needed" || rangeBasis.destinationReachableWithReserve,
    ) { "not-needed response must prove destination reachability" }
    require((suggestionState == "suggested") == (itinerary != null)) {
        "only a suggested response may contain a complete itinerary"
    }
    require((suggestionState == "gasoline_fallback") == (gasolineFallback != null)) {
        "only a gasoline fallback response may contain gasoline metrics"
    }
    val usableMeters = rangeBasis.usableRangeBeforeReserveKm * 1_000
    candidates.forEach { predictive ->
        require(predictive.candidate.distanceFromPreviousWaypointMeters <= usableMeters + 1.0) {
            "predictive candidate is unreachable before reserve"
        }
    }
    return ApiPredictiveCandidates(
        suggestionState = suggestionState,
        departureAt = departureAt,
        maximumDetourMinutes = maximumDetourMinutes,
        excludedMimitStationIds = excludedMimitStationIds,
        baseRoute = baseRoute.toApiRoute(),
        trafficState = rangeBasis.trafficState,
        rangeBasis = ApiPredictiveRangeBasis(
            effectiveCngRangeKm = rangeBasis.effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = rangeBasis.estimatedRemainingCngRangeKm,
            reserveCngRangeKm = rangeBasis.reserveCngRangeKm,
            usableRangeBeforeReserveKm = rangeBasis.usableRangeBeforeReserveKm,
            remainingRouteDistanceKm = rangeBasis.remainingRouteDistanceKm,
            rangeShortfallToDestinationKm = rangeBasis.rangeShortfallToDestinationKm,
            destinationReachableWithReserve = rangeBasis.destinationReachableWithReserve,
            consumptionModel = rangeBasis.consumptionModel,
            trafficState = rangeBasis.trafficState,
            trafficAdjusted = rangeBasis.trafficAdjusted,
        ),
        candidates = candidates.map { predictive ->
            ApiPredictiveRankedCandidate(
                candidate = predictive.candidate.toApiRankedCandidate(),
                estimatedRemainingRangeAtArrivalKm = (
                    predictive.estimatedRemainingRangeAtArrivalKm
                ),
                reserveMarginAtArrivalKm = predictive.reserveMarginAtArrivalKm,
            )
        },
        itinerary = itinerary?.toApiPredictiveItinerary(),
        gasolineFallback = gasolineFallback?.let {
            ApiGasolineFallback(
                estimatedRemainingGasolineRangeKm = it.estimatedRemainingGasolineRangeKm,
                reserveGasolineRangeKm = it.reserveGasolineRangeKm,
                usableGasolineRangeKm = it.usableGasolineRangeKm,
                cngRangeUsedBeforeSwitchKm = it.cngRangeUsedBeforeSwitchKm,
                requiredGasolineRangeKm = it.requiredGasolineRangeKm,
                gasolineMarginAtDestinationKm = it.gasolineMarginAtDestinationKm,
                strategy = it.strategy,
            )
        },
    )
}

private fun PredictiveItineraryDto.toApiPredictiveItinerary(): ApiPredictiveItinerary =
    ApiPredictiveItinerary(
        stops = stops.map { stop ->
            ApiPredictiveItineraryStop(
                sequence = stop.sequence,
                stationId = stop.stationId,
                mimitStationId = stop.mimitStationId,
                name = stop.name,
                municipality = stop.municipality,
                province = stop.province,
                latitude = stop.location.latitude,
                longitude = stop.location.longitude,
                arrivalAt = stop.arrivalAt,
                legDistanceMeters = stop.legDistanceMeters,
                legDurationSeconds = stop.legDurationSeconds,
                availableRangeAtDepartureKm = stop.availableRangeAtDepartureKm,
                estimatedRemainingRangeAtArrivalKm = (
                    stop.estimatedRemainingRangeAtArrivalKm
                ),
                reserveMarginAtArrivalKm = stop.reserveMarginAtArrivalKm,
                opening = stop.opening.toApiOpeningEvaluation(),
                phone = stop.phone,
                brand = stop.brand,
                operator = stop.operator,
                osmMatchConfidence = stop.osmMatchConfidence,
                price = stop.price?.toApiCngPrice(),
            )
        },
        destinationLeg = ApiPredictiveDestinationLeg(
            distanceMeters = destinationLeg.distanceMeters,
            durationSeconds = destinationLeg.durationSeconds,
            availableRangeAtDepartureKm = destinationLeg.availableRangeAtDepartureKm,
            estimatedRemainingRangeAtArrivalKm = (
                destinationLeg.estimatedRemainingRangeAtArrivalKm
            ),
            reserveMarginAtArrivalKm = destinationLeg.reserveMarginAtArrivalKm,
            destinationEta = destinationLeg.destinationEta,
        ),
        totalDistanceMeters = totalDistanceMeters,
        totalDurationSeconds = totalDurationSeconds,
        refuelAssumption = refuelAssumption,
        distanceModel = distanceModel,
    )

private fun RankedCandidateDto.toApiRankedCandidate(): ApiRankedCandidate = ApiRankedCandidate(
    stationId = stationId,
    mimitStationId = mimitStationId,
    name = name,
    municipality = municipality,
    province = province,
    latitude = latitude,
    longitude = longitude,
    distanceFromPreviousWaypointMeters = distanceFromPreviousWaypointMeters,
    detourMinutes = detourMinutes,
    stationEta = stationEta,
    destinationEta = destinationEta,
    opening = opening.toApiOpeningEvaluation(),
    phone = phone,
    brand = brand,
    operator = operator,
    osmMatchConfidence = osmMatchConfidence,
    price = price?.toApiCngPrice(),
    ranking = ApiRankingBreakdown(
        rank = ranking.rank,
        totalScore = ranking.totalScore,
        detourScore = ranking.detourScore,
        openingScore = ranking.openingScore,
        priceScore = ranking.priceScore,
        priceFreshnessScore = ranking.priceFreshnessScore,
    ),
)

private fun OpeningEvaluationDto.toApiOpeningEvaluation(): ApiOpeningEvaluation =
    ApiOpeningEvaluation(
        state = state,
        validation = validation,
        openingHours = openingHours,
        source = source,
        sourceConfidence = sourceConfidence,
        evaluatedAt = evaluatedAt,
        timezone = timezone,
        nextChangeAt = nextChangeAt,
        warnings = warnings,
    )

private fun CngPriceDto.toApiCngPrice(): ApiCngPrice = ApiCngPrice(
    unitPrice = unitPrice,
    currency = currency,
    unit = unit,
    serviceMode = serviceMode,
    observedAt = observedAt,
    ingestedAt = ingestedAt,
    sourceName = sourceName,
    ageSeconds = ageSeconds,
    freshnessState = freshnessState,
)

private fun RouteWithCngStopResponseDto.toApiRouteWithCngStop(): ApiRouteWithCngStop {
    require(provider == "valhalla") { "unsupported routing provider" }
    require(distanceMeters >= 0 && durationSeconds >= 0) { "negative route cost" }
    require(legs.size == 2) { "selected-stop route must contain exactly two legs" }
    require(legs.map(RouteLegDto::kind) == EXPECTED_LEG_KINDS) { "unexpected route leg order" }
    return ApiRouteWithCngStop(
        selectedStop = ApiSelectedCngStop(
            mimitStationId = selectedStop.mimitStationId,
            name = selectedStop.name,
            municipality = selectedStop.municipality,
            province = selectedStop.province,
            latitude = selectedStop.location.latitude,
            longitude = selectedStop.location.longitude,
            expectedArrivalAt = selectedStop.expectedArrivalAt,
            dwellTimeSeconds = selectedStop.dwellTimeSeconds,
        ),
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        legs = legs.map { leg ->
            require(leg.geometry.format == "polyline6") { "unsupported route geometry format" }
            require(leg.distanceMeters >= 0 && leg.durationSeconds >= 0) { "negative route cost" }
            ApiRouteLeg(
                kind = leg.kind,
                originLatitude = leg.origin.latitude,
                originLongitude = leg.origin.longitude,
                destinationLatitude = leg.destination.latitude,
                destinationLongitude = leg.destination.longitude,
                distanceMeters = leg.distanceMeters,
                durationSeconds = leg.durationSeconds,
                encodedPolyline = leg.geometry.encodedPolyline,
                maneuvers = leg.maneuvers.map(ManeuverDto::toApiManeuver),
            )
        },
        provider = provider,
        navigation = navigation.toApiNavigationTiming(durationSeconds, refuelingStopCount = 1),
    )
}

private fun RouteWithCngItineraryResponseDto.toApiRouteWithCngItinerary():
    ApiRouteWithCngItinerary {
    require(provider == "valhalla") { "unsupported routing provider" }
    require(rangeValidation == "all_legs_preserve_reserve") {
        "itinerary route has not preserved the reserve"
    }
    require(selectedStops.isNotEmpty() && legs.size == selectedStops.size + 1) {
        "itinerary route stop and leg counts do not reconcile"
    }
    require(selectedStops.map(CngItineraryStopDto::sequence) == (1..selectedStops.size).toList()) {
        "itinerary stop sequence is not contiguous"
    }
    require(legs.map(CngItineraryRouteLegDto::sequence) == (1..legs.size).toList()) {
        "itinerary leg sequence is not contiguous"
    }
    return ApiRouteWithCngItinerary(
        selectedStops = selectedStops.map { stop ->
            ApiSelectedCngStop(
                mimitStationId = stop.mimitStationId,
                name = stop.name,
                municipality = stop.municipality,
                province = stop.province,
                latitude = stop.location.latitude,
                longitude = stop.location.longitude,
                expectedArrivalAt = stop.expectedArrivalAt,
                dwellTimeSeconds = stop.dwellTimeSeconds,
            )
        },
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        legs = legs.map { leg ->
            require(leg.geometry.format == "polyline6") {
                "unsupported route geometry format"
            }
            ApiCngItineraryRouteLeg(
                sequence = leg.sequence,
                kind = leg.kind,
                originLatitude = leg.origin.latitude,
                originLongitude = leg.origin.longitude,
                destinationLatitude = leg.destination.latitude,
                destinationLongitude = leg.destination.longitude,
                distanceMeters = leg.distanceMeters,
                durationSeconds = leg.durationSeconds,
                encodedPolyline = leg.geometry.encodedPolyline,
                maneuvers = leg.maneuvers.map(ManeuverDto::toApiManeuver),
                availableRangeAtDepartureKm = leg.availableRangeAtDepartureKm,
                estimatedRemainingRangeAtArrivalKm = (
                    leg.estimatedRemainingRangeAtArrivalKm
                ),
                reserveMarginAtArrivalKm = leg.reserveMarginAtArrivalKm,
            )
        },
        provider = provider,
        rangeValidation = rangeValidation,
        navigation = navigation.toApiNavigationTiming(
            durationSeconds,
            refuelingStopCount = selectedStops.size,
        ),
    )
}

private fun NavigationTimingDto?.toApiNavigationTiming(
    fallbackDrivingDurationSeconds: Double,
    refuelingStopCount: Int,
): ApiNavigationTiming {
    if (this == null) {
        val dwellSeconds = refuelingStopCount * 20.0 * 60.0
        return ApiNavigationTiming(
            routeId = "route_00000000000000000000000000000000",
            drivingDurationSeconds = fallbackDrivingDurationSeconds,
            remainingDrivingDurationSeconds = fallbackDrivingDurationSeconds,
            refuelingStopCount = refuelingStopCount,
            dwellSecondsPerRefuelingStop = 20 * 60,
            totalRefuelingDwellSeconds = dwellSeconds,
            totalTripDurationSeconds = fallbackDrivingDurationSeconds + dwellSeconds,
            departureAt = null,
            drivingArrivalAt = null,
            tripArrivalAt = null,
        )
    }
    require(routeId.matches(Regex("^route_[0-9a-f]{32}$"))) { "invalid route identity" }
    require(
        drivingDurationSeconds >= 0 &&
            remainingDrivingDurationSeconds >= 0 &&
            refuelingStopCount >= 0 &&
            dwellSecondsPerRefuelingStop >= 0 &&
            totalRefuelingDwellSeconds >= 0 &&
            totalTripDurationSeconds >= drivingDurationSeconds,
    ) { "invalid navigation timing" }
    require(
        kotlin.math.abs(
            totalRefuelingDwellSeconds -
                refuelingStopCount * dwellSecondsPerRefuelingStop,
        ) <= 0.001,
    ) { "navigation dwell total does not reconcile" }
    require(
        kotlin.math.abs(
            totalTripDurationSeconds -
                drivingDurationSeconds - totalRefuelingDwellSeconds,
        ) <= 0.001,
    ) { "navigation trip duration does not reconcile" }
    return ApiNavigationTiming(
        routeId = routeId,
        drivingDurationSeconds = drivingDurationSeconds,
        remainingDrivingDurationSeconds = remainingDrivingDurationSeconds,
        refuelingStopCount = refuelingStopCount,
        dwellSecondsPerRefuelingStop = dwellSecondsPerRefuelingStop,
        totalRefuelingDwellSeconds = totalRefuelingDwellSeconds,
        totalTripDurationSeconds = totalTripDurationSeconds,
        departureAt = departureAt,
        drivingArrivalAt = drivingArrivalAt,
        tripArrivalAt = tripArrivalAt,
    )
}

private val EXPECTED_LEG_KINDS = listOf(
    "origin_to_cng_station",
    "cng_station_to_destination",
)
