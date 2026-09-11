package org.compass.cng.ui.route

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.compass.cng.BuildConfig
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.DestinationKind
import org.compass.cng.domain.model.DestinationSuggestion
import org.compass.cng.domain.model.ResolvedDestination
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.PlaceKind
import org.compass.cng.domain.model.PlaceSearchResult
import org.compass.cng.domain.model.PlaceSearchSource
import org.compass.cng.domain.model.PredictiveCngStation
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveItineraryStop
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithIntermediateStops
import org.compass.cng.domain.vehicle.VehicleProfile
import org.compass.cng.domain.vehicle.VehicleProfiles
import org.compass.cng.navigation.NavigationRoute
import org.compass.cng.navigation.NavigationCameraMode
import org.compass.cng.navigation.NavigationPhase
import org.compass.cng.navigation.NavigationConnectivity
import org.compass.cng.navigation.NavigationRouteSource
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.GpsStatus
import org.compass.cng.navigation.OffRouteStatus
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.navigation.RouteUpdateFailure
import org.compass.cng.navigation.RouteUpdateReason
import org.compass.cng.ui.map.NavigationMap
import org.compass.cng.ui.map.RouteMap
import org.compass.cng.ui.map.RoutePointPickerMap
import org.compass.cng.ui.theme.CompassButton
import org.compass.cng.ui.theme.CompassOutlinedButton
import org.compass.cng.ui.theme.CompassTextButton
import org.compass.cng.ui.theme.compassSemanticColors

