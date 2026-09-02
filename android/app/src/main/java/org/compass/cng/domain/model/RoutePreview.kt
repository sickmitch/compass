package org.compass.cng.domain.model

import java.time.OffsetDateTime

data class NavigationTiming(
    val routeId: String,
    val drivingDurationSeconds: Double,
    val remainingDrivingDurationSeconds: Double,
    val refuelingStopCount: Int,
    val dwellSecondsPerRefuelingStop: Int,
    val totalRefuelingDwellSeconds: Double,
    val totalTripDurationSeconds: Double,
    val departureAt: OffsetDateTime?,
    val drivingArrivalAt: OffsetDateTime?,
    val tripArrivalAt: OffsetDateTime?,
) {
    init {
        require(routeId.isNotBlank()) { "route identity must not be blank" }
        require(drivingDurationSeconds >= 0 && remainingDrivingDurationSeconds >= 0) {
            "navigation driving duration must not be negative"
        }
        require(refuelingStopCount >= 0 && dwellSecondsPerRefuelingStop >= 0) {
            "navigation refuelling values must not be negative"
        }
        require(totalRefuelingDwellSeconds >= 0) { "total dwell must not be negative" }
        require(totalTripDurationSeconds >= drivingDurationSeconds) {
            "trip duration must include driving duration"
        }
    }

    companion object {
        fun legacy(drivingDurationSeconds: Double, refuelingStopCount: Int = 0) =
            NavigationTiming(
                routeId = "legacy_route",
                drivingDurationSeconds = drivingDurationSeconds,
                remainingDrivingDurationSeconds = drivingDurationSeconds,
                refuelingStopCount = refuelingStopCount,
                dwellSecondsPerRefuelingStop = 20 * 60,
                totalRefuelingDwellSeconds = refuelingStopCount * 20.0 * 60.0,
                totalTripDurationSeconds =
                    drivingDurationSeconds + refuelingStopCount * 20.0 * 60.0,
                departureAt = null,
                drivingArrivalAt = null,
                tripArrivalAt = null,
            )
    }
}

data class RoutePreview(
    val origin: Coordinate,
    val destination: Coordinate,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<Coordinate>,
    val maneuvers: List<Maneuver>,
    val provider: String,
    val navigation: NavigationTiming = NavigationTiming.legacy(durationSeconds),
)

data class Maneuver(
    val type: Int,
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val streetNames: List<String>,
    val verbalTransitionAlertInstruction: String? = null,
    val verbalPreTransitionInstruction: String? = null,
    val verbalPostTransitionInstruction: String? = null,
    val bearingBefore: Int? = null,
    val bearingAfter: Int? = null,
    val travelMode: String?,
    val travelType: String?,
)
