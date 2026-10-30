package org.compass.cng.ui.map

import android.graphics.PointF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.compass.cng.R
import org.compass.cng.navigation.NavigationState
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.navigation.NavigationCameraConfig
import org.compass.cng.navigation.NavigationCameraController
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.followTopPaddingPixels
import org.compass.cng.navigation.resolvedBearingDegrees
import org.compass.cng.navigation.routePortions
import org.compass.cng.navigation.tracksVehiclePosition
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM
import org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP
import org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconKeepUpright
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** MapLibre navigation renderer. All matching/progress decisions come from NavigationState. */
@Composable
fun NavigationMap(
    state: NavigationState,
    cameraMode: NavigationCameraMode,
    cameraConfig: NavigationCameraConfig = NavigationCameraConfig(),
    bottomObstructionPixels: Int = 0,
    onCameraModeChange: (NavigationCameraMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val route = requireNotNull(state.route)
    val appearance = compassMapAppearance()
    val mapView = rememberMapViewWithLifecycle()
    val cameraController = remember(cameraConfig) { NavigationCameraController(cameraConfig) }
    val puckAnimator = remember(route.routeId) { MapPuckAnimator() }
    val scaleGestureActive = remember(mapView) { mutableStateOf(false) }
    val manualFollowZoom = remember(mapView) { mutableStateOf<Double?>(null) }
    val density = LocalDensity.current.density
    val currentCameraMode by rememberUpdatedState(cameraMode)
    val currentOnCameraModeChange by rememberUpdatedState(onCameraModeChange)
    val currentNavigationState by rememberUpdatedState(state)
    val currentBottomObstructionPixels by rememberUpdatedState(bottomObstructionPixels)
    AndroidView(factory = { mapView }, modifier = modifier)

    DisposableEffect(mapView, puckAnimator) {
        var registeredMap: MapLibreMap? = null
        val scaleListener = object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                scaleGestureActive.value = true
                if (currentCameraMode.tracksVehiclePosition) {
                    registeredMap?.let { map ->
                        map.uiSettings.focalPoint = followPuckFocalPoint(
                            map,
                            cameraConfig,
                            currentBottomObstructionPixels,
                        )
                    }
                    Log.i(NAVIGATION_MAP_LOG_TAG, "camera_interaction=zoom mode=follow")
                }
            }

            override fun onScale(detector: StandardScaleGestureDetector) = Unit

            override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                scaleGestureActive.value = false
                if (currentCameraMode.tracksVehiclePosition) {
                    registeredMap?.let { map ->
                        manualFollowZoom.value = map.cameraPosition.zoom
                        Log.i(
                            NAVIGATION_MAP_LOG_TAG,
                            "camera_interaction=zoom mode=follow " +
                                "manual_zoom=${manualFollowZoom.value} retained=true",
                        )
                        updateCamera(
                            map = map,
                            state = currentNavigationState,
                            cameraMode = currentCameraMode,
                            cameraController = cameraController,
                            density = density,
                            viewportHeightPixels = mapView.height,
                            bottomObstructionPixels = currentBottomObstructionPixels,
                            followImmediately = true,
                            zoomOverride = manualFollowZoom.value,
                        )
                    }
                }
            }
        }
        val moveListener = object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                val scaleInProgress = registeredMap
                    ?.gesturesManager
                    ?.standardScaleGestureDetector
                    ?.isInProgress == true
                val isMultiTouch = (detector.currentEvent?.pointerCount ?: 1) > 1
                if (!scaleGestureActive.value && !scaleInProgress && !isMultiTouch) {
                    manualFollowZoom.value = null
                    currentOnCameraModeChange(NavigationCameraMode.FREE)
                }
            }

            override fun onMove(detector: MoveGestureDetector) = Unit

            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        }
        mapView.getMapAsync { map ->
            registeredMap = map
            map.addOnScaleListener(scaleListener)
            map.addOnMoveListener(moveListener)
        }
        onDispose {
            registeredMap?.removeOnScaleListener(scaleListener)
            registeredMap?.removeOnMoveListener(moveListener)
            puckAnimator.cancel()
        }
    }

    LaunchedEffect(mapView, appearance, route.routeId) {
        mapView.getMapAsync { map ->
            map.setStyle(appearance.styleUrl) { style ->
                val localizedLayerCount = localizeMapLabelsInItalian(style)
                val filteredPoiLayerCount = filterMapPoisForNavigation(style)
                Log.i(
                    NAVIGATION_MAP_LOG_TAG,
                    "map_style_loaded surface=navigation theme=${appearance.theme.name.lowercase()} " +
                        "source=${mapStyleSource(appearance.styleUrl)} locale=it " +
                        "layers=$localizedLayerCount poi_layers=$filteredPoiLayerCount",
                )
                val portions = state.routePortions()
                val remainingPoints = portions.remaining.map(::point)
                val travelledPoints = portions.travelled.map(::point)
                val firstMapLabelLayerId = style.layers.firstOrNull { it is SymbolLayer }?.id
                style.addSource(GeoJsonSource(REMAINING_SOURCE, lineFeature(remainingPoints)))
                val remainingLayer = LineLayer(REMAINING_LAYER, REMAINING_SOURCE).withProperties(
                        lineColor(appearance.navigationRouteColor()),
                        lineWidth(NAVIGATION_REMAINING_ROUTE_WIDTH),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND),
                    )
                if (firstMapLabelLayerId == null) {
                    style.addLayer(remainingLayer)
                } else {
                    style.addLayerBelow(remainingLayer, firstMapLabelLayerId)
                }
                style.addImage(
                    NAVIGATION_ROUTE_CHEVRON_IMAGE,
                    requireNotNull(
                        mapView.context.getDrawable(R.drawable.ic_route_direction_chevron),
                    ),
                )
                val remainingDirectionLayer = SymbolLayer(
                    REMAINING_DIRECTION_LAYER,
                    REMAINING_SOURCE,
                ).withProperties(
                    symbolPlacement(SYMBOL_PLACEMENT_LINE),
                    symbolSpacing(NAVIGATION_ROUTE_CHEVRON_SPACING),
                    iconImage(NAVIGATION_ROUTE_CHEVRON_IMAGE),
                    iconSize(NAVIGATION_ROUTE_CHEVRON_SCALE),
                    iconPitchAlignment(ICON_PITCH_ALIGNMENT_MAP),
                    iconRotationAlignment(ICON_ROTATION_ALIGNMENT_MAP),
                    iconKeepUpright(false),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                )
                if (firstMapLabelLayerId == null) {
                    style.addLayer(remainingDirectionLayer)
                } else {
                    style.addLayerBelow(remainingDirectionLayer, firstMapLabelLayerId)
                }
                style.addSource(
                    GeoJsonSource(
                        TRAVELLED_SOURCE,
                        lineFeature(travelledPoints),
                    ),
                )
                val travelledLayer = LineLayer(TRAVELLED_LAYER, TRAVELLED_SOURCE).withProperties(
                        lineColor(appearance.palette.travelledRoute),
                        lineWidth(7f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND),
                    )
                if (firstMapLabelLayerId == null) {
                    style.addLayer(travelledLayer)
                } else {
                    style.addLayerBelow(travelledLayer, firstMapLabelLayerId)
                }
                val initialPuck = state.snappedLocation ?: route.origin
                val initialBearing = state.navigationPosition?.bearingDegrees
                    ?: cameraController.instruction(state).bearingDegrees
                style.addImage(
                    NAVIGATION_VEHICLE_IMAGE,
                    requireNotNull(
                        mapView.context.getDrawable(appearance.navigationPuckDrawableRes()),
                    ),
                )
                style.addSource(
                    GeoJsonSource(
                        PUCK_SOURCE,
                        navigationPuckFeature(initialPuck, initialBearing),
                    ),
                )
                state.navigationPosition?.let(puckAnimator::reset)
                val vehicleLayer = SymbolLayer(PUCK_LAYER, PUCK_SOURCE).withProperties(
                        iconImage(NAVIGATION_VEHICLE_IMAGE),
                        iconSize(navigationPuckScaleExpression()),
                        iconPitchAlignment(ICON_PITCH_ALIGNMENT_VIEWPORT),
                        iconAnchor(ICON_ANCHOR_CENTER),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                    )
                configureVehicleLayer(vehicleLayer, cameraMode)
                style.addLayer(vehicleLayer)
                val stopFeatures = route.fuelStops.map { stop ->
                    Feature.fromGeometry(
                        Point.fromLngLat(stop.location.longitude, stop.location.latitude),
                    )
                }
                style.addSource(
                    GeoJsonSource(
                        FUEL_STOPS_SOURCE,
                        FeatureCollection.fromFeatures(stopFeatures),
                    ),
                )
                style.addLayer(
                    CircleLayer(FUEL_STOPS_LAYER, FUEL_STOPS_SOURCE).withProperties(
                        circleRadius(11f),
                        circleColor(appearance.palette.cng),
                        circleStrokeColor(appearance.palette.markerStroke),
                        circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    SymbolLayer(FUEL_STOPS_TEXT_LAYER, FUEL_STOPS_SOURCE).withProperties(
                        textField("CNG"),
                        textFont(arrayOf("Noto Sans Regular")),
                        textSize(8f),
                        textColor(appearance.palette.markerText),
                        textAllowOverlap(true),
                        textIgnorePlacement(true),
                    ),
                )
                val intermediateStopFeatures = route.intermediateStops.map { stop ->
                    Feature.fromGeometry(
                        Point.fromLngLat(stop.location.longitude, stop.location.latitude),
                    )
                }
                style.addSource(
                    GeoJsonSource(
                        INTERMEDIATE_STOPS_SOURCE,
                        FeatureCollection.fromFeatures(intermediateStopFeatures),
                    ),
                )
                style.addLayer(
                    CircleLayer(INTERMEDIATE_STOPS_LAYER, INTERMEDIATE_STOPS_SOURCE).withProperties(
                        circleRadius(11f),
                        circleColor(appearance.palette.selectedCng),
                        circleStrokeColor(appearance.palette.markerStroke),
                        circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    SymbolLayer(
                        INTERMEDIATE_STOPS_TEXT_LAYER,
                        INTERMEDIATE_STOPS_SOURCE,
                    ).withProperties(
                        textField("T"),
                        textFont(arrayOf("Noto Sans Regular")),
                        textSize(9f),
                        textColor(appearance.palette.markerText),
                        textAllowOverlap(true),
                        textIgnorePlacement(true),
                    ),
                )
                updateNavigationCallout(
                    style = style,
                    sourceId = MANEUVER_CALLOUT_SOURCE,
                    imageId = MANEUVER_CALLOUT_IMAGE,
                    callout = state.nextManeuverCallout(),
                    density = density,
                    appearance = appearance,
                )
                style.addLayer(
                    SymbolLayer(MANEUVER_CALLOUT_LAYER, MANEUVER_CALLOUT_SOURCE).withProperties(
                        iconImage(MANEUVER_CALLOUT_IMAGE),
                        iconAnchor(ICON_ANCHOR_BOTTOM),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                    ),
                )
                updateNavigationCallout(
                    style = style,
                    sourceId = STOP_CALLOUT_SOURCE,
                    imageId = STOP_CALLOUT_IMAGE,
                    callout = state.nextStopCallout(),
                    density = density,
                    appearance = appearance,
                )
                style.addLayer(
                    SymbolLayer(STOP_CALLOUT_LAYER, STOP_CALLOUT_SOURCE).withProperties(
                        iconImage(STOP_CALLOUT_IMAGE),
                        iconAnchor(ICON_ANCHOR_BOTTOM),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                    ),
                )
                Log.i(
                    NAVIGATION_MAP_LOG_TAG,
                    "map_symbols vehicle=arrow cng=badge intermediate_stop=badge " +
                        "vehicle_alignment=${vehicleAlignment(cameraMode)}",
                )

                updateCamera(
                    map = map,
                    state = state,
                    cameraMode = cameraMode,
                    cameraController = cameraController,
                    density = density,
                    viewportHeightPixels = mapView.height,
                    bottomObstructionPixels = bottomObstructionPixels,
                )
            }
        }
    }

    LaunchedEffect(mapView, cameraMode, route.routeId, bottomObstructionPixels) {
        mapView.getMapAsync { map ->
            if (!cameraMode.tracksVehiclePosition) {
                manualFollowZoom.value = null
            }
            configureNavigationGestures(
                map,
                cameraMode,
                cameraConfig,
                bottomObstructionPixels,
            )
            map.style?.getLayerAs<SymbolLayer>(PUCK_LAYER)?.let { layer ->
                configureVehicleLayer(layer, cameraMode)
                Log.i(
                    NAVIGATION_MAP_LOG_TAG,
                    "vehicle_alignment=${vehicleAlignment(cameraMode)}",
                )
            }
        }
    }

    LaunchedEffect(
        mapView,
        cameraMode,
        route.routeId,
        state.navigationPosition,
        state.currentManeuver,
        state.nextManeuver,
        state.nextFuelStop,
        state.nextIntermediateStop,
        state.distanceToNextManeuverMeters,
        bottomObstructionPixels,
    ) {
        val portions = state.routePortions()
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            style.getSourceAs<GeoJsonSource>(TRAVELLED_SOURCE)?.setGeoJson(
                lineFeature(portions.travelled.map(::point)),
            )
            style.getSourceAs<GeoJsonSource>(REMAINING_SOURCE)?.setGeoJson(
                lineFeature(portions.remaining.map(::point)),
            )
            val cameraDrivenByPuck =
                state.navigationPosition != null && cameraMode.tracksVehiclePosition
            state.navigationPosition?.let { position ->
                puckAnimator.moveTo(
                    position = position,
                    onTransition = { transition ->
                        Log.i(
                            NAVIGATION_MAP_LOG_TAG,
                            "puck_motion mode=${transition.mode.name.lowercase()} " +
                                "duration_ms=${transition.durationMillis} " +
                                "distance_m=${transition.distanceMeters.toInt()} " +
                                "source=matched camera_sync=puck_pose",
                        )
                    },
                ) { pose ->
                    map.style?.getSourceAs<GeoJsonSource>(PUCK_SOURCE)?.setGeoJson(
                        navigationPuckFeature(pose.coordinate, pose.bearingDegrees),
                    )
                    if (
                        currentCameraMode.tracksVehiclePosition &&
                        !scaleGestureActive.value
                    ) {
                        updateCamera(
                            map = map,
                            state = state.copy(
                                navigationPosition = position.copy(
                                    coordinate = pose.coordinate,
                                    bearingDegrees = pose.bearingDegrees,
                                ),
                            ),
                            cameraMode = currentCameraMode,
                            cameraController = cameraController,
                            density = density,
                            viewportHeightPixels = mapView.height,
                            bottomObstructionPixels = bottomObstructionPixels,
                            followImmediately = true,
                            zoomOverride = manualFollowZoom.value,
                        )
                    }
                }
            }

            if (!cameraDrivenByPuck) {
                updateCamera(
                    map = map,
                    state = state,
                    cameraMode = cameraMode,
                    cameraController = cameraController,
                    density = density,
                    viewportHeightPixels = mapView.height,
                    bottomObstructionPixels = bottomObstructionPixels,
                )
            }
        }
    }

    LaunchedEffect(
        mapView,
        appearance,
        route.routeId,
        state.currentManeuver,
        state.nextFuelStop,
        state.nextIntermediateStop,
    ) {
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            updateNavigationCallout(
                style = style,
                sourceId = MANEUVER_CALLOUT_SOURCE,
                imageId = MANEUVER_CALLOUT_IMAGE,
                callout = state.nextManeuverCallout(),
                density = density,
                appearance = appearance,
            )
            updateNavigationCallout(
                style = style,
                sourceId = STOP_CALLOUT_SOURCE,
                imageId = STOP_CALLOUT_IMAGE,
                callout = state.nextStopCallout(),
                density = density,
                appearance = appearance,
            )
        }
    }
}

