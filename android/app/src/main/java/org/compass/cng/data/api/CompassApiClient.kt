package org.compass.cng.data.api

import java.io.IOException
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
) {
    private val apiBaseUrl = baseUrl.toHttpUrl()
    private val routeUrl = resolve("api/v1/routes")
    private val rankedCandidatesUrl = resolve("api/v1/cng/ranked-candidates")
    private val routeWithCngStopUrl = resolve("api/v1/routes/with-cng-stop")

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

    private fun resolve(path: String): HttpUrl = apiBaseUrl.resolve(path)
        ?: error("COMPASS_API_BASE_URL cannot resolve $path")

    private suspend inline fun <reified ResponseDto> post(
        url: HttpUrl,
        requestJson: String,
    ): ResponseDto = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
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
            throw error
        } catch (error: ApiClientException) {
            throw error
        } catch (error: IOException) {
            throw ApiClientException.Network(error)
        } catch (error: IllegalArgumentException) {
            throw ApiClientException.InvalidResponse(error)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
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
private data class RouteWithCngStopRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
    @SerialName("mimit_station_id") val mimitStationId: String,
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
private data class SelectedCngStopDto(
    @SerialName("mimit_station_id") val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: CoordinateDto,
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
    travelMode = travelMode,
    travelType = travelType,
)

private fun RankedCandidatesResponseDto.toApiRankedCandidates(): ApiRankedCandidates {
    require(stage == "ranking") { "unsupported candidate response stage" }
    require(costBasis.provider == "valhalla") { "unsupported routing provider" }
    require(!costBasis.trafficAware) { "unexpected traffic-aware response" }
    require(candidates.size == rankingEvaluation.rankedCandidateCount) {
        "candidate count does not match ranking metrics"
    }
    return ApiRankedCandidates(
        departureAt = departureAt,
        maximumDetourMinutes = maximumDetourMinutes,
        baseRoute = baseRoute.toApiRoute(),
        trafficState = costBasis.trafficState,
        candidates = candidates.map { candidate ->
            ApiRankedCandidate(
                stationId = candidate.stationId,
                mimitStationId = candidate.mimitStationId,
                name = candidate.name,
                municipality = candidate.municipality,
                province = candidate.province,
                latitude = candidate.latitude,
                longitude = candidate.longitude,
                distanceFromPreviousWaypointMeters = candidate.distanceFromPreviousWaypointMeters,
                detourMinutes = candidate.detourMinutes,
                stationEta = candidate.stationEta,
                destinationEta = candidate.destinationEta,
                opening = ApiOpeningEvaluation(
                    state = candidate.opening.state,
                    validation = candidate.opening.validation,
                    openingHours = candidate.opening.openingHours,
                    source = candidate.opening.source,
                    sourceConfidence = candidate.opening.sourceConfidence,
                    evaluatedAt = candidate.opening.evaluatedAt,
                    timezone = candidate.opening.timezone,
                    nextChangeAt = candidate.opening.nextChangeAt,
                    warnings = candidate.opening.warnings,
                ),
                phone = candidate.phone,
                brand = candidate.brand,
                operator = candidate.operator,
                osmMatchConfidence = candidate.osmMatchConfidence,
                price = candidate.price?.let { price ->
                    ApiCngPrice(
                        unitPrice = price.unitPrice,
                        currency = price.currency,
                        unit = price.unit,
                        serviceMode = price.serviceMode,
                        observedAt = price.observedAt,
                        ingestedAt = price.ingestedAt,
                        sourceName = price.sourceName,
                        ageSeconds = price.ageSeconds,
                        freshnessState = price.freshnessState,
                    )
                },
                ranking = ApiRankingBreakdown(
                    rank = candidate.ranking.rank,
                    totalScore = candidate.ranking.totalScore,
                    detourScore = candidate.ranking.detourScore,
                    openingScore = candidate.ranking.openingScore,
                    priceScore = candidate.ranking.priceScore,
                    priceFreshnessScore = candidate.ranking.priceFreshnessScore,
                ),
            )
        },
    )
}

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
    )
}

private val EXPECTED_LEG_KINDS = listOf(
    "origin_to_cng_station",
    "cng_station_to_destination",
)
