package org.compass.cng.ui.map

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import org.compass.cng.navigation.NavigationPosition
import org.compass.cng.navigation.NavigationPuckMotionConfig
import org.compass.cng.navigation.NavigationPuckPose
import org.compass.cng.navigation.NavigationPuckTransition
import org.compass.cng.navigation.NavigationPuckTransitionMode
import org.compass.cng.navigation.planNavigationPuckTransition

/** Android frame driver for the pure navigation-puck transition planner. */
internal class MapPuckAnimator(
    private val config: NavigationPuckMotionConfig = NavigationPuckMotionConfig(),
) {
    private var animator: ValueAnimator? = null
    private var displayedPose: NavigationPuckPose? = null
    private var previousTargetTimestampEpochMillis: Long? = null
    private var previousTarget: NavigationPosition? = null
    private var frameListener: ((NavigationPuckPose) -> Unit)? = null

    fun reset(position: NavigationPosition) {
        animator?.cancel()
        animator = null
        displayedPose = NavigationPuckPose(position.coordinate, position.bearingDegrees)
        previousTargetTimestampEpochMillis = position.timestampEpochMillis
        previousTarget = null
    }

    fun moveTo(
        position: NavigationPosition,
        onTransition: (NavigationPuckTransition) -> Unit = {},
        onFrame: (NavigationPuckPose) -> Unit,
    ) {
        frameListener = onFrame
        // UI timers and panel changes can deliver the same GPS fix repeatedly.
        // Keep the active frame animation instead of cancelling it and snapping.
        if (position == previousTarget) return
        if (previousTargetTimestampEpochMillis?.let { position.timestampEpochMillis < it } == true) return
        previousTarget = position
        animator?.cancel()
        val transition = planNavigationPuckTransition(
            displayedPose = displayedPose,
            previousTargetTimestampEpochMillis = previousTargetTimestampEpochMillis,
            targetPosition = position,
            config = config,
        )
        previousTargetTimestampEpochMillis = position.timestampEpochMillis
        onTransition(transition)
        when (transition.mode) {
            NavigationPuckTransitionMode.HOLD -> onFrame(transition.target)
            NavigationPuckTransitionMode.SNAP -> {
                displayedPose = transition.target
                onFrame(transition.target)
            }
            NavigationPuckTransitionMode.ANIMATE -> {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = transition.durationMillis
                    interpolator = LinearInterpolator()
                    addUpdateListener { valueAnimator ->
                        transition.poseAt(valueAnimator.animatedValue as Float).also { pose ->
                            displayedPose = pose
                            frameListener?.invoke(pose)
                        }
                    }
                    start()
                }
            }
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        previousTarget = null
        frameListener = null
    }
}
