package org.compass.cng.navigation

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.compass.cng.domain.model.Maneuver

data class NavigationEnginePolicy(
    val offRouteMinimumDistanceMeters: Double = 20.0,
    val offRouteAccuracyMultiplier: Double = 1.5,
    val offRouteConsecutiveFixes: Int = 3,
    val offRouteMinimumDurationMillis: Long = 2_000L,
    val offRouteRecoveryConsecutiveFixes: Int = 2,
    val offRouteStationarySpeedMetersPerSecond: Double = 1.2,
    val offRouteStationaryDistanceMultiplier: Double = 3.0,
    val offRouteHeadingMismatchDegrees: Double = 65.0,
    val offRouteHeadingMinimumSpeedMetersPerSecond: Double = 4.0,
    val offRouteHeadingMinimumDistanceMeters: Double = 10.0,
    val offRouteHeadingAccuracyMultiplier: Double = 1.0,
    val offRouteBackwardsProgressMeters: Double = 60.0,
    val gpsLostAfterMillis: Long = 15_000,
    val arrivalDistanceMeters: Double = 20.0,
    val approachingFuelStopDistanceMeters: Double = 500.0,
    val atFuelStopDistanceMeters: Double = 30.0,
    val minimumManeuverApproachMeters: Double = 80.0,
    val maneuverApproachSeconds: Double = 8.0,
) {
    init {
        require(offRouteMinimumDistanceMeters > 0.0)
        require(offRouteAccuracyMultiplier > 0.0)
        require(offRouteConsecutiveFixes > 0)
        require(offRouteMinimumDurationMillis >= 0L)
        require(offRouteRecoveryConsecutiveFixes > 0)
        require(offRouteStationarySpeedMetersPerSecond >= 0.0)
        require(offRouteStationaryDistanceMultiplier > 1.0)
        require(offRouteHeadingMismatchDegrees in 0.0..180.0)
        require(offRouteHeadingMinimumSpeedMetersPerSecond >= 0.0)
        require(offRouteHeadingMinimumDistanceMeters >= 0.0)
        require(offRouteHeadingAccuracyMultiplier > 0.0)
        require(offRouteBackwardsProgressMeters >= 0.0)
    }
}

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
    private var consecutiveRecoveryFixes = 0
    private var offRouteEpisodeStartedAtMillis: Long? = null
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
        consecutiveRecoveryFixes = 0
        offRouteEpisodeStartedAtMillis = null
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
        val headingConflictDistanceThreshold = maxOf(
            policy.offRouteHeadingMinimumDistanceMeters,
            filtered.accuracyMeters * policy.offRouteHeadingAccuracyMultiplier,
        )
        val backwardsConflict = match.progressDeltaMeters < -policy.offRouteBackwardsProgressMeters
        val explicitlyStationary = filtered.speedMetersPerSecond
            ?.let { it <= policy.offRouteStationarySpeedMetersPerSecond } == true
        val lateralConflict = match.distanceFromRouteMeters > offRouteThreshold &&
            (!explicitlyStationary ||
                match.distanceFromRouteMeters >
                offRouteThreshold * policy.offRouteStationaryDistanceMultiplier)
        val poorFix = lateralConflict ||
            (headingConflict && match.distanceFromRouteMeters > headingConflictDistanceThreshold) ||
            backwardsConflict
        if (poorFix) {
            if (consecutiveOffRouteFixes == 0) {
                offRouteEpisodeStartedAtMillis = filtered.timestampEpochMillis
            }
            consecutiveOffRouteFixes += 1
            consecutiveRecoveryFixes = 0
        } else {
            consecutiveOffRouteFixes = 0
            offRouteEpisodeStartedAtMillis = null
            if (previousState.offRouteStatus == OffRouteStatus.OFF_ROUTE) {
                consecutiveRecoveryFixes += 1
            } else {
                consecutiveRecoveryFixes = 0
            }
        }
        val offRouteDurationMillis = offRouteEpisodeStartedAtMillis?.let { startedAt ->
            (filtered.timestampEpochMillis - startedAt).coerceAtLeast(0L)
        } ?: 0L
        val offRouteStatus = when {
            previousState.offRouteStatus == OffRouteStatus.OFF_ROUTE && poorFix ->
                OffRouteStatus.OFF_ROUTE
            previousState.offRouteStatus == OffRouteStatus.OFF_ROUTE &&
                !poorFix &&
                consecutiveRecoveryFixes < policy.offRouteRecoveryConsecutiveFixes ->
                OffRouteStatus.OFF_ROUTE
            consecutiveOffRouteFixes >= policy.offRouteConsecutiveFixes &&
                offRouteDurationMillis >= policy.offRouteMinimumDurationMillis ->
                OffRouteStatus.OFF_ROUTE
            consecutiveOffRouteFixes > 0 -> OffRouteStatus.SUSPECTED
            else -> OffRouteStatus.ON_ROUTE
        }
        val routeMatchConfidence = routeMatchConfidence(
            distanceFromRouteMeters = match.distanceFromRouteMeters,
            distanceThresholdMeters = offRouteThreshold,
            headingDifferenceDegrees = match.headingDifferenceDegrees,
            headingRelevant = filtered.speedMetersPerSecond
                ?.let { it >= policy.offRouteHeadingMinimumSpeedMetersPerSecond } == true,
            backwardsConflict = backwardsConflict,
        )

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
        val maneuverIndex = upcomingManeuverIndex(route, match.segmentIndex)
        val currentManeuver = route.maneuvers.getOrNull(maneuverIndex)
        val nextManeuver = route.maneuvers.getOrNull(maneuverIndex + 1)
        val distanceToManeuver = currentManeuver?.let {
            maxOf(
                0.0,
                routeMatcher.distanceAtShapeIndex(it.beginShapeIndex) -
                    match.distanceAlongGeometryMeters,
            )
        }
        val speed = filtered.speedMetersPerSecond ?: 0.0
        val navigationBearing = headingController.update(match.segmentBearingDegrees, speed)
        val progressIsReliable = offRouteStatus == OffRouteStatus.ON_ROUTE
        val computedPhase = if (progressIsReliable) navigationPhase(
            distanceRemainingMeters = distanceRemaining,
            distanceToManeuverMeters = distanceToManeuver,
            speedMetersPerSecond = speed,
            nextFuelStop = nextFuel,
        ) else previousState.phase.takeUnless {
            it == NavigationPhase.GPS_LOST || it == NavigationPhase.REROUTING
        } ?: phaseBehindRouteUpdate
        if (previousState.reroutingStatus != ReroutingStatus.IN_PROGRESS) {
            phaseBehindRouteUpdate = computedPhase
        }
        mutableState.value = previousState.copy(
            phase = if (previousState.reroutingStatus == ReroutingStatus.IN_PROGRESS) {
                NavigationPhase.REROUTING
            } else {
                computedPhase
            },
            rawLocation = rawLocation,
            navigationPosition = if (progressIsReliable) {
                NavigationPosition(
                    coordinate = match.snappedCoordinate,
                    routeSegmentIndex = match.segmentIndex,
                    speedMetersPerSecond = speed,
                    bearingDegrees = navigationBearing,
                    horizontalAccuracyMeters = filtered.accuracyMeters,
                    timestampEpochMillis = filtered.timestampEpochMillis,
                )
            } else {
                previousState.navigationPosition
            },
            currentRoadName = if (progressIsReliable) {
                currentManeuver?.streetNames?.firstOrNull()
            } else {
                previousState.currentRoadName
            },
            distanceRemainingMeters = if (progressIsReliable) {
                distanceRemaining
            } else {
                previousState.distanceRemainingMeters
            },
            drivingDurationRemainingSeconds = if (progressIsReliable) {
                drivingRemaining
            } else {
                previousState.drivingDurationRemainingSeconds
            },
            totalDurationRemainingSeconds = if (progressIsReliable) {
                drivingRemaining + remainingDwell
            } else {
                previousState.totalDurationRemainingSeconds
            },
            estimatedArrivalAt = if (progressIsReliable) {
                now.plusMillis(((drivingRemaining + remainingDwell) * 1_000).toLong())
            } else {
                previousState.estimatedArrivalAt
            },
            currentManeuver = if (progressIsReliable) currentManeuver else previousState.currentManeuver,
            nextManeuver = if (progressIsReliable) nextManeuver else previousState.nextManeuver,
            distanceToNextManeuverMeters = if (progressIsReliable) {
                distanceToManeuver
            } else {
                previousState.distanceToNextManeuverMeters
            },
            routeProgressFraction = if (progressIsReliable) {
                progressFraction
            } else {
                previousState.routeProgressFraction
            },
            nextFuelStop = if (progressIsReliable) nextFuel else previousState.nextFuelStop,
            offRouteStatus = offRouteStatus,
            distanceFromRouteMeters = match.distanceFromRouteMeters,
            routeMatchConfidence = routeMatchConfidence,
            offRouteDurationMillis = offRouteDurationMillis,
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
        val previousState = mutableState.value
        val updateNotice = if (previousState.routeUpdateReason == RouteUpdateReason.OFF_ROUTE) {
            previousState.totalDurationRemainingSeconds?.let { previousDuration ->
                NavigationRouteUpdateNotice(
                    routeId = route.routeId,
                    previousDurationSeconds = previousDuration,
                    updatedDurationSeconds = route.totalTripDurationSeconds,
                    createdAtEpochMillis = refreshedAtEpochMillis,
                )
            }
        } else {
            null
        }
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
            routeUpdateNotice = updateNotice,
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

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(voiceGuidanceEnabled = enabled)
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
        consecutiveRecoveryFixes = 0
        offRouteEpisodeStartedAtMillis = null
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

    private fun routeMatchConfidence(
        distanceFromRouteMeters: Double,
        distanceThresholdMeters: Double,
        headingDifferenceDegrees: Double?,
        headingRelevant: Boolean,
        backwardsConflict: Boolean,
    ): Double {
        val lateralConfidence = (
            1.0 - distanceFromRouteMeters / (distanceThresholdMeters * 2.0)
        ).coerceIn(0.0, 1.0)
        val headingConfidence = if (headingRelevant && headingDifferenceDegrees != null) {
            (1.0 - headingDifferenceDegrees / 180.0).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        return minOf(
            lateralConfidence,
            headingConfidence,
            if (backwardsConflict) 0.0 else 1.0,
        )
    }

    private fun upcomingManeuverIndex(route: NavigationRoute, segmentIndex: Int): Int {
        // A Valhalla instruction describes the transition at begin_shape_index. Once matching has
        // entered that outgoing segment, the transition is complete and guidance must advance to
        // the next begin index. end_shape_index describes the travelled span after the transition;
        // using it here pairs the next transition's distance with the previous instruction.
        val index = route.maneuvers.indexOfFirst { it.beginShapeIndex > segmentIndex }
        return if (index >= 0) index else maxOf(0, route.maneuvers.lastIndex)
    }
}
