package org.compass.cng.navigation

data class CachedNavigationRoute(
    val route: NavigationRoute,
    val cachedAtEpochMillis: Long,
    val navigationWasActive: Boolean,
    val progress: NavigationProgressSnapshot? = null,
)

/** Small, map-safe checkpoint needed to resume local guidance after process recreation. */
data class NavigationProgressSnapshot(
    val savedAtEpochMillis: Long,
    val navigationPosition: NavigationPosition?,
    val routeProgressFraction: Double,
    val distanceRemainingMeters: Double?,
    val drivingDurationRemainingSeconds: Double?,
    val totalDurationRemainingSeconds: Double?,
    val estimatedArrivalAtEpochMillis: Long?,
    val currentRoadName: String?,
    val currentManeuverIndex: Int?,
    val nextManeuverIndex: Int?,
    val distanceToNextManeuverMeters: Double?,
    val completedFuelStopSequences: Set<Int>,
    val activeFuelStopVisit: NavigationFuelStopVisit?,
    val lastCompletedFuelStopSequence: Int?,
    val lastSpokenInstruction: String?,
    val voiceGuidanceEnabled: Boolean,
    val lastSuccessfulRouteRefreshEpochMillis: Long?,
    val locationMode: NavigationLocationMode = NavigationLocationMode.DEVICE,
    val completedIntermediateStopSequences: Set<Int> = emptySet(),
    val activeIntermediateStopVisit: NavigationIntermediateStopVisit? = null,
    val lastCompletedIntermediateStopSequence: Int? = null,
) {
    init {
        require(savedAtEpochMillis >= 0L)
        require(routeProgressFraction in 0.0..1.0)
        require(completedFuelStopSequences.all { it > 0 })
        require(completedIntermediateStopSequences.all { it > 0 })
    }
}

interface NavigationRouteStore {
    fun load(): CachedNavigationRoute?

    fun save(
        route: NavigationRoute,
        navigationWasActive: Boolean,
        progress: NavigationProgressSnapshot? = null,
    )

    fun clear()
}

object NoOpNavigationRouteStore : NavigationRouteStore {
    override fun load(): CachedNavigationRoute? = null

    override fun save(
        route: NavigationRoute,
        navigationWasActive: Boolean,
        progress: NavigationProgressSnapshot?,
    ) = Unit

    override fun clear() = Unit
}
