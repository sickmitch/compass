package org.compass.cng.ui.route

import android.content.res.Resources
import org.compass.cng.navigation.NavigationPhase

internal fun systemUsesGestureNavigation(resources: Resources): Boolean {
    val resourceId = resources.getIdentifier(
        "config_navBarInteractionMode",
        "integer",
        "android",
    )
    return resourceId != 0 && runCatching { resources.getInteger(resourceId) }.getOrNull() == 2
}

internal fun shouldShowHeaderBackButton(
    canNavigateBack: Boolean,
    systemUsesGestures: Boolean,
): Boolean = canNavigateBack && !systemUsesGestures

internal fun shouldStopNavigationOnBack(
    stage: PlannerStage,
    phase: NavigationPhase,
): Boolean = stage == PlannerStage.NAVIGATION_PREVIEW &&
    phase != NavigationPhase.ROUTE_PREVIEW && phase != NavigationPhase.IDLE
