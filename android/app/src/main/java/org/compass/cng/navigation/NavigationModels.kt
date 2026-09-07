package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.GasolineFallback
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteSpeedLimit
import org.compass.cng.domain.model.RouteWithCngItinerary
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.SelectedCngStop
import java.time.Instant

enum class NavigationPhase {
    IDLE,
    ROUTE_PREVIEW,
    NAVIGATING,
    APPROACHING_MANEUVER,
    APPROACHING_FUEL_STOP,
    AT_FUEL_STOP,
    REROUTING,
    GPS_LOST,
    ARRIVED,
}

data class NavigationFuelStop(
    val sequence: Int,
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: Coordinate,
    val expectedArrivalAt: java.time.OffsetDateTime?,
    val dwellTimeSeconds: Int,
)

data class NavigationLeg(
    val sequence: Int,
    val origin: Coordinate,
    val destination: Coordinate,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<Coordinate>,
    val maneuvers: List<Maneuver>,
    val shapeIndexOffset: Int,
    val availableRangeAtDepartureKm: Double? = null,
    val estimatedRemainingRangeAtArrivalKm: Double? = null,
    val reserveMarginAtArrivalKm: Double? = null,
    val speedLimits: List<RouteSpeedLimit> = emptyList(),
    val speedLimitSource: String? = null,
) {
    init {
        require(speedLimitSource == null || speedLimitSource == "valhalla_graph")
        require(speedLimits.isEmpty() || speedLimitSource == "valhalla_graph")
        require(speedLimits.all {
            it.beginShapeIndex >= shapeIndexOffset &&
                it.endShapeIndex <= shapeIndexOffset + geometry.lastIndex
        }) { "leg speed-limit profile exceeds its joined geometry range" }
    }
}

data class NavigationFuelPlan(
    val effectiveCngRangeKm: Double,
    val initialRemainingCngRangeKm: Double,
    val reserveCngRangeKm: Double,
    val maximumDetourMinutes: Double? = null,
    val excludedMimitStationIds: Set<String> = emptySet(),
)

data class NavigationRoute(
    val routeId: String,
    val origin: Coordinate,
    val destination: Coordinate,
    val totalDistanceMeters: Double,
    val drivingDurationSeconds: Double,
    val totalTripDurationSeconds: Double,
    val geometry: List<Coordinate>,
    val legs: List<NavigationLeg>,
    val maneuvers: List<Maneuver>,
    val fuelStops: List<NavigationFuelStop>,
    val fuelPlan: NavigationFuelPlan? = null,
    val timing: NavigationTiming,
    val provider: String,
    val gasolineFallback: GasolineFallback? = null,
    val speedLimits: List<RouteSpeedLimit> = emptyList(),
    val speedLimitSource: String? = null,
) {
    init {
        require(speedLimitSource == null || speedLimitSource == "valhalla_graph")
        require(speedLimits.isEmpty() || speedLimitSource == "valhalla_graph")
        require(speedLimits.zipWithNext().all { (first, second) ->
            second.beginShapeIndex >= first.endShapeIndex
        }) { "navigation speed-limit profile must be ordered and non-overlapping" }
        require(speedLimits.all { it.endShapeIndex <= geometry.lastIndex }) {
            "navigation speed-limit profile exceeds route geometry"
        }
    }

    fun asRoutePreview(): RoutePreview = RoutePreview(
        origin = origin,
        destination = destination,
        distanceMeters = totalDistanceMeters,
        durationSeconds = drivingDurationSeconds,
        geometry = geometry,
        maneuvers = maneuvers,
        provider = provider,
        navigation = timing,
        speedLimits = speedLimits,
        speedLimitSource = speedLimitSource,
    )
}

data class NavigationRouteUpdateNotice(
    val routeId: String,
    val previousDurationSeconds: Double,
    val updatedDurationSeconds: Double,
    val createdAtEpochMillis: Long,
) {
    init {
        require(routeId.isNotBlank())
        require(previousDurationSeconds.isFinite() && previousDurationSeconds >= 0.0)
        require(updatedDurationSeconds.isFinite() && updatedDurationSeconds >= 0.0)
        require(createdAtEpochMillis >= 0L)
    }

    val durationDeltaSeconds: Double
        get() = updatedDurationSeconds - previousDurationSeconds
}

