package org.compass.cng.navigation

import kotlinx.coroutines.flow.StateFlow

/** Application-scoped boundary shared by UI and the foreground location service. */
class NavigationSession(
    private val engine: NavigationEngine = NavigationEngine(),
    private val routeStore: NavigationRouteStore = NoOpNavigationRouteStore,
    private val eventLogger: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<NavigationState> = engine.state
    private val restoredCache = routeStore.load()
    val restoredNavigationWasActive: Boolean = restoredCache?.navigationWasActive == true
    private var lastCheckpointAtEpochMillis: Long? = null

    init {
        restoredCache?.let { cached ->
            if (cached.navigationWasActive && cached.progress != null) {
                engine.restore(
                    route = cached.route,
                    progress = cached.progress,
                    cachedAtEpochMillis = cached.cachedAtEpochMillis,
                    nowEpochMillis = clock(),
                )
                lastCheckpointAtEpochMillis = cached.progress.savedAtEpochMillis
            } else {
                engine.preview(
                    route = cached.route,
                    source = NavigationRouteSource.CACHE,
                    cachedAtEpochMillis = cached.cachedAtEpochMillis,
                )
            }
            eventLogger(
                "navigation route restored from cache: active=${cached.navigationWasActive} " +
                    "progress=${cached.progress != null} fuel_stops=${cached.route.fuelStops.size}",
            )
        }
    }

    fun preview(route: NavigationRoute) {
        engine.preview(route)
        routeStore.save(route, navigationWasActive = false, progress = null)
        eventLogger("navigation route cached: active=false fuel_stops=${route.fuelStops.size}")
    }

    fun start() {
        engine.start()
        persistCheckpoint(force = true)
        eventLogger("navigation route cache marked active")
    }

    fun updateLocation(location: NavigationLocation): NavigationState {
        val hadActiveFuelStop = state.value.activeFuelStopVisit != null
        val hadActiveIntermediateStop = state.value.activeIntermediateStopVisit != null
        val previousIntermediateCompletion = state.value.lastIntermediateStopCompletionMode
        engine.updateLocation(location)
        val current = state.value
        persistCheckpoint(
            force = hadActiveFuelStop != (current.activeFuelStopVisit != null) ||
                hadActiveIntermediateStop != (current.activeIntermediateStopVisit != null),
        )
        if (!hadActiveIntermediateStop && current.activeIntermediateStopVisit != null) {
            eventLogger("navigation intermediate stop reached; guidance_paused=true dwell_seconds=0")
        }
        if (
            previousIntermediateCompletion != current.lastIntermediateStopCompletionMode &&
            current.lastIntermediateStopCompletionMode ==
            NavigationIntermediateStopCompletionMode.GPS_DEPARTURE
        ) {
            eventLogger("navigation intermediate stop completed by GPS departure; eta_recalculated=true")
        }
        return current
    }

    fun tick(nowEpochMillis: Long) {
        engine.tick(nowEpochMillis)
        persistCheckpoint(force = false)
    }

    fun stopToPreview() {
        engine.stopToPreview()
        routeStore.clear()
        eventLogger("navigation route cache cleared: reason=operator_stop")
    }

    fun beginRouteUpdate(reason: RouteUpdateReason) {
        engine.beginRouteUpdate(reason)
    }

    fun replaceRoute(
        route: NavigationRoute,
        refreshedAtEpochMillis: Long,
        currentLocation: NavigationLocation?,
    ) {
        engine.replaceRoute(route, refreshedAtEpochMillis, currentLocation)
        persistCheckpoint(force = true)
        eventLogger("navigation route cache replaced from live route")
    }

    fun failRouteUpdate(
        failure: RouteUpdateFailure = RouteUpdateFailure.NETWORK_OR_SERVER,
    ) {
        val wasConnectivityRecovery =
            state.value.routeUpdateReason == RouteUpdateReason.CONNECTIVITY_RECOVERY
        engine.failRouteUpdate(failure)
        persistCheckpoint(force = true)
        if (failure == RouteUpdateFailure.NETWORK_OR_SERVER) {
            if (wasConnectivityRecovery) {
                eventLogger("navigation recovery deferred: downloaded_route_active=true")
            } else {
                eventLogger("navigation degraded: cached_route_active=true rerouting_available=false")
            }
        }
    }

    fun recordSpokenInstruction(instruction: String) {
        engine.recordSpokenInstruction(instruction)
        persistCheckpoint(force = true)
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        engine.setVoiceGuidanceEnabled(enabled)
        persistCheckpoint(force = true)
        eventLogger("navigation voice guidance enabled=$enabled")
    }

    fun setLocationMode(mode: NavigationLocationMode) {
        engine.setLocationMode(mode)
        persistCheckpoint(force = true)
        eventLogger("navigation location mode=${mode.name.lowercase()}")
    }

    fun completeFuelStop(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        engine.completeFuelStop(nowEpochMillis).also { completed ->
            if (completed) {
                persistCheckpoint(force = true)
                eventLogger("navigation CNG refuelling completed by operator")
            }
        }

    fun completeIntermediateStop(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        engine.completeIntermediateStop(nowEpochMillis = nowEpochMillis).also { completed ->
            if (completed) {
                persistCheckpoint(force = true)
                eventLogger("navigation intermediate stop completed by operator")
            }
        }

    fun networkLost() {
        val before = state.value.connectivity
        engine.networkLost()
        if (state.value.connectivity != before) {
            persistCheckpoint(force = true)
            eventLogger("navigation connectivity: offline; downloaded_route_retained=true")
        }
    }

    fun networkRestored() {
        val before = state.value.connectivity
        engine.networkRestored()
        if (state.value.connectivity != before) {
            persistCheckpoint(force = true)
            eventLogger("navigation connectivity: recovering; downloaded_route_retained=true")
        }
    }

    fun clear() {
        engine.clear()
        routeStore.clear()
        eventLogger("navigation route cache cleared: reason=session_clear")
    }

    private fun persistCheckpoint(force: Boolean) {
        val current = state.value
        val route = current.route ?: return
        if (current.phase == NavigationPhase.IDLE || current.phase == NavigationPhase.ROUTE_PREVIEW) {
            return
        }
        val now = clock()
        val lastCheckpoint = lastCheckpointAtEpochMillis
        if (!force && lastCheckpoint != null && now - lastCheckpoint < CHECKPOINT_INTERVAL_MILLIS) {
            return
        }
        val progress = current.toProgressSnapshot(now)
        routeStore.save(route, navigationWasActive = true, progress = progress)
        lastCheckpointAtEpochMillis = now
    }

    private fun NavigationState.toProgressSnapshot(savedAtEpochMillis: Long) =
        NavigationProgressSnapshot(
            savedAtEpochMillis = savedAtEpochMillis,
            navigationPosition = navigationPosition,
            routeProgressFraction = routeProgressFraction,
            distanceRemainingMeters = distanceRemainingMeters,
            drivingDurationRemainingSeconds = drivingDurationRemainingSeconds,
            totalDurationRemainingSeconds = totalDurationRemainingSeconds,
            estimatedArrivalAtEpochMillis = estimatedArrivalAt?.toEpochMilli(),
            currentRoadName = currentRoadName,
            currentManeuverIndex = route?.maneuvers?.indexOf(currentManeuver)?.takeIf { it >= 0 },
            nextManeuverIndex = route?.maneuvers?.indexOf(nextManeuver)?.takeIf { it >= 0 },
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            completedFuelStopSequences = fuelStopProgress
                .filter { it.lifecycle == NavigationFuelStopLifecycle.COMPLETED }
                .mapTo(linkedSetOf()) { it.stop.sequence },
            activeFuelStopVisit = activeFuelStopVisit,
            lastCompletedFuelStopSequence = lastCompletedFuelStop?.sequence,
            lastSpokenInstruction = lastSpokenInstruction,
            voiceGuidanceEnabled = voiceGuidanceEnabled,
            lastSuccessfulRouteRefreshEpochMillis = lastSuccessfulRouteRefreshEpochMillis,
            locationMode = locationMode,
            completedIntermediateStopSequences = intermediateStopProgress
                .filter { it.lifecycle == NavigationIntermediateStopLifecycle.COMPLETED }
                .mapTo(linkedSetOf()) { it.stop.sequence },
            activeIntermediateStopVisit = activeIntermediateStopVisit,
            lastCompletedIntermediateStopSequence = lastCompletedIntermediateStop?.sequence,
        )

    private companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 5_000L
    }
}
