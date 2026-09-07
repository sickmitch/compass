package org.compass.cng.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.compass.cng.BuildConfig
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.CngItineraryRouteLeg
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
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary
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
            val baseRoute = state.baseRoute
            when {
                state.stage == PlannerStage.SERVER_CONNECTION -> ServerConnectionContent(
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
                state.stage == PlannerStage.CONFIGURE_ROUTE -> ConfigureRouteContent(
                    route = baseRoute,
                    originLatitudeInput = state.originLatitudeInput,
                    originLongitudeInput = state.originLongitudeInput,
                    destinationLatitudeInput = state.destinationLatitudeInput,
                    destinationLongitudeInput = state.destinationLongitudeInput,
                    originDisplayName = state.originDisplayName,
                    destinationDisplayName = state.destinationDisplayName,
                    originLocationMethod = state.originLocationMethod,
                    destinationLocationMethod = state.destinationLocationMethod,
                    originCurrentLocationStatus = state.originCurrentLocationStatus,
                    destinationCurrentLocationStatus = state.destinationCurrentLocationStatus,
                    routeInputsDirty = state.routeInputsDirty,
                    isCalculating = state.operation == PlannerOperation.BASE_ROUTE,
                    message = state.message,
                    onOriginLatitudeChanged = viewModel::updateOriginLatitude,
                    onOriginLongitudeChanged = viewModel::updateOriginLongitude,
                    onDestinationLatitudeChanged = viewModel::updateDestinationLatitude,
                    onDestinationLongitudeChanged = viewModel::updateDestinationLongitude,
                    onSearch = viewModel::openPlaceSearch,
                    onCoordinates = viewModel::selectCoordinateInput,
                    onUseCurrentLocation = onUseCurrentLocation,
                    onApply = viewModel::applyRouteInputs,
                    onDirectRoute = viewModel::openNavigationPreview,
                    onAddStop = viewModel::openAddStop,
                    onExtendedPlanning = viewModel::openPredictiveRange,
                )
                state.stage == PlannerStage.DESTINATION_SEARCH -> DestinationSearchContent(
                    target = state.placeSearchTarget,
                    query = state.placeSearchQuery,
                    results = state.placeSearchResults,
                    isSearching = state.operation == PlannerOperation.PLACE_SEARCH,
                    message = state.message,
                    source = state.placeSearchSource,
                    cachedAtEpochMillis = state.placeSearchCachedAtEpochMillis,
                    onQueryChanged = viewModel::updatePlaceSearchQuery,
                    onSearch = viewModel::searchDestinations,
                    onSelect = viewModel::selectPlace,
                )
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
                else -> when (state.stage) {
                    PlannerStage.FOLLOW,
                    PlannerStage.CONFIGURE_ROUTE,
                    PlannerStage.DESTINATION_SEARCH -> Unit
                    PlannerStage.PREVIEW -> PreviewContent(
                        route = baseRoute,
                        onStartNavigation = viewModel::openNavigationPreview,
                        onEditRoute = viewModel::openRouteConfiguration,
                        selectedVehicleName = state.vehicleProfiles.selectedProfile?.name,
                        onVehicleProfiles = viewModel::openVehicleProfiles,
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
                    PlannerStage.SELECTED_ROUTE -> {
                        val itineraryRoute = state.selectedItineraryRoute
                        if (itineraryRoute != null) {
                            SelectedItineraryRouteContent(
                                selectedRoute = itineraryRoute,
                                onStartNavigation = viewModel::openNavigationPreview,
                                onChangePlan = viewModel::navigateBack,
                                onRemoveStops = viewModel::removeCngStop,
                            )
                        } else {
                            SelectedRouteContent(
                                selectedRoute = requireNotNull(state.selectedRoute),
                                onStartNavigation = viewModel::openNavigationPreview,
                                onChangeStation = viewModel::navigateBack,
                                onRemoveStop = viewModel::removeCngStop,
                            )
                        }
                    }
                    PlannerStage.NAVIGATION_PREVIEW -> NavigationPreviewContent(
                        route = requireNotNull(navigationState.route),
                        state = navigationState,
                        message = state.message,
                        onStartNavigation = onStartNavigation,
                        onStartNavigationReplay = onStartNavigationReplay,
                        onRequestRouteUpdate = onRequestRouteUpdate,
                        onSimulateOffRoute = onSimulateOffRoute,
                        onReplaceUnavailableFuelStop = onReplaceUnavailableFuelStop,
                        onVoiceGuidanceEnabledChange = onVoiceGuidanceEnabledChange,
                        onStopNavigation = onStopNavigation,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    stage: PlannerStage,
    navigationPhase: NavigationPhase,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canNavigateBack) {
            TextButton(onClick = onNavigateBack) {
                Text("Indietro")
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Compass",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (stage) {
                    PlannerStage.FOLLOW -> "Segui posizione"
                    PlannerStage.CONFIGURE_ROUTE -> "Modifica partenza e destinazione"
                    PlannerStage.DESTINATION_SEARCH -> "Cerca posizione"
                    PlannerStage.PREVIEW -> "Anteprima percorso"
                    PlannerStage.CONFIGURE_CNG -> "Aggiungi tappa · Metano"
                    PlannerStage.CONFIGURE_PREDICTIVE -> "Crea viaggio"
                    PlannerStage.VEHICLE_PROFILES -> "Profili dei mezzi"
                    PlannerStage.SERVER_CONNECTION -> "Connessione al server"
                    PlannerStage.CNG_CANDIDATES -> "Stazioni Metano lungo il percorso"
                    PlannerStage.PREDICTIVE_ITINERARY -> "Piano rifornimenti CNG"
                    PlannerStage.PREDICTIVE_STATUS -> "Autonomia CNG"
                    PlannerStage.SELECTED_ROUTE -> "Percorso con rifornimento"
                    PlannerStage.NAVIGATION_PREVIEW -> when (navigationPhase) {
                        NavigationPhase.ROUTE_PREVIEW -> "Navigazione pronta"
                        NavigationPhase.REROUTING -> "Ricalcolo percorso"
                        NavigationPhase.GPS_LOST -> "Segnale GPS perso"
                        NavigationPhase.ARRIVED -> "Destinazione raggiunta"
                        else -> "Navigazione attiva"
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun PreviewContent(
    route: RoutePreview,
    onStartNavigation: () -> Unit,
    onEditRoute: () -> Unit,
    selectedVehicleName: String?,
    onVehicleProfiles: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f),
        )
        RouteSummary(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Button(
            onClick = onStartNavigation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("Avvia navigazione")
        }
        OutlinedButton(
            onClick = onEditRoute,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("Modifica percorso")
        }
        TextButton(
            onClick = onVehicleProfiles,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selectedVehicleName?.let { "Mezzo: $it" }
                    ?: "Configura profili mezzi",
            )
        }
        Text(
            text = "Indicazioni principali",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ManeuverList(
            maneuvers = route.maneuvers,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.52f),
        )
    }
}

@Composable
private fun NavigationPreviewContent(
    route: NavigationRoute,
    state: NavigationState,
    message: String?,
    onStartNavigation: () -> Unit,
    onStartNavigationReplay: () -> Unit,
    onRequestRouteUpdate: () -> Unit,
    onSimulateOffRoute: () -> Unit,
    onReplaceUnavailableFuelStop: () -> Unit,
    onVoiceGuidanceEnabledChange: (Boolean) -> Unit,
    onStopNavigation: () -> Unit,
) {
    if (state.phase != NavigationPhase.ROUTE_PREVIEW) {
        ActiveNavigationScreen(
            state = state,
            onRequestRouteUpdate = onRequestRouteUpdate,
            onSimulateOffRoute = onSimulateOffRoute,
            onReplaceUnavailableFuelStop = onReplaceUnavailableFuelStop,
            onVoiceGuidanceEnabledChange = onVoiceGuidanceEnabledChange,
            onStopNavigation = onStopNavigation,
        )
        return
    }
    val preview = route.asRoutePreview()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = preview,
                cngStops = route.fuelStops.map { it.location },
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
                        "Percorso pronto per la navigazione",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.routeSource == NavigationRouteSource.CACHE) {
                        Text(
                            "Percorso e manovre recuperati dalla cache del dispositivo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "${formatDistance(route.totalDistanceMeters)} · " +
                            "${formatDuration(route.drivingDurationSeconds)} di guida",
                    )
                    Text(
                        "Durata totale ${formatDuration(route.totalTripDurationSeconds)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                                "Tappe CNG salvate: prezzi, orari e dati live possono non essere aggiornati.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
                    Text(
                        "ID percorso ${route.routeId.takeLast(12)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            Button(
                onClick = onStartNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text("Inizia navigazione GPS")
            }
        }
        if (BuildConfig.DEBUG) {
            item {
                OutlinedButton(
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
                TextButton(
                    onClick = {
                        confirmFuelStopReplacement = false
                        onReplaceUnavailableFuelStop()
                    },
                ) {
                    Text("Cerca alternativa")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFuelStopReplacement = false }) {
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
                    Button(onClick = { cameraMode = NavigationCameraMode.OVERVIEW }) {
                        Text("Panoramica")
                    }
                } else {
                    OutlinedButton(onClick = { cameraMode = NavigationCameraMode.OVERVIEW }) {
                        Text("Panoramica")
                    }
                }
                if (cameraMode == NavigationCameraMode.FOLLOW) {
                    Button(onClick = { cameraMode = NavigationCameraMode.FOLLOW }) {
                        Text("Ricentra")
                    }
                } else {
                    OutlinedButton(onClick = { cameraMode = NavigationCameraMode.FOLLOW }) {
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
                    trafficTimingText(state.route.timing),
                    color = if (state.route.timing.trafficAware) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.connectivity == NavigationConnectivity.REROUTING_UNAVAILABLE) {
                    Text(
                        "Connessione Compass assente: navigazione locale attiva, ricalcolo non disponibile.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.routeSource == NavigationRouteSource.CACHE && state.route.fuelStops.isNotEmpty()) {
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
                    OutlinedButton(
                        onClick = { confirmFuelStopReplacement = true },
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Salta / sostituisci tappa CNG")
                    }
                }
                if (BuildConfig.DEBUG) {
                    OutlinedButton(
                        onClick = onRequestRouteUpdate,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ricalcola percorso (debug)")
                    }
                    OutlinedButton(
                        onClick = onSimulateOffRoute,
                        enabled = state.reroutingStatus != ReroutingStatus.IN_PROGRESS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Simula deviazione (debug)")
                    }
                }
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
    originLocationMethod: RouteLocationMethod?,
    destinationLocationMethod: RouteLocationMethod?,
    originCurrentLocationStatus: CurrentLocationAcquisitionStatus,
    destinationCurrentLocationStatus: CurrentLocationAcquisitionStatus,
    routeInputsDirty: Boolean,
    isCalculating: Boolean,
    message: String?,
    onOriginLatitudeChanged: (String) -> Unit,
    onOriginLongitudeChanged: (String) -> Unit,
    onDestinationLatitudeChanged: (String) -> Unit,
    onDestinationLongitudeChanged: (String) -> Unit,
    onSearch: (RouteEndpoint) -> Unit,
    onCoordinates: (RouteEndpoint) -> Unit,
    onUseCurrentLocation: (RouteEndpoint) -> Unit,
    onApply: () -> Unit,
    onDirectRoute: () -> Unit,
    onAddStop: () -> Unit,
    onExtendedPlanning: () -> Unit,
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
    val routeReady = endpointsReady && route != null && !routeInputsDirty && !isCalculating
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RouteEndpointSelector(
                    title = "Partenza",
                    endpoint = RouteEndpoint.ORIGIN,
                    displayName = originDisplayName,
                    selectedMethod = originLocationMethod,
                    currentLocationStatus = originCurrentLocationStatus,
                    currentLocationLast = false,
                    latitudeInput = originLatitudeInput,
                    longitudeInput = originLongitudeInput,
                    onLatitudeChanged = onOriginLatitudeChanged,
                    onLongitudeChanged = onOriginLongitudeChanged,
                    onSearch = onSearch,
                    onCoordinates = onCoordinates,
                    onUseCurrentLocation = onUseCurrentLocation,
                )
                HorizontalDivider()
                RouteEndpointSelector(
                    title = "Destinazione",
                    endpoint = RouteEndpoint.DESTINATION,
                    displayName = destinationDisplayName,
                    selectedMethod = destinationLocationMethod,
                    currentLocationStatus = destinationCurrentLocationStatus,
                    currentLocationLast = true,
                    latitudeInput = destinationLatitudeInput,
                    longitudeInput = destinationLongitudeInput,
                    onLatitudeChanged = onDestinationLatitudeChanged,
                    onLongitudeChanged = onDestinationLongitudeChanged,
                    onSearch = onSearch,
                    onCoordinates = onCoordinates,
                    onUseCurrentLocation = onUseCurrentLocation,
                )
                message?.let { InlineError(it) }
                Button(
                    onClick = onApply,
                    enabled = endpointsReady && !isCalculating,
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
                    Text(
                        when {
                            isCalculating -> "Calcolo percorso…"
                            routeReady -> "Ricalcola percorso"
                            else -> "Calcola percorso"
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onAddStop,
                        enabled = routeReady,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Imposta una sosta")
                    }
                    OutlinedButton(
                        onClick = onExtendedPlanning,
                        enabled = routeReady,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Pianificazione estesa")
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    OutlinedButton(
                        onClick = onDirectRoute,
                        enabled = routeReady,
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text("Usa percorso diretto")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun RouteEndpointSelector(
    title: String,
    endpoint: RouteEndpoint,
    displayName: String,
    selectedMethod: RouteLocationMethod?,
    currentLocationStatus: CurrentLocationAcquisitionStatus,
    currentLocationLast: Boolean,
    latitudeInput: String,
    longitudeInput: String,
    onLatitudeChanged: (String) -> Unit,
    onLongitudeChanged: (String) -> Unit,
    onSearch: (RouteEndpoint) -> Unit,
    onCoordinates: (RouteEndpoint) -> Unit,
    onUseCurrentLocation: (RouteEndpoint) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (
            displayName != "Non selezionata" &&
            selectedMethod != RouteLocationMethod.CURRENT_LOCATION
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val methods = buildList {
            if (!currentLocationLast) add(RouteLocationMethod.CURRENT_LOCATION)
            add(RouteLocationMethod.FAVORITES)
            add(RouteLocationMethod.SEARCH)
            add(RouteLocationMethod.COORDINATES)
            if (currentLocationLast) add(RouteLocationMethod.CURRENT_LOCATION)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            methods.forEach { method ->
                val label = when (method) {
                    RouteLocationMethod.CURRENT_LOCATION -> "Posizione attuale"
                    RouteLocationMethod.FAVORITES -> "Posizioni preferite"
                    RouteLocationMethod.SEARCH -> "Ricerca"
                    RouteLocationMethod.COORDINATES -> "Coordinate"
                }
                if (method == RouteLocationMethod.CURRENT_LOCATION) {
                    CurrentLocationChoiceButton(
                        selected = method == selectedMethod,
                        status = currentLocationStatus,
                        onClick = { onUseCurrentLocation(endpoint) },
                    )
                } else if (method == selectedMethod) {
                    Button(
                        onClick = {
                            when (method) {
                                RouteLocationMethod.CURRENT_LOCATION -> onUseCurrentLocation(endpoint)
                                RouteLocationMethod.SEARCH -> onSearch(endpoint)
                                RouteLocationMethod.COORDINATES -> onCoordinates(endpoint)
                                RouteLocationMethod.FAVORITES -> Unit
                            }
                        },
                        modifier = Modifier.wrapContentWidth(),
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = {
                            when (method) {
                                RouteLocationMethod.CURRENT_LOCATION -> onUseCurrentLocation(endpoint)
                                RouteLocationMethod.SEARCH -> onSearch(endpoint)
                                RouteLocationMethod.COORDINATES -> onCoordinates(endpoint)
                                RouteLocationMethod.FAVORITES -> Unit
                            }
                        },
                        enabled = method != RouteLocationMethod.FAVORITES,
                        modifier = Modifier.wrapContentWidth(),
                    ) { Text(label) }
                }
            }
        }
        if (selectedMethod == RouteLocationMethod.FAVORITES) {
            Text("Posizioni preferite · prossimamente")
        }
        if (selectedMethod == RouteLocationMethod.COORDINATES) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CoordinateTextField(
                    value = latitudeInput,
                    onValueChange = onLatitudeChanged,
                    label = "Latitudine",
                    modifier = Modifier.weight(1f),
                )
                CoordinateTextField(
                    value = longitudeInput,
                    onValueChange = onLongitudeChanged,
                    label = "Longitudine",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CurrentLocationChoiceButton(
    selected: Boolean,
    status: CurrentLocationAcquisitionStatus,
    onClick: () -> Unit,
) {
    val successContainer = Color(0xFFA8DAB5)
    val successContent = Color(0xFF073B1C)
    val failureContainer = Color(0xFFFFCDD2)
    val failureContent = Color(0xFF7F0000)
    val active = selected || status != CurrentLocationAcquisitionStatus.IDLE
    if (active) {
        val containerColor = when (status) {
            CurrentLocationAcquisitionStatus.SUCCESS -> successContainer
            CurrentLocationAcquisitionStatus.FAILURE -> failureContainer
            CurrentLocationAcquisitionStatus.ACQUIRING,
            CurrentLocationAcquisitionStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        }
        val contentColor = when (status) {
            CurrentLocationAcquisitionStatus.SUCCESS -> successContent
            CurrentLocationAcquisitionStatus.FAILURE -> failureContent
            CurrentLocationAcquisitionStatus.ACQUIRING,
            CurrentLocationAcquisitionStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Button(
            onClick = onClick,
            modifier = Modifier.wrapContentWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Posizione attuale")
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
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.wrapContentWidth(),
        ) {
            Text("Posizione attuale")
        }
    }
}

@Composable
private fun DestinationSearchContent(
    target: RouteEndpoint,
    query: String,
    results: List<PlaceSearchResult>,
    isSearching: Boolean,
    message: String?,
    source: PlaceSearchSource,
    cachedAtEpochMillis: Long?,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (PlaceSearchResult) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Indirizzo, città, attività, POI oppure coordinate",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = {
                    Text(if (target == RouteEndpoint.ORIGIN) "Partenza" else "Destinazione")
                },
                placeholder = { Text("es. Duomo di Milano") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
            Button(
                onClick = onSearch,
                enabled = !isSearching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSearching) "Ricerca…" else "Cerca")
            }
            if (isSearching) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                InlineError(it)
            }
            if (source == PlaceSearchSource.CACHE && results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Risultati salvati sul dispositivo: ricerca live non disponibile" +
                        (cachedAtEpochMillis?.let { " · cache ${formatCacheTime(it)}" } ?: "") + ".",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        itemsIndexed(results, key = { _, result -> result.id }) { _, result ->
            Card(onClick = { onSelect(result) }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(result.displayName, fontWeight = FontWeight.SemiBold)
                    result.address?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        when (result.kind) {
                            PlaceKind.ADDRESS -> "Indirizzo"
                            PlaceKind.LOCALITY -> "Città o località"
                            PlaceKind.POI -> result.category?.let { "Luogo · $it" } ?: "Luogo"
                            PlaceKind.COORDINATE -> "Coordinate"
                            PlaceKind.UNKNOWN -> "Risultato"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoordinateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
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
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
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
                        Button(
                            onClick = { onSelect(profile.id) },
                            enabled = profiles.selectedProfileId != profile.id,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (profiles.selectedProfileId == profile.id) "Selezionato" else "Usa")
                        }
                        OutlinedButton(
                            onClick = { onEdit(profile) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Modifica") }
                        TextButton(onClick = { onDelete(profile.id) }) { Text("Elimina") }
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
                    TextButton(onClick = { onEdit(null) }) { Text("Svuota") }
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
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
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
    onDetourChanged: (String) -> Unit,
    onEvaluate: () -> Unit,
) {
    val selectedVehicleName = vehicleProfiles.selectedProfile?.name
    val reserveFocus = remember { FocusRequester() }
    val effectiveRangeFocus = remember { FocusRequester() }
    val detourFocus = remember { FocusRequester() }
    val gasolineFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
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
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Profilo veicolo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (vehicleProfiles.profiles.isEmpty()) {
                    Text(
                        "Nessun profilo salvato: inserisci valori personalizzati.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    vehicleProfiles.profiles.forEach { profile ->
                        VehicleProfileChoiceButton(
                            profile = profile,
                            selected = profile.id == vehicleProfiles.selectedProfileId,
                            onClick = { onSelectVehicleProfile(profile.id) },
                        )
                    }
                    VehicleCustomValuesChoiceButton(
                        selected = vehicleProfiles.selectedProfileId == null,
                        onClick = onUseCustomVehicleValues,
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
                Button(onClick = onEvaluate, modifier = Modifier.fillMaxWidth()) {
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
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("Nessun profilo · valori personalizzati")
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
                    label = { Text("Autonomia CNG effettiva (km)") },
                    supportingText = { Text("Usata per costruire il corridoio di ricerca.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { InlineError(it) }
                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
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
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = rankedStations.baseRoute,
            candidateStations = rankedStations.candidates,
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
                    items = rankedStations.candidates,
                    key = { _, station -> station.mimitStationId },
                ) { _, station ->
                    CandidateCard(
                        station = station,
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
                    RankingBadge(station.ranking.rank)
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
                        CandidatePrice(price)
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
                        TextButton(
                            onClick = {
                                uriHandler.openUri("tel:${phone.filterPhoneCharacters()}")
                            },
                        ) {
                            Text("Chiama")
                        }
                    }
                    Button(onClick = onChoose, enabled = enabled) {
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
                Button(
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
                TextButton(
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
                        Button(
                            onClick = onUseGasolineFallback,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continua con fallback benzina")
                        }
                    }
                    Button(onClick = onChangeInputs, modifier = Modifier.fillMaxWidth()) {
                        Text("Modifica autonomia")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedRouteContent(
    selectedRoute: RouteWithCngStop,
    onStartNavigation: () -> Unit,
    onChangeStation: () -> Unit,
    onRemoveStop: () -> Unit,
) {
    val route = selectedRoute.asRoutePreview()
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            cngStops = listOf(selectedRoute.selectedStop.location),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.42f),
        )
        RouteSummary(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button(
            onClick = onStartNavigation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Avvia navigazione")
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tappa Metano", style = MaterialTheme.typography.labelMedium)
                Text(
                    selectedRoute.selectedStop.name
                        ?: "Stazione MIMIT ${selectedRoute.selectedStop.mimitStationId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    listOfNotNull(
                        selectedRoute.selectedStop.municipality,
                        selectedRoute.selectedStop.province,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRemoveStop, modifier = Modifier.weight(1f)) {
                Text("Rimuovi tappa")
            }
            Button(onClick = onChangeStation, modifier = Modifier.weight(1f)) {
                Text("Cambia stazione")
            }
        }
        SelectedRouteManeuvers(
            legs = selectedRoute.legs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.58f),
        )
    }
}

@Composable
private fun SelectedItineraryRouteContent(
    selectedRoute: RouteWithCngItinerary,
    onStartNavigation: () -> Unit,
    onChangePlan: () -> Unit,
    onRemoveStops: () -> Unit,
) {
    val route = selectedRoute.asRoutePreview()
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            cngStops = selectedRoute.selectedStops.map { it.location },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.38f),
        )
        RouteSummary(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button(
            onClick = onStartNavigation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Avvia navigazione")
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${selectedRoute.selectedStops.size} soste CNG verificate",
                    style = MaterialTheme.typography.labelMedium,
                )
                selectedRoute.selectedStops.forEachIndexed { index, stop ->
                    Text(
                        "${index + 1}. ${stop.name ?: "MIMIT ${stop.mimitStationId}"} · ${listOfNotNull(stop.municipality, stop.province).joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Ogni tratta conserva la riserva impostata.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRemoveStops, modifier = Modifier.weight(1f)) {
                Text("Rimuovi soste")
            }
            Button(onClick = onChangePlan, modifier = Modifier.weight(1f)) {
                Text("Rivedi piano")
            }
        }
        SelectedItineraryManeuvers(
            legs = selectedRoute.legs,
            stopCount = selectedRoute.selectedStops.size,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.62f),
        )
    }
}

@Composable
private fun SelectedItineraryManeuvers(
    legs: List<CngItineraryRouteLeg>,
    stopCount: Int,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        legs.forEachIndexed { legIndex, leg ->
            item(key = "itinerary-leg-$legIndex") {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        itineraryLegTitle(legIndex, stopCount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${formatDistance(leg.route.distanceMeters)} · autonomia all'arrivo ${formatKilometers(leg.estimatedRemainingRangeAtArrivalKm)} · ${formatSignedKilometers(leg.reserveMarginAtArrivalKm)} sulla riserva",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(
                items = leg.route.maneuvers,
                key = { maneuverIndex, _ -> "itinerary-$legIndex-$maneuverIndex" },
            ) { maneuverIndex, maneuver ->
                ManeuverRow(number = maneuverIndex + 1, maneuver = maneuver)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

private fun itineraryLegTitle(legIndex: Int, stopCount: Int): String = when {
    legIndex == 0 -> "Verso il rifornimento 1"
    legIndex == stopCount -> "Dall'ultimo rifornimento alla destinazione"
    else -> "Dal rifornimento $legIndex al rifornimento ${legIndex + 1}"
}

@Composable
private fun SelectedRouteManeuvers(
    legs: List<CngRouteLeg>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        legs.forEachIndexed { legIndex, leg ->
            item(key = "leg-$legIndex") {
                Text(
                    if (legIndex == 0) "Verso la stazione" else "Dalla stazione alla destinazione",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(
                items = leg.route.maneuvers,
                key = { maneuverIndex, _ -> "$legIndex-$maneuverIndex" },
            ) { maneuverIndex, maneuver ->
                ManeuverRow(number = maneuverIndex + 1, maneuver = maneuver)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
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
            SummaryValue(label = "Routing", value = route.provider.replaceFirstChar(Char::uppercase))
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
private fun ManeuverList(maneuvers: List<Maneuver>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        itemsIndexed(maneuvers) { index, maneuver ->
            ManeuverRow(number = index + 1, maneuver = maneuver)
            if (index < maneuvers.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
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
    val (label, containerColor, contentColor) = when (state) {
        OpeningState.OPEN -> Triple(
            "Aperto all'arrivo",
            Color(0xFF146C3A),
            Color.White,
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
private fun CandidatePrice(price: CngPrice) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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

private fun formatTime(value: OffsetDateTime): String = value.format(DateTimeFormatter.ofPattern("HH:mm"))

private val ACTIVE_NAVIGATION_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatDateTime(value: OffsetDateTime): String = value.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

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
