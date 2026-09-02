package org.compass.cng.navigation

import java.time.Instant

/** Deterministic fixture runner for complete navigation sessions without live GPS or a server. */
class NavigationReplay(
    private val engine: NavigationEngine = NavigationEngine(),
) {
    fun run(route: NavigationRoute, locations: List<NavigationLocation>): List<NavigationState> {
        engine.preview(route)
        engine.start()
        return locations.map { location ->
            engine.updateLocation(
                rawLocation = location,
                now = Instant.ofEpochMilli(location.timestampEpochMillis),
            )
            engine.state.value
        }
    }
}
