package org.compass.cng.navigation

data class RouteUpdatePolicy(
    val trafficRefreshIntervalMillis: Long = 5 * 60 * 1_000L,
    val failedAttemptBackoffMillis: Long = 60_000L,
    val connectivityRecoveryBackoffMillis: List<Long> = listOf(5_000L, 15_000L, 60_000L),
) {
    init {
        require(connectivityRecoveryBackoffMillis.isNotEmpty())
        require(connectivityRecoveryBackoffMillis.all { it > 0L })
    }
}

/** Decides when a server route update is justified; it never performs network work itself. */
class RouteUpdateController(
    private val policy: RouteUpdatePolicy = RouteUpdatePolicy(),
) {
    private var lastAttemptAtMillis: Long? = null
    private var lastSuccessfulUpdateAtMillis: Long? = null
    private var offRouteEpisodeRequested = false
    private var connectivityRecoveryRequested = false
    private var connectivityRecoveryFailureCount = 0

    fun navigationStarted(nowEpochMillis: Long) {
        lastAttemptAtMillis = null
        lastSuccessfulUpdateAtMillis = nowEpochMillis
        offRouteEpisodeRequested = false
        connectivityRecoveryRequested = false
        connectivityRecoveryFailureCount = 0
    }

    fun nextUpdate(state: NavigationState, nowEpochMillis: Long): RouteUpdateReason? {
        if (state.route == null || state.reroutingStatus == ReroutingStatus.IN_PROGRESS ||
            state.phase == NavigationPhase.IDLE || state.phase == NavigationPhase.ROUTE_PREVIEW ||
            state.phase == NavigationPhase.AT_FUEL_STOP ||
            state.phase == NavigationPhase.AT_INTERMEDIATE_STOP ||
            state.phase == NavigationPhase.ARRIVED
        ) {
            return null
        }
        if (state.connectivity == NavigationConnectivity.OFFLINE) return null
        if (state.rawLocation == null && state.snappedLocation == null) return null
        if (state.offRouteStatus == OffRouteStatus.ON_ROUTE) offRouteEpisodeRequested = false
        val lastAttempt = lastAttemptAtMillis
        if (state.connectivity == NavigationConnectivity.RECOVERING &&
            !connectivityRecoveryRequested
        ) {
            val recoveryBackoff = policy.connectivityRecoveryBackoffMillis[
                (connectivityRecoveryFailureCount - 1).coerceAtLeast(0).coerceAtMost(
                    policy.connectivityRecoveryBackoffMillis.lastIndex,
                )
            ]
            if (lastAttempt != null && nowEpochMillis - lastAttempt < recoveryBackoff) return null
            connectivityRecoveryRequested = true
            return RouteUpdateReason.CONNECTIVITY_RECOVERY
        }
        if (lastAttempt != null && nowEpochMillis - lastAttempt < policy.failedAttemptBackoffMillis) {
            return null
        }
        if (state.offRouteStatus == OffRouteStatus.OFF_ROUTE && !offRouteEpisodeRequested) {
            offRouteEpisodeRequested = true
            return RouteUpdateReason.OFF_ROUTE
        }
        if (state.offRouteStatus != OffRouteStatus.ON_ROUTE) return null
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
        connectivityRecoveryRequested = false
        connectivityRecoveryFailureCount = 0
    }

    fun updateFailed(retryConnectivityRecovery: Boolean = false) {
        offRouteEpisodeRequested = false
        connectivityRecoveryRequested = false
        if (retryConnectivityRecovery) connectivityRecoveryFailureCount += 1
    }

    fun connectivityRestored() {
        lastAttemptAtMillis = null
        connectivityRecoveryRequested = false
        connectivityRecoveryFailureCount = 0
    }

    fun forceDebugUpdate(): RouteUpdateReason = RouteUpdateReason.MANUAL_DEBUG
}
