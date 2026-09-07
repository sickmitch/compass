package org.compass.cng.ui.route

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.ui.map.NavigationMap
import org.compass.cng.ui.map.FollowMap

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
                shape = CircleShape,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                TextButton(
                    onClick = onCreateTrip,
                    modifier = Modifier.testTag("follow_create_trip"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Crea viaggio") }
            }
            if (cameraMode != NavigationCameraMode.FOLLOW) {
                Surface(
                    shape = CircleShape,
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    TextButton(
                        onClick = { cameraMode = NavigationCameraMode.FOLLOW },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Text("Ricentra") }
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
    onStopNavigation: () -> Unit,
) {
    val activeRoute = requireNotNull(state.route)
    LaunchedEffect(Unit) {
        Log.i(NAVIGATION_UI_LOG_TAG, "surface=driving visible=true")
    }
    val ui = state.toDrivingUiModel()
    LaunchedEffect(ui.currentSpeedLimitKph, state.currentRouteSegmentIndex) {
        Log.i(
            NAVIGATION_UI_LOG_TAG,
            "road_context segment=${state.currentRouteSegmentIndex ?: "unmatched"} " +
                "speed_limit_kph=${ui.currentSpeedLimitKph ?: "unavailable"}",
        )
    }
    val cameraConfig = remember { NavigationCameraConfig() }
    var cameraMode by rememberSaveable { mutableStateOf(NavigationCameraMode.FOLLOW) }
    var cameraGestureRevision by rememberSaveable { mutableStateOf(0L) }
    var showTripSummary by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    var confirmFuelStopReplacement by rememberSaveable { mutableStateOf(false) }
    var tripSummaryHeightPixels by remember { mutableIntStateOf(0) }
    var routeUpdateNoticeHeightPixels by remember { mutableIntStateOf(0) }
    var routeUpdateNoticeVisible by remember { mutableStateOf(false) }
    var speedCompliance by remember(activeRoute.routeId) {
        mutableStateOf(SpeedLimitComplianceStatus.UNAVAILABLE)
    }
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
    val observedSpeedMetersPerSecond = state.navigationPosition
        ?.speedMetersPerSecond
        ?.takeIf { state.gpsStatus == GpsStatus.ACTIVE }
    val currentSpeedKph = speedKphForDisplay(observedSpeedMetersPerSecond)
    LaunchedEffect(observedSpeedMetersPerSecond, ui.currentSpeedLimitKph) {
        val next = speedLimitComplianceStatus(
            previous = speedCompliance,
            speedMetersPerSecond = observedSpeedMetersPerSecond,
            speedLimitKph = ui.currentSpeedLimitKph,
        )
        if (next != speedCompliance) {
            Log.i(
                NAVIGATION_UI_LOG_TAG,
                "speed_compliance from=${speedCompliance.name.lowercase()} " +
                    "to=${next.name.lowercase()} speed_kph=${currentSpeedKph ?: "unavailable"} " +
                    "limit_kph=${ui.currentSpeedLimitKph ?: "unavailable"}",
            )
            speedCompliance = next
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
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ui.currentSpeedLimitKph?.let { limit ->
                    SpeedLimitBadge(
                        speedLimitKph = limit,
                        currentSpeedKph = currentSpeedKph,
                        complianceStatus = speedCompliance,
                        modifier = Modifier.padding(bottom = 44.dp),
                    )
                }
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
                    onShowTripSummary = {
                        showTripSummary = true
                        Log.i(NAVIGATION_UI_LOG_TAG, "trip_summary visible=true")
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
private fun RouteUpdateNoticeCard(
    notice: RouteUpdateNoticeUiModel,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .testTag("navigation_route_update_notice")
            .clearAndSetSemantics { contentDescription = notice.accessibilityDescription },
        shape = RoundedCornerShape(22.dp),
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
private fun SpeedLimitBadge(
    speedLimitKph: Int,
    currentSpeedKph: Int?,
    complianceStatus: SpeedLimitComplianceStatus,
    modifier: Modifier = Modifier,
) {
    val overLimit = complianceStatus == SpeedLimitComplianceStatus.OVER_LIMIT
    val warningColor = Color(0xFFFF3B30)
    val accessibilityLabel = if (overLimit && currentSpeedKph != null) {
        "Limite $speedLimitKph chilometri orari superato, velocità $currentSpeedKph"
    } else {
        "Limite $speedLimitKph chilometri orari"
    }
    Box(
        modifier = modifier
            .size(68.dp)
            .testTag(if (overLimit) "navigation_speed_limit_over" else "navigation_speed_limit")
            .clearAndSetSemantics { contentDescription = accessibilityLabel },
        contentAlignment = Alignment.Center,
    ) {
        if (overLimit) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(3.dp, warningColor),
            ) {}
        }
        Surface(
            modifier = Modifier.size(58.dp),
            shape = CircleShape,
            color = Color.White,
            contentColor = if (overLimit) warningColor else Color.Black,
            border = BorderStroke(4.dp, Color(0xFFD20A0A)),
            shadowElevation = if (overLimit) 12.dp else 7.dp,
        ) {
            Text(
                text = speedLimitKph.toString(),
                modifier = Modifier.wrapContentSize(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                maxLines = 1,
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
        shape = RoundedCornerShape(18.dp),
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
            shape = RoundedCornerShape(12.dp),
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
    onShowTripSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!tripSummaryVisible) {
            Surface(
                shape = CircleShape,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                TextButton(
                    onClick = onShowTripSummary,
                    modifier = Modifier.testTag("navigation_trip_toggle"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Viaggio") }
            }
        }
        Surface(
            shape = CircleShape,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            TextButton(
                onClick = onOverview,
                modifier = Modifier.testTag("navigation_overview"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Panoramica") }
        }
        if (cameraMode != NavigationCameraMode.FOLLOW) {
            Surface(
                shape = CircleShape,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                TextButton(
                    onClick = onRecenter,
                    modifier = Modifier.testTag("navigation_recenter"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text("Ricentra") }
            }
        }
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
                    Surface(
                        modifier = Modifier.testTag("navigation_cng_badge"),
                        shape = RoundedCornerShape(7.dp),
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
                TextButton(
                    onClick = onHide,
                    modifier = Modifier.testTag("navigation_trip_toggle"),
                ) {
                    TripPanelActionLabel("Nascondi", pointsUp = false)
                }
                TextButton(
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
                    OutlinedButton(
                        onClick = { showManeuverGallery = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("navigation_maneuver_gallery_entry"),
                    ) {
                        Text("Verifica iconografia manovre")
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { showJunctionSignGallery = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("navigation_junction_sign_gallery_entry"),
                    ) {
                        Text("Verifica segnaletica e uscite")
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
                        TextButton(onClick = onClose) { Text("Chiudi") }
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
                                shape = RoundedCornerShape(14.dp),
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
                        TextButton(onClick = onClose) { Text("Chiudi") }
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
            TextButton(onClick = onConfirm) { Text("Cerca alternativa") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}

private val DETAIL_CLOCK_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private const val NAVIGATION_UI_LOG_TAG = "CompassNavigationUi"