data class NavigationState(
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val route: NavigationRoute? = null,
    val rawLocation: NavigationLocation? = null,
    val navigationPosition: NavigationPosition? = null,
    val currentRoadName: String? = null,
    val distanceRemainingMeters: Double? = null,
    val drivingDurationRemainingSeconds: Double? = null,
    val totalDurationRemainingSeconds: Double? = null,
    val estimatedArrivalAt: Instant? = null,
    val currentManeuver: Maneuver? = null,
    val nextManeuver: Maneuver? = null,
    val distanceToNextManeuverMeters: Double? = null,
    val routeProgressFraction: Double = 0.0,
    val nextFuelStop: NavigationFuelStopProgress? = null,
    val offRouteStatus: OffRouteStatus = OffRouteStatus.ON_ROUTE,
    val gpsStatus: GpsStatus = GpsStatus.UNAVAILABLE,
    val reroutingStatus: ReroutingStatus = ReroutingStatus.IDLE,
    val routeUpdateReason: RouteUpdateReason? = null,
    val routeUpdateFailure: RouteUpdateFailure? = null,
    val lastSuccessfulRouteRefreshEpochMillis: Long? = null,
    val routeUpdateNotice: NavigationRouteUpdateNotice? = null,
    val lastSpokenInstruction: String? = null,
    val rejectedLocationCount: Int = 0,
    val routeSource: NavigationRouteSource = NavigationRouteSource.LIVE,
    val routeCachedAtEpochMillis: Long? = null,
    val connectivity: NavigationConnectivity = NavigationConnectivity.ONLINE,
) {
    val snappedLocation: Coordinate?
        get() = navigationPosition?.coordinate

    val currentRouteSegmentIndex: Int?
        get() = navigationPosition?.routeSegmentIndex

    val currentSpeedMetersPerSecond: Double
        get() = navigationPosition?.speedMetersPerSecond ?: 0.0

    val currentSpeedLimitKph: Int?
        get() {
            if (offRouteStatus != OffRouteStatus.ON_ROUTE) return null
            val segmentIndex = currentRouteSegmentIndex ?: return null
            return route?.speedLimits
                ?.firstOrNull { segmentIndex in it.beginShapeIndex until it.endShapeIndex }
                ?.speedLimitKph
        }

    val vehicleBearingDegrees: Double?
        get() = navigationPosition?.bearingDegrees
}

enum class NavigationRouteSource {
    LIVE,
    CACHE,
}

enum class NavigationConnectivity {
    ONLINE,
    REROUTING_UNAVAILABLE,
}

enum class ReroutingStatus {
    IDLE,
    IN_PROGRESS,
    FAILED,
}

enum class RouteUpdateReason {
    OFF_ROUTE,
    TRAFFIC_REFRESH,
    MANUAL_DEBUG,
    FUEL_STOP_UNAVAILABLE,
}

enum class RouteUpdateFailure {
    NETWORK_OR_SERVER,
    NO_SAFE_FUEL_ALTERNATIVE,
    FUEL_RANGE_PLAN_REQUIRED,
}

enum class OffRouteStatus {
    ON_ROUTE,
    SUSPECTED,
    OFF_ROUTE,
}

enum class GpsStatus {
    UNAVAILABLE,
    ACQUIRING,
    ACTIVE,
    LOST,
}

data class NavigationLocation(
    val coordinate: Coordinate,
    val accuracyMeters: Double,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val timestampEpochMillis: Long,
    val receivedAtEpochMillis: Long? = null,
)

/** The single authoritative map-matched vehicle position exposed to navigation consumers. */
data class NavigationPosition(
    val coordinate: Coordinate,
    val routeSegmentIndex: Int,
    val speedMetersPerSecond: Double,
    val bearingDegrees: Double,
    val horizontalAccuracyMeters: Double,
    val timestampEpochMillis: Long,
)

data class NavigationFuelStopProgress(
    val stop: NavigationFuelStop,
    val distanceRemainingMeters: Double,
)

fun RoutePreview.toNavigationRoute(
    gasolineFallback: GasolineFallback? = null,
): NavigationRoute = buildNavigationRoute(
    route = this,
    sourceLegs = listOf(this),
    stops = emptyList(),
    rangeLegs = emptyList(),
    gasolineFallback = gasolineFallback,
)

fun RouteWithCngStop.toNavigationRoute(): NavigationRoute = buildNavigationRoute(
    route = asRoutePreview(),
    sourceLegs = legs.map { it.route },
    stops = listOf(selectedStop),
    rangeLegs = emptyList(),
)

fun RouteWithCngItinerary.toNavigationRoute(
    maximumDetourMinutes: Double? = null,
    excludedMimitStationIds: Set<String> = emptySet(),
): NavigationRoute = buildNavigationRoute(
    route = asRoutePreview(),
    sourceLegs = legs.map { it.route },
    stops = selectedStops,
    rangeLegs = legs.map {
        NavigationRangeLeg(
            availableRangeAtDepartureKm = it.availableRangeAtDepartureKm,
            estimatedRemainingRangeAtArrivalKm = it.estimatedRemainingRangeAtArrivalKm,
            reserveMarginAtArrivalKm = it.reserveMarginAtArrivalKm,
        )
    },
    maximumDetourMinutes = maximumDetourMinutes,
    excludedMimitStationIds = excludedMimitStationIds,
)