private fun updateCamera(
    map: MapLibreMap,
    state: NavigationState,
    cameraMode: NavigationCameraMode,
    cameraController: NavigationCameraController,
    density: Float,
    viewportHeightPixels: Int,
    bottomObstructionPixels: Int,
    followImmediately: Boolean = false,
    zoomOverride: Double? = null,
) {
    when (cameraMode) {
        NavigationCameraMode.FREE -> return
        NavigationCameraMode.OVERVIEW -> {
            val remaining = state.routePortions().remaining.distinct()
            if (remaining.size >= 2) {
                val bounds = LatLngBounds.Builder()
                remaining.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                val padding = (cameraController.config.overviewEdgePaddingDp * density).toInt()
                map.getCameraForLatLngBounds(
                    bounds.build(),
                    intArrayOf(padding, padding, padding, padding),
                )?.let { overview ->
                    map.easeCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(overview)
                                .bearing(0.0)
                                .tilt(0.0)
                                .padding(0.0, 0.0, 0.0, 0.0)
                                .build(),
                        ),
                        cameraController.config.overviewAnimationMillis,
                    )
                }
            } else {
                val target = remaining.firstOrNull() ?: return
                map.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(target.latitude, target.longitude))
                            .bearing(0.0)
                            .tilt(0.0)
                            .zoom(cameraController.config.urbanZoom)
                            .padding(0.0, 0.0, 0.0, 0.0)
                            .build(),
                    ),
                    cameraController.config.overviewAnimationMillis,
                )
            }
        }
        NavigationCameraMode.FOLLOW,
        NavigationCameraMode.NORTH_UP,
        -> {
            val camera = cameraController.instruction(state)
            val bottomPaddingPixels = bottomObstructionPixels
                .coerceIn(0, viewportHeightPixels)
            val followBearing = if (followImmediately) {
                state.vehicleBearingDegrees ?: camera.bearingDegrees
            } else {
                camera.bearingDegrees
            }
            val bearing = requireNotNull(cameraMode.resolvedBearingDegrees(followBearing))
            val pitch = if (cameraMode == NavigationCameraMode.NORTH_UP) {
                0.0
            } else {
                camera.pitchDegrees
            }
            map.uiSettings.focalPoint = followPuckFocalPoint(
                map,
                cameraController.config,
                bottomObstructionPixels,
            )
            val update = CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(camera.target.latitude, camera.target.longitude))
                    .bearing(bearing)
                    .tilt(pitch)
                    .zoom(zoomOverride ?: camera.zoom)
                    .padding(
                        0.0,
                        followTopPaddingPixels(
                            viewportHeightPixels,
                            cameraController.config.followPuckVerticalFraction,
                            bottomPaddingPixels,
                        ),
                        0.0,
                        bottomPaddingPixels.toDouble(),
                    )
                    .build(),
            )
            if (followImmediately) {
                map.moveCamera(update)
            } else {
                Log.i(
                    NAVIGATION_MAP_LOG_TAG,
                    "camera_instruction mode=${cameraMode.name.lowercase()} " +
                        "bearing=${bearing.toInt()} pitch=${pitch.toInt()} zoom=${camera.zoom} " +
                        "next_maneuver_spacing=${state.nextManeuver?.distanceMeters} " +
                        "target_alignment=puck_anchor",
                )
                map.easeCamera(update, camera.animationMillis)
            }
        }
    }
}

