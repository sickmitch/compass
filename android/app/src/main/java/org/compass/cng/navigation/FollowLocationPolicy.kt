package org.compass.cng.navigation

/** Shared freshness rules for route-free follow and Google search location bias. */
internal object FollowLocationPolicy {
    const val POLL_INTERVAL_MILLIS = 5_000L
    const val REPOLL_AFTER_MILLIS = 10_000L
    const val SEARCH_ORIGIN_MAX_AGE_MILLIS = 120_000L
    const val MAXIMUM_FUTURE_SKEW_MILLIS = 5_000L
    const val MAXIMUM_ACCURACY_METERS = 10_000.0

    fun shouldRepoll(location: NavigationLocation?, nowEpochMillis: Long): Boolean =
        location == null || !isUsable(location, nowEpochMillis, REPOLL_AFTER_MILLIS)

    fun canBiasSearch(location: NavigationLocation, nowEpochMillis: Long): Boolean =
        isUsable(location, nowEpochMillis, SEARCH_ORIGIN_MAX_AGE_MILLIS)

    private fun isUsable(
        location: NavigationLocation,
        nowEpochMillis: Long,
        maximumAgeMillis: Long,
    ): Boolean = location.accuracyMeters.isFinite() &&
        location.accuracyMeters in 0.0..MAXIMUM_ACCURACY_METERS &&
        location.timestampEpochMillis in
        (nowEpochMillis - maximumAgeMillis)..
        (nowEpochMillis + MAXIMUM_FUTURE_SKEW_MILLIS)
}
