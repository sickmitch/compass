package org.compass.cng.navigation

import java.time.Clock
import java.time.OffsetDateTime
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.PredictiveSuggestionState

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
}

/** Re-enters Compass for every reroute so Valhalla, traffic and the CNG plan stay authoritative. */
class CompassNavigationRouteRecalculator(
    private val routingRepository: RoutingRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : NavigationRouteRecalculator {
    override suspend fun recalculate(
        state: NavigationState,
        reason: RouteUpdateReason,
    ): NavigationRoute {
        val route = requireNotNull(state.route)
        val origin = when (reason) {
            RouteUpdateReason.OFF_ROUTE -> state.rawLocation?.coordinate ?: state.snappedLocation
            RouteUpdateReason.TRAFFIC_REFRESH,
            RouteUpdateReason.MANUAL_DEBUG,
            RouteUpdateReason.FUEL_STOP_UNAVAILABLE,
            -> state.snappedLocation ?: state.rawLocation?.coordinate
        } ?: route.origin
        val remainingStops = remainingFuelStops(state)
        return when (remainingStops.size) {
            0 -> routingRepository.previewRoute(origin, route.destination).toNavigationRoute(
                gasolineFallback = route.gasolineFallback,
            )
            1 -> routingRepository.routeWithCngStop(
                origin = origin,
                destination = route.destination,
                mimitStationId = remainingStops.single().mimitStationId,
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
                ).toNavigationRoute()
            }
        }
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
                routingRepository.routeWithCngItinerary(
                    origin = origin,
                    destination = route.destination,
                    mimitStationIds = itinerary.stops.map { it.station.mimitStationId },
                    effectiveCngRangeKm = plan.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = remainingRange,
                    reserveCngRangeKm = plan.reserveCngRangeKm,
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
            route = replacement,
            excludedMimitStationId = unavailableStop.mimitStationId,
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

    private companion object {
        const val MINIMUM_RANGE_MARGIN_KM = 0.1
    }
}