private fun configureNavigationGestures(
    map: MapLibreMap,
    cameraMode: NavigationCameraMode,
    cameraConfig: NavigationCameraConfig,
    bottomObstructionPixels: Int,
) {
    val following = cameraMode.tracksVehiclePosition
    map.uiSettings.apply {
        isRotateGesturesEnabled = !following
        isTiltGesturesEnabled = !following
        focalPoint = if (following) {
            followPuckFocalPoint(map, cameraConfig, bottomObstructionPixels)
        } else {
            null
        }
    }
    Log.i(
        NAVIGATION_MAP_LOG_TAG,
        if (following) {
            "gesture_policy=zoom_follow pan_free " +
                "puck_vertical_fraction=${cameraConfig.followPuckVerticalFraction}"
        } else {
            "gesture_policy=free focal_point=gesture"
        },
    )
}

private fun followPuckFocalPoint(
    map: MapLibreMap,
    cameraConfig: NavigationCameraConfig,
    bottomObstructionPixels: Int,
): PointF = PointF(
    map.width / 2f,
    (map.height - bottomObstructionPixels.toFloat().coerceIn(0f, map.height)) *
        cameraConfig.followPuckVerticalFraction.toFloat(),
)

private fun configureVehicleLayer(
    layer: SymbolLayer,
    cameraMode: NavigationCameraMode,
) {
    if (cameraMode == NavigationCameraMode.FOLLOW) {
        layer.setProperties(
            iconRotate(0f),
            iconRotationAlignment(ICON_ROTATION_ALIGNMENT_VIEWPORT),
        )
    } else {
        layer.setProperties(
            iconRotate(get(PUCK_BEARING_PROPERTY)),
            iconRotationAlignment(ICON_ROTATION_ALIGNMENT_MAP),
        )
    }
}

