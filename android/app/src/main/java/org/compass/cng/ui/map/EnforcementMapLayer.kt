package org.compass.cng.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.compass.cng.CompassApplication
import org.compass.cng.enforcement.EnforcementControl
import org.compass.cng.enforcement.EnforcementMapState
import org.compass.cng.enforcement.mapControls
import org.compass.cng.navigation.NavigationLocation
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.Property.TEXT_PITCH_ALIGNMENT_VIEWPORT
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.textPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val SOURCE = "compass-enforcement"
private const val LAYER = "compass-enforcement-markers"
private const val IMAGE = "compass-enforcement-camera"
private val ENFORCEMENT_YELLOW = 0xFFFFE69A.toInt()
private val ENFORCEMENT_INK = 0xFF302915.toInt()

@Composable
internal fun rememberEnforcementState(location: NavigationLocation?): EnforcementMapState {
    val application = LocalContext.current.applicationContext as? CompassApplication
        ?: return EnforcementMapState()
    val repository = application.container.enforcementRepository
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLocation by rememberUpdatedState(location)
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                withContext(Dispatchers.IO) { repository.update(currentLocation) }
                delay(5_000)
            }
        }
    }
    val container = application.container
    val navigation by container.navigationSession.state.collectAsStateWithLifecycle()
    val freeFollow = navigation.phase in setOf(
        org.compass.cng.navigation.NavigationPhase.IDLE,
        org.compass.cng.navigation.NavigationPhase.ROUTE_PREVIEW,
    )
    LaunchedEffect(container, lifecycle, freeFollow) {
        if (freeFollow) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val voice = org.compass.cng.navigation.AndroidTextToSpeechVoiceGuidance(application)
            try {
                while (isActive) {
                    container.enforcementApproach.update(currentLocation)
                    container.enforcementApproach.claimSpeech(
                        container.appPreferencesRepository.load().voiceGuidanceDefault,
                    )?.let { voice.speak(it) }
                    delay(1_000)
                }
            } finally { voice.shutdown() }
        }
    }
    return repository.state.collectAsStateWithLifecycle().value
}

/** Also called on style load: theme changes must not discard the control layer. */
internal fun installEnforcementLayer(style: Style, state: EnforcementMapState) {
    if (style.getSource(SOURCE) == null) {
        style.addSource(GeoJsonSource(SOURCE, enforcementFeatures(state)))
        // MapLibre divides bitmap pixels by density / 160: 80 px at xhdpi = 40 dp
        // on every device, independent of Bitmap.createBitmap's device default density.
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            density = DisplayMetrics.DENSITY_XHIGH
        }
        val canvas = Canvas(bitmap)
        canvas.scale(1.25f, 1.25f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ENFORCEMENT_INK
        canvas.drawCircle(32f, 32f, 30f, paint)
        paint.color = ENFORCEMENT_YELLOW
        canvas.drawCircle(32f, 32f, 26f, paint)
        paint.color = ENFORCEMENT_INK
        canvas.drawRoundRect(15f, 23f, 49f, 44f, 4f, 4f, paint)
        canvas.drawRect(22f, 18f, 33f, 25f, paint)
        paint.color = ENFORCEMENT_YELLOW
        canvas.drawCircle(33f, 33f, 8f, paint)
        style.addImage(IMAGE, bitmap)
        style.addLayer(SymbolLayer(LAYER, SOURCE).withProperties(
            iconImage(IMAGE), iconSize(1f), iconAllowOverlap(true),
            iconPitchAlignment(ICON_PITCH_ALIGNMENT_VIEWPORT),
            textPitchAlignment(TEXT_PITCH_ALIGNMENT_VIEWPORT), textOptional(true),
            textField(get("label")), textFont(arrayOf("Noto Sans Regular")),
            textSize(14f), textOffset(arrayOf(0f, 2.1f)),
            textColor(ENFORCEMENT_INK), textHaloColor(ENFORCEMENT_YELLOW), textHaloWidth(2f),
        ).apply { minZoom = 11.0f })
    } else {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(enforcementFeatures(state))
    }
}

