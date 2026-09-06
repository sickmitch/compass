package org.compass.cng.ui.route

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.LocalContentColor
import kotlin.math.sqrt

/** Vector maneuver glyphs that remain crisp and theme-aware at every density. */
@Composable
internal fun ManeuverIcon(
    visual: ManeuverVisual,
    modifier: Modifier = Modifier,
) {
    val color = LocalContentColor.current
    val typeTag = visual.type?.toString() ?: "unknown"
    Canvas(
        modifier = modifier
            .testTag("maneuver_icon_$typeTag")
            .semantics { contentDescription = visual.accessibilityLabel },
    ) {
        when (visual.family) {
            ManeuverVisualFamily.UNKNOWN -> drawUnknown(color)
            ManeuverVisualFamily.START -> drawStart(visual.direction, color)
            ManeuverVisualFamily.DESTINATION -> drawDestination(visual.direction, color)
            ManeuverVisualFamily.STRAIGHT,
            ManeuverVisualFamily.TURN,
            ManeuverVisualFamily.U_TURN,
            -> drawDirectionalRoute(visual.direction, color)
            ManeuverVisualFamily.RAMP -> drawFork(
                visual.direction,
                color,
                branchAlpha = 0.32f,
                selectedReach = 0.31f,
            )
            ManeuverVisualFamily.EXIT -> drawExit(visual.direction, color)
            ManeuverVisualFamily.KEEP -> drawFork(
                visual.direction,
                color,
                branchAlpha = 0.42f,
                selectedReach = 0.25f,
            )
            ManeuverVisualFamily.MERGE -> drawMerge(color)
            ManeuverVisualFamily.ROUNDABOUT_ENTER -> drawRoundabout(color, exiting = false)
            ManeuverVisualFamily.ROUNDABOUT_EXIT -> drawRoundabout(color, exiting = true)
            ManeuverVisualFamily.FERRY_ENTER -> drawFerry(color, entering = true)
            ManeuverVisualFamily.FERRY_EXIT -> drawFerry(color, entering = false)
            ManeuverVisualFamily.TRANSIT -> drawTransit(color, transfer = false, remain = false)
            ManeuverVisualFamily.TRANSIT_TRANSFER -> drawTransit(color, transfer = true, remain = false)
            ManeuverVisualFamily.TRANSIT_REMAIN -> drawTransit(color, transfer = false, remain = true)
            ManeuverVisualFamily.TRANSIT_CONNECTION_START -> drawTransitConnectionStart(color)
            ManeuverVisualFamily.TRANSIT_CONNECTION_TRANSFER -> drawTransitConnectionTransfer(color)
            ManeuverVisualFamily.TRANSIT_CONNECTION_DESTINATION -> drawTransitConnectionDestination(color, postTransit = false)
            ManeuverVisualFamily.POST_TRANSIT_CONNECTION_DESTINATION -> drawTransitConnectionDestination(color, postTransit = true)
        }
    }
}

private data class RouteStroke(
    val path: Path,
    val tip: Offset,
    val previous: Offset,
)

internal data class BranchingManeuverGeometry(
    val trunkX: Float,
    val junctionX: Float,
    val selectedPreviousX: Float,
    val selectedTipX: Float,
    val alternativeTipX: Float,
    val selectedPreviousY: Float,
    val selectedTipY: Float,
) {
    val selectedTerminalLength: Float
        get() = sqrt(
            ((selectedTipX - selectedPreviousX) * (selectedTipX - selectedPreviousX)) +
                ((selectedTipY - selectedPreviousY) * (selectedTipY - selectedPreviousY)),
        )
}

internal data class SlightTurnGeometry(
    val startX: Float,
    val curveExitX: Float,
    val tipX: Float,
    val curveExitY: Float = 0.42f,
    val tipY: Float = 0.18f,
) {
    val terminalLength: Float
        get() = sqrt(
            ((tipX - curveExitX) * (tipX - curveExitX)) +
                ((tipY - curveExitY) * (tipY - curveExitY)),
        )
}