private fun vehicleAlignment(cameraMode: NavigationCameraMode): String =
    if (cameraMode == NavigationCameraMode.FOLLOW) "viewport" else "map"

internal fun localizeMapLabelsInItalian(style: Style): Int {
    var localized = 0
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        if (shouldPreferItalianLabels(layer.id, layer.sourceLayer) && !layer.textField.isNull) {
            layer.setProperties(textField(italianMapLabelExpression()))
            localized += 1
        }
    }
    return localized
}

internal fun filterMapPoisForNavigation(style: Style): Int {
    var filtered = 0
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        if (isMapPoiLayer(layer.sourceLayer)) {
            layer.setFilter(navigationPoiFilter(layer.filter))
            filtered += 1
        }
    }
    Log.i(
        NAVIGATION_MAP_LOG_TAG,
        "map_poi_policy mode=navigation layers=$filtered " +
            "classes=fuel subclasses=charging_station,toll_booth,border_control,traffic_signals",
    )
    return filtered
}

private fun navigationPuckFeature(
    coordinate: org.compass.cng.domain.model.Coordinate,
    bearingDegrees: Double,
): Feature = Feature.fromGeometry(point(coordinate)).also { feature ->
    feature.addNumberProperty(PUCK_BEARING_PROPERTY, bearingDegrees)
}

