package org.compass.cng.navigation

import kotlinx.coroutines.flow.StateFlow

/** Application-scoped boundary shared by UI and the foreground location service. */
class NavigationSession(
    private val engine: NavigationEngine = NavigationEngine(),
) {
    val state: StateFlow<NavigationState> = engine.state

    fun preview(route: NavigationRoute) {
        engine.preview(route)
    }

    fun start() {
        engine.start()
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
    }

    fun failRouteUpdate(
        failure: RouteUpdateFailure = RouteUpdateFailure.NETWORK_OR_SERVER,
    ) {
        engine.failRouteUpdate(failure)
    }

    fun recordSpokenInstruction(instruction: String) {
        engine.recordSpokenInstruction(instruction)
    }

    fun clear() {
        engine.clear()
    }
}
