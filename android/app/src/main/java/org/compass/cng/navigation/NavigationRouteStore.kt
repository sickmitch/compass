package org.compass.cng.navigation

data class CachedNavigationRoute(
    val route: NavigationRoute,
    val cachedAtEpochMillis: Long,
    val navigationWasActive: Boolean,
)

interface NavigationRouteStore {
    fun load(): CachedNavigationRoute?

    fun save(route: NavigationRoute, navigationWasActive: Boolean)

    fun clear()
}

object NoOpNavigationRouteStore : NavigationRouteStore {
    override fun load(): CachedNavigationRoute? = null

    override fun save(route: NavigationRoute, navigationWasActive: Boolean) = Unit

    override fun clear() = Unit
}
