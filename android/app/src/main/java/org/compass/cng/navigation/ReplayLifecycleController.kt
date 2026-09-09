package org.compass.cng.navigation

/** Keeps debug replay continuity explicit while an asynchronous route update is in flight. */
internal class ReplayLifecycleController {
    var isReplayActive: Boolean = false
        private set

    private var resumeAfterRouteUpdate = false

    fun navigationStarted(replay: Boolean) {
        isReplayActive = replay
        resumeAfterRouteUpdate = false
    }

    fun simulatedOffRouteStarted() {
        routeUpdateStarted()
    }

    fun routeUpdateStarted(): Boolean {
        if (!isReplayActive) return false
        resumeAfterRouteUpdate = true
        isReplayActive = false
        return true
    }

    /** Returns true exactly once when replay must restart on the updated/downloaded route. */
    fun routeUpdateFinished(): Boolean {
        if (!resumeAfterRouteUpdate) return false
        resumeAfterRouteUpdate = false
        isReplayActive = true
        return true
    }
}
