package org.compass.cng.navigation

import kotlinx.coroutines.flow.StateFlow

/** Application-scoped boundary shared by UI and the foreground location service. */
class NavigationSession(
    private val engine: NavigationEngine = NavigationEngine(),
    private val routeStore: NavigationRouteStore = NoOpNavigationRouteStore,
    private val eventLogger: (String) -> Unit = {},
) {
    val state: StateFlow<NavigationState> = engine.state

    init {
        routeStore.load()?.let { cached ->
            engine.preview(
                route = cached.route,
                source = NavigationRouteSource.CACHE,
                cachedAtEpochMillis = cached.cachedAtEpochMillis,
            )
            eventLogger(
                "navigation route restored from cache: active=${cached.navigationWasActive} " +
                    "fuel_stops=${cached.route.fuelStops.size}",
            )
        }
    }

    fun preview(route: NavigationRoute) {
        engine.preview(route)
        routeStore.save(route, navigationWasActive = false)
        eventLogger("navigation route cached: active=false fuel_stops=${route.fuelStops.size}")
    }

    fun start() {
        engine.start()
        state.value.route?.let { routeStore.save(it, navigationWasActive = true) }
        eventLogger("navigation route cache marked active")
    }

    fun updateLocation(location: NavigationLocation): NavigationState {
        engine.updateLocation(location)
        return state.value
    }

    fun tick(nowEpochMillis: Long) {
        engine.tick(nowEpochMillis)
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
        routeStore.save(route, navigationWasActive = true)
        eventLogger("navigation route cache replaced from live route")
    }

    fun failRouteUpdate(
        failure: RouteUpdateFailure = RouteUpdateFailure.NETWORK_OR_SERVER,
    ) {
        engine.failRouteUpdate(failure)
        if (failure == RouteUpdateFailure.NETWORK_OR_SERVER) {
            eventLogger("navigation degraded: cached_route_active=true rerouting_available=false")
        }
    }

    fun recordSpokenInstruction(instruction: String) {
        engine.recordSpokenInstruction(instruction)
    }

    fun clear() {
        engine.clear()
        routeStore.clear()
        eventLogger("navigation route cache cleared: reason=session_clear")
    }
}
