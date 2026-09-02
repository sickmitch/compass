package org.compass.cng.navigation

import org.compass.cng.domain.model.Coordinate

/** Route geometry split at the current snapped position for navigation rendering. */
internal data class NavigationRoutePortions(
    val travelled: List<Coordinate>,
    val remaining: List<Coordinate>,
)

internal fun NavigationState.routePortions(): NavigationRoutePortions {
    val activeRoute = requireNotNull(route)
    val snapped = snappedLocation
    val segmentIndex = currentRouteSegmentIndex
    if (snapped == null || segmentIndex == null) {
        return NavigationRoutePortions(
            travelled = listOf(activeRoute.geometry.first()),
            remaining = activeRoute.geometry,
        )
    }

    val safeSegmentIndex = segmentIndex.coerceIn(0, activeRoute.geometry.lastIndex - 1)
    return NavigationRoutePortions(
        travelled = activeRoute.geometry.take(safeSegmentIndex + 1) + snapped,
        remaining = listOf(snapped) + activeRoute.geometry.drop(safeSegmentIndex + 1),
    )
}
