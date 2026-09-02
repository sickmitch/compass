package org.compass.cng.navigation

data class RouteUpdatePolicy(
    val trafficRefreshIntervalMillis: Long = 5 * 60 * 1_000L,
    val failedAttemptBackoffMillis: Long = 60_000L,
)

/** Decides when a server route update is justified; it never performs network work itself. */
class RouteUpdateController(
    private val policy: RouteUpdatePolicy = RouteUpdatePolicy(),
) {
    private var lastAttemptAtMillis: Long? = null
    private var lastSuccessfulUpdateAtMillis: Long? = null
    private var offRouteEpisodeRequested = false

    fun navigationStarted(nowEpochMillis: Long) {
        lastAttemptAtMillis = null
        lastSuccessfulUpdateAtMillis = nowEpochMillis
        offRouteEpisodeRequested = false
    }

    fun nextUpdate(state: NavigationState, nowEpochMillis: Long): RouteUpdateReason? {
        if (state.route == null || state.reroutingStatus == ReroutingStatus.IN_PROGRESS ||
            state.phase == NavigationPhase.IDLE || state.phase == NavigationPhase.ROUTE_PREVIEW ||
            state.phase == NavigationPhase.AT_FUEL_STOP || state.phase == NavigationPhase.ARRIVED
        ) {
            return null
        }
        if (state.rawLocation == null && state.snappedLocation == null) return null
        if (state.offRouteStatus == OffRouteStatus.ON_ROUTE) offRouteEpisodeRequested = false
        val lastAttempt = lastAttemptAtMillis
        if (lastAttempt != null && nowEpochMillis - lastAttempt < policy.failedAttemptBackoffMillis) {
            return null
        }
        if (state.offRouteStatus == OffRouteStatus.OFF_ROUTE && !offRouteEpisodeRequested) {
            offRouteEpisodeRequested = true
            return RouteUpdateReason.OFF_ROUTE
        }
        val lastSuccess = lastSuccessfulUpdateAtMillis ?: nowEpochMillis
        if (nowEpochMillis - lastSuccess >= policy.trafficRefreshIntervalMillis) {
            return RouteUpdateReason.TRAFFIC_REFRESH
        }
        return null
    }

    fun attemptStarted(nowEpochMillis: Long) {
        lastAttemptAtMillis = nowEpochMillis
    }

    fun updateSucceeded(nowEpochMillis: Long) {
        lastSuccessfulUpdateAtMillis = nowEpochMillis
        lastAttemptAtMillis = null
    }

    fun updateFailed() {
        offRouteEpisodeRequested = false
    }

    fun forceDebugUpdate(): RouteUpdateReason = RouteUpdateReason.MANUAL_DEBUG
}
