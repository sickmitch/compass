package org.compass.cng.ui.map

import android.os.Bundle
import android.util.Log
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
import org.maplibre.android.style.layers.SymbolLayer
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
    modifier: Modifier = Modifier,
    candidateStations: List<RankedCngStation> = emptyList(),
    selectedCandidateStationId: String? = null,
    cngStops: List<Coordinate> = emptyList(),
) {
    val mapView = rememberMapViewWithLifecycle()
    val appearance = compassMapAppearance()

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )

    LaunchedEffect(
        mapView,
        route,
        appearance,
        candidateStations,
        selectedCandidateStationId,
        cngStops,
    ) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(appearance.styleUrl)) { style ->
                Log.i(
                    COMPASS_MAP_LOG_TAG,
                    "map_style_loaded surface=preview theme=${appearance.theme.name.lowercase()} " +
                        "source=${mapStyleSource(appearance.styleUrl)}",
                )
                val routePoints = route.geometry.map {
                    Point.fromLngLat(it.longitude, it.latitude)
                }
                if (routePoints.size >= 2) {
                    val firstMapLabelLayerId = style.layers.firstOrNull { it is SymbolLayer }?.id
                    style.addSource(
                        GeoJsonSource(
                            ROUTE_SOURCE_ID,
                            Feature.fromGeometry(LineString.fromLngLats(routePoints)),
                        ),
                    )
                    val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                            lineColor(appearance.palette.route),
                            lineWidth(6f),
                            lineCap(LINE_CAP_ROUND),
                            lineJoin(LINE_JOIN_ROUND),
                        )
                    if (firstMapLabelLayerId == null) {
                        style.addLayer(routeLayer)
                    } else {
                        style.addLayerBelow(routeLayer, firstMapLabelLayerId)
                    }
                }
                addEndpointLayer(
                    style = style,
                    idPrefix = "origin",
                    coordinate = route.origin,
                    color = appearance.palette.origin,
                    strokeColor = appearance.palette.markerStroke,
                )
                addEndpointLayer(
                    style = style,
                    idPrefix = "destination",
                    coordinate = route.destination,
                    color = appearance.palette.destination,
                    strokeColor = appearance.palette.markerStroke,
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
                            circleColor(appearance.palette.cng),
                            circleStrokeColor(appearance.palette.markerStroke),
                            circleStrokeWidth(1.5f),
                        ),
                    )
                    candidateStations
                        .firstOrNull { it.mimitStationId == selectedCandidateStationId }
                        ?.let { selected ->
                            addEndpointLayer(
                                style = style,
                                idPrefix = "selected-cng-candidate",
                                coordinate = selected.location,
                                color = appearance.palette.selectedCng,
                                strokeColor = appearance.palette.markerStroke,
                            )
                        }
                }
                cngStops.forEachIndexed { index, stop ->
                    addEndpointLayer(
                        style = style,
                        idPrefix = "cng-stop-$index",
                        coordinate = stop,
                        color = appearance.palette.cng,
                        strokeColor = appearance.palette.markerStroke,
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
    strokeColor: Int,
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
            circleStrokeColor(strokeColor),
            circleStrokeWidth(2f),
        ),
    )
}

@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
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
private const val COMPASS_MAP_LOG_TAG = "CompassNavigationUi"
