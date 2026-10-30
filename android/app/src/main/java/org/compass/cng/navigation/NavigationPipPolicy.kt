package org.compass.cng.navigation

internal fun shouldOfferNavigationPip(navigationVisible: Boolean, phase: NavigationPhase): Boolean =
    navigationVisible && phase !in setOf(
        NavigationPhase.IDLE,
        NavigationPhase.ROUTE_PREVIEW,
        NavigationPhase.ARRIVED,
    )
