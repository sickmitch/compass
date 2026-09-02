package org.compass.cng.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.NavigationCameraController
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.routePortions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** MapLibre navigation renderer. All matching/progress decisions come from NavigationState. */
@Composable
fun NavigationMap(
    state: NavigationState,
    mapStyleUrl: String,
    cameraMode: NavigationCameraMode,
    modifier: Modifier = Modifier,
) {
    val route = requireNotNull(state.route)
    val mapView = rememberMapViewWithLifecycle()
    val cameraController = NavigationCameraController()
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView, mapStyleUrl, route.routeId) {
        mapView.getMapAsync { map ->
            map.setStyle(mapStyleUrl) { style ->
                val portions = state.routePortions()
                val remainingPoints = portions.remaining.map(::point)
                val travelledPoints = portions.travelled.map(::point)
                style.addSource(GeoJsonSource(REMAINING_SOURCE, lineFeature(remainingPoints)))
                style.addLayer(
                    LineLayer(REMAINING_LAYER, REMAINING_SOURCE).withProperties(
                        lineColor(Color.rgb(20, 108, 58)),
                        lineWidth(7f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(
                    GeoJsonSource(
                        TRAVELLED_SOURCE,
                        lineFeature(travelledPoints),
                    ),
                )
                style.addLayer(
                    LineLayer(TRAVELLED_LAYER, TRAVELLED_SOURCE).withProperties(
                        lineColor(Color.rgb(104, 111, 108)),
                        lineWidth(7f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND),
                    ),
                )
                val initialPuck = state.snappedLocation ?: route.origin
                style.addSource(
                    GeoJsonSource(
                        PUCK_SOURCE,
                        Feature.fromGeometry(
                            Point.fromLngLat(initialPuck.longitude, initialPuck.latitude),
                        ),
                    ),
                )
                style.addLayer(
                    CircleLayer(PUCK_LAYER, PUCK_SOURCE).withProperties(
                        circleRadius(8f),
                        circleColor(Color.rgb(0, 132, 122)),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2.5f),
                    ),
                )
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
                        circleRadius(6f),
                        circleColor(Color.rgb(0, 132, 122)),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2f),
                    ),
                )

                val bounds = LatLngBounds.Builder()
                route.geometry.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 72))
            }
        }
    }

    LaunchedEffect(
        mapView,
        cameraMode,
        route.routeId,
        state.snappedLocation,
        state.currentRouteSegmentIndex,
        state.vehicleBearingDegrees,
        state.currentSpeedMetersPerSecond,
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
            state.snappedLocation?.let { snapped ->
                style.getSourceAs<GeoJsonSource>(PUCK_SOURCE)?.setGeoJson(
                    Feature.fromGeometry(point(snapped)),
                )
            }

            if (cameraMode == NavigationCameraMode.OVERVIEW) {
                val remaining = portions.remaining.distinct()
                map.setPadding(0, 0, 0, 0)
                if (remaining.size >= 2) {
                    val bounds = LatLngBounds.Builder()
                    remaining.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 72))
                } else {
                    val target = remaining.first()
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(target.latitude, target.longitude),
                            16.0,
                        ),
                    )
                }
                return@getMapAsync
            }

            val snapped = state.snappedLocation ?: return@getMapAsync
            val camera = cameraController.instruction(state)
            map.setPadding(0, 0, 0, mapView.height / 3)
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(snapped.latitude, snapped.longitude))
                        .bearing(camera.bearingDegrees)
                        .tilt(camera.pitchDegrees)
                        .zoom(camera.zoom)
                        .build(),
                ),
                camera.animationMillis,
            )
        }
    }
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
private const val FUEL_STOPS_SOURCE = "navigation-fuel-stops-source"
private const val FUEL_STOPS_LAYER = "navigation-fuel-stops-layer"