private fun buildNavigationRoute(
    route: RoutePreview,
    sourceLegs: List<RoutePreview>,
    stops: List<SelectedCngStop>,
    rangeLegs: List<NavigationRangeLeg>,
    maximumDetourMinutes: Double? = null,
    excludedMimitStationIds: Set<String> = emptySet(),
    gasolineFallback: GasolineFallback? = null,
): NavigationRoute {
    require(sourceLegs.isNotEmpty()) { "navigation route needs at least one leg" }
    val navigationLegs = mutableListOf<NavigationLeg>()
    val joinedGeometry = mutableListOf<Coordinate>()
    var shapeOffset = 0
    sourceLegs.forEachIndexed { index, leg ->
        require(leg.geometry.size >= 2) { "navigation route leg needs route geometry" }
        val adjustedManeuvers = leg.maneuvers.map { maneuver ->
            maneuver.copy(
                beginShapeIndex = maneuver.beginShapeIndex + shapeOffset,
                endShapeIndex = maneuver.endShapeIndex + shapeOffset,
            )
        }
        val adjustedSpeedLimits = leg.speedLimits.map { speedLimit ->
            speedLimit.copy(
                beginShapeIndex = speedLimit.beginShapeIndex + shapeOffset,
                endShapeIndex = speedLimit.endShapeIndex + shapeOffset,
            )
        }
        val range = rangeLegs.getOrNull(index)
        navigationLegs += NavigationLeg(
            sequence = index + 1,
            origin = leg.origin,
            destination = leg.destination,
            distanceMeters = leg.distanceMeters,
            durationSeconds = leg.durationSeconds,
            geometry = leg.geometry,
            maneuvers = adjustedManeuvers,
            shapeIndexOffset = shapeOffset,
            availableRangeAtDepartureKm = range?.availableRangeAtDepartureKm,
            estimatedRemainingRangeAtArrivalKm = range?.estimatedRemainingRangeAtArrivalKm,
            reserveMarginAtArrivalKm = range?.reserveMarginAtArrivalKm,
            speedLimits = adjustedSpeedLimits,
            speedLimitSource = leg.speedLimitSource,
        )
        if (index == 0) joinedGeometry += leg.geometry else joinedGeometry += leg.geometry.drop(1)
        shapeOffset += leg.geometry.size - 1
    }
    return NavigationRoute(
        routeId = route.navigation.routeId,
        origin = route.origin,
        destination = route.destination,
        totalDistanceMeters = route.distanceMeters,
        drivingDurationSeconds = route.navigation.drivingDurationSeconds,
        totalTripDurationSeconds = route.navigation.totalTripDurationSeconds,
        geometry = joinedGeometry,
        legs = navigationLegs,
        maneuvers = navigationLegs.flatMap(NavigationLeg::maneuvers),
        fuelStops = stops.mapIndexed { index, stop -> stop.toNavigationFuelStop(index + 1) },
        fuelPlan = rangeLegs.firstOrNull()?.let { first ->
            NavigationFuelPlan(
                effectiveCngRangeKm = rangeLegs.drop(1)
                    .maxOfOrNull(NavigationRangeLeg::availableRangeAtDepartureKm)
                    ?: first.availableRangeAtDepartureKm,
                initialRemainingCngRangeKm = first.availableRangeAtDepartureKm,
                reserveCngRangeKm = first.estimatedRemainingRangeAtArrivalKm -
                    first.reserveMarginAtArrivalKm,
                maximumDetourMinutes = maximumDetourMinutes,
                excludedMimitStationIds = excludedMimitStationIds,
            )
        },
        timing = route.navigation,
        provider = route.provider,
        gasolineFallback = gasolineFallback,
        speedLimits = navigationLegs.flatMap(NavigationLeg::speedLimits),
        speedLimitSource = sourceLegs.mapNotNull(RoutePreview::speedLimitSource)
            .firstOrNull(),
    )
}

private fun SelectedCngStop.toNavigationFuelStop(sequence: Int): NavigationFuelStop =
    NavigationFuelStop(
        sequence = sequence,
        mimitStationId = mimitStationId,
        name = name,
        municipality = municipality,
        province = province,
        location = location,
        expectedArrivalAt = expectedArrivalAt,
        dwellTimeSeconds = dwellTimeSeconds,
    )

private data class NavigationRangeLeg(
    val availableRangeAtDepartureKm: Double,
    val estimatedRemainingRangeAtArrivalKm: Double,
    val reserveMarginAtArrivalKm: Double,
)
