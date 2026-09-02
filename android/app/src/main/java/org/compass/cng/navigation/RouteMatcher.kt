package org.compass.cng.navigation

import kotlin.math.cos
import kotlin.math.sqrt
import org.compass.cng.domain.model.Coordinate

data class RouteMatch(
    val snappedCoordinate: Coordinate,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val distanceFromRouteMeters: Double,
    val distanceAlongGeometryMeters: Double,
    val geometryLengthMeters: Double,
    val segmentBearingDegrees: Double,
    val headingDifferenceDegrees: Double?,
    val progressDeltaMeters: Double,
)

data class RouteMatcherPolicy(
    val backwardSearchSegments: Int = 8,
    val forwardSearchSegments: Int = 60,
    val backwardsToleranceMeters: Double = 25.0,
    val backwardsPenaltyMultiplier: Double = 2.5,
    val maximumHeadingPenaltyMeters: Double = 55.0,
    val headingMinimumSpeedMetersPerSecond: Double = 2.0,
)

/** Projects fixes onto a local route window and penalizes wrong direction/backtracking. */
class RouteMatcher(
    routeGeometry: List<Coordinate>,
    private val policy: RouteMatcherPolicy = RouteMatcherPolicy(),
) {
    private val geometry = routeGeometry.toList()
    private val cumulativeDistances = DoubleArray(geometry.size)
    private var previousMatch: RouteMatch? = null

    init {
        require(geometry.size >= 2) { "route matcher needs at least two geometry points" }
        for (index in 1 until geometry.size) {
            cumulativeDistances[index] = cumulativeDistances[index - 1] +
                distanceMeters(geometry[index - 1], geometry[index])
        }
    }

    fun reset() {
        previousMatch = null
    }

    fun match(location: NavigationLocation): RouteMatch {
        val previous = previousMatch
        val firstSegment = previous?.let {
            (it.segmentIndex - policy.backwardSearchSegments).coerceAtLeast(0)
        } ?: 0
        val lastSegment = previous?.let {
            (it.segmentIndex + policy.forwardSearchSegments).coerceAtMost(geometry.lastIndex - 1)
        } ?: geometry.lastIndex - 1

        var best: RouteMatch? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (segmentIndex in firstSegment..lastSegment) {
            val candidate = project(location.coordinate, segmentIndex)
            var score = candidate.distanceFromRouteMeters
            val speed = location.speedMetersPerSecond ?: 0.0
            val heading = location.bearingDegrees
            if (speed >= policy.headingMinimumSpeedMetersPerSecond && heading != null) {
                score += bearingDifference(heading, candidate.segmentBearingDegrees) / 180.0 *
                    policy.maximumHeadingPenaltyMeters
            }
            if (previous != null) {
                val backwards = previous.distanceAlongGeometryMeters -
                    candidate.distanceAlongGeometryMeters - policy.backwardsToleranceMeters
                if (backwards > 0.0) score += backwards * policy.backwardsPenaltyMultiplier
            }
            if (score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        val selected = requireNotNull(best)
        val result = selected.copy(
            headingDifferenceDegrees = location.bearingDegrees?.let {
                bearingDifference(it, selected.segmentBearingDegrees)
            },
            progressDeltaMeters = previous?.let {
                selected.distanceAlongGeometryMeters - it.distanceAlongGeometryMeters
            } ?: 0.0,
        )
        previousMatch = result
        return result
    }

    fun distanceAlongRoute(coordinate: Coordinate): Double {
        var nearest: RouteMatch? = null
        for (segmentIndex in 0 until geometry.lastIndex) {
            val projected = project(coordinate, segmentIndex)
            if (nearest == null || projected.distanceFromRouteMeters < nearest.distanceFromRouteMeters) {
                nearest = projected
            }
        }
        return requireNotNull(nearest).distanceAlongGeometryMeters
    }

    fun distanceAtShapeIndex(index: Int): Double = cumulativeDistances[index.coerceIn(0, geometry.lastIndex)]

    val geometryLengthMeters: Double get() = cumulativeDistances.last()

    private fun project(point: Coordinate, segmentIndex: Int): RouteMatch {
        val start = geometry[segmentIndex]
        val end = geometry[segmentIndex + 1]
        val referenceLatitudeRadians = ((start.latitude + end.latitude + point.latitude) / 3).toRadians()
        fun Coordinate.xy(): Pair<Double, Double> = Pair(
            longitude.toRadians() * cos(referenceLatitudeRadians) * EARTH_RADIUS_METERS,
            latitude.toRadians() * EARTH_RADIUS_METERS,
        )
        val (startX, startY) = start.xy()
        val (endX, endY) = end.xy()
        val (pointX, pointY) = point.xy()
        val dx = endX - startX
        val dy = endY - startY
        val squaredLength = dx * dx + dy * dy
        val fraction = if (squaredLength == 0.0) {
            0.0
        } else {
            ((pointX - startX) * dx + (pointY - startY) * dy) / squaredLength
        }.coerceIn(0.0, 1.0)
        val projectedX = startX + fraction * dx
        val projectedY = startY + fraction * dy
        val snapped = Coordinate(
            latitude = start.latitude + fraction * (end.latitude - start.latitude),
            longitude = start.longitude + fraction * (end.longitude - start.longitude),
        )
        val segmentLength = cumulativeDistances[segmentIndex + 1] - cumulativeDistances[segmentIndex]
        return RouteMatch(
            snappedCoordinate = snapped,
            segmentIndex = segmentIndex,
            segmentFraction = fraction,
            distanceFromRouteMeters = sqrt(
                (pointX - projectedX) * (pointX - projectedX) +
                    (pointY - projectedY) * (pointY - projectedY),
            ),
            distanceAlongGeometryMeters = cumulativeDistances[segmentIndex] + fraction * segmentLength,
            geometryLengthMeters = cumulativeDistances.last(),
            segmentBearingDegrees = bearingDegrees(start, end),
            headingDifferenceDegrees = null,
            progressDeltaMeters = 0.0,
        )
    }
}