private fun point(coordinate: org.compass.cng.domain.model.Coordinate): Point =
    Point.fromLngLat(coordinate.longitude, coordinate.latitude)

private fun lineFeature(points: List<Point>): Feature {
    val safePoints = when {
        points.size >= 2 -> points
        points.size == 1 -> listOf(points.first(), points.first())
        else -> listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.0, 0.0))
    }
    return Feature.fromGeometry(LineString.fromLngLats(safePoints))
}

internal enum class NavigationCalloutKind {
    MANEUVER,
    CNG_STOP,
    INTERMEDIATE_STOP,
}

internal data class NavigationMapCallout(
    val coordinate: Coordinate,
    val label: String,
    val symbol: String,
    val kind: NavigationCalloutKind,
)

internal fun NavigationState.nextManeuverCallout(): NavigationMapCallout? {
    val route = route ?: return null
    val maneuver = currentManeuver ?: return null
    val coordinate = route.geometry.getOrNull(maneuver.beginShapeIndex) ?: return null
    val label = maneuver.streetNames.firstOrNull()
        ?: maneuver.instruction.trim().trimEnd('.')
    return NavigationMapCallout(
        coordinate = coordinate,
        label = compactCalloutLabel(label),
        symbol = maneuverMapSymbol(maneuver.type),
        kind = NavigationCalloutKind.MANEUVER,
    )
}