internal fun slightTurnGeometry(direction: ManeuverDirection): SlightTurnGeometry {
    require(
        direction == ManeuverDirection.SLIGHT_LEFT ||
            direction == ManeuverDirection.SLIGHT_RIGHT,
    )
    val side = if (direction == ManeuverDirection.SLIGHT_RIGHT) 1f else -1f
    return SlightTurnGeometry(
        startX = 0.50f,
        curveExitX = 0.50f + (side * 0.11f),
        tipX = 0.50f + (side * 0.23f),
    )
}

internal fun branchingManeuverGeometry(
    direction: ManeuverDirection,
    selectedReach: Float,
    alternativeStraight: Boolean = false,
): BranchingManeuverGeometry {
    require(direction == ManeuverDirection.LEFT || direction == ManeuverDirection.RIGHT)
    val side = if (direction == ManeuverDirection.RIGHT) 1f else -1f
    val trunkX = 0.50f + (side * 0.06f)
    return BranchingManeuverGeometry(
        trunkX = trunkX,
        junctionX = trunkX,
        selectedPreviousX = 0.50f + (side * (selectedReach * 0.58f)),
        selectedTipX = 0.50f + (side * selectedReach),
        alternativeTipX = if (alternativeStraight) 0.50f else 0.50f - (side * 0.14f),
        selectedPreviousY = if (alternativeStraight) 0.44f else 0.42f,
        selectedTipY = if (alternativeStraight) 0.24f else 0.18f,
    )
}

private fun DrawScope.point(x: Float, y: Float): Offset =
    Offset(size.width * x, size.height * y)

private fun DrawScope.routeStrokeWidth(): Float = size.minDimension * 0.105f

private fun DrawScope.arrowLength(): Float = size.minDimension * 0.20f

private fun DrawScope.routeStroke(direction: ManeuverDirection): RouteStroke {
    val path = Path()
    return when (direction) {
        ManeuverDirection.SLIGHT_RIGHT,
        ManeuverDirection.SLIGHT_LEFT,
        -> {
            val geometry = slightTurnGeometry(direction)
            path.moveTo(size.width * geometry.startX, size.height * 0.86f)
            path.lineTo(size.width * geometry.startX, size.height * 0.60f)
            path.cubicTo(
                size.width * geometry.startX,
                size.height * 0.51f,
                size.width * (geometry.startX + ((geometry.curveExitX - geometry.startX) * 0.55f)),
                size.height * 0.46f,
                size.width * geometry.curveExitX,
                size.height * geometry.curveExitY,
            )
            path.lineTo(size.width * geometry.tipX, size.height * geometry.tipY)
            RouteStroke(
                path,
                point(geometry.tipX, geometry.tipY),
                point(geometry.curveExitX, geometry.curveExitY),
            )
        }
        ManeuverDirection.RIGHT -> {
            path.moveTo(size.width * 0.34f, size.height * 0.84f)
            path.lineTo(size.width * 0.34f, size.height * 0.47f)
            path.quadraticTo(
                size.width * 0.34f,
                size.height * 0.39f,
                size.width * 0.43f,
                size.height * 0.39f,
            )
            path.lineTo(size.width * 0.78f, size.height * 0.39f)
            RouteStroke(path, point(0.78f, 0.39f), point(0.57f, 0.39f))
        }
        ManeuverDirection.SHARP_RIGHT -> {
            path.moveTo(size.width * 0.32f, size.height * 0.84f)
            path.lineTo(size.width * 0.32f, size.height * 0.45f)
            path.quadraticTo(
                size.width * 0.32f,
                size.height * 0.36f,
                size.width * 0.43f,
                size.height * 0.41f,
            )
            path.lineTo(size.width * 0.77f, size.height * 0.66f)
            RouteStroke(path, point(0.77f, 0.66f), point(0.59f, 0.53f))
        }
        ManeuverDirection.U_TURN_RIGHT -> uTurnStroke(right = true)
        ManeuverDirection.U_TURN_LEFT -> uTurnStroke(right = false)
        ManeuverDirection.SHARP_LEFT -> mirror(routeStroke(ManeuverDirection.SHARP_RIGHT))
        ManeuverDirection.LEFT -> mirror(routeStroke(ManeuverDirection.RIGHT))
        ManeuverDirection.NONE,
        ManeuverDirection.STRAIGHT,
        -> {
            path.moveTo(size.width * 0.50f, size.height * 0.84f)
            path.lineTo(size.width * 0.50f, size.height * 0.18f)
            RouteStroke(path, point(0.50f, 0.18f), point(0.50f, 0.40f))
        }
    }
}