@Composable
fun RoutePlannerScreen(
    viewModel: RoutePlannerViewModel,
    modifier: Modifier = Modifier,
    onStartNavigation: () -> Unit,
    onStartNavigationReplay: () -> Unit,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onVoiceGuidanceEnabledChange: (Boolean) -> Unit,
    onCompleteFuelStop: () -> Unit,
    onCompleteIntermediateStop: () -> Unit,
    onUseCurrentLocation: (RouteEndpoint) -> Unit,
    onStopNavigation: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
    BackHandler(
        enabled = state.stage != PlannerStage.FOLLOW &&
            state.stage != PlannerStage.PREVIEW && !state.isBusy,
        onBack = {
            if (navigationState.phase != NavigationPhase.ROUTE_PREVIEW &&
                navigationState.phase != NavigationPhase.IDLE
            ) {
                onStopNavigation()
            } else {
                viewModel.navigateBack()
            }
        },
    )
    val showDrivingSurface = state.stage == PlannerStage.NAVIGATION_PREVIEW &&
        navigationState.route != null &&
        navigationState.phase != NavigationPhase.ROUTE_PREVIEW
    if (showDrivingSurface) {
        Surface(modifier = modifier.fillMaxSize()) {
            ActiveNavigationScreen(
                state = navigationState,
                onRequestRouteUpdate = onRequestRouteUpdate,
                onSimulateOffRoute = onSimulateOffRoute,
                onReplaceUnavailableFuelStop = onReplaceUnavailableFuelStop,
                onVoiceGuidanceEnabledChange = onVoiceGuidanceEnabledChange,
                onCompleteFuelStop = onCompleteFuelStop,
                onCompleteIntermediateStop = onCompleteIntermediateStop,
                onStopNavigation = onStopNavigation,
            )
        }
        return
    }
    if (state.stage == PlannerStage.FOLLOW) {
        Surface(modifier = modifier.fillMaxSize()) {
            RouteFreeFollowScreen(
                location = state.followLocation,
                statusMessage = state.message,
                onCreateTrip = viewModel::openRouteConfiguration,
            )
        }
        return
    }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (state.stage != PlannerStage.CONFIGURE_ROUTE) {
                Header(
                    stage = state.stage,
                    searchTarget = state.placeSearchTarget,
                    navigationPhase = navigationState.phase,
                    canNavigateBack = state.stage != PlannerStage.PREVIEW,
                    onNavigateBack = {
                        if (navigationState.phase != NavigationPhase.ROUTE_PREVIEW &&
                            navigationState.phase != NavigationPhase.IDLE
                        ) {
                            onStopNavigation()
                        } else {
                            viewModel.navigateBack()
                        }
                    },
                )
            }
            AnimatedContent(
                targetState = state,
                contentKey = { it.stage },
                transitionSpec = {
                    (fadeIn() + slideInHorizontally { it / 8 }) togetherWith
                        (fadeOut() + slideOutHorizontally { -it / 8 })
                },
                label = "creazione-viaggio",
                modifier = Modifier.weight(1f),
            ) { animatedState ->
                val state = animatedState
                val visibleStage = state.stage
                val baseRoute = state.baseRoute
                when {
                visibleStage == PlannerStage.SERVER_CONNECTION -> ServerConnectionContent(
                    baseUrl = state.serverBaseUrlInput,
                    username = state.serverUsernameInput,
                    password = state.serverPasswordInput,
                    allowInsecureHttp = state.serverAllowInsecureHttp,
                    message = state.message,
                    onBaseUrlChanged = viewModel::updateServerBaseUrl,
                    onUsernameChanged = viewModel::updateServerUsername,
                    onPasswordChanged = viewModel::updateServerPassword,
                    onAllowInsecureHttpChanged = viewModel::updateServerAllowInsecureHttp,
                    onSave = viewModel::saveServerConnection,
                )
                visibleStage == PlannerStage.CONFIGURE_ROUTE -> ConfigureRouteContent(
                    route = baseRoute,
                    originLatitudeInput = state.originLatitudeInput,
                    originLongitudeInput = state.originLongitudeInput,
                    destinationLatitudeInput = state.destinationLatitudeInput,
                    destinationLongitudeInput = state.destinationLongitudeInput,
                    originDisplayName = state.originDisplayName,
                    destinationDisplayName = state.destinationDisplayName,
                    originAttributions = state.originAttributions,
                    destinationAttributions = state.destinationAttributions,
                    originLocationMethod = state.originLocationMethod,
                    destinationLocationMethod = state.destinationLocationMethod,
                    originCurrentLocationStatus = state.originCurrentLocationStatus,
                    destinationCurrentLocationStatus = state.destinationCurrentLocationStatus,
                    routeInputsDirty = state.routeInputsDirty,
                    isCalculating = state.operation == PlannerOperation.BASE_ROUTE,
                    message = state.message,
                    onSearch = viewModel::openPlaceSearch,
                    onCoordinates = viewModel::openMapPointPicker,
                    onUseCurrentLocation = onUseCurrentLocation,
                    onApply = viewModel::applyRouteInputs,
                )
                visibleStage == PlannerStage.DESTINATION_SEARCH -> DestinationSearchContent(
                    target = state.placeSearchTarget,
                    query = state.placeSearchQuery,
                    results = state.destinationSuggestions,
                    isSearching = state.operation == PlannerOperation.PLACE_SEARCH,
                    isResolving = state.operation == PlannerOperation.PLACE_RESOLUTION ||
                        state.operation == PlannerOperation.INTERMEDIATE_STOP_ROUTE,
                    message = state.message,
                    pendingResolvedDestination = state.pendingResolvedDestination,
                    onQueryChanged = viewModel::updatePlaceSearchQuery,
                    onSearch = viewModel::searchDestinations,
                    onSelect = viewModel::selectDestinationSuggestion,
                    onConfirmCoordinateOnly = viewModel::confirmCoordinateOnlyDestination,
                )
                visibleStage == PlannerStage.MAP_POINT_PICKER -> MapPointPickerContent(
                    target = requireNotNull(state.mapPickerTarget),
                    coordinate = requireNotNull(state.mapPickerCoordinate),
                    onCoordinateSelected = viewModel::updateMapPickerCoordinate,
                    onChoose = viewModel::confirmMapPointPicker,
                )
                visibleStage == PlannerStage.INTERMEDIATE_STOP_PREVIEW -> {
                    IntermediateStopPreviewContent(
                        directRoute = requireNotNull(state.baseRoute),
                        candidateRoute = requireNotNull(state.pendingIntermediateStopsRoute),
                        highlightedStop = requireNotNull(state.pendingIntermediateStopCoordinate),
                        onChoose = viewModel::chooseIntermediateStop,
                        onChange = viewModel::navigateBack,
                    )
                }
                baseRoute == null && state.operation == PlannerOperation.BASE_ROUTE -> {
                    LoadingState("Calcolo del percorso…")
                }
                baseRoute == null -> ErrorState(
                    title = "Percorso non disponibile",
                    message = state.message ?: "Il percorso non è disponibile.",
                    actionLabel = "Riprova",
                    onAction = viewModel::retryBaseRoute,
                )
                state.operation == PlannerOperation.CNG_CANDIDATES -> LoadingRouteState(
                    route = baseRoute,
                    message = "Cerco e valuto le stazioni Metano…",
                )
                state.operation == PlannerOperation.PREDICTIVE_CANDIDATES -> LoadingRouteState(
                    route = baseRoute,
                    message = "Valuto autonomia e stazioni raggiungibili…",
                )
                else -> when (visibleStage) {
                    PlannerStage.FOLLOW,
                    PlannerStage.CONFIGURE_ROUTE,
                    PlannerStage.DESTINATION_SEARCH,
                    PlannerStage.MAP_POINT_PICKER,
                    PlannerStage.INTERMEDIATE_STOP_PREVIEW -> Unit
                    PlannerStage.INTERMEDIATE_STOPS -> IntermediateStopsContent(
                        route = state.intermediateStopsRoute?.asRoutePreview() ?: baseRoute,
                        stops = state.plannedIntermediateStops,
                        maximumDeviationKmInput = state.intermediateStopMaximumDeviationKmInput,
                        isCalculating = state.operation == PlannerOperation.INTERMEDIATE_STOP_ROUTE,
                        message = state.message,
                        onMaximumDeviationChanged =
                            viewModel::updateIntermediateStopMaximumDeviationKm,
                        onUseCurrentLocation = {
                            onUseCurrentLocation(RouteEndpoint.INTERMEDIATE_STOP)
                        },
                        onSearch = {
                            viewModel.openPlaceSearch(RouteEndpoint.INTERMEDIATE_STOP)
                        },
                        onMapSelection = {
                            viewModel.openMapPointPicker(RouteEndpoint.INTERMEDIATE_STOP)
                        },
                        onMove = viewModel::moveIntermediateStop,
                        onEdit = viewModel::editIntermediateStop,
                        onDelete = viewModel::deleteIntermediateStop,
                        onCalculate = viewModel::calculateIntermediateStopsRoute,
                    )
                    PlannerStage.PREVIEW -> PreviewContent(
                        route = baseRoute,
                        onStartNavigation = viewModel::openNavigationPreview,
                        onEditRoute = viewModel::openRouteConfiguration,
                        onAddStop = viewModel::openAddStop,
                        onExtendedPlanning = viewModel::openPredictiveRange,
                        onAddIntermediateStops = viewModel::addIntermediateStop,
                        allowCngPlanning = !state.intermediateStopEnabled,
                    )
                    PlannerStage.CONFIGURE_CNG -> ConfigureCngContent(
                        route = baseRoute,
                        rangeInput = state.effectiveRangeKmInput,
                        detourInput = state.maximumDetourMinutesInput,
                        message = state.message,
                        onRangeChanged = viewModel::updateEffectiveRange,
                        onDetourChanged = viewModel::updateMaximumDetour,
                        onSearch = viewModel::searchCngStations,
                    )
                    PlannerStage.CONFIGURE_PREDICTIVE -> ConfigurePredictiveContent(
                        route = baseRoute,
                        effectiveRangeInput = state.effectiveRangeKmInput,
                        remainingRangeInput = state.estimatedRemainingRangeKmInput,
                        reserveRangeInput = state.reserveRangeKmInput,
                        remainingGasolineRangeInput = (
                            state.estimatedRemainingGasolineRangeKmInput
                        ),
                        effectiveGasolineRangeInput = state.effectiveGasolineRangeKmInput,
                        gasolineReserveRangeInput = state.gasolineReserveRangeKmInput,
                        vehicleProfiles = state.vehicleProfiles,
                        detourInput = state.maximumDetourMinutesInput,
                        message = state.message,
                        onEffectiveRangeChanged = viewModel::updateEffectiveRange,
                        onRemainingRangeChanged = viewModel::updateEstimatedRemainingRange,
                        onReserveRangeChanged = viewModel::updateReserveRange,
                        onRemainingGasolineRangeChanged = (
                            viewModel::updateEstimatedRemainingGasolineRange
                        ),
                        onSelectVehicleProfile = viewModel::selectVehicleProfile,
                        onUseCustomVehicleValues = viewModel::clearVehicleProfileSelection,
                        onEditVehicleProfile = viewModel::editVehicleProfile,
                        onDeleteVehicleProfile = viewModel::deleteVehicleProfile,
                        vehicleProfileNameInput = state.vehicleProfileNameInput,
                        vehicleProfileCngRangeInput = state.vehicleProfileCngRangeInput,
                        vehicleProfileCngReserveInput = state.vehicleProfileCngReserveInput,
                        vehicleProfileGasolineRangeInput = state.vehicleProfileGasolineRangeInput,
                        vehicleProfileGasolineReserveInput =
                            state.vehicleProfileGasolineReserveInput,
                        onVehicleProfileNameChanged = viewModel::updateVehicleProfileName,
                        onVehicleProfileCngRangeChanged =
                            viewModel::updateVehicleProfileCngRange,
                        onVehicleProfileCngReserveChanged =
                            viewModel::updateVehicleProfileCngReserve,
                        onVehicleProfileGasolineRangeChanged =
                            viewModel::updateVehicleProfileGasolineRange,
                        onVehicleProfileGasolineReserveChanged =
                            viewModel::updateVehicleProfileGasolineReserve,
                        onSaveVehicleProfile = viewModel::saveVehicleProfile,
                        onDetourChanged = viewModel::updateMaximumDetour,
                        onEvaluate = viewModel::evaluatePredictiveRange,
                    )
                    PlannerStage.VEHICLE_PROFILES -> VehicleProfilesContent(
                        profiles = state.vehicleProfiles,
                        editingProfileId = state.editingVehicleProfileId,
                        nameInput = state.vehicleProfileNameInput,
                        cngRangeInput = state.vehicleProfileCngRangeInput,
                        cngReserveInput = state.vehicleProfileCngReserveInput,
                        gasolineRangeInput = state.vehicleProfileGasolineRangeInput,
                        gasolineReserveInput = state.vehicleProfileGasolineReserveInput,
                        message = state.message,
                        onEdit = viewModel::editVehicleProfile,
                        onSelect = viewModel::selectVehicleProfile,
                        onDelete = viewModel::deleteVehicleProfile,
                        onNameChanged = viewModel::updateVehicleProfileName,
                        onCngRangeChanged = viewModel::updateVehicleProfileCngRange,
                        onCngReserveChanged = viewModel::updateVehicleProfileCngReserve,
                        onGasolineRangeChanged = viewModel::updateVehicleProfileGasolineRange,
                        onGasolineReserveChanged = viewModel::updateVehicleProfileGasolineReserve,
                        onSave = viewModel::saveVehicleProfile,
                    )
                    PlannerStage.SERVER_CONNECTION -> Unit
                    PlannerStage.CNG_CANDIDATES -> CandidateContent(
                        rankedStations = requireNotNull(state.rankedStations),
                        predictiveSuggestion = state.predictiveSuggestion,
                        pendingStation = state.pendingStation,
                        message = state.message,
                        onSelect = viewModel::selectStation,
                    )
                    PlannerStage.PREDICTIVE_ITINERARY -> PredictiveItineraryContent(
                        suggestion = requireNotNull(state.predictiveSuggestion),
                        calculating = state.operation == PlannerOperation.SELECTED_ROUTE,
                        message = state.message,
                        onCalculateRoute = viewModel::acceptPredictiveItinerary,
                    )
                    PlannerStage.PREDICTIVE_STATUS -> PredictiveStatusContent(
                        suggestion = requireNotNull(state.predictiveSuggestion),
                        onChangeInputs = viewModel::navigateBack,
                        onUseGasolineFallback = viewModel::openGasolineFallbackNavigation,
                    )
                    PlannerStage.NAVIGATION_PREVIEW -> navigationState.route?.let { route ->
                        NavigationPreviewContent(
                            route = route,
                            state = navigationState,
                            destinationLabel = state.destinationDisplayName,
                            plannedIntermediateStops = state.plannedIntermediateStops,
                            message = state.message,
                            onStartNavigation = onStartNavigation,
                            onStartNavigationReplay = onStartNavigationReplay,
                            onRequestRouteUpdate = onRequestRouteUpdate,
                            onSimulateOffRoute = onSimulateOffRoute,
                            onReplaceUnavailableFuelStop = onReplaceUnavailableFuelStop,
                            onVoiceGuidanceEnabledChange = onVoiceGuidanceEnabledChange,
                            onCompleteFuelStop = onCompleteFuelStop,
                            onCompleteIntermediateStop = onCompleteIntermediateStop,
                            onEditEndpoints = viewModel::openRouteConfiguration,
                            onEditIntermediateStop = viewModel::editIntermediateStop,
                            onDeleteIntermediateStop = viewModel::deleteIntermediateStop,
                            onEditCngPlan = viewModel::editCngPlan,
                            onDeleteCngStop = viewModel::deleteCngStopFromSummary,
                            onStopNavigation = onStopNavigation,
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun Header(
    stage: PlannerStage,
    searchTarget: RouteEndpoint,
    navigationPhase: NavigationPhase,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
) {
    val creationStep = stage.creationStep(searchTarget)
    val animatedProgress by animateFloatAsState(
        targetValue = (creationStep ?: 0) / 3f,
        label = "avanzamento-creazione-viaggio",
    )
    val title = when (stage) {
        PlannerStage.FOLLOW -> "Segui posizione"
        PlannerStage.CONFIGURE_ROUTE -> "Modifica percorso"
        PlannerStage.DESTINATION_SEARCH -> "Cerca posizione"
        PlannerStage.MAP_POINT_PICKER -> "Scegli sulla mappa"
        PlannerStage.INTERMEDIATE_STOP_PREVIEW -> "Verifica tappa"
        PlannerStage.INTERMEDIATE_STOPS -> "Organizza le tappe"
        PlannerStage.PREVIEW -> "Personalizza viaggio"
        PlannerStage.CONFIGURE_CNG -> "Imposta tappa CNG"
        PlannerStage.CONFIGURE_PREDICTIVE -> "Imposta piano CNG"
        PlannerStage.VEHICLE_PROFILES -> "Profili dei mezzi"
        PlannerStage.SERVER_CONNECTION -> "Connessione al server"
        PlannerStage.CNG_CANDIDATES -> "Seleziona tappa CNG"
        PlannerStage.PREDICTIVE_ITINERARY -> "Piano rifornimenti"
        PlannerStage.PREDICTIVE_STATUS -> "Autonomia CNG"
        PlannerStage.NAVIGATION_PREVIEW -> when (navigationPhase) {
            NavigationPhase.ROUTE_PREVIEW -> "Riepilogo viaggio"
            NavigationPhase.REROUTING -> "Ricalcolo percorso"
            NavigationPhase.GPS_LOST -> "Segnale GPS perso"
            NavigationPhase.ARRIVED -> "Destinazione raggiunta"
            else -> "Navigazione attiva"
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canNavigateBack) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
                    }
                } else {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Compass",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (creationStep != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Passaggio $creationStep di 3",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        listOf("Destinazione", "Percorso", "Riepilogo")[creationStep - 1],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
        }
    }
}

private fun PlannerStage.creationStep(searchTarget: RouteEndpoint): Int? = when (this) {
    PlannerStage.DESTINATION_SEARCH -> if (searchTarget == RouteEndpoint.INTERMEDIATE_STOP) {
        2
    } else {
        1
    }
    PlannerStage.INTERMEDIATE_STOP_PREVIEW -> 2
    PlannerStage.MAP_POINT_PICKER -> if (searchTarget == RouteEndpoint.INTERMEDIATE_STOP) 2 else 1
    PlannerStage.INTERMEDIATE_STOPS -> 2
    PlannerStage.PREVIEW,
    PlannerStage.CONFIGURE_CNG,
    PlannerStage.CONFIGURE_PREDICTIVE,
    PlannerStage.CNG_CANDIDATES,
    PlannerStage.PREDICTIVE_ITINERARY,
    PlannerStage.PREDICTIVE_STATUS -> 2
    PlannerStage.NAVIGATION_PREVIEW -> 3
    PlannerStage.FOLLOW,
    PlannerStage.CONFIGURE_ROUTE,
    PlannerStage.VEHICLE_PROFILES,
    PlannerStage.SERVER_CONNECTION -> null
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
private fun LoadingRouteState(route: RoutePreview, message: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(message)
                Text(
                    "Il calcolo usa distanze e tempi della rete stradale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(message)
                Spacer(modifier = Modifier.height(16.dp))
                CompassButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun PreviewContent(
    route: RoutePreview,
    onStartNavigation: () -> Unit,
    onEditRoute: () -> Unit,
    onAddStop: () -> Unit,
    onExtendedPlanning: () -> Unit,
    onAddIntermediateStops: () -> Unit,
    allowCngPlanning: Boolean,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompassOutlinedButton(
                    onClick = onEditRoute,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text("Cambia percorso")
                }
                RouteMap(
                    route = route,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
            }
        }
        item {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Personalizza il viaggio",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                RouteSummary(route = route, modifier = Modifier.fillMaxWidth())
                Text(
                    "Scegli una modalità. La mappa e il riepilogo si aggiorneranno senza perdere partenza e destinazione.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CompassOutlinedButton(
                        onClick = onAddStop,
                        enabled = allowCngPlanning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Sosta CNG")
                    }
                    CompassOutlinedButton(
                        onClick = onExtendedPlanning,
                        enabled = allowCngPlanning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Piano CNG")
                    }
                }
                CompassOutlinedButton(
                    onClick = onAddIntermediateStops,
                    enabled = allowCngPlanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Aggiungi tappe")
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CompassButton(
                        onClick = onStartNavigation,
                        modifier = Modifier.wrapContentWidth(),
                    ) {
                        Text("Percorso diretto")
                    }
                }
                if (!allowCngPlanning) {
                    Text(
                        "Il percorso contiene già una tappa ordinaria; rimuovila per pianificare i rifornimenti CNG.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntermediateStopPreviewContent(
    directRoute: RoutePreview,
    candidateRoute: RouteWithIntermediateStops,
    highlightedStop: Coordinate,
    onChoose: () -> Unit,
    onChange: () -> Unit,
) {
    val preview = candidateRoute.asRoutePreview()
    val addedDistanceMeters =
        (candidateRoute.distanceMeters - directRoute.distanceMeters).coerceAtLeast(0.0)
    val addedDurationSeconds =
        (candidateRoute.durationSeconds - directRoute.durationSeconds).coerceAtLeast(0.0)
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = preview,
            intermediateStops = candidateRoute.stops,
            highlightedIntermediateStop = highlightedStop,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Tappa proposta",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Il punto evidenziato verrà inserito tra partenza e destinazione.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SummaryValue("Percorso", formatDistance(candidateRoute.distanceMeters))
                    SummaryValue("Deviazione", "+${formatDistance(addedDistanceMeters)}")
                    SummaryValue("Tempo", "+${formatDuration(addedDurationSeconds)}")
                }
                CompassButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                    Text("Scegli")
                }
                CompassTextButton(onClick = onChange, modifier = Modifier.fillMaxWidth()) {
                    Text("Cambia risultato")
                }
            }
        }
    }
}

@Composable
private fun MapPointPickerContent(
    target: RouteEndpoint,
    coordinate: Coordinate,
    onCoordinateSelected: (Coordinate) -> Unit,
    onChoose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RoutePointPickerMap(
            initialCoordinate = coordinate,
            selectedCoordinate = coordinate,
            onCoordinateSelected = onCoordinateSelected,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    when (target) {
                        RouteEndpoint.ORIGIN -> "Partenza sulla mappa"
                        RouteEndpoint.INTERMEDIATE_STOP -> "Tappa sulla mappa"
                        RouteEndpoint.DESTINATION -> "Destinazione sulla mappa"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Tocca la mappa per spostare il punto.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "%.6f, %.6f".format(Locale.US, coordinate.latitude, coordinate.longitude),
                    style = MaterialTheme.typography.labelMedium,
                )
                CompassButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                    Text("Scegli")
                }
            }
        }
    }
}