internal fun NavigationState.nextStopCallout(): NavigationMapCallout? {
    val fuel = nextFuelStop?.stop
    val intermediate = nextIntermediateStop?.stop
    return when {
        fuel == null && intermediate == null -> null
        fuel != null && (intermediate == null || fuel.sequence < intermediate.sequence) -> {
            NavigationMapCallout(
                coordinate = fuel.location,
                label = compactCalloutLabel(fuel.name ?: "Sosta CNG"),
                symbol = "CNG",
                kind = NavigationCalloutKind.CNG_STOP,
            )
        }
        else -> requireNotNull(intermediate).let { stop ->
            NavigationMapCallout(
                coordinate = stop.location,
                label = compactCalloutLabel(stop.mapLabel),
                symbol = "T",
                kind = NavigationCalloutKind.INTERMEDIATE_STOP,
            )
        }
    }
}

internal fun maneuverMapSymbol(type: Int): String = when (type) {
    9, 10, 11, 18, 20, 23 -> "↱"
    14, 15, 16, 19, 21, 24 -> "↰"
    12 -> "↻"
    13 -> "↺"
    26, 27 -> "○"
    4, 5, 6 -> "◆"
    else -> "↑"
}

internal fun compactCalloutLabel(value: String, maximumCharacters: Int = 28): String {
    val normalized = value.replace(Regex("\\s+"), " ").trim().ifEmpty { "Prossimo punto" }
    return if (normalized.length <= maximumCharacters) {
        normalized
    } else {
        normalized.take(maximumCharacters - 1).trimEnd() + "…"
    }
}

