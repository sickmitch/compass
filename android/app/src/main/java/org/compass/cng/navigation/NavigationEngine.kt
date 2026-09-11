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
    val approachingIntermediateStopDistanceMeters: Double = 500.0,
    val atIntermediateStopDistanceMeters: Double = 30.0,
    val intermediateStopDepartureDistanceMeters: Double = 60.0,
    val intermediateStopDepartureConsecutiveFixes: Int = 2,
    val intermediateStopDepartureMinimumSpeedMetersPerSecond: Double = 1.0,
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
        require(approachingIntermediateStopDistanceMeters > atIntermediateStopDistanceMeters)
        require(atIntermediateStopDistanceMeters > 0.0)
        require(intermediateStopDepartureDistanceMeters > atIntermediateStopDistanceMeters)
        require(intermediateStopDepartureConsecutiveFixes > 0)
        require(intermediateStopDepartureMinimumSpeedMetersPerSecond >= 0.0)
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
    private var intermediateStopDistances =
        emptyList<Pair<NavigationIntermediateStop, Double>>()
    private val completedFuelStopSequences = mutableSetOf<Int>()
    private val completedIntermediateStopSequences = mutableSetOf<Int>()
    private var consecutiveIntermediateDepartureFixes = 0
    private var lastReliableDistanceAlongGeometryMeters: Double? = null
    private var phaseBehindRouteUpdate = NavigationPhase.NAVIGATING

    fun preview(
        route: NavigationRoute,
        source: NavigationRouteSource = NavigationRouteSource.LIVE,
        cachedAtEpochMillis: Long? = null,
    ) {
        resetTracking(route)
        val plannedFuelStops = plannedFuelStopProgress()
        val plannedIntermediateStops = plannedIntermediateStopProgress()
        mutableState.value = NavigationState(
            phase = NavigationPhase.ROUTE_PREVIEW,
            route = route,
            distanceRemainingMeters = route.totalDistanceMeters,
            drivingDurationRemainingSeconds = route.drivingDurationSeconds,
            totalDurationRemainingSeconds = route.totalTripDurationSeconds,
            currentManeuver = route.maneuvers.firstOrNull(),
            nextManeuver = route.maneuvers.getOrNull(1),
            nextFuelStop = plannedFuelStops.firstOrNull(),
            fuelStopProgress = plannedFuelStops,
            nextIntermediateStop = plannedIntermediateStops.firstOrNull(),
            intermediateStopProgress = plannedIntermediateStops,
            routeSource = source,
            routeCachedAtEpochMillis = cachedAtEpochMillis,
        )
    }

    /** Restores only locally derivable guidance state; live freshness is never inferred. */
    fun restore(
        route: NavigationRoute,
        progress: NavigationProgressSnapshot,
        cachedAtEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        resetTracking(route)
        completedFuelStopSequences += progress.completedFuelStopSequences
        completedIntermediateStopSequences += progress.completedIntermediateStopSequences
        val distanceAlong = progress.navigationPosition?.let { position ->
            requireNotNull(matcher).match(
                NavigationLocation(
                    coordinate = position.coordinate,
                    accuracyMeters = position.horizontalAccuracyMeters,
                    speedMetersPerSecond = position.speedMetersPerSecond,
                    bearingDegrees = position.bearingDegrees,
                    timestampEpochMillis = position.timestampEpochMillis,
                ),
            ).distanceAlongGeometryMeters
        }
        lastReliableDistanceAlongGeometryMeters = distanceAlong
        val activeVisit = progress.activeFuelStopVisit
        val fuelProgress = fuelStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = distanceAlong
                ?: route.totalDistanceMeters * progress.routeProgressFraction,
            geometryLengthMeters = requireNotNull(matcher).geometryLengthMeters,
            now = Instant.ofEpochMilli(nowEpochMillis),
            activeVisit = activeVisit,
        )
        val intermediateProgress = intermediateStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = distanceAlong
                ?: route.totalDistanceMeters * progress.routeProgressFraction,
            geometryLengthMeters = requireNotNull(matcher).geometryLengthMeters,
            now = Instant.ofEpochMilli(nowEpochMillis),
            activeVisit = progress.activeIntermediateStopVisit,
        )
        val remainingTotal = progress.totalDurationRemainingSeconds
        val restored = NavigationState(
            phase = if (activeVisit != null) {
                NavigationPhase.AT_FUEL_STOP
            } else if (progress.activeIntermediateStopVisit != null) {
                NavigationPhase.AT_INTERMEDIATE_STOP
            } else {
                NavigationPhase.GPS_LOST
            },
            route = route,
            navigationPosition = progress.navigationPosition,
            currentRoadName = progress.currentRoadName,
            distanceRemainingMeters = progress.distanceRemainingMeters,
            drivingDurationRemainingSeconds = progress.drivingDurationRemainingSeconds,
            totalDurationRemainingSeconds = remainingTotal,
            estimatedArrivalAt = remainingTotal?.let {
                Instant.ofEpochMilli(nowEpochMillis).plusMillis((it * 1_000).toLong())
            },
            currentManeuver = progress.currentManeuverIndex?.let(route.maneuvers::getOrNull),
            nextManeuver = progress.nextManeuverIndex?.let(route.maneuvers::getOrNull),
            distanceToNextManeuverMeters = progress.distanceToNextManeuverMeters,
            routeProgressFraction = progress.routeProgressFraction,
            nextFuelStop = fuelProgress.firstOrNull {
                it.lifecycle != NavigationFuelStopLifecycle.COMPLETED
            },
            fuelStopProgress = fuelProgress,
            nextIntermediateStop = intermediateProgress.firstOrNull {
                it.lifecycle != NavigationIntermediateStopLifecycle.COMPLETED
            },
            intermediateStopProgress = intermediateProgress,
            activeFuelStopVisit = activeVisit,
            activeIntermediateStopVisit = progress.activeIntermediateStopVisit,
            lastCompletedFuelStop = progress.lastCompletedFuelStopSequence?.let { sequence ->
                route.fuelStops.firstOrNull { it.sequence == sequence }
            },
            lastCompletedIntermediateStop =
                progress.lastCompletedIntermediateStopSequence?.let { sequence ->
                    route.intermediateStops.firstOrNull { it.sequence == sequence }
                },
            gpsStatus = GpsStatus.LOST,
            lastSuccessfulRouteRefreshEpochMillis =
                progress.lastSuccessfulRouteRefreshEpochMillis,
            lastSpokenInstruction = progress.lastSpokenInstruction,
            voiceGuidanceEnabled = progress.voiceGuidanceEnabled,
            routeSource = NavigationRouteSource.CACHE,
            routeCachedAtEpochMillis = cachedAtEpochMillis,
            connectivity = NavigationConnectivity.REROUTING_UNAVAILABLE,
            locationMode = progress.locationMode,
        )
        mutableState.value = activeVisit?.let {
            refuellingStateAt(restored, nowEpochMillis)
        } ?: restored
        phaseBehindRouteUpdate = if (activeVisit != null) {
            NavigationPhase.AT_FUEL_STOP
        } else if (progress.activeIntermediateStopVisit != null) {
            NavigationPhase.AT_INTERMEDIATE_STOP
        } else {
            NavigationPhase.NAVIGATING
        }
        trackingStartedAtMillis = nowEpochMillis
    }

    fun start(nowEpochMillis: Long = System.currentTimeMillis()) {
        val route = mutableState.value.route ?: return
        if (matcher == null) resetTracking(route)
        mutableState.value = mutableState.value.copy(
            phase = if (mutableState.value.activeFuelStopVisit != null) {
                NavigationPhase.AT_FUEL_STOP
            } else if (mutableState.value.activeIntermediateStopVisit != null) {
                NavigationPhase.AT_INTERMEDIATE_STOP
            } else {
                NavigationPhase.NAVIGATING
            },
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
        intermediateStopDistances = emptyList()
        completedFuelStopSequences.clear()
        completedIntermediateStopSequences.clear()
        consecutiveIntermediateDepartureFixes = 0
        lastReliableDistanceAlongGeometryMeters = null
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
        if (previousState.activeFuelStopVisit != null) {
            lastAcceptedFixAtMillis = filtered.timestampEpochMillis
            mutableState.value = refuellingStateAt(
                state = previousState.copy(
                    phase = NavigationPhase.AT_FUEL_STOP,
                    rawLocation = rawLocation,
                    gpsStatus = GpsStatus.ACTIVE,
                    offRouteStatus = OffRouteStatus.ON_ROUTE,
                    offRouteDurationMillis = 0L,
                ),
                nowEpochMillis = now.toEpochMilli(),
            )
            return
        }
        if (previousState.activeIntermediateStopVisit != null) {
            lastAcceptedFixAtMillis = filtered.timestampEpochMillis
            val visit = previousState.activeIntermediateStopVisit
            val departureThreshold = maxOf(
                policy.intermediateStopDepartureDistanceMeters,
                filtered.accuracyMeters * 2.0,
            )
            val hasDeparted = distanceMeters(filtered.coordinate, visit.stop.location) >
                departureThreshold &&
                (filtered.speedMetersPerSecond ?: 0.0) >=
                policy.intermediateStopDepartureMinimumSpeedMetersPerSecond
            consecutiveIntermediateDepartureFixes = if (hasDeparted) {
                consecutiveIntermediateDepartureFixes + 1
            } else {
                0
            }
            if (
                consecutiveIntermediateDepartureFixes >=
                policy.intermediateStopDepartureConsecutiveFixes
            ) {
                completeIntermediateStop(
                    completionMode = NavigationIntermediateStopCompletionMode.GPS_DEPARTURE,
                    nowEpochMillis = now.toEpochMilli(),
                )
            } else {
                mutableState.value = previousState.copy(
                    phase = NavigationPhase.AT_INTERMEDIATE_STOP,
                    rawLocation = rawLocation,
                    gpsStatus = GpsStatus.ACTIVE,
                    offRouteStatus = OffRouteStatus.ON_ROUTE,
                    offRouteDurationMillis = 0L,
                )
            }
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

        if (progressIsFirstReliableFix()) {
            fuelStopDistances
                .takeWhile { (_, routeDistance) ->
                    routeDistance + policy.atFuelStopDistanceMeters <
                        match.distanceAlongGeometryMeters
                }
                .forEach { (stop, _) -> completedFuelStopSequences += stop.sequence }
            intermediateStopDistances
                .takeWhile { (_, routeDistance) ->
                    routeDistance + policy.atIntermediateStopDistanceMeters <
                        match.distanceAlongGeometryMeters
                }
                .forEach { (stop, _) -> completedIntermediateStopSequences += stop.sequence }
        }
        val reachedFuelStop = if (offRouteStatus == OffRouteStatus.ON_ROUTE) {
            firstReachedFuelStop(match.distanceAlongGeometryMeters)
        } else {
            null
        }
        val activeVisit = reachedFuelStop?.let { (stop, _) ->
            val arrivedAt = now.toEpochMilli()
            NavigationFuelStopVisit(
                stop = stop,
                arrivedAtEpochMillis = arrivedAt,
                plannedCompletionAtEpochMillis = arrivedAt + stop.dwellTimeSeconds * 1_000L,
                remainingDwellSeconds = stop.dwellTimeSeconds.toDouble(),
            )
        }
        val reachedIntermediateStop = if (
            offRouteStatus == OffRouteStatus.ON_ROUTE && activeVisit == null
        ) {
            firstReachedIntermediateStop(match.distanceAlongGeometryMeters)
        } else {
            null
        }
        val activeIntermediateVisit = reachedIntermediateStop?.let { (stop, _) ->
            NavigationIntermediateStopVisit(stop, now.toEpochMilli())
        }
        val authoritativeDistanceAlong = reachedFuelStop?.second
            ?: reachedIntermediateStop?.second
            ?: match.distanceAlongGeometryMeters
        val progressFraction = if (match.geometryLengthMeters == 0.0) {
            0.0
        } else {
            (authoritativeDistanceAlong / match.geometryLengthMeters).coerceIn(0.0, 1.0)
        }
        val distanceRemaining = route.totalDistanceMeters * (1.0 - progressFraction)
        val drivingRemaining = route.drivingDurationSeconds * (1.0 - progressFraction)
        if (offRouteStatus == OffRouteStatus.ON_ROUTE) {
            lastReliableDistanceAlongGeometryMeters = authoritativeDistanceAlong
        }
        val fuelProgress = fuelStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = authoritativeDistanceAlong,
            geometryLengthMeters = match.geometryLengthMeters,
            now = now,
            activeVisit = activeVisit,
        )
        val nextFuel = fuelProgress.firstOrNull {
            it.lifecycle != NavigationFuelStopLifecycle.COMPLETED
        }
        val intermediateProgress = intermediateStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = authoritativeDistanceAlong,
            geometryLengthMeters = match.geometryLengthMeters,
            now = now,
            activeVisit = activeIntermediateVisit,
        )
        val nextIntermediate = intermediateProgress.firstOrNull {
            it.lifecycle != NavigationIntermediateStopLifecycle.COMPLETED
        }
        val remainingDwell = remainingFuelDwellSeconds(
            nowEpochMillis = now.toEpochMilli(),
            activeVisit = activeVisit,
        )
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
        val computedPhase = if (activeVisit != null) {
            NavigationPhase.AT_FUEL_STOP
        } else if (activeIntermediateVisit != null) {
            NavigationPhase.AT_INTERMEDIATE_STOP
        } else if (progressIsReliable) navigationPhase(
            distanceRemainingMeters = distanceRemaining,
            distanceToManeuverMeters = distanceToManeuver,
            speedMetersPerSecond = speed,
            nextFuelStop = nextFuel,
            nextIntermediateStop = nextIntermediate,
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
                    coordinate = reachedFuelStop?.first?.location
                        ?: reachedIntermediateStop?.first?.location
                        ?: match.snappedCoordinate,
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
            fuelStopProgress = if (progressIsReliable) {
                fuelProgress
            } else {
                previousState.fuelStopProgress
            },
            activeFuelStopVisit = activeVisit,
            nextIntermediateStop = if (progressIsReliable) {
                nextIntermediate
            } else {
                previousState.nextIntermediateStop
            },
            intermediateStopProgress = if (progressIsReliable) {
                intermediateProgress
            } else {
                previousState.intermediateStopProgress
            },
            activeIntermediateStopVisit = activeIntermediateVisit,
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
            current.phase == NavigationPhase.ROUTE_PREVIEW ||
            current.activeFuelStopVisit != null || current.activeIntermediateStopVisit != null
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
        val plannedFuelStops = plannedFuelStopProgress()
        val plannedIntermediateStops = plannedIntermediateStopProgress()
        trackingStartedAtMillis = refreshedAtEpochMillis
        mutableState.value = NavigationState(
            phase = NavigationPhase.NAVIGATING,
            route = route,
            distanceRemainingMeters = route.totalDistanceMeters,
            drivingDurationRemainingSeconds = route.drivingDurationSeconds,
            totalDurationRemainingSeconds = route.totalTripDurationSeconds,
            currentManeuver = route.maneuvers.firstOrNull(),
            nextManeuver = route.maneuvers.getOrNull(1),
            nextFuelStop = plannedFuelStops.firstOrNull(),
            fuelStopProgress = plannedFuelStops,
            nextIntermediateStop = plannedIntermediateStops.firstOrNull(),
            intermediateStopProgress = plannedIntermediateStops,
            gpsStatus = GpsStatus.ACQUIRING,
            lastSuccessfulRouteRefreshEpochMillis = refreshedAtEpochMillis,
            routeUpdateNotice = updateNotice,
            routeSource = NavigationRouteSource.LIVE,
            connectivity = NavigationConnectivity.ONLINE,
            locationMode = previousState.locationMode,
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
                if (current.routeUpdateReason == RouteUpdateReason.CONNECTIVITY_RECOVERY) {
                    NavigationConnectivity.RECOVERING
                } else {
                    NavigationConnectivity.REROUTING_UNAVAILABLE
                }
            } else {
                current.connectivity
            },
        )
    }

    fun networkLost() {
        val current = mutableState.value
        if (current.route == null) return
        if (current.reroutingStatus == ReroutingStatus.IN_PROGRESS) {
            mutableState.value = current.copy(
                phase = phaseBehindRouteUpdate,
                reroutingStatus = ReroutingStatus.FAILED,
                routeUpdateFailure = RouteUpdateFailure.NETWORK_OR_SERVER,
                connectivity = NavigationConnectivity.OFFLINE,
            )
        } else {
            mutableState.value = current.copy(connectivity = NavigationConnectivity.OFFLINE)
        }
    }

    fun networkRestored() {
        val current = mutableState.value
        if (current.route != null &&
            (current.connectivity == NavigationConnectivity.OFFLINE ||
                current.routeSource == NavigationRouteSource.CACHE &&
                current.connectivity == NavigationConnectivity.REROUTING_UNAVAILABLE)
        ) {
            mutableState.value = current.copy(connectivity = NavigationConnectivity.RECOVERING)
        }
    }

    fun recordSpokenInstruction(instruction: String) {
        mutableState.value = mutableState.value.copy(lastSpokenInstruction = instruction)
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(voiceGuidanceEnabled = enabled)
    }

    fun setLocationMode(mode: NavigationLocationMode) {
        mutableState.value = mutableState.value.copy(locationMode = mode)
    }

    /** Completes only the active visit; GPS proximity alone never resumes navigation. */
    fun completeFuelStop(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val current = mutableState.value
        val visit = current.activeFuelStopVisit ?: return false
        val route = current.route ?: return false
        completedFuelStopSequences += visit.stop.sequence
        val routeMatcher = requireNotNull(matcher)
        val distanceAlong = lastReliableDistanceAlongGeometryMeters ?: 0.0
        val fuelProgress = fuelStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = distanceAlong,
            geometryLengthMeters = routeMatcher.distanceAtShapeIndex(route.geometry.lastIndex),
            now = Instant.ofEpochMilli(nowEpochMillis),
            activeVisit = null,
        )
        val nextFuel = fuelProgress.firstOrNull {
            it.lifecycle != NavigationFuelStopLifecycle.COMPLETED
        }
        val drivingRemaining = current.drivingDurationRemainingSeconds ?: 0.0
        val remainingDwell = remainingFuelDwellSeconds(
            nowEpochMillis = nowEpochMillis,
            activeVisit = null,
        )
        val totalRemaining = drivingRemaining + remainingDwell
        mutableState.value = current.copy(
            phase = navigationPhase(
                distanceRemainingMeters = current.distanceRemainingMeters ?: route.totalDistanceMeters,
                distanceToManeuverMeters = current.distanceToNextManeuverMeters,
                speedMetersPerSecond = current.currentSpeedMetersPerSecond,
                nextFuelStop = nextFuel,
                nextIntermediateStop = current.nextIntermediateStop,
            ),
            totalDurationRemainingSeconds = totalRemaining,
            estimatedArrivalAt = Instant.ofEpochMilli(nowEpochMillis)
                .plusMillis((totalRemaining * 1_000).toLong()),
            nextFuelStop = nextFuel,
            fuelStopProgress = fuelProgress,
            activeFuelStopVisit = null,
            lastCompletedFuelStop = visit.stop,
            offRouteStatus = OffRouteStatus.ON_ROUTE,
            offRouteDurationMillis = 0L,
        )
        phaseBehindRouteUpdate = mutableState.value.phase
        return true
    }

    fun completeIntermediateStop(
        completionMode: NavigationIntermediateStopCompletionMode =
            NavigationIntermediateStopCompletionMode.USER_CONFIRMATION,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = mutableState.value
        val visit = current.activeIntermediateStopVisit ?: return false
        val route = current.route ?: return false
        completedIntermediateStopSequences += visit.stop.sequence
        consecutiveIntermediateDepartureFixes = 0
        val routeMatcher = requireNotNull(matcher)
        val distanceAlong = lastReliableDistanceAlongGeometryMeters ?: 0.0
        val progress = intermediateStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = distanceAlong,
            geometryLengthMeters = routeMatcher.geometryLengthMeters,
            now = Instant.ofEpochMilli(nowEpochMillis),
            activeVisit = null,
        )
        val nextIntermediate = progress.firstOrNull {
            it.lifecycle != NavigationIntermediateStopLifecycle.COMPLETED
        }
        val drivingRemaining = current.drivingDurationRemainingSeconds ?: 0.0
        val remainingDwell = remainingFuelDwellSeconds(nowEpochMillis, current.activeFuelStopVisit)
        val totalRemaining = drivingRemaining + remainingDwell
        mutableState.value = current.copy(
            phase = navigationPhase(
                distanceRemainingMeters = current.distanceRemainingMeters ?: route.totalDistanceMeters,
                distanceToManeuverMeters = current.distanceToNextManeuverMeters,
                speedMetersPerSecond = current.currentSpeedMetersPerSecond,
                nextFuelStop = current.nextFuelStop,
                nextIntermediateStop = nextIntermediate,
            ),
            totalDurationRemainingSeconds = totalRemaining,
            estimatedArrivalAt = Instant.ofEpochMilli(nowEpochMillis)
                .plusMillis((totalRemaining * 1_000).toLong()),
            nextIntermediateStop = nextIntermediate,
            intermediateStopProgress = progress,
            activeIntermediateStopVisit = null,
            lastCompletedIntermediateStop = visit.stop,
            lastIntermediateStopCompletionMode = completionMode,
            offRouteStatus = OffRouteStatus.ON_ROUTE,
            offRouteDurationMillis = 0L,
        )
        phaseBehindRouteUpdate = mutableState.value.phase
        return true
    }

    fun tick(nowEpochMillis: Long) {
        val state = mutableState.value.activeFuelStopVisit?.let {
            refuellingStateAt(mutableState.value, nowEpochMillis).also { updated ->
                mutableState.value = updated
            }
        } ?: mutableState.value
        if (state.phase == NavigationPhase.IDLE || state.phase == NavigationPhase.ROUTE_PREVIEW) return
        val freshnessReference = lastAcceptedFixAtMillis ?: trackingStartedAtMillis ?: return
        if (nowEpochMillis - freshnessReference > policy.gpsLostAfterMillis) {
            mutableState.value = state.copy(
                phase = if (state.activeFuelStopVisit != null) {
                    NavigationPhase.AT_FUEL_STOP
                } else if (state.activeIntermediateStopVisit != null) {
                    NavigationPhase.AT_INTERMEDIATE_STOP
                } else {
                    NavigationPhase.GPS_LOST
                },
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
        completedFuelStopSequences.clear()
        completedIntermediateStopSequences.clear()
        consecutiveIntermediateDepartureFixes = 0
        lastReliableDistanceAlongGeometryMeters = null
        phaseBehindRouteUpdate = NavigationPhase.NAVIGATING
        val routeMatcher = requireNotNull(matcher)
        fuelStopDistances = route.fuelStops.map { it to routeMatcher.distanceAlongRoute(it.location) }
            .sortedBy { it.second }
        intermediateStopDistances = route.intermediateStops
            .map { it to routeMatcher.distanceAlongRoute(it.location) }
            .sortedBy { it.second }
    }

    private fun plannedFuelStopProgress(): List<NavigationFuelStopProgress> =
        fuelStopDistances.map { (stop, routeDistance) ->
            NavigationFuelStopProgress(
                stop = stop,
                distanceRemainingMeters = routeDistance,
                lifecycle = NavigationFuelStopLifecycle.PLANNED,
                estimatedArrivalAt = stop.expectedArrivalAt?.toInstant(),
            )
        }

    private fun plannedIntermediateStopProgress(): List<NavigationIntermediateStopProgress> =
        intermediateStopDistances.map { (stop, routeDistance) ->
            NavigationIntermediateStopProgress(
                stop = stop,
                distanceRemainingMeters = routeDistance,
            )
        }

    private fun progressIsFirstReliableFix(): Boolean =
        lastReliableDistanceAlongGeometryMeters == null

    private fun firstReachedFuelStop(
        distanceAlongGeometryMeters: Double,
    ): Pair<NavigationFuelStop, Double>? {
        val (stop, routeDistance) = fuelStopDistances.firstOrNull { (candidate, _) ->
            candidate.sequence !in completedFuelStopSequences
        } ?: return null
        val insideArrivalArea = kotlin.math.abs(
            routeDistance - distanceAlongGeometryMeters,
        ) <= policy.atFuelStopDistanceMeters
        val previousDistance = lastReliableDistanceAlongGeometryMeters
        val crossedBetweenFixes = previousDistance != null &&
            previousDistance < routeDistance - policy.atFuelStopDistanceMeters &&
            distanceAlongGeometryMeters > routeDistance + policy.atFuelStopDistanceMeters
        return (stop to routeDistance).takeIf { insideArrivalArea || crossedBetweenFixes }
    }

    private fun firstReachedIntermediateStop(
        distanceAlongGeometryMeters: Double,
    ): Pair<NavigationIntermediateStop, Double>? {
        val (stop, routeDistance) = intermediateStopDistances.firstOrNull { (candidate, _) ->
            candidate.sequence !in completedIntermediateStopSequences
        } ?: return null
        val insideArrivalArea = kotlin.math.abs(
            routeDistance - distanceAlongGeometryMeters,
        ) <= policy.atIntermediateStopDistanceMeters
        val previousDistance = lastReliableDistanceAlongGeometryMeters
        val crossedBetweenFixes = previousDistance != null &&
            previousDistance < routeDistance - policy.atIntermediateStopDistanceMeters &&
            distanceAlongGeometryMeters > routeDistance + policy.atIntermediateStopDistanceMeters
        return (stop to routeDistance).takeIf { insideArrivalArea || crossedBetweenFixes }
    }

    private fun intermediateStopProgressAt(
        route: NavigationRoute,
        distanceAlongGeometryMeters: Double,
        geometryLengthMeters: Double,
        now: Instant,
        activeVisit: NavigationIntermediateStopVisit?,
    ): List<NavigationIntermediateStopProgress> = intermediateStopDistances.map {
        (stop, routeDistance) ->
        val completed = stop.sequence in completedIntermediateStopSequences
        val active = activeVisit?.stop?.sequence == stop.sequence
        val distanceToStop = if (completed || active) {
            0.0
        } else {
            maxOf(0.0, routeDistance - distanceAlongGeometryMeters)
        }
        val lifecycle = when {
            completed -> NavigationIntermediateStopLifecycle.COMPLETED
            active -> NavigationIntermediateStopLifecycle.ARRIVED
            distanceToStop <= policy.approachingIntermediateStopDistanceMeters ->
                NavigationIntermediateStopLifecycle.APPROACHING
            else -> NavigationIntermediateStopLifecycle.PLANNED
        }
        val drivingSecondsToStop = if (geometryLengthMeters <= 0.0) {
            0.0
        } else {
            route.drivingDurationSeconds *
                (distanceToStop / geometryLengthMeters).coerceIn(0.0, 1.0)
        }
        NavigationIntermediateStopProgress(
            stop = stop,
            distanceRemainingMeters = distanceToStop,
            lifecycle = lifecycle,
            estimatedArrivalAt = when {
                completed -> null
                active -> Instant.ofEpochMilli(requireNotNull(activeVisit).arrivedAtEpochMillis)
                else -> now.plusMillis((drivingSecondsToStop * 1_000).toLong())
            },
        )
    }

    private fun fuelStopProgressAt(
        route: NavigationRoute,
        distanceAlongGeometryMeters: Double,
        geometryLengthMeters: Double,
        now: Instant,
        activeVisit: NavigationFuelStopVisit?,
    ): List<NavigationFuelStopProgress> {
        var dwellBeforeStopSeconds = 0.0
        return fuelStopDistances.map { (stop, routeDistance) ->
            val completed = stop.sequence in completedFuelStopSequences
            val isActive = activeVisit?.stop?.sequence == stop.sequence
            val distanceToStop = if (completed || isActive) {
                0.0
            } else {
                maxOf(0.0, routeDistance - distanceAlongGeometryMeters)
            }
            val lifecycle = when {
                completed -> NavigationFuelStopLifecycle.COMPLETED
                isActive -> NavigationFuelStopLifecycle.REFUELING
                distanceToStop <= policy.approachingFuelStopDistanceMeters ->
                    NavigationFuelStopLifecycle.APPROACHING
                else -> NavigationFuelStopLifecycle.PLANNED
            }
            val eta = when {
                completed -> null
                isActive -> Instant.ofEpochMilli(activeVisit.arrivedAtEpochMillis)
                else -> {
                    val drivingSecondsToStop = if (geometryLengthMeters <= 0.0) {
                        0.0
                    } else {
                        route.drivingDurationSeconds *
                            (distanceToStop / geometryLengthMeters).coerceIn(0.0, 1.0)
                    }
                    now.plusMillis(
                        ((drivingSecondsToStop + dwellBeforeStopSeconds) * 1_000).toLong(),
                    )
                }
            }
            if (!completed) {
                dwellBeforeStopSeconds += if (isActive) {
                    activeVisit.remainingDwellSeconds
                } else {
                    stop.dwellTimeSeconds.toDouble()
                }
            }
            NavigationFuelStopProgress(
                stop = stop,
                distanceRemainingMeters = distanceToStop,
                lifecycle = lifecycle,
                estimatedArrivalAt = eta,
            )
        }
    }

    private fun remainingFuelDwellSeconds(
        nowEpochMillis: Long,
        activeVisit: NavigationFuelStopVisit?,
    ): Double = fuelStopDistances.sumOf { (stop, _) ->
        when {
            stop.sequence in completedFuelStopSequences -> 0.0
            activeVisit?.stop?.sequence == stop.sequence ->
                ((activeVisit.plannedCompletionAtEpochMillis - nowEpochMillis) / 1_000.0)
                    .coerceAtLeast(0.0)
            else -> stop.dwellTimeSeconds.toDouble()
        }
    }

    private fun refuellingStateAt(
        state: NavigationState,
        nowEpochMillis: Long,
    ): NavigationState {
        val visit = state.activeFuelStopVisit ?: return state
        val route = state.route ?: return state
        val remainingSeconds = (
            visit.plannedCompletionAtEpochMillis - nowEpochMillis
        ).div(1_000.0).coerceAtLeast(0.0)
        val updatedVisit = visit.copy(remainingDwellSeconds = remainingSeconds)
        val remainingDwell = remainingFuelDwellSeconds(nowEpochMillis, updatedVisit)
        val drivingRemaining = state.drivingDurationRemainingSeconds ?: 0.0
        val totalRemaining = drivingRemaining + remainingDwell
        val routeMatcher = requireNotNull(matcher)
        val progress = fuelStopProgressAt(
            route = route,
            distanceAlongGeometryMeters = lastReliableDistanceAlongGeometryMeters ?: 0.0,
            geometryLengthMeters = routeMatcher.distanceAtShapeIndex(route.geometry.lastIndex),
            now = Instant.ofEpochMilli(nowEpochMillis),
            activeVisit = updatedVisit,
        )
        return state.copy(
            phase = NavigationPhase.AT_FUEL_STOP,
            totalDurationRemainingSeconds = totalRemaining,
            estimatedArrivalAt = Instant.ofEpochMilli(nowEpochMillis)
                .plusMillis((totalRemaining * 1_000).toLong()),
            nextFuelStop = progress.firstOrNull {
                it.lifecycle != NavigationFuelStopLifecycle.COMPLETED
            },
            fuelStopProgress = progress,
            activeFuelStopVisit = updatedVisit,
        )
    }

    private fun navigationPhase(
        distanceRemainingMeters: Double,
        distanceToManeuverMeters: Double?,
        speedMetersPerSecond: Double,
        nextFuelStop: NavigationFuelStopProgress?,
        nextIntermediateStop: NavigationIntermediateStopProgress?,
    ): NavigationPhase {
        if (distanceRemainingMeters <= policy.arrivalDistanceMeters) return NavigationPhase.ARRIVED
        val fuelDistance = nextFuelStop?.distanceRemainingMeters
        if (fuelDistance != null && fuelDistance <= policy.atFuelStopDistanceMeters) {
            return NavigationPhase.AT_FUEL_STOP
        }
        if (fuelDistance != null && fuelDistance <= policy.approachingFuelStopDistanceMeters) {
            return NavigationPhase.APPROACHING_FUEL_STOP
        }
        val intermediateDistance = nextIntermediateStop?.distanceRemainingMeters
        if (
            intermediateDistance != null &&
            intermediateDistance <= policy.atIntermediateStopDistanceMeters
        ) {
            return NavigationPhase.AT_INTERMEDIATE_STOP
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
