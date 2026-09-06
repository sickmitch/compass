package org.compass.cng.ui.map

import android.graphics.PointF
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
import org.compass.cng.navigation.NavigationCameraConfig
import org.compass.cng.navigation.NavigationCameraController
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.followTopPaddingPixels
import org.compass.cng.navigation.routePortions
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
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER
import org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
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
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
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
                if (currentCameraMode == NavigationCameraMode.FOLLOW) {
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
                if (currentCameraMode == NavigationCameraMode.FOLLOW) {
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
                            cameraMode = NavigationCameraMode.FOLLOW,
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
                        lineColor(appearance.palette.route),
                        lineWidth(7f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND),
                    )
                if (firstMapLabelLayerId == null) {
                    style.addLayer(remainingLayer)
                } else {
                    style.addLayerBelow(remainingLayer, firstMapLabelLayerId)
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
                    requireNotNull(mapView.context.getDrawable(R.drawable.ic_navigation_vehicle)),
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
                        iconSize(0.55f),
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
                Log.i(
                    NAVIGATION_MAP_LOG_TAG,
                    "map_symbols vehicle=arrow cng=badge " +
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
            if (cameraMode != NavigationCameraMode.FOLLOW) {
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
                state.navigationPosition != null && cameraMode == NavigationCameraMode.FOLLOW
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
                        currentCameraMode == NavigationCameraMode.FOLLOW &&
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
                            cameraMode = NavigationCameraMode.FOLLOW,
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
        NavigationCameraMode.FOLLOW -> {
            val camera = cameraController.instruction(state)
            val bottomPaddingPixels = bottomObstructionPixels
                .coerceIn(0, viewportHeightPixels)
            val bearing = if (followImmediately) {
                state.vehicleBearingDegrees ?: camera.bearingDegrees
            } else {
                camera.bearingDegrees
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
                    .tilt(camera.pitchDegrees)
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
                    "camera_instruction mode=follow bearing=${camera.bearingDegrees.toInt()} " +
                        "pitch=${camera.pitchDegrees.toInt()} zoom=${camera.zoom} " +
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
    val following = cameraMode == NavigationCameraMode.FOLLOW
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

private fun localizeMapLabelsInItalian(style: Style): Int {
    var localized = 0
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        if (shouldPreferItalianLabels(layer.id, layer.sourceLayer) && !layer.textField.isNull) {
            layer.setProperties(textField(italianMapLabelExpression()))
            localized += 1
        }
    }
    return localized
}

private fun filterMapPoisForNavigation(style: Style): Int {
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

private const val REMAINING_SOURCE = "navigation-remaining-source"
private const val REMAINING_LAYER = "navigation-remaining-layer"
private const val TRAVELLED_SOURCE = "navigation-travelled-source"
private const val TRAVELLED_LAYER = "navigation-travelled-layer"
private const val PUCK_SOURCE = "navigation-puck-source"
private const val PUCK_LAYER = "navigation-puck-layer"
private const val PUCK_BEARING_PROPERTY = "bearing"
private const val NAVIGATION_VEHICLE_IMAGE = "compass-navigation-vehicle"
private const val FUEL_STOPS_SOURCE = "navigation-fuel-stops-source"
private const val FUEL_STOPS_LAYER = "navigation-fuel-stops-layer"
private const val FUEL_STOPS_TEXT_LAYER = "navigation-fuel-stops-text-layer"
private const val NAVIGATION_MAP_LOG_TAG = "CompassNavigationUi"
