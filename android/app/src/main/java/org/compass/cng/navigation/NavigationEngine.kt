package org.compass.cng.navigation

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.compass.cng.domain.model.Maneuver

data class NavigationEnginePolicy(
    val offRouteMinimumDistanceMeters: Double = 35.0,
    val offRouteAccuracyMultiplier: Double = 1.5,
    val offRouteConsecutiveFixes: Int = 3,
    val offRouteHeadingMismatchDegrees: Double = 110.0,
    val offRouteHeadingMinimumSpeedMetersPerSecond: Double = 4.0,
    val offRouteBackwardsProgressMeters: Double = 60.0,
    val gpsLostAfterMillis: Long = 15_000,
    val arrivalDistanceMeters: Double = 20.0,
    val approachingFuelStopDistanceMeters: Double = 500.0,
    val atFuelStopDistanceMeters: Double = 30.0,
    val minimumManeuverApproachMeters: Double = 80.0,
    val maneuverApproachSeconds: Double = 8.0,
)

/** Pure client-side navigation state machine; it performs no Android or network work. */
class NavigationEngine(
    private val policy: NavigationEnginePolicy = NavigationEnginePolicy(),
    private val locationFilter: LocationFilter = LocationFilter(),
    private val headingController: NavigationHeadingController = NavigationHeadingController(),
) {
    private val mutableState = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = mutableState.asStateFlow()

    private var matcher: RouteMatcher? = null
    private var consecutiveOffRouteFixes = 0
    private var lastAcceptedFixAtMillis: Long? = null
    private var trackingStartedAtMillis: Long? = null
    private var fuelStopDistances = emptyList<Pair<NavigationFuelStop, Double>>()
    private var phaseBehindRouteUpdate = NavigationPhase.NAVIGATING

    fun preview(
        route: NavigationRoute,
        source: NavigationRouteSource = NavigationRouteSource.LIVE,
        cachedAtEpochMillis: Long? = null,
    ) {
        resetTracking(route)
        mutableState.value = NavigationState(
            phase = NavigationPhase.ROUTE_PREVIEW,
            route = route,
            distanceRemainingMeters = route.totalDistanceMeters,
            drivingDurationRemainingSeconds = route.drivingDurationSeconds,
            totalDurationRemainingSeconds = route.totalTripDurationSeconds,
            currentManeuver = route.maneuvers.firstOrNull(),
            nextManeuver = route.maneuvers.getOrNull(1),
            routeSource = source,
            routeCachedAtEpochMillis = cachedAtEpochMillis,
        )
    }

    fun start(nowEpochMillis: Long = System.currentTimeMillis()) {
        val route = mutableState.value.route ?: return
        if (matcher == null) resetTracking(route)
        mutableState.value = mutableState.value.copy(
            phase = NavigationPhase.NAVIGATING,
            gpsStatus = GpsStatus.ACQUIRING,
            offRouteStatus = OffRouteStatus.ON_ROUTE,
        )
        trackingStartedAtMillis = nowEpochMillis
    }

    fun stopToPreview() {
        val route = mutableState.value.route ?: return clear()
        preview(
            route = route,
            source = mutableState.value.routeSource,
            cachedAtEpochMillis = mutableState.value.routeCachedAtEpochMillis,
        )
    }

    fun clear() {
        locationFilter.reset()
        matcher = null
        consecutiveOffRouteFixes = 0
        lastAcceptedFixAtMillis = null
        trackingStartedAtMillis = null
        fuelStopDistances = emptyList()
        phaseBehindRouteUpdate = NavigationPhase.NAVIGATING
        mutableState.value = NavigationState()
    }

    fun updateLocation(rawLocation: NavigationLocation, now: Instant = Instant.now()) {
        val previousState = mutableState.value
        val route = previousState.route ?: return
        if (previousState.phase == NavigationPhase.IDLE ||
            previousState.phase == NavigationPhase.ROUTE_PREVIEW
        ) {
            return
        }
        val filtered = locationFilter.filter(rawLocation)
        if (filtered == null) {
            mutableState.value = previousState.copy(
                rawLocation = rawLocation,
                rejectedLocationCount = previousState.rejectedLocationCount + 1,
            )
            return
        }
        val routeMatcher = requireNotNull(matcher)
        val match = routeMatcher.match(filtered)
        lastAcceptedFixAtMillis = filtered.timestampEpochMillis
        val offRouteThreshold = maxOf(
            policy.offRouteMinimumDistanceMeters,
            filtered.accuracyMeters * policy.offRouteAccuracyMultiplier,
        )
        val headingConflict = filtered.speedMetersPerSecond
            ?.let { it >= policy.offRouteHeadingMinimumSpeedMetersPerSecond } == true &&
            (match.headingDifferenceDegrees ?: 0.0) >= policy.offRouteHeadingMismatchDegrees
        val backwardsConflict = match.progressDeltaMeters < -policy.offRouteBackwardsProgressMeters
        val poorFix = match.distanceFromRouteMeters > offRouteThreshold ||
            (headingConflict && match.distanceFromRouteMeters > offRouteThreshold * 0.6) ||
            backwardsConflict
        if (poorFix) {
            consecutiveOffRouteFixes += 1
        } else {
            consecutiveOffRouteFixes = 0
        }
        val offRouteStatus = when {
            consecutiveOffRouteFixes >= policy.offRouteConsecutiveFixes -> OffRouteStatus.OFF_ROUTE
            consecutiveOffRouteFixes > 0 -> OffRouteStatus.SUSPECTED
            else -> OffRouteStatus.ON_ROUTE
        }

        val progressFraction = if (match.geometryLengthMeters == 0.0) {
            0.0
        } else {
            (match.distanceAlongGeometryMeters / match.geometryLengthMeters).coerceIn(0.0, 1.0)
        }
        val distanceRemaining = route.totalDistanceMeters * (1.0 - progressFraction)
        val drivingRemaining = route.drivingDurationSeconds * (1.0 - progressFraction)
        val nextFuel = fuelStopDistances
            .firstOrNull { (_, routeDistance) -> routeDistance + policy.atFuelStopDistanceMeters >= match.distanceAlongGeometryMeters }
            ?.let { (stop, routeDistance) ->
                NavigationFuelStopProgress(
                    stop = stop,
                    distanceRemainingMeters = maxOf(0.0, routeDistance - match.distanceAlongGeometryMeters),
                )
            }
        val remainingDwell = fuelStopDistances.count { (_, routeDistance) ->
            routeDistance + policy.atFuelStopDistanceMeters >= match.distanceAlongGeometryMeters
        } * route.timing.dwellSecondsPerRefuelingStop.toDouble()
        val maneuverIndex = activeManeuverIndex(route, match.segmentIndex)
        val currentManeuver = route.maneuvers.getOrNull(maneuverIndex)
        val nextManeuver = route.maneuvers.getOrNull(maneuverIndex + 1)
        val distanceToManeuver = currentManeuver?.let {
            maxOf(0.0, routeMatcher.distanceAtShapeIndex(it.endShapeIndex) - match.distanceAlongGeometryMeters)
        }
        val speed = filtered.speedMetersPerSecond ?: 0.0
        val navigationBearing = headingController.update(match.segmentBearingDegrees, speed)
        val computedPhase = navigationPhase(
            distanceRemainingMeters = distanceRemaining,
            distanceToManeuverMeters = distanceToManeuver,
            speedMetersPerSecond = speed,
            nextFuelStop = nextFuel,
        )
        phaseBehindRouteUpdate = computedPhase
        mutableState.value = previousState.copy(
            phase = if (previousState.reroutingStatus == ReroutingStatus.IN_PROGRESS) {
                NavigationPhase.REROUTING
            } else {
                computedPhase
            },
            rawLocation = rawLocation,
            navigationPosition = NavigationPosition(
                coordinate = match.snappedCoordinate,
                routeSegmentIndex = match.segmentIndex,
                speedMetersPerSecond = speed,
                bearingDegrees = navigationBearing,
                horizontalAccuracyMeters = filtered.accuracyMeters,
                timestampEpochMillis = filtered.timestampEpochMillis,
            ),
            currentRoadName = currentManeuver?.streetNames?.firstOrNull(),
            distanceRemainingMeters = distanceRemaining,
            drivingDurationRemainingSeconds = drivingRemaining,
            totalDurationRemainingSeconds = drivingRemaining + remainingDwell,
            estimatedArrivalAt = now.plusMillis(((drivingRemaining + remainingDwell) * 1_000).toLong()),
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            distanceToNextManeuverMeters = distanceToManeuver,
            routeProgressFraction = progressFraction,
            nextFuelStop = nextFuel,
            offRouteStatus = offRouteStatus,
            gpsStatus = GpsStatus.ACTIVE,
        )
    }

    fun beginRouteUpdate(reason: RouteUpdateReason) {
        val current = mutableState.value
        if (current.route == null || current.phase == NavigationPhase.IDLE ||
            current.phase == NavigationPhase.ROUTE_PREVIEW
        ) {
            return
        }
        if (current.phase != NavigationPhase.REROUTING) phaseBehindRouteUpdate = current.phase
        mutableState.value = current.copy(
            phase = NavigationPhase.REROUTING,
            reroutingStatus = ReroutingStatus.IN_PROGRESS,
            routeUpdateReason = reason,
            routeUpdateFailure = null,
        )
    }

    fun replaceRoute(
        route: NavigationRoute,
        refreshedAtEpochMillis: Long,
        currentLocation: NavigationLocation?,
    ) {
        resetTracking(route)
        trackingStartedAtMillis = refreshedAtEpochMillis
        mutableState.value = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            distanceRemainingMeters = route.totalDistanceMeters,
            drivingDurationRemainingSeconds = route.drivingDurationSeconds,
            totalDurationRemainingSeconds = route.totalTripDurationSeconds,
            currentManeuver = route.maneuvers.firstOrNull(),
            nextManeuver = route.maneuvers.getOrNull(1),
            gpsStatus = GpsStatus.ACQUIRING,
            lastSuccessfulRouteRefreshEpochMillis = refreshedAtEpochMillis,
            routeSource = NavigationRouteSource.LIVE,
            connectivity = NavigationConnectivity.ONLINE,
        )
        phaseBehindRouteUpdate = NavigationPhase.NAVIGATING
        currentLocation?.let {
            updateLocation(it, Instant.ofEpochMilli(refreshedAtEpochMillis))
        }
    }

    fun failRouteUpdate(
        failure: RouteUpdateFailure = RouteUpdateFailure.NETWORK_OR_SERVER,
    ) {
        val current = mutableState.value
        if (current.reroutingStatus != ReroutingStatus.IN_PROGRESS) return
        mutableState.value = current.copy(
            phase = phaseBehindRouteUpdate,
            reroutingStatus = ReroutingStatus.FAILED,
            routeUpdateFailure = failure,
            connectivity = if (failure == RouteUpdateFailure.NETWORK_OR_SERVER) {
                NavigationConnectivity.REROUTING_UNAVAILABLE
            } else {
                current.connectivity
            },
        )
    }

    fun recordSpokenInstruction(instruction: String) {
        mutableState.value = mutableState.value.copy(lastSpokenInstruction = instruction)
    }

    fun tick(nowEpochMillis: Long) {
        val state = mutableState.value
        if (state.phase == NavigationPhase.IDLE || state.phase == NavigationPhase.ROUTE_PREVIEW) return
        val freshnessReference = lastAcceptedFixAtMillis ?: trackingStartedAtMillis ?: return
        if (nowEpochMillis - freshnessReference > policy.gpsLostAfterMillis) {
            mutableState.value = state.copy(
                phase = NavigationPhase.GPS_LOST,
                gpsStatus = GpsStatus.LOST,
            )
        }
    }

    private fun resetTracking(route: NavigationRoute) {
        locationFilter.reset()
        headingController.reset()
        matcher = RouteMatcher(route.geometry)
        consecutiveOffRouteFixes = 0
        lastAcceptedFixAtMillis = null
        trackingStartedAtMillis = null
        phaseBehindRouteUpdate = NavigationPhase.NAVIGATING
        val routeMatcher = requireNotNull(matcher)
        fuelStopDistances = route.fuelStops.map { it to routeMatcher.distanceAlongRoute(it.location) }
            .sortedBy { it.second }
    }

    private fun navigationPhase(
        distanceRemainingMeters: Double,
        distanceToManeuverMeters: Double?,
        speedMetersPerSecond: Double,
        nextFuelStop: NavigationFuelStopProgress?,
    ): NavigationPhase {
        if (distanceRemainingMeters <= policy.arrivalDistanceMeters) return NavigationPhase.ARRIVED
        val fuelDistance = nextFuelStop?.distanceRemainingMeters
        if (fuelDistance != null && fuelDistance <= policy.atFuelStopDistanceMeters) {
            return NavigationPhase.AT_FUEL_STOP
        }
        if (fuelDistance != null && fuelDistance <= policy.approachingFuelStopDistanceMeters) {
            return NavigationPhase.APPROACHING_FUEL_STOP
        }
        val maneuverThreshold = maxOf(
            policy.minimumManeuverApproachMeters,
            speedMetersPerSecond * policy.maneuverApproachSeconds,
        )
        if (distanceToManeuverMeters != null && distanceToManeuverMeters <= maneuverThreshold) {
            return NavigationPhase.APPROACHING_MANEUVER
        }
        return NavigationPhase.NAVIGATING
    }

    private fun activeManeuverIndex(route: NavigationRoute, segmentIndex: Int): Int {
        val index = route.maneuvers.indexOfFirst { it.endShapeIndex > segmentIndex }
        return if (index >= 0) index else maxOf(0, route.maneuvers.lastIndex)
    }
}