@Composable
private fun IntermediateStopsContent(
    route: RoutePreview,
    stops: List<PlannedIntermediateStop>,
    maximumDeviationKmInput: String,
    isCalculating: Boolean,
    message: String?,
    onMaximumDeviationChanged: (String) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onSearch: () -> Unit,
    onMapSelection: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCalculate: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = route,
                intermediateStops = stops.map { it.location },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
        }
        item {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Tappe del viaggio",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (stops.isEmpty()) {
                        "Aggiungi una o più tappe intermedie. Non viene applicato alcun tempo di sosta."
                    } else {
                        "Tieni premuta una tappa e trascinala per cambiarne l’ordine."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
            var accumulatedDrag by remember(stop.id) { mutableStateOf(0f) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp)
                    .pointerInput(stop.id, index, stops.size) {
                        detectDragGesturesAfterLongPress(
                            onDragEnd = { accumulatedDrag = 0f },
                            onDragCancel = { accumulatedDrag = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedDrag += dragAmount.y
                                val threshold = 56.dp.toPx()
                                when {
                                    accumulatedDrag > threshold && index < stops.lastIndex -> {
                                        onMove(index, index + 1)
                                        accumulatedDrag = 0f
                                    }
                                    accumulatedDrag < -threshold && index > 0 -> {
                                        onMove(index, index - 1)
                                        accumulatedDrag = 0f
                                    }
                                }
                            },
                        )
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Tappa ${index + 1}", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (stop.locationMethod) {
                                RouteLocationMethod.CURRENT_LOCATION -> "Posizione acquisita"
                                RouteLocationMethod.FAVORITES -> "Posizione preferita"
                                RouteLocationMethod.SEARCH -> "Posizione selezionata"
                                RouteLocationMethod.COORDINATES -> "Punto scelto sulla mappa"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompassTextButton(onClick = { onEdit(stop.id) }) { Text("Modifica") }
                    CompassTextButton(onClick = { onDelete(stop.id) }) { Text("Elimina") }
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Aggiungi tappa", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompassOutlinedButton(onClick = onUseCurrentLocation) {
                        Text("Posizione attuale")
                    }
                    CompassOutlinedButton(onClick = {}, enabled = false) {
                        Text("Posizioni preferite")
                    }
                    CompassOutlinedButton(onClick = onSearch) { Text("Ricerca") }
                    CompassOutlinedButton(onClick = onMapSelection) {
                        Text("Selezione dalla mappa")
                    }
                }
                OutlinedTextField(
                    value = maximumDeviationKmInput,
                    onValueChange = onMaximumDeviationChanged,
                    label = { Text("Deviazione massima complessiva") },
                    suffix = { Text("km") },
                    supportingText = {
                        Text("Predefinita al 30% della lunghezza del percorso diretto.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { InlineError(it) }
                CompassButton(
                    onClick = onCalculate,
                    enabled = stops.isNotEmpty() && !isCalculating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isCalculating) "Calcolo percorso…" else "Calcola percorso")
                }
            }
        }
    }
}

