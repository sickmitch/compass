package org.compass.cng.ui.map

import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.Coordinate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
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

@Composable
fun RouteMap(
    route: RoutePreview,
    mapStyleUrl: String,
    modifier: Modifier = Modifier,
    candidateStations: List<RankedCngStation> = emptyList(),
    cngStops: List<Coordinate> = emptyList(),
) {
    val mapView = rememberMapViewWithLifecycle()

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )

    LaunchedEffect(mapView, route, mapStyleUrl, candidateStations, cngStops) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(mapStyleUrl)) { style ->
                val routePoints = route.geometry.map {
                    Point.fromLngLat(it.longitude, it.latitude)
                }
                if (routePoints.size >= 2) {
                    style.addSource(
                        GeoJsonSource(
                            ROUTE_SOURCE_ID,
                            Feature.fromGeometry(LineString.fromLngLats(routePoints)),
                        ),
                    )
                    style.addLayer(
                        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                            lineColor(Color.rgb(20, 108, 58)),
                            lineWidth(6f),
                            lineCap(LINE_CAP_ROUND),
                            lineJoin(LINE_JOIN_ROUND),
                        ),
                    )
                }
                addEndpointLayer(
                    style = style,
                    idPrefix = "origin",
                    coordinate = route.origin,
                    color = Color.rgb(20, 108, 58),
                )
                addEndpointLayer(
                    style = style,
                    idPrefix = "destination",
                    coordinate = route.destination,
                    color = Color.rgb(183, 48, 36),
                )
                if (candidateStations.isNotEmpty()) {
                    val candidateFeatures = candidateStations.map { station ->
                        Feature.fromGeometry(
                            Point.fromLngLat(
                                station.location.longitude,
                                station.location.latitude,
                            ),
                        ).also { feature ->
                            feature.addStringProperty("mimit_station_id", station.mimitStationId)
                        }
                    }
                    style.addSource(
                        GeoJsonSource(
                            CNG_CANDIDATES_SOURCE_ID,
                            FeatureCollection.fromFeatures(candidateFeatures),
                        ),
                    )
                    style.addLayer(
                        CircleLayer(CNG_CANDIDATES_LAYER_ID, CNG_CANDIDATES_SOURCE_ID).withProperties(
                            circleRadius(5.5f),
                            circleColor(Color.rgb(0, 132, 122)),
                            circleStrokeColor(Color.WHITE),
                            circleStrokeWidth(1.5f),
                        ),
                    )
                }
                cngStops.forEachIndexed { index, stop ->
                    addEndpointLayer(
                        style = style,
                        idPrefix = "cng-stop-$index",
                        coordinate = stop,
                        color = Color.rgb(0, 132, 122),
                    )
                }

                val boundsBuilder = LatLngBounds.Builder()
                    .include(LatLng(route.origin.latitude, route.origin.longitude))
                    .include(LatLng(route.destination.latitude, route.destination.longitude))
                route.geometry.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
                candidateStations.forEach { station ->
                    boundsBuilder.include(
                        LatLng(station.location.latitude, station.location.longitude),
                    )
                }
                cngStops.forEach { stop ->
                    boundsBuilder.include(LatLng(stop.latitude, stop.longitude))
                }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 72),
                )
            }
        }
    }
}

private fun addEndpointLayer(
    style: Style,
    idPrefix: String,
    coordinate: Coordinate,
    color: Int,
) {
    val sourceId = "$idPrefix-source"
    style.addSource(
        GeoJsonSource(
            sourceId,
            Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)),
        ),
    )
    style.addLayer(
        CircleLayer("$idPrefix-layer", sourceId).withProperties(
            circleRadius(7f),
            circleColor(color),
            circleStrokeColor(Color.WHITE),
            circleStrokeWidth(2f),
        ),
    )
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(Bundle())
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    return mapView
}

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val CNG_CANDIDATES_SOURCE_ID = "cng-candidates-source"
private const val CNG_CANDIDATES_LAYER_ID = "cng-candidates-layer"
