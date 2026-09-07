package org.compass.cng.ui.route

import kotlin.math.abs
import kotlin.math.roundToInt
import org.compass.cng.navigation.NavigationRouteUpdateNotice

internal const val ROUTE_UPDATE_NOTICE_VISIBLE_MILLIS = 10_000L

internal data class RouteUpdateNoticeUiModel(
    val routeId: String,
    val previousDuration: String,
    val updatedDuration: String,
    val durationDifference: String,
    val addsTime: Boolean,
    val accessibilityDescription: String,
)

internal fun NavigationRouteUpdateNotice.toUiModel(): RouteUpdateNoticeUiModel {
    val difference = formatRouteDurationDifference(durationDeltaSeconds)
    val previous = formatDuration(previousDurationSeconds)
    val updated = formatDuration(updatedDurationSeconds)
    return RouteUpdateNoticeUiModel(
        routeId = routeId,
        previousDuration = previous,
        updatedDuration = updated,
        durationDifference = difference,
        addsTime = durationDeltaSeconds >= 30.0,
        accessibilityDescription =
            "Percorso alternativo calcolato. Durata precedente $previous, " +
                "nuova durata $updated, differenza $difference.",
    )
}

internal fun routeUpdateNoticeRemainingMillis(
    notice: NavigationRouteUpdateNotice,
    nowEpochMillis: Long,
): Long = (
    ROUTE_UPDATE_NOTICE_VISIBLE_MILLIS -
        (nowEpochMillis - notice.createdAtEpochMillis).coerceAtLeast(0L)
    ).coerceIn(0L, ROUTE_UPDATE_NOTICE_VISIBLE_MILLIS)

internal fun formatRouteDurationDifference(durationDeltaSeconds: Double): String {
    if (!durationDeltaSeconds.isFinite() || abs(durationDeltaSeconds) < 30.0) return "0 min"
    val roundedMinutes = maxOf(1, (abs(durationDeltaSeconds) / 60.0).roundToInt())
    val hours = roundedMinutes / 60
    val minutes = roundedMinutes % 60
    val duration = if (hours > 0 && minutes > 0) {
        "$hours h $minutes min"
    } else if (hours > 0) {
        "$hours h"
    } else {
        "$minutes min"
    }
    return if (durationDeltaSeconds > 0.0) "+$duration" else "−$duration"
}