private fun enforcementFeatures(state: EnforcementMapState): FeatureCollection =
    FeatureCollection.fromFeatures(state.data?.mapControls().orEmpty().map { control ->
        Feature.fromGeometry(Point.fromLngLat(control.longitude, control.latitude)).apply {
            addStringProperty("key", control.physicalControlKey)
            addStringProperty("kind", control.kind)
            val label = if (control.kind == "average_speed") "Tutor" else "Velox"
            addStringProperty("label", control.speedLimitKph?.let { "$label · ${it.toInt()} km/h" } ?: label)
        }
    })

@Composable
internal fun EnforcementMapOverlay(mapView: MapView, state: EnforcementMapState, modifier: Modifier) {
    var details by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<EnforcementControl?>(null) }
    val currentState by rememberUpdatedState(state)
    val uriHandler = LocalUriHandler.current
    val application = LocalContext.current.applicationContext as? CompassApplication
    val approach = application?.container?.enforcementApproach?.notice
        ?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(mapView, state.data) {
        selected = selected?.let { old -> currentState.data?.mapControls()?.firstOrNull {
            it.physicalControlKey == old.physicalControlKey && it.kind == old.kind
        } }
        mapView.getMapAsync { map -> map.style?.let { installEnforcementLayer(it, currentState) } }
    }
    DisposableEffect(mapView) {
        var mapReference: MapLibreMap? = null
        var disposed = false
        val listener = MapLibreMap.OnMapClickListener { point ->
            val map = mapReference
            val feature = map?.queryRenderedFeatures(map.projection.toScreenLocation(point), LAYER)
                ?.firstOrNull()
            if (feature == null) false else {
                selected = currentState.data?.mapControls()?.firstOrNull {
                    it.physicalControlKey == feature.getStringProperty("key") &&
                        it.kind == feature.getStringProperty("kind")
                }
                details = selected != null
                details
            }
        }
        mapView.getMapAsync { map ->
            if (!disposed) { mapReference = map; map.addOnMapClickListener(listener) }
        }
        onDispose { disposed = true; mapReference?.removeOnMapClickListener(listener) }
    }
    if (approach != null) Surface(
        color = Color(ENFORCEMENT_YELLOW),
        contentColor = Color(ENFORCEMENT_INK),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.widthIn(max = 280.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("${approach.label} · ${approach.roundedDistance} m",
                style = MaterialTheme.typography.titleMedium)
            Text(approach.control.speedLimitKph?.let { "Limite ${it.toInt()} km/h" }
                ?: "Limite non disponibile", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (details) {
        val data = state.data
        val control = selected
        val sourceTime = control?.fetchedAt ?: data?.controls?.minOfOrNull { it.fetchedAt }
        val date = sourceTime?.let {
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.parse(it))
        }
        AlertDialog(onDismissRequest = { details = false },
            title = { Text(if (control?.kind == "average_speed") "Tutor" else "Autovelox") },
            text = { Text(buildString {
                if (control != null) append(control.speedLimitKph?.let {
                    "Limite segnalato: ${it.toInt()} km/h.\n"
                } ?: "Limite non disponibile.\n")
                append("Le icone indicano le posizioni censite. Gli avvisi richiedono strada e direzione verificate; i controlli non associati restano visibili solo sulla mappa.\n")
                if (state.saved) append("Dati salvati sul telefono; aggiornamento non verificato.\n")
                if (state.unavailable) append("Aggiornamento non disponibile.\n")
                if (data == null) append("Nessun pacchetto disponibile per questa posizione.\n")
                if (data?.truncated == true) append("Sono disponibili solo alcuni dei controlli nell’area.\n")
                if (date != null) append("Dati acquisiti: $date.\n")
                append("L’assenza di icone non garantisce l’assenza di controlli.\n\n")
                append(data?.attribution ?: "© OpenStreetMap contributors — ODbL 1.0")
            }) },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Chiudi") } },
            dismissButton = { TextButton(onClick = {
                uriHandler.openUri("https://www.openstreetmap.org/copyright")
            }) { Text("Fonte e licenza") } })
    }
}
