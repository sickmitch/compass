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
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.navigation.NavigationCameraConfig
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.navigation.NavigationPosition
import org.compass.cng.navigation.followTopPaddingPixels
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER
import org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Route-free map: it follows the device GPS without matching it to an itinerary. */
@Composable
fun FollowMap(
    location: NavigationLocation?,
    cameraMode: NavigationCameraMode,
    cameraConfig: NavigationCameraConfig = NavigationCameraConfig(),
    onCameraModeChange: (NavigationCameraMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appearance = compassMapAppearance()
    val mapView = rememberMapViewWithLifecycle()
    val puckAnimator = remember { MapPuckAnimator() }
    val scaleGestureActive = remember(mapView) { mutableStateOf(false) }
    val manualFollowZoom = remember(mapView) { mutableStateOf<Double?>(null) }
    val currentCameraMode by rememberUpdatedState(cameraMode)
    val currentOnCameraModeChange by rememberUpdatedState(onCameraModeChange)
    val currentLocation by rememberUpdatedState(location)
    AndroidView(factory = { mapView }, modifier = modifier)

    DisposableEffect(mapView, puckAnimator) {
        var registeredMap: MapLibreMap? = null
        val scaleListener = object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                scaleGestureActive.value = true
                if (currentCameraMode == NavigationCameraMode.FOLLOW) {
                    registeredMap?.uiSettings?.focalPoint = followFocalPoint(
                        registeredMap,
                        cameraConfig,
                    )
                }
            }

            override fun onScale(detector: StandardScaleGestureDetector) = Unit

            override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                scaleGestureActive.value = false
                if (currentCameraMode == NavigationCameraMode.FOLLOW) {
                    registeredMap?.let { map ->
                        manualFollowZoom.value = map.cameraPosition.zoom
                        currentLocation?.let { current ->
                            moveFollowCamera(map, current, cameraConfig, manualFollowZoom.value, true)
                        }
                    }
                }
            }
        }
        val moveListener = object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                val scaling = registeredMap?.gesturesManager
                    ?.standardScaleGestureDetector?.isInProgress == true
                val multiTouch = (detector.currentEvent?.pointerCount ?: 1) > 1
                if (!scaleGestureActive.value && !scaling && !multiTouch) {
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

    LaunchedEffect(mapView, appearance) {
        mapView.getMapAsync { map ->
            map.setStyle(appearance.styleUrl) { style ->
                localizeMapLabelsInItalian(style)
                filterMapPoisForNavigation(style)
                style.addImage(
                    FOLLOW_VEHICLE_IMAGE,
                    requireNotNull(
                        mapView.context.getDrawable(appearance.navigationPuckDrawableRes()),
                    ),
                )
                style.addSource(
                    GeoJsonSource(
                        FOLLOW_PUCK_SOURCE,
                        FeatureCollection.fromFeatures(
                            location?.let { arrayOf(followPuckFeature(it)) }
                                ?: emptyArray<Feature>(),
                        ),
                    ),
                )
                style.addLayer(
                    SymbolLayer(FOLLOW_PUCK_LAYER, FOLLOW_PUCK_SOURCE).withProperties(
                        iconImage(FOLLOW_VEHICLE_IMAGE),
                        iconSize(navigationPuckScaleExpression()),
                        iconPitchAlignment(ICON_PITCH_ALIGNMENT_VIEWPORT),
                        iconAnchor(ICON_ANCHOR_CENTER),
                        iconRotate(0f),
                        iconRotationAlignment(ICON_ROTATION_ALIGNMENT_VIEWPORT),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                    ),
                )
                if (location == null) {
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(DEFAULT_ITALY_CENTER, 5.5),
                    )
                } else {
                    puckAnimator.reset(location.toNavigationPosition())
                    moveFollowCamera(map, location, cameraConfig, null, true)
                }
                Log.i(FOLLOW_MAP_LOG_TAG, "surface=follow route=none gps=${location != null}")
            }
        }
    }

    LaunchedEffect(mapView, cameraMode) {
        mapView.getMapAsync { map ->
            val following = cameraMode == NavigationCameraMode.FOLLOW
            map.uiSettings.apply {
                isRotateGesturesEnabled = !following
                isTiltGesturesEnabled = !following
                focalPoint = if (following) followFocalPoint(map, cameraConfig) else null
            }
            if (following) {
                location?.let { moveFollowCamera(map, it, cameraConfig, manualFollowZoom.value, true) }
            }
        }
    }

    LaunchedEffect(mapView, location) {
        val current = location ?: return@LaunchedEffect
        val position = current.toNavigationPosition()
        mapView.getMapAsync { map ->
            puckAnimator.moveTo(position) { pose ->
                map.style?.getSourceAs<GeoJsonSource>(FOLLOW_PUCK_SOURCE)?.setGeoJson(
                    followPuckFeature(pose.coordinate, pose.bearingDegrees),
                )
                if (currentCameraMode == NavigationCameraMode.FOLLOW && !scaleGestureActive.value) {
                    moveFollowCamera(
                        map,
                        current.copy(
                            coordinate = pose.coordinate,
                            bearingDegrees = pose.bearingDegrees,
                        ),
                        cameraConfig,
                        manualFollowZoom.value,
                        true,
                    )
                }
            }
        }
    }
}