private fun updateNavigationCallout(
    style: Style,
    sourceId: String,
    imageId: String,
    callout: NavigationMapCallout?,
    density: Float,
    appearance: CompassMapAppearance,
) {
    val featureCollection = FeatureCollection.fromFeatures(
        callout?.let { callout ->
            arrayOf(Feature.fromGeometry(point(callout.coordinate)))
        } ?: emptyArray(),
    )
    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, featureCollection))
    } else {
        source.setGeoJson(featureCollection)
    }
    if (callout != null) {
        style.addImage(
            imageId,
            navigationCalloutBitmap(callout, density, appearance),
        )
    }
}

private fun navigationCalloutBitmap(
    callout: NavigationMapCallout,
    density: Float,
    appearance: CompassMapAppearance,
): Bitmap {
    val scale = density.coerceAtLeast(1f)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (appearance.theme == CompassMapTheme.NIGHT) 0xFFF4FFF8.toInt() else 0xFF10251A.toInt()
        textSize = 14f * scale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val symbolPaint = Paint(textPaint).apply {
        color = appearance.palette.markerText
        textSize = if (callout.symbol.length > 1) 9f * scale else 18f * scale
        textAlign = Paint.Align.CENTER
    }
    val horizontalPadding = 12f * scale
    val symbolDiameter = 30f * scale
    val gap = 8f * scale
    val bodyHeight = 42f * scale
    val tailHeight = 8f * scale
    val labelWidth = textPaint.measureText(callout.label)
    val width = horizontalPadding * 2 + symbolDiameter + gap + labelWidth
    val bitmap = Bitmap.createBitmap(
        width.toInt().coerceAtLeast(1),
        (bodyHeight + tailHeight).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    val background = if (appearance.theme == CompassMapTheme.NIGHT) {
        0xE6121C17.toInt()
    } else {
        0xF2FFFFFF.toInt()
    }
    val accent = when (callout.kind) {
        NavigationCalloutKind.MANEUVER -> NAVIGATION_REMAINING_ROUTE_COLOR
        NavigationCalloutKind.CNG_STOP -> appearance.palette.cng
        NavigationCalloutKind.INTERMEDIATE_STOP -> appearance.palette.selectedCng
    }
    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 2f * scale
    }
    // Keep the entire stroke inside the bitmap and draw one continuous silhouette.
    val inset = strokePaint.strokeWidth / 2f + scale
    val body = RectF(inset, inset, width - inset, bodyHeight)
    val tailCenter = width / 2f
    val radius = 12f * scale
    val outline = Path().apply {
        moveTo(body.left + radius, body.top)
        lineTo(body.right - radius, body.top)
        quadTo(body.right, body.top, body.right, body.top + radius)
        lineTo(body.right, body.bottom - radius)
        quadTo(body.right, body.bottom, body.right - radius, body.bottom)
        lineTo(tailCenter + 7f * scale, body.bottom)
        lineTo(tailCenter, body.bottom + tailHeight - inset)
        lineTo(tailCenter - 7f * scale, body.bottom)
        lineTo(body.left + radius, body.bottom)
        quadTo(body.left, body.bottom, body.left, body.bottom - radius)
        lineTo(body.left, body.top + radius)
        quadTo(body.left, body.top, body.left + radius, body.top)
        close()
    }
    strokePaint.strokeJoin = Paint.Join.ROUND
    canvas.drawPath(outline, backgroundPaint)
    canvas.drawPath(outline, strokePaint)
    canvas.drawCircle(
        horizontalPadding + symbolDiameter / 2f,
        bodyHeight / 2f,
        symbolDiameter / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent },
    )
    val textBaseline = bodyHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(
        callout.symbol,
        horizontalPadding + symbolDiameter / 2f,
        bodyHeight / 2f - (symbolPaint.ascent() + symbolPaint.descent()) / 2f,
        symbolPaint,
    )
    canvas.drawText(
        callout.label,
        horizontalPadding + symbolDiameter + gap,
        textBaseline,
        textPaint,
    )
    return bitmap
}

