package org.compass.cng.navigation

data class FollowLocationStabilizerPolicy(
    val preferredFixRetentionMillis: Long = 10_000,
    val materiallyWorseAccuracyRatio: Double = 1.5,
    val materiallyWorseAccuracyMarginMeters: Double = 10.0,
) {
    init {
        require(preferredFixRetentionMillis >= 0)
        require(materiallyWorseAccuracyRatio >= 1.0)
        require(materiallyWorseAccuracyMarginMeters >= 0.0)
    }
}

/**
 * Produces one stable route-free camera location from concurrent GPS and network fixes.
 *
 * Android can deliver both providers a few milliseconds apart. A newer but much less accurate
 * network fix must not temporarily replace a recent GPS fix and pull the camera away from it.
 */
class FollowLocationStabilizer(
    private val policy: FollowLocationStabilizerPolicy = FollowLocationStabilizerPolicy(),
    private val locationFilter: LocationFilter = LocationFilter(),
) {
    private var lastSelectedFix: NavigationLocation? = null

    fun reset() {
        lastSelectedFix = null
        locationFilter.reset()
    }

    fun update(candidate: NavigationLocation): NavigationLocation? {
        val previous = lastSelectedFix
        if (previous != null) {
            val elapsedMillis = candidate.timestampEpochMillis - previous.timestampEpochMillis
            if (elapsedMillis <= 0L) return null

            val materiallyWorse =
                candidate.accuracyMeters >=
                previous.accuracyMeters * policy.materiallyWorseAccuracyRatio &&
                candidate.accuracyMeters - previous.accuracyMeters >=
                policy.materiallyWorseAccuracyMarginMeters
            if (elapsedMillis <= policy.preferredFixRetentionMillis && materiallyWorse) {
                return null
            }
        }

        val filtered = locationFilter.filter(candidate) ?: return null
        lastSelectedFix = candidate
        return filtered
    }
}
