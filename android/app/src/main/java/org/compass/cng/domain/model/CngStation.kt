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

data class SelectedCngStop(
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: Coordinate,
)

enum class CngRouteLegKind {
    ORIGIN_TO_CNG_STATION,
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