private fun DrawScope.uTurnStroke(right: Boolean): RouteStroke {
    val direction = if (right) 1f else -1f
    val startX = 0.50f - (direction * 0.13f)
    val endX = 0.50f + (direction * 0.16f)
    val path = Path().apply {
        moveTo(size.width * startX, size.height * 0.84f)
        lineTo(size.width * startX, size.height * 0.43f)
        cubicTo(
            size.width * startX,
            size.height * 0.18f,
            size.width * endX,
            size.height * 0.18f,
            size.width * endX,
            size.height * 0.43f,
        )
        lineTo(size.width * endX, size.height * 0.68f)
    }
    return RouteStroke(path, point(endX, 0.68f), point(endX, 0.47f))
}

private fun DrawScope.mirror(stroke: RouteStroke): RouteStroke {
    val mirrored = Path()
    val source = stroke.path
    val matrix = androidx.compose.ui.graphics.Matrix().apply {
        translate(size.width, 0f)
        scale(-1f, 1f, 1f)
    }
    mirrored.addPath(source)
    mirrored.transform(matrix)
    return RouteStroke(
        path = mirrored,
        tip = Offset(size.width - stroke.tip.x, stroke.tip.y),
        previous = Offset(size.width - stroke.previous.x, stroke.previous.y),
    )
}

private fun DrawScope.drawDirectionalRoute(
    direction: ManeuverDirection,
    color: Color,
    withArrow: Boolean = true,
    arrowColor: Color = color,
) {
    val stroke = routeStroke(direction)
    drawRouteStroke(stroke, color, withArrow, arrowColor)
}

private fun DrawScope.drawRouteStroke(
    stroke: RouteStroke,
    color: Color,
    withArrow: Boolean = true,
    arrowColor: Color = color,
) {
    val visiblePath = if (withArrow) {
        stroke.path.withTrimmedEnd(arrowLength())
    } else {
        stroke.path
    }
    drawPath(
        path = visiblePath,
        color = color,
        // The visible path ends at the arrow base. A shaft painted through the taper would turn
        // the final pixels of the triangle into a thin spike even with a butt terminal.
        style = Stroke(routeStrokeWidth(), cap = StrokeCap.Butt, join = StrokeJoin.Round),
    )
    if (withArrow) drawArrowHead(stroke.tip, stroke.tip - stroke.previous, arrowColor)
}

private fun Path.withTrimmedEnd(distance: Float): Path {
    val measure = PathMeasure()
    measure.setPath(this, false)
    val stopDistance = (measure.length - distance).coerceAtLeast(0f)
    return Path().also { destination ->
        measure.getSegment(0f, stopDistance, destination, true)
    }
}

