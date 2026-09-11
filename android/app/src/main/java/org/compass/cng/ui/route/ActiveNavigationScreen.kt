package org.compass.cng.ui.route

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddRoad
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.compass.cng.BuildConfig
import org.compass.cng.navigation.GpsStatus
import org.compass.cng.navigation.NavigationCameraConfig
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.mapControlPolicy
import org.compass.cng.navigation.toggleOrientation
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.ui.map.NavigationMap
import org.compass.cng.ui.map.FollowMap
import org.compass.cng.ui.theme.CompassButton
import org.compass.cng.ui.theme.CompassOutlinedButton
import org.compass.cng.ui.theme.CompassTextButton

/** GPS-follow surface shown when no destination or route exists. */
@Composable
internal fun RouteFreeFollowScreen(
    location: NavigationLocation?,
    statusMessage: String? = null,
    onCreateTrip: () -> Unit,
) {
    val cameraConfig = remember { NavigationCameraConfig() }
    var cameraMode by rememberSaveable { mutableStateOf(NavigationCameraMode.FOLLOW) }
    var cameraGestureRevision by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(cameraMode, cameraGestureRevision) {
        if (cameraMode == NavigationCameraMode.FREE) {
            delay(cameraConfig.freeModeAutoRecenterMillis)
            if (cameraMode == NavigationCameraMode.FREE) {
                cameraMode = NavigationCameraMode.FOLLOW
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        FollowMap(
            location = location,
            cameraMode = cameraMode,
            cameraConfig = cameraConfig,
            onCameraModeChange = { mode ->
                if (mode == NavigationCameraMode.FREE) cameraGestureRevision += 1
                cameraMode = mode
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (location == null) {
            Card(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
            ) {
                Text(
                    statusMessage ?: "Ricerca del segnale GPS…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                CompassTextButton(
                    onClick = onCreateTrip,
                    modifier = Modifier.testTag("follow_create_trip"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Rounded.AddRoad, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Crea viaggio")
                }
            }
            if (cameraMode != NavigationCameraMode.FOLLOW) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    CompassTextButton(
                        onClick = { cameraMode = NavigationCameraMode.FOLLOW },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ricentra")
                    }
                }
            }
        }
    }
}

/** Automotive navigation surface. Routing and progress remain authoritative in NavigationState. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveNavigationScreen(
    state: NavigationState,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onVoiceGuidanceEnabledChange: (Boolean) -> Unit,
    onCompleteFuelStop: () -> Unit,
    onCompleteIntermediateStop: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    val navigationView = LocalView.current
    DisposableEffect(navigationView) {
        val wasKeepingScreenOn = navigationView.keepScreenOn
        navigationView.keepScreenOn = true
        onDispose { navigationView.keepScreenOn = wasKeepingScreenOn }
    }
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=driving visible=true")
    }
    val ui = state.toDrivingUiModel()
    LaunchedEffect(ui.isRouteRecalculationInProgress, state.routeUpdateReason) {
        Log.i(
            NAVIGATION_UI_LOG_TAG,
            "route_recalculation_indicator visible=${ui.isRouteRecalculationInProgress} " +
                "reason=${state.routeUpdateReason ?: "none"}",
        )
    }
    LaunchedEffect(ui.currentSpeedLimitKph, state.currentRouteSegmentIndex) {
        Log.i(
            NAVIGATION_UI_LOG_TAG,
            "road_context segment=${state.currentRouteSegmentIndex ?: "unmatched"} " +
                "speed_limit_kph=${ui.currentSpeedLimitKph ?: "unavailable"}",
        )
    }
    LaunchedEffect(
        ui.nextCngStop?.stationId,
        ui.nextCngStop?.lifecycle,
        ui.nextCngStop?.availabilityLabel,
        ui.nextCngStop?.price,
    ) {
        ui.nextCngStop?.let { stop ->
            Log.i(
                NAVIGATION_UI_LOG_TAG,
                "cng_guidance station=${stop.stationId} lifecycle=${stop.lifecycle.name.lowercase()} " +
                    "availability=${stop.availabilityLabel ?: "hidden"} " +
                    "price_visible=${stop.price != null} dwell=${stop.dwellDuration}",
            )
        }
    }
    val cameraConfig = remember { NavigationCameraConfig() }
    var cameraMode by rememberSaveable { mutableStateOf(NavigationCameraMode.FOLLOW) }
    var cameraGestureRevision by rememberSaveable { mutableStateOf(0L) }
    var showTripSummary by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    var confirmFuelStopReplacement by rememberSaveable { mutableStateOf(false) }
    var confirmStopNavigation by rememberSaveable { mutableStateOf(false) }
    var tripSummaryHeightPixels by remember { mutableIntStateOf(0) }
    var routeUpdateNoticeHeightPixels by remember { mutableIntStateOf(0) }
    var routeUpdateNoticeVisible by remember { mutableStateOf(false) }
    val routeUpdateNotice = state.routeUpdateNotice
    val routeUpdateNoticeUi = routeUpdateNotice?.toUiModel()
    LaunchedEffect(routeUpdateNotice) {
        routeUpdateNoticeVisible = false
        routeUpdateNotice?.let { notice ->
            val remainingMillis = routeUpdateNoticeRemainingMillis(
                notice = notice,
                nowEpochMillis = System.currentTimeMillis(),
            )
            if (remainingMillis > 0L) {
                routeUpdateNoticeVisible = true
                Log.i(
                    NAVIGATION_UI_LOG_TAG,
                    "route_update_notice visible=true route=${notice.routeId} " +
                        "duration_delta_seconds=${notice.durationDeltaSeconds.toLong()}",
                )
                delay(remainingMillis)
                routeUpdateNoticeVisible = false
                Log.i(NAVIGATION_UI_LOG_TAG, "route_update_notice visible=false reason=timeout")
            }
        }
    }
    val density = LocalDensity.current
    val bottomPanelHeightPixels =
        (if (showTripSummary) tripSummaryHeightPixels else 0) +
            (if (routeUpdateNoticeVisible) routeUpdateNoticeHeightPixels else 0)
    val bottomObstructionPixels = if (bottomPanelHeightPixels > 0) {
        bottomPanelHeightPixels + WindowInsets.safeDrawing.getBottom(density)
    } else {
        0
    }
    LaunchedEffect(showTripSummary, routeUpdateNoticeVisible, bottomObstructionPixels) {
        if (bottomObstructionPixels > 0) {
            Log.i(
                NAVIGATION_UI_LOG_TAG,
                "bottom_panel_layout obstruction_px=$bottomObstructionPixels " +
                    "trip_visible=$showTripSummary reroute_visible=$routeUpdateNoticeVisible",
            )
        }
    }
    LaunchedEffect(cameraMode, cameraGestureRevision) {
        if (cameraMode == NavigationCameraMode.FREE) {
            delay(cameraConfig.freeModeAutoRecenterMillis)
            if (cameraMode == NavigationCameraMode.FREE) {
                Log.i(NAVIGATION_UI_LOG_TAG, "camera_mode=follow reason=idle_timeout")
                cameraMode = NavigationCameraMode.FOLLOW
            }
        }
    }

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
    if (confirmStopNavigation) {
        NavigationStopConfirmationDialog(
            onDismiss = {
                confirmStopNavigation = false
                Log.i(NAVIGATION_UI_LOG_TAG, "navigation_stop confirmed=false navigation_active=true")
            },
            onConfirm = {
                confirmStopNavigation = false
                Log.i(NAVIGATION_UI_LOG_TAG, "navigation_stop confirmed=true source=map_control")
                onStopNavigation()
            },
        )
    }
    if (showDeveloperTools) {
        NavigationDeveloperScreen(
            state = state,
            onRequestRouteUpdate = onRequestRouteUpdate,
            onSimulateOffRoute = {
                showDeveloperTools = false
                onSimulateOffRoute()
            },
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
            onCompleteFuelStop = onCompleteFuelStop,
            onCompleteIntermediateStop = onCompleteIntermediateStop,
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
            cameraMode = cameraMode,
            cameraConfig = cameraConfig,
            bottomObstructionPixels = bottomObstructionPixels,
            onCameraModeChange = { mode ->
                if (mode == NavigationCameraMode.FREE) {
                    cameraGestureRevision += 1
                }
                if (mode != cameraMode) {
                    Log.i(NAVIGATION_UI_LOG_TAG, "camera_mode=${mode.name.lowercase()} reason=gesture")
                } else {
                    Log.i(NAVIGATION_UI_LOG_TAG, "camera_interaction=gesture")
                }
                cameraMode = mode
            },
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
            ui.nextCngStop?.let { stop ->
                CngGuidanceCard(
                    stop = stop,
                    onOpenDetails = { showDetails = true },
                    onCompleteFuelStop = onCompleteFuelStop,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            state.nextIntermediateStop?.let { stop ->
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (state.activeIntermediateStopVisit != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("navigation_intermediate_stop_card"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                if (state.activeIntermediateStopVisit != null) {
                                    "Tappa raggiunta"
                                } else {
                                    "Tappa intermedia"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.activeIntermediateStopVisit == null) {
                                Text(
                                    stop.distanceRemainingMeters.navigationDistanceLabel(),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (state.activeIntermediateStopVisit != null) {
                            CompassButton(onClick = onCompleteIntermediateStop) {
                                Text("Termina tappa")
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = ui.isRouteRecalculationInProgress,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                RouteRecalculationIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                VoiceGuidanceToggle(
                    enabled = state.voiceGuidanceEnabled,
                    onToggle = {
                        onVoiceGuidanceEnabledChange(!state.voiceGuidanceEnabled)
                    },
                    modifier = Modifier.padding(bottom = 44.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                MapModeControls(
                    cameraMode = cameraMode,
                    tripSummaryVisible = showTripSummary,
                    onOverview = {
                        Log.i(NAVIGATION_UI_LOG_TAG, "camera_mode=overview reason=control")
                        cameraMode = NavigationCameraMode.OVERVIEW
                    },
                    onRecenter = {
                        Log.i(NAVIGATION_UI_LOG_TAG, "camera_mode=follow reason=recenter")
                        cameraMode = NavigationCameraMode.FOLLOW
                    },
                    onToggleOrientation = {
                        val nextMode = cameraMode.toggleOrientation()
                        Log.i(
                            NAVIGATION_UI_LOG_TAG,
                            "camera_mode=${nextMode.name.lowercase()} reason=orientation_control",
                        )
                        cameraMode = nextMode
                    },
                    onShowTripSummary = {
                        showTripSummary = true
                        Log.i(NAVIGATION_UI_LOG_TAG, "trip_summary visible=true")
                    },
                    onStopNavigation = {
                        confirmStopNavigation = true
                        Log.i(
                            NAVIGATION_UI_LOG_TAG,
                            "navigation_stop confirmation_visible=true source=map_control",
                        )
                    },
                )
            }
            routeUpdateNoticeUi?.let { notice ->
                AnimatedVisibility(
                    visible = routeUpdateNoticeVisible,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { routeUpdateNoticeHeightPixels = it.height },
                    ) {
                        RouteUpdateNoticeCard(
                            notice = notice,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (showTripSummary) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { tripSummaryHeightPixels = it.height },
                ) {
                    TripBottomBar(
                        ui = ui,
                        onOpenDetails = { showDetails = true },
                        onHide = {
                            showTripSummary = false
                            Log.i(NAVIGATION_UI_LOG_TAG, "trip_summary visible=false")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CngGuidanceCard(
    stop: NavigationCngUiModel,
    onOpenDetails: () -> Unit,
    onCompleteFuelStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .testTag("navigation_next_cng_stop")
            .clickable(
                enabled = stop.lifecycle != org.compass.cng.navigation.NavigationFuelStopLifecycle.REFUELING,
                onClick = onOpenDetails,
            )
            .then(
                if (stop.lifecycle ==
                    org.compass.cng.navigation.NavigationFuelStopLifecycle.REFUELING
                ) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics {
                        contentDescription = buildString {
                            append("Prossima tappa CNG, ${stop.name}, ${stop.distance}")
                            stop.arrivalTime?.let { append(", arrivo $it") }
                            stop.availabilityLabel?.let { append(", $it") }
                            stop.price?.let { append(", prezzo $it") }
                            append(", ${stop.lifecycleLabel}")
                        }
                    }
                }
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = "CNG",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stop.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(stop.distance)
                            stop.arrivalTime?.let { append(" · arrivo $it") }
                            append(" · ${stop.lifecycleLabel}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    stop.availabilityLabel?.let { availability ->
                        Text(
                            text = availability,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (stop.availabilityIsWarning) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    stop.price?.let { price ->
                        Text(
                            text = price,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (stop.lifecycle == org.compass.cng.navigation.NavigationFuelStopLifecycle.REFUELING) {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (stop.refuelingPlannedDurationElapsed) {
                            "Tempo previsto concluso"
                        } else {
                            "Rifornimento · ${stop.refuelingRemainingDuration} rimanenti"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompassButton(
                        onClick = onCompleteFuelStop,
                        modifier = Modifier.testTag("navigation_complete_refueling"),
                    ) {
                        Text("Completato")
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteRecalculationIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .testTag("navigation_route_recalculation_indicator")
            .clearAndSetSemantics { contentDescription = "Ricalcolo rotta in corso" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.primary,
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Text(
                text = "Ricalcolo rotta",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RouteUpdateNoticeCard(
    notice: RouteUpdateNoticeUiModel,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .testTag("navigation_route_update_notice")
            .clearAndSetSemantics { contentDescription = notice.accessibilityDescription },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "Percorso alternativo calcolato",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DrivingSummaryValue("Prima", notice.previousDuration)
                DrivingSummaryValue("Ora", notice.updatedDuration)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = notice.durationDifference,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (notice.addsTime) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                    )
                    Text(
                        text = "Differenza",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceGuidanceToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        modifier = modifier
            .size(width = 76.dp, height = 58.dp)
            .testTag("navigation_voice_toggle")
            .clearAndSetSemantics {
                contentDescription = if (enabled) {
                    "Voce attiva. Tocca per disattivare"
                } else {
                    "Voce disattivata. Tocca per attivare"
                }
            },
        shape = CircleShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        tonalElevation = 7.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (enabled) {
                    Icons.AutoMirrored.Rounded.VolumeUp
                } else {
                    Icons.AutoMirrored.Rounded.VolumeOff
                },
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = if (enabled) "Voce" else "Muta",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ManeuverOverlay(ui: NavigationDrivingUiModel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.testTag("navigation_maneuver_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryManeuverIcon(
                    visual = ui.maneuverVisual,
                    roundaboutExitCount = ui.roundaboutExitCount,
                )
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
            ui.junctionSign?.let { sign ->
                JunctionSignPanel(
                    sign = sign,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            ui.followingInstruction?.let { following ->
                HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ui.followingManeuverVisual?.let { visual ->
                        ManeuverIcon(
                            visual = visual,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                    }
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
}

@Composable
private fun PrimaryManeuverIcon(
    visual: ManeuverVisual,
    roundaboutExitCount: Int?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(62.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ManeuverIcon(
                visual = visual,
                modifier = Modifier.size(46.dp),
            )
            roundaboutExitCount?.let { count ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(22.dp)
                        .testTag("navigation_roundabout_exit_count"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JunctionSignPanel(
    sign: NavigationJunctionSignUiModel,
    modifier: Modifier = Modifier,
) {
    val heading = listOfNotNull(
        sign.exitNumber?.let { "Uscita $it" },
        sign.branches,
    ).joinToString(" · ")
    val destination = listOfNotNull(
        sign.exitName,
        sign.toward?.let { "verso $it" },
    ).joinToString(" · ")
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.testTag("navigation_junction_sign"),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (heading.isNotBlank()) {
                    Text(
                        text = heading,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (destination.isNotBlank()) {
                    Text(
                        text = destination,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapModeControls(
    cameraMode: NavigationCameraMode,
    tripSummaryVisible: Boolean,
    onOverview: () -> Unit,
    onRecenter: () -> Unit,
    onToggleOrientation: () -> Unit,
    onShowTripSummary: () -> Unit,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val policy = cameraMode.mapControlPolicy()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!tripSummaryVisible) {
            Surface(
                shape = CircleShape,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                CompassTextButton(
                    onClick = onShowTripSummary,
                    modifier = Modifier.testTag("navigation_trip_toggle"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Viaggio") }
            }
        }
        if (policy.showOrientationToggle) {
            MapIconControlButton(
                testTag = "navigation_orientation",
                contentDescription = if (cameraMode == NavigationCameraMode.NORTH_UP) {
                    "Orientamento nord in alto. Tocca per seguire la direzione di marcia"
                } else {
                    "Orientamento direzione di marcia. Tocca per mantenere il nord in alto"
                },
                onClick = onToggleOrientation,
            ) {
                Icon(
                    imageVector = if (cameraMode == NavigationCameraMode.NORTH_UP) {
                        Icons.Rounded.Explore
                    } else {
                        Icons.Rounded.Navigation
                    },
                    contentDescription = null,
                )
            }
        }
        if (policy.showOverview) {
            MapIconControlButton(
                testTag = "navigation_overview",
                contentDescription = "Mostra la panoramica del percorso rimanente",
                onClick = onOverview,
            ) {
                Icon(Icons.Rounded.Route, contentDescription = null)
            }
        }
        if (policy.showRecenter) {
            Surface(
                shape = CircleShape,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                CompassTextButton(
                    onClick = onRecenter,
                    modifier = Modifier.testTag("navigation_recenter"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text("Ricentra") }
            }
        }
        MapIconControlButton(
            testTag = "navigation_stop",
            contentDescription = "Termina navigazione",
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onStopNavigation,
        ) {
            Icon(Icons.Rounded.StopCircle, contentDescription = null)
        }
    }
}

@Composable
private fun MapIconControlButton(
    testTag: String,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .testTag(testTag)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        tonalElevation = 6.dp,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun OrientationGlyph(northUp: Boolean) {
    if (northUp) {
        Text(
            text = "N",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
        )
        return
    }
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(24.dp)) {
        val tip = Offset(size.width / 2f, size.height * 0.18f)
        drawLine(
            color,
            Offset(size.width / 2f, size.height * 0.82f),
            tip,
            3.dp.toPx(),
            StrokeCap.Round,
        )
        drawLine(
            color,
            tip,
            Offset(size.width * 0.28f, size.height * 0.42f),
            3.dp.toPx(),
            StrokeCap.Round,
        )
        drawLine(
            color,
            tip,
            Offset(size.width * 0.72f, size.height * 0.42f),
            3.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

@Composable
private fun RouteOverviewGlyph() {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(25.dp)) {
        val start = Offset(size.width * 0.20f, size.height * 0.78f)
        val bend = Offset(size.width * 0.43f, size.height * 0.42f)
        val end = Offset(size.width * 0.80f, size.height * 0.20f)
        drawLine(color, start, bend, 3.dp.toPx(), StrokeCap.Round)
        drawLine(color, bend, end, 3.dp.toPx(), StrokeCap.Round)
        drawCircle(color, 3.5.dp.toPx(), start)
        drawCircle(color, 3.5.dp.toPx(), end)
    }
}

@Composable
private fun StopNavigationGlyph() {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(24.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.22f, size.height * 0.22f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
private fun TripBottomBar(
    ui: NavigationDrivingUiModel,
    onOpenDetails: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.testTag("navigation_bottom_bar"),
        shape = MaterialTheme.shapes.large,
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
                    Surface(
                        modifier = Modifier.testTag("navigation_cng_badge"),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = "CNG",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CompassTextButton(
                    onClick = onHide,
                    modifier = Modifier.testTag("navigation_trip_toggle"),
                ) {
                    TripPanelActionLabel("Nascondi", pointsUp = false)
                }
                CompassTextButton(
                    onClick = onOpenDetails,
                    modifier = Modifier.testTag("navigation_details_button"),
                ) {
                    TripPanelActionLabel("Dettagli", pointsUp = true)
                }
            }
        }
    }
}

@Composable
private fun TripPanelActionLabel(label: String, pointsUp: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label)
        val color = androidx.compose.material3.LocalContentColor.current
        Canvas(
            modifier = Modifier
                .size(14.dp)
                .clearAndSetSemantics {},
        ) {
            val upperY = size.height * 0.34f
            val lowerY = size.height * 0.66f
            val centerY = if (pointsUp) upperY else lowerY
            val outerY = if (pointsUp) lowerY else upperY
            val strokeWidth = 1.8.dp.toPx()
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.16f, outerY),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.5f, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.5f, centerY),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.84f, outerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
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
    onCompleteFuelStop: () -> Unit,
    onCompleteIntermediateStop: () -> Unit,
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
            modifier = Modifier.fillMaxWidth(),
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
                itemsIndexed(ui.cngStops) { index, stop ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${index + 1}. ${stop.name}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        listOfNotNull(stop.operatorLabel, stop.locationLabel)
                            .joinToString(" · ")
                            .takeIf(String::isNotBlank)
                            ?.let { subtitle ->
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        Text(
                            buildString {
                                append(stop.lifecycleLabel)
                                append(" · ${stop.distance}")
                                stop.arrivalTime?.let { append(" · arrivo $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stop.reason,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            stop.availabilityLabel?.let { availability ->
                                Text(
                                    availability,
                                    color = if (stop.availabilityIsWarning) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            stop.price?.let { price ->
                                Text(price, fontWeight = FontWeight.Bold)
                            }
                        }
                        stop.openingHours?.let { hours ->
                            Text(
                                hours,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Sosta prevista ${stop.dwellDuration}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        stop.phone?.let { phone ->
                            Text(
                                "Telefono $phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
                state.nextFuelStop?.let {
                    item {
                        CompassOutlinedButton(
                            onClick = onReplaceFuelStop,
                            enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS &&
                                state.activeFuelStopVisit == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Salta o sostituisci la prossima tappa CNG")
                        }
                    }
                }
                state.activeFuelStopVisit?.let { visit ->
                    item {
                        CompassButton(
                            onClick = {
                                onDismiss()
                                onCompleteFuelStop()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("navigation_details_complete_refueling"),
                        ) {
                            Text(
                                if (visit.plannedDurationElapsed) {
                                    "Conferma e riprendi il percorso"
                                } else {
                                    "Rifornimento completato"
                                },
                            )
                        }
                    }
                }
                state.activeIntermediateStopVisit?.let {
                    item {
                        CompassButton(
                            onClick = {
                                onDismiss()
                                onCompleteIntermediateStop()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("navigation_details_complete_intermediate_stop"),
                        ) {
                            Text("Termina tappa e riprendi")
                        }
                    }
                }
            }
            item {
                CompassOutlinedButton(
                    onClick = onRequestRouteUpdate,
                    enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS &&
                        state.activeFuelStopVisit == null &&
                        state.activeIntermediateStopVisit == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ricalcola percorso")
                }
            }
            if (BuildConfig.DEBUG) {
                item {
                    CompassTextButton(
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
                CompassOutlinedButton(
                    onClick = onStopNavigation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Termina navigazione")
                }
            }
        }
    }
}

private fun Double.navigationDistanceLabel(): String = if (this >= 1_000.0) {
    String.format(java.util.Locale.ITALIAN, "%.1f km", this / 1_000.0)
} else {
    "${(this / 10.0).toInt() * 10} m"
}

@Composable
private fun NavigationDeveloperScreen(
    state: NavigationState,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onClose: () -> Unit,
) {
    val route = requireNotNull(state.route)
    var showManeuverGallery by rememberSaveable { mutableStateOf(false) }
    var showJunctionSignGallery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=developer_tools visible=true")
    }
    if (showManeuverGallery) {
        NavigationManeuverGallery(
            onClose = { showManeuverGallery = false },
        )
        return
    }
    if (showJunctionSignGallery) {
        NavigationJunctionSignGallery(
            onClose = { showJunctionSignGallery = false },
        )
        return
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
                        CompassTextButton(onClick = onClose) { Text("Chiudi") }
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
                            Text("Limite tratto: ${state.currentSpeedLimitKph?.let { "$it km/h" } ?: "—"}")
                            Text("Fonte limiti: ${route.speedLimitSource ?: "NON DISPONIBILE"}")
                            Text("Sorgente rotta: ${state.routeSource}")
                            Text("Connettività: ${state.connectivity}")
                            Text("Fuori rotta: ${state.offRouteStatus}")
                            Text("Ricalcolo: ${state.reroutingStatus}")
                            Text(
                                "Posizione guida: " + if (state.navigationPosition == null) {
                                    "NON DISPONIBILE"
                                } else {
                                    "AGGANCIATA AL PERCORSO"
                                },
                            )
                            state.navigationPosition?.let { position ->
                                Text("Precisione fix: ${position.horizontalAccuracyMeters.toInt()} m")
                                Text("Velocità filtrata: ${position.speedMetersPerSecond.toInt()} m/s")
                                Text("Direzione stabilizzata: ${position.bearingDegrees.toInt()}°")
                            }
                            Text("Fix rifiutati: ${state.rejectedLocationCount}")
                            Text("ID rotta: ${route.routeId}")
                            state.lastSpokenInstruction?.let { Text("Voce: $it") }
                        }
                    }
                }
                item {
                    CompassOutlinedButton(
                        onClick = { showManeuverGallery = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("navigation_maneuver_gallery_entry"),
                    ) {
                        Text("Verifica iconografia manovre")
                    }
                }
                item {
                    CompassOutlinedButton(
                        onClick = { showJunctionSignGallery = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("navigation_junction_sign_gallery_entry"),
                    ) {
                        Text("Verifica segnaletica e uscite")
                    }
                }
                item {
                    CompassOutlinedButton(
                        onClick = onRequestRouteUpdate,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ricalcola percorso (debug)")
                    }
                }
                item {
                    CompassOutlinedButton(
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
private fun NavigationManeuverGallery(onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=maneuver_gallery visible=true types=37")
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("navigation_maneuver_gallery"),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Iconografia manovre",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Tipi Valhalla 0–36 · solo debug",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CompassTextButton(onClick = onClose) { Text("Chiudi") }
                    }
                }
                itemsIndexed(
                    items = valhallaManeuverVisualCatalog,
                    key = { _, visual -> visual.type ?: -1 },
                ) { _, visual ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(50.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    ManeuverIcon(
                                        visual = visual,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    "Tipo ${visual.type}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    visual.accessibilityLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class JunctionSignSample(
    val title: String,
    val instruction: String,
    val visual: ManeuverVisual,
    val roundaboutExitCount: Int? = null,
    val sign: NavigationJunctionSignUiModel? = null,
)

private val junctionSignSamples = listOf(
    JunctionSignSample(
        title = "Uscita autostradale",
        instruction = "Prendi l'uscita verso Bologna Casalecchio.",
        visual = maneuverVisual(20, null),
        sign = NavigationJunctionSignUiModel(
            exitNumber = "1",
            branches = "A14",
            toward = "Bologna / Ancona",
            exitName = "Bologna Casalecchio",
        ),
    ),
    JunctionSignSample(
        title = "Rotatoria",
        instruction = "Prendi la 2ª uscita.",
        visual = maneuverVisual(26, null),
        roundaboutExitCount = 2,
    ),
    JunctionSignSample(
        title = "Diramazione a destra",
        instruction = "Mantieni la destra verso Firenze.",
        visual = maneuverVisual(23, null),
        sign = NavigationJunctionSignUiModel(
            exitNumber = null,
            branches = "A1 / E 35",
            toward = "Firenze",
            exitName = null,
        ),
    ),
    JunctionSignSample(
        title = "Diramazione a sinistra",
        instruction = "Mantieni la sinistra verso Milano.",
        visual = maneuverVisual(24, null),
        sign = NavigationJunctionSignUiModel(
            exitNumber = null,
            branches = "A1 / E 35",
            toward = "Milano",
            exitName = null,
        ),
    ),
)

@Composable
private fun NavigationJunctionSignGallery(onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=junction_sign_gallery visible=true samples=4")
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("navigation_junction_sign_gallery"),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 16.dp,
                    bottom = 24.dp,
                ),
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
                                "Segnaletica e uscite",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Esempi di resa · solo debug",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CompassTextButton(onClick = onClose) { Text("Chiudi") }
                    }
                }
                itemsIndexed(junctionSignSamples) { _, sample ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PrimaryManeuverIcon(
                                    visual = sample.visual,
                                    roundaboutExitCount = sample.roundaboutExitCount,
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        sample.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        sample.instruction,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            sample.sign?.let { JunctionSignPanel(it) }
                        }
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
            CompassTextButton(onClick = onConfirm) { Text("Cerca alternativa") }
        },
        dismissButton = {
            CompassTextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}

@Composable
private fun NavigationStopConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminare la navigazione?") },
        text = {
            Text("Il percorso attivo verrà chiuso e Compass tornerà a seguire la posizione GPS.")
        },
        confirmButton = {
            CompassTextButton(onClick = onConfirm) { Text("Termina") }
        },
        dismissButton = {
            CompassTextButton(onClick = onDismiss) { Text("Continua") }
        },
    )
}

private const val NAVIGATION_UI_LOG_TAG = "CompassNavigationUi"
