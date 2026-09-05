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

    fun reset(position: NavigationPosition) {
        animator?.cancel()
        animator = null
        displayedPose = NavigationPuckPose(position.coordinate, position.bearingDegrees)
        previousTargetTimestampEpochMillis = position.timestampEpochMillis
    }

    fun moveTo(
        position: NavigationPosition,
        onTransition: (NavigationPuckTransition) -> Unit = {},
        onFrame: (NavigationPuckPose) -> Unit,
    ) {
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
            NavigationPuckTransitionMode.HOLD -> return
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
                            onFrame(pose)
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
    }
}