private fun DrawScope.drawArrowHead(tip: Offset, vector: Offset, color: Color) {
    val magnitude = sqrt((vector.x * vector.x) + (vector.y * vector.y)).coerceAtLeast(0.001f)
    val unit = Offset(vector.x / magnitude, vector.y / magnitude)
    val perpendicular = Offset(-unit.y, unit.x)
    val arrowLength = arrowLength()
    val arrowHalfWidth = size.minDimension * 0.105f
    val base = tip - (unit * arrowLength)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(
            base.x + (perpendicular.x * arrowHalfWidth),
            base.y + (perpendicular.y * arrowHalfWidth),
        )
        lineTo(
            base.x - (perpendicular.x * arrowHalfWidth),
            base.y - (perpendicular.y * arrowHalfWidth),
        )
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStart(direction: ManeuverDirection, color: Color) {
    drawDirectionalRoute(direction, color)
    val start = when (direction) {
        ManeuverDirection.LEFT -> point(0.66f, 0.84f)
        ManeuverDirection.RIGHT -> point(0.34f, 0.84f)
        else -> point(0.50f, 0.84f)
    }
    drawCircle(color, radius = size.minDimension * 0.10f, center = start)
}

private fun DrawScope.drawDestination(direction: ManeuverDirection, color: Color) {
    val target = when (direction) {
        ManeuverDirection.RIGHT -> point(0.70f, 0.35f)
        ManeuverDirection.LEFT -> point(0.30f, 0.35f)
        else -> point(0.50f, 0.30f)
    }
    drawLine(
        color = color.copy(alpha = 0.52f),
        start = point(0.50f, 0.84f),
        end = target,
        strokeWidth = routeStrokeWidth() * 0.72f,
        cap = StrokeCap.Round,
    )
    val radius = size.minDimension * 0.16f
    val diamond = Path().apply {
        moveTo(target.x, target.y - radius)
        lineTo(target.x + radius, target.y)
        lineTo(target.x, target.y + radius)
        lineTo(target.x - radius, target.y)
        close()
    }
    drawPath(diamond, color)
}

private fun DrawScope.drawFork(
    direction: ManeuverDirection,
    color: Color,
    branchAlpha: Float,
    selectedReach: Float,
) {
    when (direction) {
        ManeuverDirection.LEFT,
        ManeuverDirection.RIGHT,
        -> drawSideBranch(
            direction = direction,
            color = color,
            branchAlpha = branchAlpha,
            selectedReach = selectedReach,
            alternativeStraight = false,
        )
        else -> {
            drawUnselectedBranch(
                start = point(0.50f, 0.55f),
                control = point(0.42f, 0.40f),
                end = point(0.29f, 0.20f),
                color = color.copy(alpha = branchAlpha),
            )
            drawUnselectedBranch(
                start = point(0.50f, 0.55f),
                control = point(0.58f, 0.40f),
                end = point(0.71f, 0.20f),
                color = color.copy(alpha = branchAlpha),
            )
            drawDirectionalRoute(ManeuverDirection.STRAIGHT, color)
        }
    }
}

private fun DrawScope.drawExit(direction: ManeuverDirection, color: Color) {
    val selected = if (direction == ManeuverDirection.LEFT) {
        ManeuverDirection.LEFT
    } else {
        ManeuverDirection.RIGHT
    }
    drawSideBranch(
        direction = selected,
        color = color,
        branchAlpha = 0.35f,
        selectedReach = 0.34f,
        alternativeStraight = true,
    )
}

private fun DrawScope.drawSideBranch(
    direction: ManeuverDirection,
    color: Color,
    branchAlpha: Float,
    selectedReach: Float,
    alternativeStraight: Boolean,
) {
    val geometry = branchingManeuverGeometry(
        direction = direction,
        selectedReach = selectedReach,
        alternativeStraight = alternativeStraight,
    )
    val selectedTip = point(geometry.selectedTipX, geometry.selectedTipY)
    val selectedPrevious = point(
        geometry.selectedPreviousX,
        geometry.selectedPreviousY,
    )
    val junction = point(geometry.junctionX, 0.55f)
    drawUnselectedBranch(
        start = junction,
        control = point(
            (geometry.junctionX + geometry.alternativeTipX) * 0.5f,
            0.40f,
        ),
        end = point(geometry.alternativeTipX, 0.18f),
        color = color.copy(alpha = branchAlpha),
    )
    val selectedPath = Path().apply {
        moveTo(size.width * geometry.trunkX, size.height * 0.86f)
        lineTo(junction.x, junction.y)
        cubicTo(
            junction.x,
            size.height * 0.46f,
            selectedPrevious.x,
            size.height * 0.45f,
            selectedPrevious.x,
            selectedPrevious.y,
        )
        lineTo(selectedTip.x, selectedTip.y)
    }
    drawRouteStroke(
        RouteStroke(selectedPath, selectedTip, selectedPrevious),
        color,
    )
}

private fun DrawScope.drawUnselectedBranch(
    start: Offset,
    control: Offset,
    end: Offset,
    color: Color,
) {
    val branch = Path().apply {
        moveTo(start.x, start.y)
        quadraticTo(control.x, control.y, end.x, end.y)
    }
    drawPath(
        path = branch,
        color = color,
        style = Stroke(
            width = routeStrokeWidth() * 0.76f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun DrawScope.drawMerge(color: Color) {
    drawLine(color.copy(alpha = 0.48f), point(0.22f, 0.82f), point(0.50f, 0.52f), routeStrokeWidth(), StrokeCap.Round)
    drawLine(color, point(0.78f, 0.82f), point(0.50f, 0.52f), routeStrokeWidth(), StrokeCap.Round)
    val tip = point(0.50f, 0.18f)
    val direction = Offset(0f, -1f)
    drawLine(color, point(0.50f, 0.52f), arrowBase(tip, direction), routeStrokeWidth(), StrokeCap.Butt)
    drawArrowHead(tip, direction, color)
}

private fun DrawScope.drawRoundabout(color: Color, exiting: Boolean) {
    val diameter = size.minDimension * 0.48f
    val topLeft = point(0.26f, 0.25f)
    drawArc(
        color = color,
        startAngle = 70f,
        sweepAngle = 285f,
        useCenter = false,
        topLeft = topLeft,
        size = Size(diameter, diameter),
        style = Stroke(routeStrokeWidth(), cap = StrokeCap.Round),
    )
    drawLine(color, point(0.50f, 0.86f), point(0.50f, 0.72f), routeStrokeWidth(), StrokeCap.Round)
    val exitStart = point(0.69f, 0.34f)
    val exitTip = point(0.83f, 0.18f)
    val exitVector = exitTip - exitStart
    drawLine(
        if (exiting) color else color.copy(alpha = 0.45f),
        exitStart,
        arrowBase(exitTip, exitVector),
        routeStrokeWidth(),
        StrokeCap.Butt,
    )
    val arrowTip = if (exiting) exitTip else point(0.28f, 0.38f)
    val vector = if (exiting) exitVector else Offset(-0.7f, -0.3f)
    drawArrowHead(arrowTip, vector, color)
}

private fun DrawScope.drawFerry(color: Color, entering: Boolean) {
    val hull = Path().apply {
        moveTo(size.width * 0.20f, size.height * 0.55f)
        lineTo(size.width * 0.80f, size.height * 0.55f)
        lineTo(size.width * 0.68f, size.height * 0.72f)
        lineTo(size.width * 0.32f, size.height * 0.72f)
        close()
    }
    drawPath(hull, color)
    drawLine(color, point(0.36f, 0.53f), point(0.36f, 0.35f), routeStrokeWidth() * 0.7f, StrokeCap.Round)
    drawLine(color, point(0.36f, 0.35f), point(0.66f, 0.35f), routeStrokeWidth() * 0.7f, StrokeCap.Round)
    val tip = if (entering) point(0.50f, 0.18f) else point(0.82f, 0.82f)
    val vector = if (entering) Offset(0f, -1f) else Offset(1f, 0f)
    val waterlineEnd = if (entering) point(0.82f, 0.82f) else arrowBase(tip, vector)
    drawLine(color.copy(alpha = 0.55f), point(0.18f, 0.82f), waterlineEnd, routeStrokeWidth() * 0.45f, StrokeCap.Butt)
    drawArrowHead(tip, vector, color)
}

private fun DrawScope.drawTransit(color: Color, transfer: Boolean, remain: Boolean) {
    val stroke = Stroke(routeStrokeWidth() * 0.72f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color,
        topLeft = point(0.22f, 0.23f),
        size = Size(size.width * 0.56f, size.height * 0.50f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.08f),
        style = stroke,
    )
    drawLine(color, point(0.30f, 0.43f), point(0.70f, 0.43f), routeStrokeWidth() * 0.55f, StrokeCap.Round)
    drawCircle(color, size.minDimension * 0.055f, point(0.34f, 0.76f))
    drawCircle(color, size.minDimension * 0.055f, point(0.66f, 0.76f))
    when {
        transfer -> {
            drawArrowHead(point(0.86f, 0.18f), Offset(1f, 0f), color)
            drawArrowHead(point(0.14f, 0.88f), Offset(-1f, 0f), color)
        }
        remain -> drawCircle(color, size.minDimension * 0.055f, point(0.50f, 0.58f))
        else -> drawArrowHead(point(0.50f, 0.14f), Offset(0f, -1f), color)
    }
}

private fun DrawScope.drawTransitConnectionStart(color: Color) {
    drawDirectionalRoute(
        direction = ManeuverDirection.STRAIGHT,
        color = color.copy(alpha = 0.72f),
        arrowColor = color,
    )
    drawCircle(color, size.minDimension * 0.09f, point(0.50f, 0.76f))
}

private fun DrawScope.drawTransitConnectionTransfer(color: Color) {
    val arrowTip = point(0.71f, 0.31f)
    val arrowDirection = Offset(1f, -1f)
    drawLine(
        color.copy(alpha = 0.55f),
        point(0.20f, 0.76f),
        arrowBase(arrowTip, arrowDirection),
        routeStrokeWidth() * 0.72f,
        StrokeCap.Butt,
    )
    drawCircle(color, size.minDimension * 0.10f, point(0.20f, 0.76f))
    drawCircle(color, size.minDimension * 0.10f, point(0.80f, 0.24f))
    drawArrowHead(arrowTip, arrowDirection, color)
}

private fun DrawScope.arrowBase(tip: Offset, vector: Offset): Offset {
    val magnitude = sqrt((vector.x * vector.x) + (vector.y * vector.y)).coerceAtLeast(0.001f)
    val unit = Offset(vector.x / magnitude, vector.y / magnitude)
    return tip - (unit * arrowLength())
}

private fun DrawScope.drawTransitConnectionDestination(color: Color, postTransit: Boolean) {
    drawDestination(ManeuverDirection.NONE, color)
    if (postTransit) {
        drawCircle(
            color = color.copy(alpha = 0.68f),
            radius = size.minDimension * 0.07f,
            center = point(0.50f, 0.82f),
        )
    } else {
        drawLine(
            color = color.copy(alpha = 0.68f),
            start = point(0.26f, 0.78f),
            end = point(0.50f, 0.64f),
            strokeWidth = routeStrokeWidth() * 0.72f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawUnknown(color: Color) {
    drawCircle(
        color = color,
        radius = size.minDimension * 0.27f,
        center = point(0.50f, 0.50f),
        style = Stroke(routeStrokeWidth() * 0.72f),
    )
    drawLine(color, point(0.50f, 0.30f), point(0.50f, 0.56f), routeStrokeWidth() * 0.72f, StrokeCap.Round)
    drawCircle(color, size.minDimension * 0.045f, point(0.50f, 0.70f))
}
