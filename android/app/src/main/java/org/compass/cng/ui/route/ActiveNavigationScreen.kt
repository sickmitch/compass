package org.compass.cng.ui.route

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.compass.cng.BuildConfig
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.ui.map.NavigationMap

/** Automotive navigation surface. Routing and progress remain authoritative in NavigationState. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveNavigationScreen(
    state: NavigationState,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    requireNotNull(state.route)
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=driving visible=true")
    }
    val ui = state.toDrivingUiModel()
    var cameraMode by rememberSaveable { mutableStateOf(NavigationCameraMode.FOLLOW) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    var confirmFuelStopReplacement by rememberSaveable { mutableStateOf(false) }

    if (confirmFuelStopReplacement) {
        FuelStopReplacementDialog(
            state = state,
            onDismiss = { confirmFuelStopReplacement = false },
            onConfirm = {
                confirmFuelStopReplacement = false
                showDetails = false
                onReplaceUnavailableFuelStop()
            },
        )
    }
    if (showDeveloperTools) {
        NavigationDeveloperScreen(
            state = state,
            onRequestRouteUpdate = onRequestRouteUpdate,
            onSimulateOffRoute = onSimulateOffRoute,
            onClose = { showDeveloperTools = false },
        )
    }
    if (showDetails) {
        NavigationDetailsSheet(
            state = state,
            ui = ui,
            onDismiss = { showDetails = false },
            onRequestRouteUpdate = onRequestRouteUpdate,
            onReplaceFuelStop = { confirmFuelStopReplacement = true },
            onOpenDeveloperTools = {
                showDetails = false
                showDeveloperTools = true
            },
            onStopNavigation = onStopNavigation,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationMap(
            state = state,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            cameraMode = cameraMode,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            ManeuverOverlay(
                ui = ui,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            MapModeControls(
                cameraMode = cameraMode,
                onOverview = { cameraMode = NavigationCameraMode.OVERVIEW },
                onRecenter = { cameraMode = NavigationCameraMode.FOLLOW },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            TripBottomBar(
                ui = ui,
                onOpenDetails = { showDetails = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ManeuverOverlay(ui: NavigationDrivingUiModel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.testTag("navigation_maneuver_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(62.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = ui.maneuverSymbol,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ui.distanceToManeuver,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = ui.primaryInstruction,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ui.targetRoad?.let { road ->
                        Text(
                            text = road,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ui.followingInstruction?.let { following ->
                HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Text(
                    text = "Poi · $following",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MapModeControls(
    cameraMode: NavigationCameraMode,
    onOverview: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = CircleShape,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            TextButton(onClick = onOverview) { Text("Panoramica") }
        }
        if (cameraMode != NavigationCameraMode.FOLLOW) {
            Surface(
                shape = CircleShape,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                TextButton(onClick = onRecenter) { Text("Ricentra") }
            }
        }
    }
}

@Composable
private fun TripBottomBar(
    ui: NavigationDrivingUiModel,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.testTag("navigation_bottom_bar"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            LinearProgressIndicator(
                progress = { ui.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DrivingSummaryValue("Rimanenti", ui.remainingDistance)
                DrivingSummaryValue("Durata", ui.remainingDuration)
                DrivingSummaryValue("Arrivo", ui.arrivalTime)
            }
            ui.nextCngStop?.let { stop ->
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "⛽", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stop.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(stop.distance)
                                stop.arrivalTime?.let { append(" · arrivo $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(
                onClick = onOpenDetails,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("navigation_details_button"),
            ) {
                Text("Dettagli viaggio ︿")
            }
        }
    }
}

@Composable
private fun DrivingSummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationDetailsSheet(
    state: NavigationState,
    ui: NavigationDrivingUiModel,
    onDismiss: () -> Unit,
    onRequestRouteUpdate: () -> Unit,
    onReplaceFuelStop: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    val route = requireNotNull(state.route)
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=trip_details visible=true")
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("navigation_details_sheet"),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Dettagli viaggio",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DrivingSummaryValue("Rimanenti", ui.remainingDistance)
                    DrivingSummaryValue("Durata", ui.remainingDuration)
                    DrivingSummaryValue("Arrivo", ui.arrivalTime)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.statusMessages.forEach { message ->
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (message.level) {
                                NavigationStatusLevel.NORMAL ->
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                NavigationStatusLevel.POSITIVE -> MaterialTheme.colorScheme.primary
                                NavigationStatusLevel.WARNING -> MaterialTheme.colorScheme.error
                            },
                            fontWeight = if (message.level == NavigationStatusLevel.NORMAL) {
                                FontWeight.Normal
                            } else {
                                FontWeight.SemiBold
                            },
                        )
                    }
                }
            }
            if (route.fuelStops.isNotEmpty()) {
                item {
                    Text(
                        "Tappe CNG pianificate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(route.fuelStops) { index, stop ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${index + 1}. ${stop.displayName()}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append(listOfNotNull(stop.municipality, stop.province).joinToString(" · "))
                                stop.expectedArrivalAt?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("arrivo ${it.format(DETAIL_CLOCK_FORMATTER)}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Sosta prevista ${formatDuration(stop.dwellTimeSeconds.toDouble())}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
                state.nextFuelStop?.let {
                    item {
                        OutlinedButton(
                            onClick = onReplaceFuelStop,
                            enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Salta o sostituisci la prossima tappa CNG")
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onRequestRouteUpdate,
                    enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ricalcola percorso")
                }
            }
            if (BuildConfig.DEBUG) {
                item {
                    TextButton(
                        onClick = onOpenDeveloperTools,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("navigation_debug_entry"),
                    ) {
                        Text("Strumenti sviluppatore")
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onStopNavigation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Termina navigazione")
                }
            }
        }
    }
}

@Composable
private fun NavigationDeveloperScreen(
    state: NavigationState,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onClose: () -> Unit,
) {
    val route = requireNotNull(state.route)
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=developer_tools visible=true")
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("navigation_developer_screen"),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Strumenti sviluppatore",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Non usare durante la guida",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        TextButton(onClick = onClose) { Text("Chiudi") }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text("Diagnostica navigazione", fontWeight = FontWeight.Bold)
                            Text("Fase: ${state.phase}")
                            Text("GPS: ${state.gpsStatus}")
                            Text("Segmento: ${state.currentRouteSegmentIndex ?: "—"}")
                            Text("Sorgente rotta: ${state.routeSource}")
                            Text("Connettività: ${state.connectivity}")
                            Text("Fuori rotta: ${state.offRouteStatus}")
                            Text("Ricalcolo: ${state.reroutingStatus}")
                            Text("ID rotta: ${route.routeId}")
                            state.lastSpokenInstruction?.let { Text("Voce: $it") }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onRequestRouteUpdate,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ricalcola percorso (debug)")
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onSimulateOffRoute,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Simula deviazione (debug)")
                    }
                }
            }
        }
    }
}

@Composable
private fun FuelStopReplacementDialog(
    state: NavigationState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sostituire la tappa CNG?") },
        text = {
            Text(
                "Compass escluderà ${state.nextFuelStop?.stop?.displayName() ?: "la stazione selezionata"} " +
                    "e cercherà un itinerario completo compatibile con autonomia e riserva. " +
                    "Se non esiste, manterrà la rotta corrente.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Cerca alternativa") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}

private val DETAIL_CLOCK_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private const val NAVIGATION_UI_LOG_TAG = "CompassNavigationUi"
