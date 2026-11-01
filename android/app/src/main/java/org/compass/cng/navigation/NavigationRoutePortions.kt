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

/** Constant-space view: camera look-ahead only reads the few points it actually needs. */
internal fun NavigationState.remainingRouteGeometry(): List<Coordinate> {
    val geometry = requireNotNull(route).geometry
    val snapped = snappedLocation ?: return geometry
    val segmentIndex = currentRouteSegmentIndex ?: return geometry
    val nextIndex = segmentIndex.coerceIn(0, geometry.lastIndex - 1) + 1
    return object : AbstractList<Coordinate>() {
        override val size: Int = 1 + geometry.size - nextIndex
        override fun get(index: Int): Coordinate {
            if (index !in indices) throw IndexOutOfBoundsException("index: $index, size: $size")
            return if (index == 0) snapped else geometry[nextIndex + index - 1]
        }
    }
}
