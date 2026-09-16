package org.compass.cng.navigation

import java.time.Clock
import java.time.OffsetDateTime
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RouteOriginDirection
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.SelectedCngStop
import org.compass.cng.domain.model.withNavigationDetailsFrom

sealed interface FuelStopReplacementResult {
    data class Replaced(
        val route: NavigationRoute,
        val excludedMimitStationId: String,
    ) : FuelStopReplacementResult

    data object NoSafeAlternative : FuelStopReplacementResult
    data object RangePlanRequired : FuelStopReplacementResult
}

interface NavigationRouteRecalculator {
    suspend fun recalculate(
        state: NavigationState,
        reason: RouteUpdateReason,
    ): NavigationRoute

    suspend fun replaceUnavailableFuelStop(
        state: NavigationState,
    ): FuelStopReplacementResult

    suspend fun removeNextStop(state: NavigationState): NavigationRoute
}

data class NavigationRerouteDirectionPolicy(
    val minimumSpeedMetersPerSecond: Double = 4.0,
    val headingToleranceDegrees: Int = 45,
) {
    init {
        require(minimumSpeedMetersPerSecond.isFinite() && minimumSpeedMetersPerSecond >= 0.0)
        require(headingToleranceDegrees in 0..180)
    }
}

/** Re-enters Compass for every reroute so Valhalla, traffic and the CNG plan stay authoritative. */
class CompassNavigationRouteRecalculator(
    private val routingRepository: RoutingRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val directionPolicy: NavigationRerouteDirectionPolicy = NavigationRerouteDirectionPolicy(),
) : NavigationRouteRecalculator {
    override suspend fun recalculate(
        state: NavigationState,
        reason: RouteUpdateReason,
    ): NavigationRoute {
        val route = requireNotNull(state.route)
        val origin = when (reason) {
            RouteUpdateReason.OFF_ROUTE,
            RouteUpdateReason.MANUAL_DEBUG,
            -> state.rawLocation?.coordinate ?: state.snappedLocation
            RouteUpdateReason.TRAFFIC_REFRESH,
            RouteUpdateReason.CONNECTIVITY_RECOVERY,
            RouteUpdateReason.FUEL_STOP_UNAVAILABLE,
            -> state.snappedLocation ?: state.rawLocation?.coordinate
        } ?: route.origin
        val originDirection = state.originDirectionFor(reason)
        val remainingStops = remainingFuelStops(state)
        val recalculated = try {
            preserveRemainingPlan(route, state, origin, remainingStops, originDirection)
        } catch (error: RoutePreviewException) {
            if (error.failure !in FUEL_PLAN_INVALIDATING_FAILURES || route.fuelPlan == null) {
                throw error
            }
            replanInvalidFuelStops(route, state, origin, remainingStops, originDirection)
        }
        return recalculated.withFuelStopDetailsFrom(route.fuelStops)
    }

    override suspend fun removeNextStop(state: NavigationState): NavigationRoute {
        val route = requireNotNull(state.route)
        val nextFuelSequence = state.nextFuelStop?.stop?.sequence
        val nextIntermediateSequence = state.nextIntermediateStop?.stop?.sequence
        require(nextFuelSequence != null || nextIntermediateSequence != null) {
            "navigation route has no removable stop"
        }
        val removeFuel = when {
            nextFuelSequence == null -> false
            nextIntermediateSequence == null -> true
            else -> nextFuelSequence < nextIntermediateSequence
        }
        val remainingFuelStops = remainingFuelStops(state).filterNot {
            removeFuel && it.sequence == nextFuelSequence
        }
        val remainingIntermediateStops = remainingIntermediateStops(state).filterNot {
            !removeFuel && it.stop.sequence == nextIntermediateSequence
        }
        val origin = state.rawLocation?.coordinate ?: state.snappedLocation ?: route.origin
        return preserveRemainingPlan(
            route = route,
            state = state,
            origin = origin,
            remainingStops = remainingFuelStops,
            originDirection = null,
            remainingIntermediateStops = remainingIntermediateStops,
        ).withFuelStopDetailsFrom(route.fuelStops)
    }

    private suspend fun preserveRemainingPlan(
        route: NavigationRoute,
        state: NavigationState,
        origin: Coordinate,
        remainingStops: List<NavigationFuelStop>,
        originDirection: RouteOriginDirection?,
        remainingIntermediateStops: List<NavigationIntermediateStopProgress> =
            remainingIntermediateStops(state),
    ): NavigationRoute {
        if (remainingIntermediateStops.isNotEmpty()) {
            val orderedStops = (
                remainingIntermediateStops.map { progress ->
                    RerouteStop(
                        sequence = progress.stop.sequence,
                        location = progress.stop.location,
                        metadata = NavigationOrderedStop(label = progress.stop.mapLabel),
                    )
                } + remainingStops.map { stop ->
                    RerouteStop(
                        sequence = stop.sequence,
                        location = stop.location,
                        metadata = NavigationOrderedStop(
                            label = stop.name ?: "Sosta CNG",
                            cngStop = stop.toSelectedCngStop(),
                        ),
                    )
                }
            ).sortedBy(RerouteStop::sequence)
            return routingRepository.routeWithIntermediateStops(
                origin = origin,
                intermediateStops = orderedStops.map(RerouteStop::location),
                destination = route.destination,
                originDirection = originDirection,
            ).toNavigationRoute(orderedStops.map(RerouteStop::metadata))
        }
        return when (remainingStops.size) {
            0 -> routingRepository.previewRoute(
                origin = origin,
                destination = route.destination,
                originDirection = originDirection,
            ).toNavigationRoute(
                gasolineFallback = route.gasolineFallback,
            )
            1 -> route.fuelPlan?.let { plan ->
                val remainingRange = remainingRangeAtProgress(route, state, plan)
                routingRepository.routeWithCngItinerary(
                    origin = origin,
                    destination = route.destination,
                    mimitStationIds = listOf(remainingStops.single().mimitStationId),
                    effectiveCngRangeKm = plan.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = remainingRange,
                    reserveCngRangeKm = plan.reserveCngRangeKm,
                    originDirection = originDirection,
                ).toNavigationRoute(
                    maximumDetourMinutes = plan.maximumDetourMinutes,
                    excludedMimitStationIds = plan.excludedMimitStationIds,
                )
            } ?: routingRepository.routeWithCngStop(
                    origin = origin,
                    destination = route.destination,
                    mimitStationId = remainingStops.single().mimitStationId,
                    originDirection = originDirection,
                ).toNavigationRoute()
            else -> {
                val plan = requireNotNull(route.fuelPlan) {
                    "multi-stop rerouting requires the original fuel range plan"
                }
                val remainingRange = remainingRangeAtProgress(route, state, plan)
                routingRepository.routeWithCngItinerary(
                    origin = origin,
                    destination = route.destination,
                    mimitStationIds = remainingStops.map(NavigationFuelStop::mimitStationId),
                    effectiveCngRangeKm = plan.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = remainingRange,
                    reserveCngRangeKm = plan.reserveCngRangeKm,
                    originDirection = originDirection,
                ).toNavigationRoute(
                    maximumDetourMinutes = plan.maximumDetourMinutes,
                    excludedMimitStationIds = plan.excludedMimitStationIds,
                )
            }
        }
    }

    private fun remainingIntermediateStops(
        state: NavigationState,
    ): List<NavigationIntermediateStopProgress> = if (state.intermediateStopProgress.isNotEmpty()) {
        state.intermediateStopProgress
            .filter { it.lifecycle != NavigationIntermediateStopLifecycle.COMPLETED }
            .sortedBy { it.stop.sequence }
    } else {
        listOfNotNull(state.nextIntermediateStop)
    }

    private fun NavigationFuelStop.toSelectedCngStop(): SelectedCngStop = SelectedCngStop(
        mimitStationId = mimitStationId,
        name = name,
        municipality = municipality,
        province = province,
        location = location,
        expectedArrivalAt = expectedArrivalAt,
        dwellTimeSeconds = dwellTimeSeconds,
        opening = opening,
        phone = phone,
        brand = brand,
        operator = operator,
        price = price,
    )

    private suspend fun replanInvalidFuelStops(
        route: NavigationRoute,
        state: NavigationState,
        origin: Coordinate,
        invalidStops: List<NavigationFuelStop>,
        originDirection: RouteOriginDirection?,
    ): NavigationRoute {
        val plan = requireNotNull(route.fuelPlan)
        val maximumDetourMinutes = requireNotNull(plan.maximumDetourMinutes) {
            "automatic fuel replanning requires the original detour policy"
        }
        val remainingRange = remainingRangeAtProgress(route, state, plan)
        val excludedIds = plan.excludedMimitStationIds +
            invalidStops.map(NavigationFuelStop::mimitStationId)
        val suggestion = routingRepository.predictiveCngStations(
            origin = origin,
            destination = route.destination,
            effectiveCngRangeKm = plan.effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = remainingRange,
            reserveCngRangeKm = plan.reserveCngRangeKm,
            maximumDetourMinutes = maximumDetourMinutes,
            departureAt = OffsetDateTime.now(clock),
            excludedMimitStationIds = excludedIds,
            originDirection = originDirection,
        )
        return when (suggestion.state) {
            PredictiveSuggestionState.NOT_NEEDED -> routingRepository.previewRoute(
                origin = origin,
                destination = route.destination,
                originDirection = originDirection,
            ).toNavigationRoute()
            PredictiveSuggestionState.SUGGESTED -> {
                val itinerary = requireNotNull(suggestion.itinerary)
                val routed = routingRepository.routeWithCngItinerary(
                    origin = origin,
                    destination = route.destination,
                    mimitStationIds = itinerary.stops.map { it.station.mimitStationId },
                    effectiveCngRangeKm = plan.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = remainingRange,
                    reserveCngRangeKm = plan.reserveCngRangeKm,
                    originDirection = originDirection,
                )
                routed.copy(
                    selectedStops = routed.selectedStops.map { selectedStop ->
                        itinerary.stops.firstOrNull {
                            it.station.mimitStationId == selectedStop.mimitStationId
                        }?.let(selectedStop::withNavigationDetailsFrom) ?: selectedStop
                    },
                ).toNavigationRoute(
                    maximumDetourMinutes = maximumDetourMinutes,
                    excludedMimitStationIds = excludedIds,
                )
            }
            else -> throw RoutePreviewException(RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE)
        }
    }

    private fun NavigationState.originDirectionFor(
        reason: RouteUpdateReason,
    ): RouteOriginDirection? {
        if (reason != RouteUpdateReason.OFF_ROUTE) return null
        val location = rawLocation ?: return null
        val speed = location.speedMetersPerSecond ?: return null
        val bearing = location.bearingDegrees ?: return null
        if (speed < directionPolicy.minimumSpeedMetersPerSecond || !bearing.isFinite()) return null
        val normalizedBearing = ((bearing % 360.0) + 360.0) % 360.0
        return RouteOriginDirection(
            headingDegrees = normalizedBearing,
            headingToleranceDegrees = directionPolicy.headingToleranceDegrees,
        )
    }

    override suspend fun replaceUnavailableFuelStop(
        state: NavigationState,
    ): FuelStopReplacementResult {
        val route = requireNotNull(state.route)
        val plan = route.fuelPlan
        val maximumDetourMinutes = plan?.maximumDetourMinutes
        if (plan == null || maximumDetourMinutes == null) {
            return FuelStopReplacementResult.RangePlanRequired
        }
        val unavailableStop = state.nextFuelStop?.stop
            ?: return FuelStopReplacementResult.NoSafeAlternative
        val origin = state.snappedLocation ?: state.rawLocation?.coordinate ?: route.origin
        val remainingRange = remainingRangeAtProgress(route, state, plan)
        val excludedIds = plan.excludedMimitStationIds + unavailableStop.mimitStationId
        val suggestion = routingRepository.predictiveCngStations(
            origin = origin,
            destination = route.destination,
            effectiveCngRangeKm = plan.effectiveCngRangeKm,
            estimatedRemainingCngRangeKm = remainingRange,
            reserveCngRangeKm = plan.reserveCngRangeKm,
            maximumDetourMinutes = maximumDetourMinutes,
            departureAt = OffsetDateTime.now(clock),
            excludedMimitStationIds = excludedIds,
        )
        val replacement = when (suggestion.state) {
            PredictiveSuggestionState.NOT_NEEDED -> routingRepository.previewRoute(
                origin,
                route.destination,
            ).toNavigationRoute()
            PredictiveSuggestionState.SUGGESTED -> {
                val itinerary = requireNotNull(suggestion.itinerary)
                val routed = routingRepository.routeWithCngItinerary(
                    origin = origin,
                    destination = route.destination,
                    mimitStationIds = itinerary.stops.map { it.station.mimitStationId },
                    effectiveCngRangeKm = plan.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = remainingRange,
                    reserveCngRangeKm = plan.reserveCngRangeKm,
                )
                routed.copy(
                    selectedStops = routed.selectedStops.map { selectedStop ->
                        itinerary.stops.firstOrNull {
                            it.station.mimitStationId == selectedStop.mimitStationId
                        }?.let(selectedStop::withNavigationDetailsFrom) ?: selectedStop
                    },
                ).toNavigationRoute(
                    maximumDetourMinutes = maximumDetourMinutes,
                    excludedMimitStationIds = excludedIds,
                )
            }
            PredictiveSuggestionState.NO_REACHABLE_STATION,
            PredictiveSuggestionState.NO_ELIGIBLE_STATION,
            PredictiveSuggestionState.NO_COMPLETE_ITINERARY,
            PredictiveSuggestionState.GASOLINE_FALLBACK,
            -> return FuelStopReplacementResult.NoSafeAlternative
        }
        return FuelStopReplacementResult.Replaced(
            route = replacement.withFuelStopDetailsFrom(route.fuelStops),
            excludedMimitStationId = unavailableStop.mimitStationId,
        )
    }

    private fun NavigationRoute.withFuelStopDetailsFrom(
        previousStops: List<NavigationFuelStop>,
    ): NavigationRoute {
        val previousById = previousStops.associateBy(NavigationFuelStop::mimitStationId)
        return copy(
            fuelStops = fuelStops.map { stop ->
                val previous = previousById[stop.mimitStationId] ?: return@map stop
                stop.copy(
                    opening = stop.opening ?: previous.opening,
                    phone = stop.phone ?: previous.phone,
                    brand = stop.brand ?: previous.brand,
                    operator = stop.operator ?: previous.operator,
                    price = stop.price ?: previous.price,
                )
            },
        )
    }

    private fun remainingFuelStops(state: NavigationState): List<NavigationFuelStop> {
        val route = requireNotNull(state.route)
        val nextId = state.nextFuelStop?.stop?.mimitStationId ?: return emptyList()
        val index = route.fuelStops.indexOfFirst { it.mimitStationId == nextId }
        return if (index < 0) emptyList() else route.fuelStops.drop(index)
    }

    private fun remainingRangeAtProgress(
        route: NavigationRoute,
        state: NavigationState,
        plan: NavigationFuelPlan,
    ): Double {
        val drivenMeters = route.totalDistanceMeters * state.routeProgressFraction
        var beforeLegMeters = 0.0
        val leg = route.legs.firstOrNull {
            val contains = drivenMeters <= beforeLegMeters + it.distanceMeters
            if (!contains) beforeLegMeters += it.distanceMeters
            contains
        } ?: route.legs.last()
        val available = leg.availableRangeAtDepartureKm ?: plan.initialRemainingCngRangeKm
        val consumedKm = ((drivenMeters - beforeLegMeters) / 1_000).coerceAtLeast(0.0)
        return (available - consumedKm).coerceIn(
            plan.reserveCngRangeKm + MINIMUM_RANGE_MARGIN_KM,
            plan.effectiveCngRangeKm,
        )
    }

    private data class RerouteStop(
        val sequence: Int,
        val location: Coordinate,
        val metadata: NavigationOrderedStop,
    )

    private companion object {
        const val MINIMUM_RANGE_MARGIN_KM = 0.1
        val FUEL_PLAN_INVALIDATING_FAILURES = setOf(
            RoutePreviewFailure.STATION_NOT_FOUND,
            RoutePreviewFailure.STATION_UNAVAILABLE,
            RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE,
        )
    }
}