private const val REMAINING_SOURCE = "navigation-remaining-source"
private const val REMAINING_LAYER = "navigation-remaining-layer"
private const val REMAINING_DIRECTION_LAYER = "navigation-remaining-direction-layer"
private const val TRAVELLED_SOURCE = "navigation-travelled-source"
private const val TRAVELLED_LAYER = "navigation-travelled-layer"
private const val PUCK_SOURCE = "navigation-puck-source"
private const val PUCK_LAYER = "navigation-puck-layer"
private const val PUCK_BEARING_PROPERTY = "bearing"
private const val NAVIGATION_VEHICLE_IMAGE = "compass-navigation-vehicle"
private const val NAVIGATION_ROUTE_CHEVRON_IMAGE = "compass-navigation-route-chevron"
private const val FUEL_STOPS_SOURCE = "navigation-fuel-stops-source"
private const val FUEL_STOPS_LAYER = "navigation-fuel-stops-layer"
private const val FUEL_STOPS_TEXT_LAYER = "navigation-fuel-stops-text-layer"
private const val INTERMEDIATE_STOPS_SOURCE = "navigation-intermediate-stops-source"
private const val INTERMEDIATE_STOPS_LAYER = "navigation-intermediate-stops-layer"
private const val INTERMEDIATE_STOPS_TEXT_LAYER = "navigation-intermediate-stops-text-layer"
private const val MANEUVER_CALLOUT_SOURCE = "navigation-maneuver-callout-source"
private const val MANEUVER_CALLOUT_LAYER = "navigation-maneuver-callout-layer"
private const val MANEUVER_CALLOUT_IMAGE = "navigation-maneuver-callout-image"
private const val STOP_CALLOUT_SOURCE = "navigation-stop-callout-source"
private const val STOP_CALLOUT_LAYER = "navigation-stop-callout-layer"
private const val STOP_CALLOUT_IMAGE = "navigation-stop-callout-image"
private const val NAVIGATION_MAP_LOG_TAG = "CompassNavigationUi"
internal const val NAVIGATION_PUCK_ICON_SCALE = 1.10f
internal const val NAVIGATION_PUCK_MIN_ICON_SCALE = NAVIGATION_PUCK_ICON_SCALE * 0.5f
internal const val NAVIGATION_PUCK_MIN_SCALE_ZOOM = 10f
internal const val NAVIGATION_PUCK_FULL_SCALE_ZOOM = 16f
internal val NAVIGATION_REMAINING_ROUTE_COLOR = 0xFF79FFA8.toInt()
internal const val NAVIGATION_REMAINING_ROUTE_WIDTH = 9f
internal const val NAVIGATION_ROUTE_CHEVRON_SPACING = 72f
internal const val NAVIGATION_ROUTE_CHEVRON_SCALE = 0.72f

internal fun navigationPuckScaleExpression() = interpolate(
    linear(),
    zoom(),
    stop(NAVIGATION_PUCK_MIN_SCALE_ZOOM, NAVIGATION_PUCK_MIN_ICON_SCALE),
    stop(NAVIGATION_PUCK_FULL_SCALE_ZOOM, NAVIGATION_PUCK_ICON_SCALE),
)
