package org.compass.cng.ui.route

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddRoad
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
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
import androidx.compose.ui.graphics.Color
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.compass.cng.BuildConfig
import org.compass.cng.navigation.NavigationCameraConfig
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.mapControlPolicy
import org.compass.cng.navigation.toggleOrientation
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.navigation.NavigationIntermediateStopProgress
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.ui.map.NavigationMap
import org.compass.cng.ui.map.FollowMap
import org.compass.cng.ui.theme.CompassButton
import org.compass.cng.ui.theme.CompassOutlinedButton
import org.compass.cng.ui.theme.CompassTextButton
import org.compass.cng.ui.theme.compassSemanticColors

/** GPS-follow surface shown when no destination or route exists. */
@Composable
internal fun RouteFreeFollowScreen(
    location: NavigationLocation?,
    statusMessage: String? = null,
    onCreateTrip: () -> Unit,
    onOpenOptions: () -> Unit,
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
            MapIconControlButton(
                testTag = "follow_options",
                contentDescription = "Opzioni",
                onClick = onOpenOptions,
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
            }
            MapIconControlButton(
                testTag = "follow_create_trip",
                contentDescription = "Crea viaggio",
                onClick = onCreateTrip,
            ) {
                Icon(Icons.Rounded.AddRoad, contentDescription = null)
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
    onRemoveNextStop: () -> Unit,
    onAddStop: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onVoiceGuidanceEnabledChange: (Boolean) -> Unit,
    onCompleteFuelStop: () -> Unit,
    onCompleteIntermediateStop: () -> Unit,
    onStopNavigation: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    val navigationView = LocalView.current
    DisposableEffect(navigationView) {
        val wasKeepingScreenOn = navigationView.keepScreenOn
        val window = navigationView.context.findActivity()?.window
        val insetsController = window?.let {
            WindowCompat.getInsetsController(it, navigationView)
        }
        val previousSystemBarsBehavior = insetsController?.systemBarsBehavior
        navigationView.keepScreenOn = true
        insetsController?.apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            navigationView.keepScreenOn = wasKeepingScreenOn
            insetsController?.apply {
                show(WindowInsetsCompat.Type.statusBars())
                previousSystemBarsBehavior?.let { systemBarsBehavior = it }
            }
        }
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
    var showCngDetails by rememberSaveable { mutableStateOf(false) }
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
                showCngDetails = false
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
    if (showCngDetails) {
        CngDetailsSheet(
            state = state,
            ui = ui,
            onDismiss = { showCngDetails = false },
            onRequestRouteUpdate = onRequestRouteUpdate,
            onReplaceFuelStop = { confirmFuelStopReplacement = true },
            onCompleteFuelStop = onCompleteFuelStop,
            onCompleteIntermediateStop = onCompleteIntermediateStop,
            onOpenDeveloperTools = {
                showCngDetails = false
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
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ),
        ) {
            ManeuverOverlay(
                ui = ui,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val intermediateFirst = shouldPrioritizeIntermediateStop(
                intermediateDistanceMeters = state.nextIntermediateStop?.distanceRemainingMeters,
                cngDistanceMeters = state.nextFuelStop?.distanceRemainingMeters,
            )
            if (intermediateFirst) {
                state.nextIntermediateStop?.let { stop ->
                    IntermediateGuidanceCard(
                        stop = stop,
                        reached = state.activeIntermediateStopVisit != null,
                        onCompleteIntermediateStop = onCompleteIntermediateStop,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
            }
            ui.nextCngStop?.let { stop ->
                CngGuidanceCard(
                    stop = stop,
                    onOpenDetails = { showCngDetails = true },
                    onCompleteFuelStop = onCompleteFuelStop,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
            if (!intermediateFirst) {
                state.nextIntermediateStop?.let { stop ->
                    IntermediateGuidanceCard(
                        stop = stop,
                        reached = state.activeIntermediateStopVisit != null,
                        onCompleteIntermediateStop = onCompleteIntermediateStop,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    )
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
                // Keep every control on one bottom baseline while the trip
                // summary is open; centering against the action stack makes
                // the ETA pill and the map controls visibly drift apart.
                verticalAlignment = Alignment.Bottom,
            ) {
                ArrivalEtaBadge(
                    arrivalTime = ui.arrivalTime,
                    progress = ui.progress,
                    trafficDetected = ui.trafficDetected,
                    modifier = Modifier.padding(
                        bottom = navigationLeftControlBottomPadding(showTripSummary),
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                MapModeControls(
                    cameraMode = cameraMode,
                    tripSummaryVisible = showTripSummary,
                    voiceGuidanceEnabled = state.voiceGuidanceEnabled,
                    onToggleVoiceGuidance = {
                        onVoiceGuidanceEnabledChange(!state.voiceGuidanceEnabled)
                    },
                    hasRemovableNextStop = shouldShowRemoveNextStop(
                        hasNextFuelStop = state.nextFuelStop != null,
                        hasNextIntermediateStop = state.nextIntermediateStop != null,
                        fuelStopVisitActive = state.activeFuelStopVisit != null,
                        intermediateStopVisitActive = state.activeIntermediateStopVisit != null,
                    ),
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
                        showTripSummary = !showTripSummary
                        Log.i(NAVIGATION_UI_LOG_TAG, "trip_summary visible=$showTripSummary")
                    },
                    onForceRouteUpdate = onRequestRouteUpdate,
                    onRemoveNextStop = onRemoveNextStop,
                    onAddStop = onAddStop,
                    onOpenOptions = onOpenOptions,
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
private fun IntermediateGuidanceCard(
    stop: NavigationIntermediateStopProgress,
    reached: Boolean,
    onCompleteIntermediateStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = (if (reached) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }).copy(alpha = NAVIGATION_GLASS_PANEL_ALPHA),
        tonalElevation = 8.dp,
        modifier = modifier.testTag("navigation_intermediate_stop_card"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = "TAPPA",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stop.stop.mapLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (reached) {
                        "Tappa raggiunta"
                    } else {
                        stop.distanceRemainingMeters.navigationDistanceLabel()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (reached) {
                CompassButton(onClick = onCompleteIntermediateStop) {
                    Text("Termina")
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = NAVIGATION_GLASS_PANEL_ALPHA),
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
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
                Column(modifier = Modifier.weight(1f, fill = false)) {
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
                                MaterialTheme.compassSemanticColors.onWarningContainer
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
                            MaterialTheme.compassSemanticColors.onWarningContainer
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
    MapIconControlButton(
        onClick = onToggle,
        modifier = modifier,
        testTag = "navigation_voice_toggle",
        contentDescription = if (enabled) {
            "Voce attiva. Tocca per disattivare"
        } else {
            "Voce disattivata. Tocca per attivare"
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (enabled) {
                    Icons.AutoMirrored.Rounded.VolumeUp
                } else {
                    Icons.AutoMirrored.Rounded.VolumeOff
                },
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun ManeuverOverlay(ui: NavigationDrivingUiModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("navigation_maneuver_card"),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(
                    alpha = NAVIGATION_GLASS_PANEL_ALPHA,
                ),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryManeuverIcon(
                        visual = ui.maneuverVisual,
                        roundaboutExitCount = ui.roundaboutExitCount,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
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
                    HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 5.dp))
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
        ui.junctionSign?.let { sign ->
            JunctionSignPanel(
                sign = sign,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, end = 12.dp),
            )
        }
    }
}

internal fun shouldPrioritizeIntermediateStop(
    intermediateDistanceMeters: Double?,
    cngDistanceMeters: Double?,
): Boolean {
    val intermediate = intermediateDistanceMeters?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return false
    val cng = cngDistanceMeters?.takeIf { it.isFinite() && it >= 0.0 }
    return cng == null || intermediate <= cng
}

internal fun shouldShowRemoveNextStop(
    hasNextFuelStop: Boolean,
    hasNextIntermediateStop: Boolean,
    fuelStopVisitActive: Boolean,
    intermediateStopVisitActive: Boolean,
): Boolean = (hasNextFuelStop || hasNextIntermediateStop) &&
    !fuelStopVisitActive && !intermediateStopVisitActive

internal const val NAVIGATION_GLASS_PANEL_ALPHA = 0.70f

internal fun navigationLeftControlBottomPadding(tripSummaryVisible: Boolean) =
    // Together with the row's 8.dp inset, reserve 32.dp above the map attribution.
    if (tripSummaryVisible) 0.dp else 24.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
    Surface(
        modifier = modifier.testTag("navigation_junction_sign"),
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

@Composable
private fun MapModeControls(
    cameraMode: NavigationCameraMode,
    tripSummaryVisible: Boolean,
    voiceGuidanceEnabled: Boolean,
    onToggleVoiceGuidance: () -> Unit,
    hasRemovableNextStop: Boolean,
    onOverview: () -> Unit,
    onRecenter: () -> Unit,
    onToggleOrientation: () -> Unit,
    onShowTripSummary: () -> Unit,
    onForceRouteUpdate: () -> Unit,
    onRemoveNextStop: () -> Unit,
    onAddStop: () -> Unit,
    onStopNavigation: () -> Unit,
    onOpenOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val policy = cameraMode.mapControlPolicy()
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MapIconControlButton(
            testTag = "navigation_trip_toggle",
            contentDescription = if (tripSummaryVisible) "Chiudi riepilogo viaggio" else "Apri riepilogo viaggio",
            onClick = onShowTripSummary,
        ) {
            Icon(
                imageVector = if (tripSummaryVisible) Icons.Rounded.KeyboardArrowDown
                    else Icons.AutoMirrored.Rounded.ListAlt,
                contentDescription = null,
            )
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
            MapIconControlButton(
                testTag = "navigation_recenter",
                contentDescription = "Ricentra sulla posizione attuale",
                onClick = onRecenter,
            ) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = actionsExpanded,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MapIconControlButton(
                        testTag = "navigation_stop",
                        contentDescription = "Termina navigazione",
                        onClick = {
                            actionsExpanded = false
                            onStopNavigation()
                        },
                    ) {
                        Icon(Icons.Rounded.StopCircle, contentDescription = null)
                    }
                    if (hasRemovableNextStop) {
                        MapIconControlButton(
                            testTag = "navigation_remove_next_stop",
                            contentDescription = "Rimuovi prossima tappa",
                            onClick = {
                                actionsExpanded = false
                                onRemoveNextStop()
                            },
                        ) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null)
                        }
                    }
                    MapIconControlButton(
                        testTag = "navigation_add_stop",
                        contentDescription = "Aggiungi tappa",
                        onClick = {
                            actionsExpanded = false
                            onAddStop()
                        },
                    ) {
                        Icon(Icons.Rounded.AddLocationAlt, contentDescription = null)
                    }
                    MapIconControlButton(
                        testTag = "navigation_force_route_update",
                        contentDescription = "Forza ricalcolo percorso",
                        onClick = {
                            actionsExpanded = false
                            onForceRouteUpdate()
                        },
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    MapIconControlButton(
                        testTag = "navigation_options",
                        contentDescription = "Opzioni",
                        onClick = {
                            actionsExpanded = false
                            onOpenOptions()
                        },
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                    }
                    VoiceGuidanceToggle(
                        enabled = voiceGuidanceEnabled,
                        onToggle = {
                            onToggleVoiceGuidance()
                            actionsExpanded = false
                        },
                    )
                }
            }
            MapIconControlButton(
                testTag = "navigation_actions_toggle",
                contentDescription = if (actionsExpanded) {
                    "Chiudi azioni navigazione"
                } else {
                    "Apri azioni navigazione"
                },
                onClick = { actionsExpanded = !actionsExpanded },
            ) {
                Icon(
                    imageVector = if (actionsExpanded) {
                        Icons.Rounded.KeyboardArrowDown
                    } else {
                        Icons.Rounded.KeyboardArrowUp
                    },
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun MapIconControlButton(
    testTag: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
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
private fun ArrivalEtaBadge(
    arrivalTime: String,
    progress: Float,
    trafficDetected: Boolean,
    modifier: Modifier = Modifier,
) {
    val ringColor = Color(0xFF98EEB0)
    Box(
        modifier = modifier
            .size(72.dp)
            .testTag("navigation_arrival_time"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - stroke,
                height = size.height - stroke,
            )
            drawArc(
                color = ringColor.copy(alpha = 0.22f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                ),
            )
        }
        Surface(
            modifier = Modifier.size(60.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = if (trafficDetected) Color(0xFFFFBE8A) else MaterialTheme.colorScheme.primary,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = arrivalTime,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text("ETA", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TripBottomBar(
    ui: NavigationDrivingUiModel,
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
                DrivingSummaryValue(
                    "Arrivo", ui.arrivalTime,
                    color = if (ui.trafficDetected) Color(0xFFFFBE8A) else Color.Unspecified,
                )
            }
            ui.statusMessages.filter { it.level == NavigationStatusLevel.WARNING }.forEach { message ->
                Text(
                    text = message.text,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.compassSemanticColors.onWarningContainer,
                )
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
        }
    }
}

@Composable
private fun DrivingSummaryValue(label: String, value: String, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = color,
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
private fun CngDetailsSheet(
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
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=cng_details visible=true")
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("navigation_cng_details_sheet"),
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
                    "Tappe CNG",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
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
                                        MaterialTheme.compassSemanticColors.onWarningContainer
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
                                color = MaterialTheme.compassSemanticColors.onWarningContainer,
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
        instruction = "Mantieni la sinistra verso Modena.",
        visual = maneuverVisual(24, null),
        sign = NavigationJunctionSignUiModel(
            exitNumber = null,
            branches = "A1 / E 35",
            toward = "Modena",
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