private fun NavigationLocation.toNavigationPosition() = NavigationPosition(
    coordinate = coordinate,
    routeSegmentIndex = 0,
    speedMetersPerSecond = speedMetersPerSecond?.coerceAtLeast(0.0) ?: 0.0,
    bearingDegrees = bearingDegrees ?: 0.0,
    horizontalAccuracyMeters = accuracyMeters,
    timestampEpochMillis = timestampEpochMillis,
)

private fun moveFollowCamera(
    map: MapLibreMap,
    location: NavigationLocation,
    config: NavigationCameraConfig,
    zoomOverride: Double?,
    immediately: Boolean,
) {
    val speed = location.speedMetersPerSecond?.coerceAtLeast(0.0) ?: 0.0
    val speedFraction = (
        (speed - config.urbanSpeedMetersPerSecond) /
            (config.motorwaySpeedMetersPerSecond - config.urbanSpeedMetersPerSecond)
        ).coerceIn(0.0, 1.0)
    val zoom = zoomOverride ?: (
        config.urbanZoom + (config.motorwayZoom - config.urbanZoom) * speedFraction
        ).coerceIn(config.minimumFollowZoom, config.maximumFollowZoom)
    val pitch = config.urbanPitchDegrees +
        (config.motorwayPitchDegrees - config.urbanPitchDegrees) * speedFraction
    val bearing = location.bearingDegrees?.takeIf { it.isFinite() }
        ?: map.cameraPosition.bearing
    map.uiSettings.focalPoint = followFocalPoint(map, config)
    val update = CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(LatLng(location.coordinate.latitude, location.coordinate.longitude))
            .bearing(bearing)
            .tilt(pitch)
            .zoom(zoom)
            .padding(
                0.0,
                followTopPaddingPixels(map.height.toInt(), config.followPuckVerticalFraction),
                0.0,
                0.0,
            )
            .build(),
    )
    if (immediately) map.moveCamera(update) else map.easeCamera(update, config.followAnimationMillis)
}

private fun followFocalPoint(
    map: MapLibreMap?,
    config: NavigationCameraConfig,
): PointF? = map?.let {
    PointF(it.width / 2f, it.height * config.followPuckVerticalFraction.toFloat())
}

private fun followPuckFeature(location: NavigationLocation): Feature =
    followPuckFeature(location.coordinate, location.bearingDegrees ?: 0.0)

private fun followPuckFeature(coordinate: Coordinate, bearing: Double): Feature =
    Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)).also {
        it.addNumberProperty("bearing", bearing)
    }

private val DEFAULT_ITALY_CENTER = LatLng(42.5, 12.5)
private const val FOLLOW_PUCK_SOURCE = "follow-puck-source"
private const val FOLLOW_PUCK_LAYER = "follow-puck-layer"
private const val FOLLOW_VEHICLE_IMAGE = "follow-navigation-vehicle"
private const val FOLLOW_MAP_LOG_TAG = "CompassFollowUi"