@Composable
private fun NavigationPreviewContent(
    route: NavigationRoute,
    state: NavigationState,
    destinationLabel: String,
    plannedIntermediateStops: List<PlannedIntermediateStop>,
    message: String?,
    onStartNavigation: () -> Unit,
    onStartNavigationReplay: () -> Unit,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onVoiceGuidanceEnabledChange: (Boolean) -> Unit,
    onCompleteFuelStop: () -> Unit,
    onCompleteIntermediateStop: () -> Unit,
    onEditEndpoints: () -> Unit,
    onEditIntermediateStop: (String) -> Unit,
    onDeleteIntermediateStop: (String) -> Unit,
    onEditCngPlan: () -> Unit,
    onDeleteCngStop: (String) -> Unit,
    onStopNavigation: () -> Unit,
) {
    if (state.phase != NavigationPhase.ROUTE_PREVIEW) {
        ActiveNavigationScreen(
            state = state,
            onRequestRouteUpdate = onRequestRouteUpdate,
            onSimulateOffRoute = onSimulateOffRoute,
            onReplaceUnavailableFuelStop = onReplaceUnavailableFuelStop,
            onVoiceGuidanceEnabledChange = onVoiceGuidanceEnabledChange,
            onCompleteFuelStop = onCompleteFuelStop,
            onCompleteIntermediateStop = onCompleteIntermediateStop,
            onStopNavigation = onStopNavigation,
        )
        return
    }
    val preview = route.asRoutePreview()
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    var editSummary by rememberSaveable { mutableStateOf(false) }
    var selectedEditItem by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = preview,
                cngStops = route.fuelStops.map { it.location },
                intermediateStops = route.intermediateStops.map { it.location },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Riepilogo viaggio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Destinazione",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        destinationLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SummaryValue("Distanza", formatDistance(route.totalDistanceMeters))
                        SummaryValue("Guida", formatDuration(route.drivingDurationSeconds))
                        SummaryValue("Totale", formatDuration(route.totalTripDurationSeconds))
                    }
                    Text(
                        trafficTimingText(route.timing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (route.fuelStops.isNotEmpty()) {
                        Text(
                            "${route.fuelStops.size} soste CNG · " +
                                "${formatDuration(route.timing.totalRefuelingDwellSeconds)} " +
                                "di rifornimento",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.routeSource == NavigationRouteSource.CACHE) {
                            Text(
                                "Prezzi e orari CNG potrebbero non essere aggiornati.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (route.intermediateStops.isNotEmpty()) {
                        Text(
                            "${route.intermediateStops.size} " +
                                if (route.intermediateStops.size == 1) {
                                    "tappa intermedia · nessun tempo di sosta"
                                } else {
                                    "tappe intermedie · nessun tempo di sosta"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    route.gasolineFallback?.let { fallback ->
                        Text(
                            "Fallback benzina · uso stimato fino a " +
                                formatKilometers(fallback.requiredGasolineRangeKm) +
                                " · margine " +
                                formatKilometers(fallback.gasolineMarginAtDestinationKm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (!route.timing.trafficAware) {
                        Text(
                            "Traffico live non disponibile: l’ETA usa i tempi di percorrenza correnti del routing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        if (route.fuelStops.isNotEmpty()) {
            item {
                Text(
                    "Tappe CNG",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(route.fuelStops) { index, stop ->
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "${index + 1}. ${stop.name ?: "MIMIT ${stop.mimitStationId}"}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(stop.municipality, stop.province).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Sosta prevista ${formatDuration(stop.dwellTimeSeconds.toDouble())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (editSummary) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Modifica",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    CompassOutlinedButton(
                        onClick = onEditEndpoints,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Partenza o destinazione")
                    }
                    plannedIntermediateStops.forEachIndexed { index, stop ->
                        val itemKey = "ordinary:${stop.id}"
                        CompassOutlinedButton(
                            onClick = {
                                selectedEditItem = if (selectedEditItem == itemKey) null else itemKey
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Tappa ${index + 1}")
                        }
                        AnimatedVisibility(visible = selectedEditItem == itemKey) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CompassButton(
                                    onClick = { onEditIntermediateStop(stop.id) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Modifica") }
                                CompassOutlinedButton(
                                    onClick = { onDeleteIntermediateStop(stop.id) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Elimina") }
                            }
                        }
                    }
                    route.fuelStops.forEachIndexed { index, stop ->
                        val itemKey = "cng:${stop.mimitStationId}"
                        CompassOutlinedButton(
                            onClick = {
                                selectedEditItem = if (selectedEditItem == itemKey) null else itemKey
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sosta CNG ${index + 1} · ${stop.name ?: stop.mimitStationId}")
                        }
                        AnimatedVisibility(visible = selectedEditItem == itemKey) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CompassButton(
                                    onClick = onEditCngPlan,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Modifica") }
                                CompassOutlinedButton(
                                    onClick = { onDeleteCngStop(stop.mimitStationId) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Elimina") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Prima indicazione",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        route.maneuvers.firstOrNull()?.let { maneuver ->
            item {
                ManeuverRow(number = 1, maneuver = maneuver)
            }
        }
        if (message != null) {
            item {
                InlineError(
                    message = message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompassOutlinedButton(
                    onClick = { editSummary = !editSummary },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(if (editSummary) "Chiudi modifica" else "Modifica")
                }
                CompassButton(
                    onClick = onStartNavigation,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Avvia navigazione")
                }
            }
        }
        if (BuildConfig.DEBUG) {
            item {
                CompassTextButton(
                    onClick = { showDeveloperTools = !showDeveloperTools },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Strumenti sviluppatore")
                }
                AnimatedVisibility(visible = showDeveloperTools) {
                    CompassOutlinedButton(
                        onClick = onStartNavigationReplay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        Text("Riproduci percorso demo")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveNavigationContent(
    state: NavigationState,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    requireNotNull(state.route)
    var cameraMode by rememberSaveable { mutableStateOf(NavigationCameraMode.FOLLOW) }
    var confirmFuelStopReplacement by rememberSaveable { mutableStateOf(false) }
    if (confirmFuelStopReplacement) {
        val fuelStop = state.nextFuelStop?.stop
        AlertDialog(
            onDismissRequest = { confirmFuelStopReplacement = false },
            title = { Text("Sostituire la tappa CNG?") },
            text = {
                Text(
                    "Compass escluderà ${fuelStop?.name ?: "la stazione selezionata"} e " +
                        "cercherà un itinerario completo compatibile con autonomia e riserva. " +
                        "Se non esiste, manterrà la rotta corrente.",
                )
            },
            confirmButton = {
                CompassTextButton(
                    onClick = {
                        confirmFuelStopReplacement = false
                        onReplaceUnavailableFuelStop()
                    },
                ) {
                    Text("Cerca alternativa")
                }
            },
            dismissButton = {
                CompassTextButton(onClick = { confirmFuelStopReplacement = false }) {
                    Text("Annulla")
                }
            },
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.62f),
        ) {
            NavigationMap(
                state = state,
                cameraMode = cameraMode,
                modifier = Modifier.fillMaxSize(),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopCenter),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        state.currentManeuver?.instruction ?: "Prosegui sul percorso",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    state.distanceToNextManeuverMeters?.let {
                        Text("Tra ${formatDistance(it)}")
                    }
                    state.currentRoadName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cameraMode == NavigationCameraMode.OVERVIEW) {
                    CompassButton(onClick = { cameraMode = NavigationCameraMode.OVERVIEW }) {
                        Text("Panoramica")
                    }
                } else {
                    CompassOutlinedButton(onClick = { cameraMode = NavigationCameraMode.OVERVIEW }) {
                        Text("Panoramica")
                    }
                }
                if (cameraMode == NavigationCameraMode.FOLLOW) {
                    CompassButton(onClick = { cameraMode = NavigationCameraMode.FOLLOW }) {
                        Text("Ricentra")
                    }
                } else {
                    CompassOutlinedButton(onClick = { cameraMode = NavigationCameraMode.FOLLOW }) {
                        Text("Ricentra")
                    }
                }
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { state.routeProgressFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SummaryValue(
                        "Rimanenti",
                        state.distanceRemainingMeters?.let(::formatDistance) ?: "—",
                    )
                    SummaryValue(
                        "Durata",
                        state.totalDurationRemainingSeconds?.let(::formatDuration) ?: "—",
                    )
                    SummaryValue(
                        "Arrivo",
                        state.estimatedArrivalAt?.let {
                            ACTIVE_NAVIGATION_TIME_FORMATTER.format(it)
                        } ?: "—",
                    )
                }
                Text(
                    text = gpsStatusLabel(state.gpsStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.gpsStatus == GpsStatus.ACTIVE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (state.routeSource == NavigationRouteSource.CACHE) {
                    Text(
                        "Navigazione disponibile sulla rotta salvata nel dispositivo.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    if (state.connectivity == NavigationConnectivity.ONLINE) {
                        trafficTimingText(state.route.timing)
                    } else {
                        "Traffico non aggiornabile · ETA calcolata sulla rotta locale"
                    },
                    color = if (state.route.timing.trafficAware) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.connectivity != NavigationConnectivity.ONLINE) {
                    Text(
                        when (state.connectivity) {
                            NavigationConnectivity.OFFLINE ->
                                "Rete assente: guida locale attiva sulla rotta scaricata."
                            NavigationConnectivity.RECOVERING ->
                                "Connessione ripristinata: aggiornamento sicuro in corso."
                            NavigationConnectivity.REROUTING_UNAVAILABLE ->
                                "Compass non raggiungibile: guida locale attiva, ricalcolo non disponibile."
                            NavigationConnectivity.ONLINE -> error("handled above")
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if ((state.routeSource == NavigationRouteSource.CACHE ||
                        state.connectivity != NavigationConnectivity.ONLINE) &&
                    state.route.fuelStops.isNotEmpty()
                ) {
                    Text(
                        "Dati CNG in cache: prezzi e orari non sono presentati come aggiornati.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.route.gasolineFallback?.let { fallback ->
                    Text(
                        "Fallback benzina attivo · uso stimato fino a " +
                            formatKilometers(fallback.requiredGasolineRangeKm),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (state.offRouteStatus != OffRouteStatus.ON_ROUTE) {
                    Text(
                        if (state.offRouteStatus == OffRouteStatus.OFF_ROUTE) {
                            "Fuori percorso confermato. Ricalcolo tramite Compass…"
                        } else {
                            "Verifica posizione rispetto al percorso…"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (state.reroutingStatus) {
                    ReroutingStatus.IN_PROGRESS -> Text(
                        if (state.routeUpdateReason == RouteUpdateReason.FUEL_STOP_UNAVAILABLE) {
                            "Cerco una tappa CNG alternativa sicura…"
                        } else {
                            "Aggiornamento del percorso in corso…"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ReroutingStatus.FAILED -> Text(
                        when (state.routeUpdateFailure) {
                            RouteUpdateFailure.NO_SAFE_FUEL_ALTERNATIVE ->
                                "Nessuna alternativa CNG sicura: mantengo la tappa corrente."
                            RouteUpdateFailure.FUEL_RANGE_PLAN_REQUIRED ->
                                "Per sostituire questa tappa serve un piano autonomia predittivo."
                            RouteUpdateFailure.NETWORK_OR_SERVER,
                            null,
                            -> "Ricalcolo non disponibile: continuo sulla rotta scaricata."
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ReroutingStatus.IDLE -> Unit
                }
                state.lastSpokenInstruction?.let {
                    Text(
                        "Voce: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.nextFuelStop?.let { fuel ->
                    Text(
                        "Prossimo rifornimento: ${fuel.stop.name ?: "MIMIT ${fuel.stop.mimitStationId}"} · " +
                            formatDistance(fuel.distanceRemainingMeters),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CompassOutlinedButton(
                        onClick = { confirmFuelStopReplacement = true },
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Salta / sostituisci tappa CNG")
                    }
                }
                if (BuildConfig.DEBUG) {
                    CompassOutlinedButton(
                        onClick = onRequestRouteUpdate,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ricalcola percorso (debug)")
                    }
                    CompassOutlinedButton(
                        onClick = onSimulateOffRoute,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Simula deviazione (debug)")
                    }
                }
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

private fun gpsStatusLabel(status: GpsStatus): String = when (status) {
    GpsStatus.UNAVAILABLE -> "GPS non disponibile"
    GpsStatus.ACQUIRING -> "Ricerca del segnale GPS…"
    GpsStatus.ACTIVE -> "GPS attivo · posizione agganciata al percorso"
    GpsStatus.LOST -> "Segnale GPS temporaneamente perso"
}

@Composable
private fun ConfigureRouteContent(
    route: RoutePreview?,
    originLatitudeInput: String,
    originLongitudeInput: String,
    destinationLatitudeInput: String,
    destinationLongitudeInput: String,
    originDisplayName: String,
    destinationDisplayName: String,
    originAttributions: List<String>,
    destinationAttributions: List<String>,
    originLocationMethod: RouteLocationMethod?,
    destinationLocationMethod: RouteLocationMethod?,
    originCurrentLocationStatus: CurrentLocationAcquisitionStatus,
    destinationCurrentLocationStatus: CurrentLocationAcquisitionStatus,
    routeInputsDirty: Boolean,
    isCalculating: Boolean,
    message: String?,
    onSearch: (RouteEndpoint) -> Unit,
    onCoordinates: (RouteEndpoint) -> Unit,
    onUseCurrentLocation: (RouteEndpoint) -> Unit,
    onApply: () -> Unit,
) {
    val originReady = originLatitudeInput.isNotBlank() &&
        originLongitudeInput.isNotBlank() &&
        (
            originLocationMethod != RouteLocationMethod.CURRENT_LOCATION ||
                originCurrentLocationStatus == CurrentLocationAcquisitionStatus.SUCCESS
            )
    val destinationReady = destinationLatitudeInput.isNotBlank() &&
        destinationLongitudeInput.isNotBlank() &&
        (
            destinationLocationMethod != RouteLocationMethod.CURRENT_LOCATION ||
                destinationCurrentLocationStatus == CurrentLocationAcquisitionStatus.SUCCESS
            )
    val endpointsReady = originReady && destinationReady
    val routeReady = endpointsReady && route != null &&
        !routeInputsDirty && !isCalculating
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                top = 24.dp,
                end = 24.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                Text(
                    "Crea viaggio",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                RouteEndpointSelector(
                    title = "Partenza",
                    titleIcon = Icons.Rounded.Navigation,
                    endpoint = RouteEndpoint.ORIGIN,
                    displayName = originDisplayName,
                    attributions = originAttributions,
                    selectedMethod = originLocationMethod,
                    currentLocationStatus = originCurrentLocationStatus,
                    currentLocationLast = false,
                    onSearch = onSearch,
                    onCoordinates = onCoordinates,
                    onUseCurrentLocation = onUseCurrentLocation,
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                RouteEndpointSelector(
                    title = "Destinazione",
                    titleIcon = Icons.Rounded.LocationOn,
                    endpoint = RouteEndpoint.DESTINATION,
                    displayName = destinationDisplayName,
                    attributions = destinationAttributions,
                    selectedMethod = destinationLocationMethod,
                    currentLocationStatus = destinationCurrentLocationStatus,
                    currentLocationLast = true,
                    onSearch = onSearch,
                    onCoordinates = onCoordinates,
                    onUseCurrentLocation = onUseCurrentLocation,
                )
            }
            message?.let { text -> item { InlineError(text) } }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 3.dp,
        ) {
            CompassButton(
                onClick = onApply,
                enabled = endpointsReady && !isCalculating,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (isCalculating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    when {
                        isCalculating -> "Calcolo percorso…"
                        routeReady -> "Ricalcola percorso"
                        else -> "Calcola percorso"
                    },
                )
            }
        }
    }
}

@Composable
private fun RouteEndpointSelector(
    title: String,
    titleIcon: ImageVector,
    endpoint: RouteEndpoint,
    displayName: String,
    attributions: List<String>,
    selectedMethod: RouteLocationMethod?,
    currentLocationStatus: CurrentLocationAcquisitionStatus,
    currentLocationLast: Boolean,
    onSearch: (RouteEndpoint) -> Unit,
    onCoordinates: (RouteEndpoint) -> Unit,
    onUseCurrentLocation: (RouteEndpoint) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(titleIcon, contentDescription = null)
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        if (
            displayName != "Non selezionata" &&
            selectedMethod != RouteLocationMethod.CURRENT_LOCATION
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (selectedMethod == RouteLocationMethod.SEARCH && attributions.isNotEmpty()) {
                Text(
                    "Risultato fornito da ${attributions.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val methods = buildList {
            if (!currentLocationLast) add(RouteLocationMethod.CURRENT_LOCATION)
            add(RouteLocationMethod.FAVORITES)
            add(RouteLocationMethod.SEARCH)
            add(RouteLocationMethod.COORDINATES)
            if (currentLocationLast) add(RouteLocationMethod.CURRENT_LOCATION)
        }
        methods.chunked(2).forEach { rowMethods ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowMethods.forEach { method ->
                    val label = when (method) {
                        RouteLocationMethod.CURRENT_LOCATION -> "Posizione attuale"
                        RouteLocationMethod.FAVORITES -> "Posizioni preferite"
                        RouteLocationMethod.SEARCH -> "Ricerca"
                        RouteLocationMethod.COORDINATES -> "Selezione dalla mappa"
                    }
                    val icon = when (method) {
                        RouteLocationMethod.CURRENT_LOCATION -> Icons.Rounded.MyLocation
                        RouteLocationMethod.FAVORITES -> Icons.Rounded.BookmarkBorder
                        RouteLocationMethod.SEARCH -> Icons.Rounded.Search
                        RouteLocationMethod.COORDINATES -> Icons.Rounded.Map
                    }
                    if (method == RouteLocationMethod.CURRENT_LOCATION) {
                        CurrentLocationChoiceButton(
                            selected = method == selectedMethod,
                            status = currentLocationStatus,
                            modifier = Modifier.weight(1f),
                            onClick = { onUseCurrentLocation(endpoint) },
                        )
                    } else if (method == selectedMethod) {
                        CompassButton(
                            onClick = {
                                when (method) {
                                    RouteLocationMethod.CURRENT_LOCATION ->
                                        onUseCurrentLocation(endpoint)
                                    RouteLocationMethod.SEARCH -> onSearch(endpoint)
                                    RouteLocationMethod.COORDINATES -> onCoordinates(endpoint)
                                    RouteLocationMethod.FAVORITES -> Unit
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(icon, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        CompassOutlinedButton(
                            onClick = {
                                when (method) {
                                    RouteLocationMethod.CURRENT_LOCATION ->
                                        onUseCurrentLocation(endpoint)
                                    RouteLocationMethod.SEARCH -> onSearch(endpoint)
                                    RouteLocationMethod.COORDINATES -> onCoordinates(endpoint)
                                    RouteLocationMethod.FAVORITES -> Unit
                                }
                            },
                            enabled = method != RouteLocationMethod.FAVORITES,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(icon, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (rowMethods.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        if (selectedMethod == RouteLocationMethod.FAVORITES) {
            Text("Posizioni preferite · prossimamente")
        }
    }
}

@Composable
private fun CurrentLocationChoiceButton(
    selected: Boolean,
    status: CurrentLocationAcquisitionStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val semanticColors = MaterialTheme.compassSemanticColors
    val active = selected || status != CurrentLocationAcquisitionStatus.IDLE
    if (active) {
        val containerColor = when (status) {
            CurrentLocationAcquisitionStatus.SUCCESS -> semanticColors.successContainer
            CurrentLocationAcquisitionStatus.FAILURE -> MaterialTheme.colorScheme.errorContainer
            CurrentLocationAcquisitionStatus.ACQUIRING ->
                MaterialTheme.colorScheme.secondaryContainer
            CurrentLocationAcquisitionStatus.IDLE -> MaterialTheme.colorScheme.primaryContainer
        }
        val contentColor = when (status) {
            CurrentLocationAcquisitionStatus.SUCCESS -> semanticColors.onSuccessContainer
            CurrentLocationAcquisitionStatus.FAILURE -> MaterialTheme.colorScheme.onErrorContainer
            CurrentLocationAcquisitionStatus.ACQUIRING ->
                MaterialTheme.colorScheme.onSecondaryContainer
            CurrentLocationAcquisitionStatus.IDLE -> MaterialTheme.colorScheme.onPrimaryContainer
        }
        CompassButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null)
                Text(
                    "Posizione attuale",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when (status) {
                    CurrentLocationAcquisitionStatus.ACQUIRING -> CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                    CurrentLocationAcquisitionStatus.SUCCESS -> Text(
                        "✓",
                        fontWeight = FontWeight.Bold,
                    )
                    CurrentLocationAcquisitionStatus.FAILURE -> Text(
                        "✕",
                        fontWeight = FontWeight.Bold,
                    )
                    CurrentLocationAcquisitionStatus.IDLE -> Unit
                }
            }
        }
    } else {
        CompassOutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Posizione attuale", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DestinationSearchContent(
    target: RouteEndpoint,
    query: String,
    results: List<DestinationSuggestion>,
    isSearching: Boolean,
    isResolving: Boolean,
    message: String?,
    pendingResolvedDestination: ResolvedDestination?,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (DestinationSuggestion) -> Unit,
    onConfirmCoordinateOnly: () -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (query != input.text && input.composition == null) {
            input = TextFieldValue(query)
        }
    }
    val isBusy = isSearching || isResolving
    val canSearch = query.count { !it.isWhitespace() } >= BuildConfig.DESTINATION_SEARCH_MIN_CHARS &&
        !isBusy
    val isRecoverableError = message != null &&
        !message.startsWith("Digita almeno") &&
        message != "Nessun luogo trovato." &&
        pendingResolvedDestination == null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Indirizzo, città, attività o POI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    input = value
                    if (value.composition == null) onQueryChanged(value.text)
                },
                label = {
                    Text(
                        when (target) {
                            RouteEndpoint.ORIGIN -> "Partenza"
                            RouteEndpoint.INTERMEDIATE_STOP -> "Tappa intermedia"
                            RouteEndpoint.DESTINATION -> "Destinazione"
                        },
                    )
                },
                placeholder = { Text("es. Duomo di Milano") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Per un indirizzo separa il civico con una virgola: " +
                    "Via Cappafredda, 12, Roverchiara.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            CompassButton(
                onClick = onSearch,
                enabled = canSearch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isBusy) "Attendi…" else "Cerca")
            }
            if (isBusy) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                if (isRecoverableError) {
                    InlineError(it)
                    CompassTextButton(onClick = onSearch, enabled = canSearch) {
                        Text("Riprova")
                    }
                } else {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Risultati forniti da Google Maps", style = MaterialTheme.typography.labelMedium)
            }
            pendingResolvedDestination?.let { resolved ->
                Spacer(modifier = Modifier.height(10.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Destinazione disponibile solo come coordinate")
                        Text(
                            "${resolved.navigationTarget.location.latitude}, " +
                                "${resolved.navigationTarget.location.longitude}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CompassButton(onClick = onConfirmCoordinateOnly) {
                            Text("Usa queste coordinate")
                        }
                    }
                }
            }
        }
        itemsIndexed(results, key = { _, result -> result.id }) { _, result ->
            Card(
                onClick = { onSelect(result) },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(result.title, fontWeight = FontWeight.SemiBold)
                    result.subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        when (result.kind) {
                            DestinationKind.ADDRESS -> "Indirizzo"
                            DestinationKind.LOCALITY -> "Città o località"
                            DestinationKind.BUSINESS -> "Attività"
                            DestinationKind.UNKNOWN -> "Risultato"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    result.distanceMeters?.let { distance ->
                        Text(
                            "${formatDistance(distance.toDouble())} in linea d'aria",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        result.attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerConnectionContent(
    baseUrl: String,
    username: String,
    password: String,
    allowInsecureHttp: Boolean,
    message: String?,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onAllowInsecureHttpChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val usesHttp = baseUrl.trim().startsWith("http://", ignoreCase = true)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Server Compass",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Usa un indirizzo HTTPS raggiungibile dal telefono. Le credenziali " +
                            "restano sul dispositivo e la password è protetta da Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChanged,
                label = { Text("Endpoint API") },
                placeholder = { Text("https://compass.example.it/") },
                supportingText = { Text("Può includere il percorso del reverse proxy.") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChanged,
                label = { Text("Nome utente") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (usesHttp) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = allowInsecureHttp,
                            onCheckedChange = onAllowInsecureHttpChanged,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("Consenti HTTP non cifrato", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Le credenziali possono essere intercettate. Usalo solo come fallback.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        message?.let { error -> item { InlineError(error) } }
        item {
            CompassButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("Salva e connetti")
            }
        }
    }
}

@Composable
private fun VehicleProfilesContent(
    profiles: VehicleProfiles,
    editingProfileId: String?,
    nameInput: String,
    cngRangeInput: String,
    cngReserveInput: String,
    gasolineRangeInput: String,
    gasolineReserveInput: String,
    message: String?,
    onEdit: (VehicleProfile?) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onCngRangeChanged: (String) -> Unit,
    onCngReserveChanged: (String) -> Unit,
    onGasolineRangeChanged: (String) -> Unit,
    onGasolineReserveChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Parametri per mezzo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Il profilo precompila autonomie piene e riserve. Le autonomie residue " +
                            "restano sempre una stima inserita dal conducente.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (profiles.profiles.isEmpty()) {
            item {
                Text(
                    "Nessun profilo salvato.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(profiles.profiles) { _, profile ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(profile.name, fontWeight = FontWeight.Bold)
                    Text(
                        "CNG ${formatKilometers(profile.effectiveCngRangeKm)} " +
                            "(riserva ${formatKilometers(profile.cngReserveKm)}) · " +
                            "benzina ${formatKilometers(profile.effectiveGasolineRangeKm)} " +
                            "(riserva ${formatKilometers(profile.gasolineReserveKm)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompassButton(
                            onClick = { onSelect(profile.id) },
                            enabled = profiles.selectedProfileId != profile.id,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (profiles.selectedProfileId == profile.id) "Selezionato" else "Usa")
                        }
                        CompassOutlinedButton(
                            onClick = { onEdit(profile) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Modifica") }
                        CompassTextButton(onClick = { onDelete(profile.id) }) { Text("Elimina") }
                    }
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (editingProfileId == null) "Nuovo profilo" else "Modifica profilo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    CompassTextButton(onClick = { onEdit(null) }) { Text("Svuota") }
                }
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = onNameChanged,
                    label = { Text("Nome mezzo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                VehicleProfileNumberField(cngRangeInput, onCngRangeChanged, "Autonomia CNG piena (km)")
                VehicleProfileNumberField(cngReserveInput, onCngReserveChanged, "Riserva CNG (km)")
                VehicleProfileNumberField(
                    gasolineRangeInput,
                    onGasolineRangeChanged,
                    "Autonomia benzina piena (km)",
                )
                VehicleProfileNumberField(
                    gasolineReserveInput,
                    onGasolineReserveChanged,
                    "Riserva benzina (km)",
                )
                message?.let { InlineError(it) }
                CompassButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text("Salva e seleziona")
                }
            }
        }
    }
}

@Composable
private fun VehicleProfileNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private enum class VehiclePanel { PARAMETERS, PROFILES, FORM }

@Composable
private fun ConfigurePredictiveContent(
    route: RoutePreview,
    effectiveRangeInput: String,
    remainingRangeInput: String,
    reserveRangeInput: String,
    remainingGasolineRangeInput: String,
    effectiveGasolineRangeInput: String,
    gasolineReserveRangeInput: String,
    vehicleProfiles: VehicleProfiles,
    detourInput: String,
    message: String?,
    onEffectiveRangeChanged: (String) -> Unit,
    onRemainingRangeChanged: (String) -> Unit,
    onReserveRangeChanged: (String) -> Unit,
    onRemainingGasolineRangeChanged: (String) -> Unit,
    onSelectVehicleProfile: (String) -> Unit,
    onUseCustomVehicleValues: () -> Unit,
    onEditVehicleProfile: (VehicleProfile?) -> Unit,
    onDeleteVehicleProfile: (String) -> Unit,
    vehicleProfileNameInput: String,
    vehicleProfileCngRangeInput: String,
    vehicleProfileCngReserveInput: String,
    vehicleProfileGasolineRangeInput: String,
    vehicleProfileGasolineReserveInput: String,
    onVehicleProfileNameChanged: (String) -> Unit,
    onVehicleProfileCngRangeChanged: (String) -> Unit,
    onVehicleProfileCngReserveChanged: (String) -> Unit,
    onVehicleProfileGasolineRangeChanged: (String) -> Unit,
    onVehicleProfileGasolineReserveChanged: (String) -> Unit,
    onSaveVehicleProfile: () -> Unit,
    onDetourChanged: (String) -> Unit,
    onEvaluate: () -> Unit,
) {
    val selectedVehicleName = vehicleProfiles.selectedProfile?.name
    var vehiclePanel by rememberSaveable { mutableStateOf(VehiclePanel.PARAMETERS) }
    val reserveFocus = remember { FocusRequester() }
    val effectiveRangeFocus = remember { FocusRequester() }
    val detourFocus = remember { FocusRequester() }
    val gasolineFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(message, vehicleProfileNameInput) {
        if (
            vehiclePanel == VehiclePanel.FORM &&
            vehicleProfileNameInput.isBlank() &&
            message?.startsWith("Profilo ") == true
        ) {
            vehiclePanel = VehiclePanel.PROFILES
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = route,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Rifornimento predittivo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Inserisci una stima reale dell'autonomia residua. Compass non legge ancora il livello del veicolo e non inventa questo dato.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (vehiclePanel == VehiclePanel.PARAMETERS) item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Profilo veicolo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                CompassOutlinedButton(
                    onClick = { vehiclePanel = VehiclePanel.PROFILES },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selectedVehicleName?.let { "Mezzo: $it" } ?: "Carica o crea profilo")
                }
                CompassTextButton(
                    onClick = onUseCustomVehicleValues,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continua senza profilo") }
            }
        }
        if (vehiclePanel == VehiclePanel.PROFILES) item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Scegli un mezzo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (vehicleProfiles.profiles.isEmpty()) {
                    Text(
                        "Nessun profilo salvato.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                vehicleProfiles.profiles.forEach { profile ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "CNG ${formatKilometers(profile.effectiveCngRangeKm)} · " +
                                    "riserva ${formatKilometers(profile.cngReserveKm)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompassButton(
                                    onClick = {
                                        onSelectVehicleProfile(profile.id)
                                        vehiclePanel = VehiclePanel.PARAMETERS
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Usa") }
                                CompassOutlinedButton(
                                    onClick = {
                                        onEditVehicleProfile(profile)
                                        vehiclePanel = VehiclePanel.FORM
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Modifica") }
                                CompassTextButton(onClick = { onDeleteVehicleProfile(profile.id) }) {
                                    Text("Elimina")
                                }
                            }
                        }
                    }
                }
                CompassButton(
                    onClick = {
                        onEditVehicleProfile(null)
                        vehiclePanel = VehiclePanel.FORM
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Aggiungi veicolo") }
                CompassTextButton(
                    onClick = { vehiclePanel = VehiclePanel.PARAMETERS },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Torna ai parametri") }
            }
        }
        if (vehiclePanel == VehiclePanel.FORM) item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Dati del mezzo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = vehicleProfileNameInput,
                    onValueChange = onVehicleProfileNameChanged,
                    label = { Text("Nome del mezzo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                VehicleProfileNumberField(
                    vehicleProfileCngRangeInput,
                    onVehicleProfileCngRangeChanged,
                    "Autonomia CNG piena (km)",
                )
                VehicleProfileNumberField(
                    vehicleProfileCngReserveInput,
                    onVehicleProfileCngReserveChanged,
                    "Riserva CNG (km)",
                )
                VehicleProfileNumberField(
                    vehicleProfileGasolineRangeInput,
                    onVehicleProfileGasolineRangeChanged,
                    "Autonomia benzina piena (km)",
                )
                VehicleProfileNumberField(
                    vehicleProfileGasolineReserveInput,
                    onVehicleProfileGasolineReserveChanged,
                    "Riserva benzina (km)",
                )
                message?.let { InlineError(it) }
                CompassButton(
                    onClick = onSaveVehicleProfile,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Salva modifiche") }
                CompassTextButton(
                    onClick = { vehiclePanel = VehiclePanel.PROFILES },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Annulla") }
            }
        }
        if (vehiclePanel == VehiclePanel.PARAMETERS) item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = remainingRangeInput,
                    onValueChange = onRemainingRangeChanged,
                    label = { Text("Autonomia CNG residua stimata (km)") },
                    supportingText = { Text("Dato fornito dal conducente, non da telemetria.") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { reserveFocus.requestFocus() },
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reserveRangeInput,
                    onValueChange = onReserveRangeChanged,
                    label = { Text("Riserva di sicurezza (km)") },
                    supportingText = { Text("Non vengono suggerite stazioni oltre questa soglia.") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { effectiveRangeFocus.requestFocus() },
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(reserveFocus),
                )
                OutlinedTextField(
                    value = effectiveRangeInput,
                    onValueChange = onEffectiveRangeChanged,
                    label = { Text("Autonomia CNG effettiva a pieno (km)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { detourFocus.requestFocus() },
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(effectiveRangeFocus),
                )
                OutlinedTextField(
                    value = detourInput,
                    onValueChange = onDetourChanged,
                    label = { Text("Deviazione massima (minuti)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = if (selectedVehicleName == null) {
                            ImeAction.Done
                        } else {
                            ImeAction.Next
                        },
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { gasolineFocus.requestFocus() },
                        onDone = { focusManager.clearFocus() },
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(detourFocus),
                )
                Text(
                    "Fallback benzina",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selectedVehicleName == null) {
                    Text(
                        "Seleziona prima un profilo mezzo per abilitare il fallback benzina.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "$selectedVehicleName · massimo $effectiveGasolineRangeInput km · " +
                            "riserva $gasolineReserveRangeInput km",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = remainingGasolineRangeInput,
                        onValueChange = onRemainingGasolineRangeChanged,
                        label = { Text("Autonomia benzina residua stimata (km, opzionale)") },
                        supportingText = {
                            Text("Dato del conducente; lasciando vuoto il fallback è disattivato.")
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() },
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(gasolineFocus),
                    )
                }
                message?.let { InlineError(it) }
                CompassButton(onClick = onEvaluate, modifier = Modifier.fillMaxWidth()) {
                    Text("Valuta e suggerisci una stazione")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun VehicleProfileChoiceButton(
    profile: VehicleProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        CompassButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(profile.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "CNG ${formatKilometers(profile.effectiveCngRangeKm)} · " +
                        "riserva ${formatKilometers(profile.cngReserveKm)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        CompassOutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(profile.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "CNG ${formatKilometers(profile.effectiveCngRangeKm)} · " +
                        "riserva ${formatKilometers(profile.cngReserveKm)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VehicleCustomValuesChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        CompassButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("Nessun profilo · valori personalizzati")
        }
    } else {
        CompassOutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("Nessun profilo · valori personalizzati")
        }
    }
}

@Composable
private fun ConfigureCngContent(
    route: RoutePreview,
    rangeInput: String,
    detourInput: String,
    message: String?,
    onRangeChanged: (String) -> Unit,
    onDetourChanged: (String) -> Unit,
    onSearch: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = route,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tipo di tappa", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Metano (CNG)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Le stazioni chiuse all'orario di arrivo vengono escluse; gli orari mancanti restano sconosciuti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = detourInput,
                    onValueChange = onDetourChanged,
                    label = { Text("Deviazione massima (minuti)") },
                    supportingText = { Text("Sono ammessi solo i risultati entro questo limite.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rangeInput,
                    onValueChange = onRangeChanged,
                    label = { Text("Autonomia CNG residua (km)") },
                    supportingText = { Text("Usata per costruire il corridoio di ricerca.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { InlineError(it) }
                CompassButton(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                    Text("Cerca stazioni Metano")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun CandidateContent(
    rankedStations: RankedCngStations,
    predictiveSuggestion: PredictiveCngSuggestion?,
    pendingStation: RankedCngStation?,
    message: String?,
    onSelect: (RankedCngStation) -> Unit,
) {
    var selectedStationId by remember(rankedStations) { mutableStateOf<String?>(null) }
    val orderedCandidates = remember(rankedStations.candidates) {
        orderCngCandidatesForSelection(rankedStations.candidates)
    }
    val priceTiers = remember(rankedStations.candidates) {
        rankVisibleCngPrices(rankedStations.candidates)
    }
    LaunchedEffect(orderedCandidates, priceTiers) {
        Log.i(
            CNG_CANDIDATE_UI_LOG_TAG,
            "candidate_order detour_minutes=" +
                orderedCandidates.joinToString(",") {
                    "%.3f".format(Locale.ROOT, it.detourMinutes)
                } +
                " monotonic=" + orderedCandidates.zipWithNext().all { (first, second) ->
                    first.detourMinutes <= second.detourMinutes
                } +
                " price_tiers=" + CandidatePriceTier.entries.joinToString(",") { tier ->
                    "${tier.name.lowercase(Locale.ROOT)}:${priceTiers.values.count { it == tier }}"
                },
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = rankedStations.baseRoute,
            candidateStations = orderedCandidates,
            selectedCandidateStationId = selectedStationId,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.38f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    if (predictiveSuggestion == null) {
                        "${rankedStations.candidates.size} stazioni idonee"
                    } else {
                        "${rankedStations.candidates.size} stazioni raggiungibili"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Deviazione ≤ ${formatMinutesLimit(rankedStations.maximumDetourMinutes)} · traffico ${trafficLabel(rankedStations.trafficState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                predictiveSuggestion?.let { suggestion ->
                    Text(
                        "Utilizzabili ${formatKilometers(suggestion.rangeBasis.usableRangeBeforeReserveKm)} prima della riserva",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        message?.let {
            InlineError(
                message = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (rankedStations.candidates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f),
                contentAlignment = Alignment.Center,
            ) {
                Text("Nessuna stazione rispetta il limite impostato.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f),
            ) {
                itemsIndexed(
                    items = orderedCandidates,
                    key = { _, station -> station.mimitStationId },
                ) { index, station ->
                    CandidateCard(
                        station = station,
                        displayRank = index + 1,
                        priceTier = priceTiers[station.mimitStationId],
                        predictiveStation = predictiveSuggestion
                            ?.candidates
                            ?.firstOrNull {
                                it.station.mimitStationId == station.mimitStationId
                            },
                        selecting = pendingStation?.mimitStationId == station.mimitStationId,
                        enabled = pendingStation == null,
                        onHighlight = { selectedStationId = station.mimitStationId },
                        onChoose = { onSelect(station) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    station: RankedCngStation,
    displayRank: Int,
    priceTier: CandidatePriceTier?,
    predictiveStation: PredictiveCngStation?,
    selecting: Boolean,
    enabled: Boolean,
    onHighlight: () -> Unit,
    onChoose: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(
        onClick = onHighlight,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    RankingBadge(displayRank)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            station.name ?: "Stazione MIMIT ${station.mimitStationId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            listOfNotNull(station.municipality, station.province).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OpeningBadge(station.opening.state)
            }

            predictiveStation?.let { predictive ->
                Text(
                    "All'arrivo: ${formatKilometers(predictive.estimatedRemainingRangeAtArrivalKm)} di autonomia · ${formatSignedKilometers(predictive.reserveMarginAtArrivalKm)} sulla riserva",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CandidateMetric("Deviazione", formatDetour(station.detourMinutes))
                CandidateMetric("Da partenza", formatDistance(station.distanceFromPreviousWaypointMeters))
                CandidateMetric("Arrivo", formatTime(station.stationEta))
            }

            Text(
                station.opening.openingHours ?: "Orari non disponibili",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    station.price?.let { price ->
                        CandidatePrice(
                            price = price,
                            tier = priceTier ?: CandidatePriceTier.OTHER,
                        )
                        Text(
                            "Rilevato ${formatDateTime(price.observedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } ?: Text(
                        "Prezzo CNG non disponibile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    station.phone?.let { phone ->
                        CompassTextButton(
                            onClick = {
                                uriHandler.openUri("tel:${phone.filterPhoneCharacters()}")
                            },
                        ) {
                            Text("Chiama")
                        }
                    }
                    CompassButton(onClick = onChoose, enabled = enabled) {
                        if (selecting) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Calcolo…")
                        } else {
                            Text("Scegli")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictiveItineraryContent(
    suggestion: PredictiveCngSuggestion,
    calculating: Boolean,
    message: String?,
    onCalculateRoute: () -> Unit,
) {
    val itinerary = requireNotNull(suggestion.itinerary)
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = suggestion.baseRoute,
            cngStops = itinerary.stops.map { it.station.location },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.36f),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.64f),
        ) {
            item(key = "plan-summary") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${itinerary.stops.size} rifornimenti pianificati",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Ogni tappa presume un pieno fino a ${formatKilometers(suggestion.rangeBasis.effectiveCngRangeKm)}; tutte le tratte conservano ${formatKilometers(suggestion.rangeBasis.reserveCngRangeKm)} di riserva.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Distanze stradali · deviazione per stazione ≤ ${formatMinutesLimit(suggestion.maximumDetourMinutes)} · traffico ${trafficLabel(suggestion.rangeBasis.trafficState)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let { InlineError(it) }
                }
            }
            itemsIndexed(
                items = itinerary.stops,
                key = { _, stop -> stop.station.mimitStationId },
            ) { _, stop ->
                PredictiveItineraryStopCard(stop)
            }
            item(key = "destination-leg") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Ultima tratta · destinazione",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${formatDistance(itinerary.destinationLeg.distanceMeters)} · arrivo ${formatTime(itinerary.destinationLeg.destinationEta)}",
                        )
                        Text(
                            "Autonomia stimata all'arrivo ${formatKilometers(itinerary.destinationLeg.estimatedRemainingRangeAtArrivalKm)} · ${formatSignedKilometers(itinerary.destinationLeg.reserveMarginAtArrivalKm)} sulla riserva",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item(key = "calculate-route") {
                CompassButton(
                    onClick = onCalculateRoute,
                    enabled = !calculating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    if (calculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifico tutte le tratte…")
                    } else {
                        Text("Calcola percorso con ${itinerary.stops.size} soste")
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictiveItineraryStopCard(stop: PredictiveItineraryStop) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Rifornimento ${stop.sequence} · ${stop.station.name ?: "MIMIT ${stop.station.mimitStationId}"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(
                            stop.station.municipality,
                            stop.station.province,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OpeningBadge(stop.opening.state)
            }
            Text(
                "Tratta precedente ${formatDistance(stop.legDistanceMeters)} · arrivo ${formatTime(stop.arrivalAt)}",
            )
            Text(
                "Autonomia all'arrivo ${formatKilometers(stop.estimatedRemainingRangeAtArrivalKm)} · ${formatSignedKilometers(stop.reserveMarginAtArrivalKm)} sulla riserva",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stop.opening.openingHours ?: "Orari non disponibili",
                style = MaterialTheme.typography.bodySmall,
            )
            stop.price?.let { price ->
                Text(
                    "${formatPrice(price)} · rilevato ${formatDateTime(price.observedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text(
                "Prezzo CNG non disponibile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stop.phone?.let { phone ->
                CompassTextButton(
                    onClick = { uriHandler.openUri("tel:${phone.filterPhoneCharacters()}") },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Chiama")
                }
            }
        }
    }
}

@Composable
private fun PredictiveStatusContent(
    suggestion: PredictiveCngSuggestion,
    onChangeInputs: () -> Unit,
    onUseGasolineFallback: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = suggestion.baseRoute,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.52f)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val status = predictiveStatusCopy(suggestion.state)
                    Text(
                        status.first,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(status.second)
                    Text(
                        "Residua stimata ${formatKilometers(suggestion.rangeBasis.estimatedRemainingCngRangeKm)} · riserva ${formatKilometers(suggestion.rangeBasis.reserveCngRangeKm)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Percorso rimanente ${formatKilometers(suggestion.rangeBasis.remainingRouteDistanceKm)} · intervallo utilizzabile ${formatKilometers(suggestion.rangeBasis.usableRangeBeforeReserveKm)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Stima fornita dal conducente · traffico ${trafficLabel(suggestion.rangeBasis.trafficState)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestion.gasolineFallback?.let { fallback ->
                        Text(
                            "Benzina necessaria stimata " +
                                "${formatKilometers(fallback.requiredGasolineRangeKm)} · " +
                                "margine sulla riserva " +
                                formatKilometers(fallback.gasolineMarginAtDestinationKm),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        CompassButton(
                            onClick = onUseGasolineFallback,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continua con fallback benzina")
                        }
                    }
                    CompassButton(onClick = onChangeInputs, modifier = Modifier.fillMaxWidth()) {
                        Text("Modifica autonomia")
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteSummary(route: RoutePreview, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryValue(label = "Distanza", value = formatDistance(route.distanceMeters))
            SummaryValue(label = "Durata", value = formatDuration(route.durationSeconds))
            SummaryValue(
                label = "Traffico",
                value = if (route.navigation.trafficAware) "Live" else "Non live",
            )
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManeuverRow(number: Int, maneuver: Maneuver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(maneuver.instruction, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${formatDistance(maneuver.distanceMeters)} · ${formatDuration(maneuver.durationSeconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RankingBadge(rank: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "#$rank",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun OpeningBadge(state: OpeningState) {
    val semanticColors = MaterialTheme.compassSemanticColors
    val (label, containerColor, contentColor) = when (state) {
        OpeningState.OPEN -> Triple(
            "Aperto all'arrivo",
            semanticColors.successContainer,
            semanticColors.onSuccessContainer,
        )
        OpeningState.CLOSED -> Triple(
            "Chiuso all'arrivo",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        OpeningState.UNKNOWN -> Triple(
            "Orario sconosciuto",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = containerColor, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CandidatePrice(price: CngPrice, tier: CandidatePriceTier) {
    val semanticColors = MaterialTheme.compassSemanticColors
    val (containerColor, contentColor) = when (tier) {
        CandidatePriceTier.CHEAPEST ->
            semanticColors.successContainer to semanticColors.onSuccessContainer
        CandidatePriceTier.SECOND_CHEAPEST ->
            semanticColors.warningContainer to semanticColors.onWarningContainer
        CandidatePriceTier.OTHER ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.wrapContentWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Prezzo Metano",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                formatPrice(price),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CandidateMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineError(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

internal fun formatDistance(distanceMeters: Double): String = if (distanceMeters >= 1_000) {
    String.format(Locale.ITALY, "%.1f km", distanceMeters / 1_000)
} else {
    "${distanceMeters.toInt()} m"
}

internal fun formatDuration(durationSeconds: Double): String {
    val totalMinutes = (durationSeconds / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} h ${minutes} min" else "$minutes min"
}

private fun formatDetour(minutes: Double): String = String.format(Locale.ITALY, "+%.1f min", minutes)

private fun formatMinutesLimit(minutes: Double): String = String.format(Locale.ITALY, "%.0f min", minutes)

private fun formatKilometers(kilometers: Double): String = String.format(
    Locale.ITALY,
    "%.1f km",
    kilometers,
)

private fun formatCacheTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(java.time.Instant.ofEpochMilli(epochMillis))

private fun formatSignedKilometers(kilometers: Double): String = String.format(
    Locale.ITALY,
    "%+.1f km",
    kilometers,
)

private fun formatTime(value: OffsetDateTime): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(value.atZoneSameInstant(ZoneId.systemDefault()))

private val ACTIVE_NAVIGATION_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatDateTime(value: OffsetDateTime): String =
    DateTimeFormatter.ofPattern("dd/MM HH:mm")
        .format(value.atZoneSameInstant(ZoneId.systemDefault()))

private fun formatPrice(price: CngPrice): String = String.format(
    Locale.ITALY,
    "%.3f %s/%s",
    price.unitPrice,
    price.currency,
    price.unit,
)

private fun trafficLabel(trafficState: String): String = when (trafficState) {
    "not_configured" -> "live non configurato"
    "configured" -> "configurato, non attivo"
    "mock" -> "mock"
    "fresh" -> "live attivo"
    "stale" -> "live non recente"
    "unavailable" -> "non disponibile"
    else -> trafficState
}

private fun predictiveStatusCopy(state: PredictiveSuggestionState): Pair<String, String> = when (state) {
    PredictiveSuggestionState.NOT_NEEDED -> Pair(
        "Rifornimento non necessario",
        "La destinazione è raggiungibile conservando la riserva impostata.",
    )
    PredictiveSuggestionState.NO_REACHABLE_STATION -> Pair(
        "Nessuna stazione raggiungibile",
        "Con questa stima non risulta una stazione raggiungibile prima della riserva. Non proseguire facendo affidamento su questo itinerario.",
    )
    PredictiveSuggestionState.NO_ELIGIBLE_STATION -> Pair(
        "Nessuna stazione idonea",
        "Esistono stazioni raggiungibili, ma nessuna rispetta disponibilità e deviazione massima.",
    )
    PredictiveSuggestionState.NO_COMPLETE_ITINERARY -> Pair(
        "Viaggio CNG non completabile",
        "Esiste una prima stazione raggiungibile, ma non una catena completa di rifornimenti che conservi la riserva fino alla destinazione. Non fare affidamento su questo itinerario.",
    )
    PredictiveSuggestionState.SUGGESTED -> error("suggested results use the itinerary screen")
    PredictiveSuggestionState.GASOLINE_FALLBACK -> Pair(
        "Fallback benzina disponibile",
        "Non esiste un itinerario completo a metano, ma la rotta diretta è stimata percorribile usando la benzina e conservando entrambe le riserve.",
    )
}

private fun String.filterPhoneCharacters(): String = filter { it.isDigit() || it == '+' }

private const val CNG_CANDIDATE_UI_LOG_TAG = "CompassCngCandidates"
