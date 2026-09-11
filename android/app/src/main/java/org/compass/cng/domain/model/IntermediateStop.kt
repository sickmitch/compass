package org.compass.cng.domain.model

data class RouteWithIntermediateStop(
    val stop: Coordinate,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<RoutePreview>,
    val provider: String,
    val navigation: NavigationTiming,
) {
    init {
        require(legs.size == 2) { "an intermediate-stop route needs exactly two legs" }
        require(legs.first().origin != stop && legs.first().destination == stop)
        require(legs.last().origin == stop && legs.last().destination != stop)
    }

    fun asRoutePreview(): RoutePreview = RoutePreview(
        origin = legs.first().origin,
        destination = legs.last().destination,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        geometry = legs.first().geometry + legs.last().geometry.drop(1),
        maneuvers = emptyList(),
        provider = provider,
        navigation = navigation,
        speedLimits = emptyList(),
        speedLimitSource = legs.mapNotNull(RoutePreview::speedLimitSource).firstOrNull(),
    )
}

data class RouteWithIntermediateStops(
    val stops: List<Coordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<RoutePreview>,
    val provider: String,
    val navigation: NavigationTiming,
) {
    init {
        require(stops.isNotEmpty()) { "an intermediate-stops route needs at least one stop" }
        require(stops.size <= 8) { "an intermediate-stops route supports at most eight stops" }
        require(legs.size == stops.size + 1) {
            "an intermediate-stops route needs one more leg than stops"
        }
        val points = listOf(legs.first().origin) + stops + legs.last().destination
        legs.forEachIndexed { index, leg ->
            require(leg.origin == points[index] && leg.destination == points[index + 1]) {
                "intermediate-stop leg order does not match the stop order"
            }
        }
    }

    fun asRoutePreview(): RoutePreview = RoutePreview(
        origin = legs.first().origin,
        destination = legs.last().destination,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        geometry = legs.flatMapIndexed { index, leg ->
            if (index == 0) leg.geometry else leg.geometry.drop(1)
        },
        maneuvers = emptyList(),
        provider = provider,
        navigation = navigation,
        speedLimits = emptyList(),
        speedLimitSource = legs.mapNotNull(RoutePreview::speedLimitSource).firstOrNull(),
    )
}

fun RouteWithIntermediateStop.asMultiple(): RouteWithIntermediateStops =
    RouteWithIntermediateStops(
        stops = listOf(stop),
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        legs = legs,
        provider = provider,
        navigation = navigation,
    )
