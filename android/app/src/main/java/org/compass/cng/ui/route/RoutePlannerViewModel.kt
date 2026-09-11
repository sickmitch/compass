package org.compass.cng.ui.route

import org.compass.cng.BuildConfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.DestinationSearchContext
import org.compass.cng.domain.model.DestinationSearchBounds
import org.compass.cng.domain.model.DestinationSuggestRequest
import org.compass.cng.domain.model.DestinationSuggestion
import org.compass.cng.domain.model.ResolvedDestination
import org.compass.cng.domain.model.PlaceSearchResult
import org.compass.cng.domain.model.PlaceSearchSource
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary
import org.compass.cng.domain.model.RouteWithIntermediateStop
import org.compass.cng.domain.model.RouteWithIntermediateStops
import org.compass.cng.domain.model.withNavigationDetailsFrom
import org.compass.cng.domain.server.InMemoryServerConnectionRepository
import org.compass.cng.domain.server.ServerConnection
import org.compass.cng.domain.server.ServerConnectionRepository
import org.compass.cng.navigation.NavigationSession
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.navigation.toNavigationRoute
import org.compass.cng.domain.vehicle.InMemoryVehicleProfileRepository
import org.compass.cng.domain.vehicle.VehicleProfile
import org.compass.cng.domain.vehicle.VehicleProfileRepository
import org.compass.cng.domain.vehicle.VehicleProfiles

enum class PlannerStage {
    FOLLOW,
    CONFIGURE_ROUTE,
    DESTINATION_SEARCH,
    MAP_POINT_PICKER,
    INTERMEDIATE_STOP_PREVIEW,
    INTERMEDIATE_STOPS,
    PREVIEW,
    CONFIGURE_CNG,
    CONFIGURE_PREDICTIVE,
    VEHICLE_PROFILES,
    SERVER_CONNECTION,
    CNG_CANDIDATES,
    PREDICTIVE_ITINERARY,
    PREDICTIVE_STATUS,
    NAVIGATION_PREVIEW,
}

enum class RouteEndpoint {
    ORIGIN,
    INTERMEDIATE_STOP,
    DESTINATION,
}

enum class RouteLocationMethod {
    CURRENT_LOCATION,
    FAVORITES,
    SEARCH,
    COORDINATES,
}

enum class CurrentLocationAcquisitionStatus {
    IDLE,
    ACQUIRING,
    SUCCESS,
    FAILURE,
}

enum class PlannerOperation {
    BASE_ROUTE,
    PLACE_SEARCH,
    PLACE_RESOLUTION,
    INTERMEDIATE_STOP_ROUTE,
    CNG_CANDIDATES,
    PREDICTIVE_CANDIDATES,
    SELECTED_ROUTE,
}

enum class CngWorkflowMode {
    MANUAL,
    PREDICTIVE,
}

data class PlannedIntermediateStop(
    val id: String,
    val location: Coordinate,
    val locationMethod: RouteLocationMethod,
    val privateDisplayName: String,
) {
    val mapLabel: String get() = "Tappa intermedia"
}

data class RoutePlannerUiState(
    val stage: PlannerStage = PlannerStage.FOLLOW,
    val operation: PlannerOperation? = null,
    val activeOrigin: Coordinate = DEFAULT_ORIGIN,
    val activeDestination: Coordinate = DEFAULT_DESTINATION,
    val originLatitudeInput: String = "",
    val originLongitudeInput: String = "",
    val destinationLatitudeInput: String = "",
    val destinationLongitudeInput: String = "",
    val intermediateStopEnabled: Boolean = false,
    val intermediateStopLatitudeInput: String = "",
    val intermediateStopLongitudeInput: String = "",
    val intermediateStopDisplayName: String = "Non selezionata",
    val intermediateStopAttributions: List<String> = emptyList(),
    val intermediateStopLocationMethod: RouteLocationMethod? = null,
    val intermediateStopCurrentLocationStatus: CurrentLocationAcquisitionStatus =
        CurrentLocationAcquisitionStatus.IDLE,
    val intermediateStopMaximumDeviationKmInput: String = "",
    val originDisplayName: String = "Non selezionata",
    val destinationDisplayName: String = "Non selezionata",
    val originAttributions: List<String> = emptyList(),
    val destinationAttributions: List<String> = emptyList(),
    val originLocationMethod: RouteLocationMethod? = null,
    val destinationLocationMethod: RouteLocationMethod? = null,
    val originCurrentLocationStatus: CurrentLocationAcquisitionStatus =
        CurrentLocationAcquisitionStatus.IDLE,
    val destinationCurrentLocationStatus: CurrentLocationAcquisitionStatus =
        CurrentLocationAcquisitionStatus.IDLE,
    val routeInputsDirty: Boolean = true,
    val placeSearchTarget: RouteEndpoint = RouteEndpoint.DESTINATION,
    val currentLocationTarget: RouteEndpoint? = null,
    val followLocation: NavigationLocation? = null,
    val placeSearchQuery: String = "",
    val placeSearchResults: List<PlaceSearchResult> = emptyList(),
    val destinationSuggestions: List<DestinationSuggestion> = emptyList(),
    val destinationSearchSessionId: String? = null,
    val destinationSearchRevision: Int = 0,
    val pendingResolvedDestination: ResolvedDestination? = null,
    val pendingDestinationSuggestion: DestinationSuggestion? = null,
    val pendingIntermediateStopRoute: RouteWithIntermediateStop? = null,
    val pendingIntermediateStopsRoute: RouteWithIntermediateStops? = null,
    val pendingIntermediateStopCoordinate: Coordinate? = null,
    val placeSearchSource: PlaceSearchSource = PlaceSearchSource.LIVE,
    val placeSearchCachedAtEpochMillis: Long? = null,
    val baseRoute: RoutePreview? = null,
    val intermediateStopRoute: RouteWithIntermediateStop? = null,
    val plannedIntermediateStops: List<PlannedIntermediateStop> = emptyList(),
    val intermediateStopsRoute: RouteWithIntermediateStops? = null,
    val editingIntermediateStopId: String? = null,
    val mapPickerTarget: RouteEndpoint? = null,
    val mapPickerCoordinate: Coordinate? = null,
    val summaryEditing: Boolean = false,
    val effectiveRangeKmInput: String = DEFAULT_EFFECTIVE_RANGE_KM,
    val estimatedRemainingRangeKmInput: String = "",
    val reserveRangeKmInput: String = DEFAULT_RESERVE_RANGE_KM,
    val estimatedRemainingGasolineRangeKmInput: String = "",
    val effectiveGasolineRangeKmInput: String = "",
    val gasolineReserveRangeKmInput: String = "",
    val maximumDetourMinutesInput: String = DEFAULT_MAXIMUM_DETOUR_MINUTES,
    val workflowMode: CngWorkflowMode? = null,
    val rankedStations: RankedCngStations? = null,
    val predictiveSuggestion: PredictiveCngSuggestion? = null,
    val pendingStation: RankedCngStation? = null,
    val selectedRoute: RouteWithCngStop? = null,
    val selectedItineraryRoute: RouteWithCngItinerary? = null,
    val message: String? = null,
    val vehicleProfiles: VehicleProfiles = VehicleProfiles(),
    val editingVehicleProfileId: String? = null,
    val vehicleProfileNameInput: String = "",
    val vehicleProfileCngRangeInput: String = "",
    val vehicleProfileCngReserveInput: String = "",
    val vehicleProfileGasolineRangeInput: String = "",
    val vehicleProfileGasolineReserveInput: String = "",
    val serverBaseUrlInput: String = "",
    val serverUsernameInput: String = "",
    val serverPasswordInput: String = "",
    val serverAllowInsecureHttp: Boolean = false,
) {
    val isBusy: Boolean get() = operation != null

    companion object {
        val DEFAULT_ORIGIN = Coordinate(latitude = 45.4642, longitude = 9.1900)
        // The former city-centre endpoint is inside a time-restricted driving area.
        // Keep the deterministic preview in Bologna, but terminate it at a
        // road-reachable destination so depart-now routing can use live traffic.
        val DEFAULT_DESTINATION = Coordinate(latitude = 44.5057, longitude = 11.3424)
        const val DEFAULT_ORIGIN_LATITUDE = "45.4642"
        const val DEFAULT_ORIGIN_LONGITUDE = "9.1900"
        const val DEFAULT_DESTINATION_LATITUDE = "44.5057"
        const val DEFAULT_DESTINATION_LONGITUDE = "11.3424"
        const val DEFAULT_EFFECTIVE_RANGE_KM = "300"
        const val DEFAULT_RESERVE_RANGE_KM = "30"
        const val DEFAULT_MAXIMUM_DETOUR_MINUTES = "10"
    }
}

class RoutePlannerViewModel(
    private val routingRepository: RoutingRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    initialOrigin: Coordinate = MILAN,
    initialDestination: Coordinate = BOLOGNA,
    private val navigationSession: NavigationSession = NavigationSession(),
    private val vehicleProfileRepository: VehicleProfileRepository =
        InMemoryVehicleProfileRepository(),
    private val serverConnectionRepository: ServerConnectionRepository =
        InMemoryServerConnectionRepository(),
    private val startInFollowMode: Boolean = false,
) : ViewModel() {
    val navigationState = navigationSession.state
    private val restoredNavigation = navigationSession.state.value.let { restored ->
        if (
            startInFollowMode &&
            restored.route != null &&
            restored.phase == org.compass.cng.navigation.NavigationPhase.ROUTE_PREVIEW &&
            !navigationSession.restoredNavigationWasActive
        ) {
            navigationSession.clear()
            navigationSession.state.value
        } else {
            restored
        }
    }
    val shouldResumeRestoredNavigation: Boolean =
        navigationSession.restoredNavigationWasActive &&
            restoredNavigation.route != null &&
            restoredNavigation.phase != org.compass.cng.navigation.NavigationPhase.ROUTE_PREVIEW
    private val initialVehicleProfiles = vehicleProfileRepository.load()
    private val initialServerConnection = serverConnectionRepository.load()
    private val mutableUiState = MutableStateFlow(
        restoredNavigation.route?.let { activeRoute ->
            RoutePlannerUiState(
                stage = PlannerStage.NAVIGATION_PREVIEW,
                operation = null,
                activeOrigin = activeRoute.origin,
                activeDestination = activeRoute.destination,
                originLatitudeInput = activeRoute.origin.latitude.toCoordinateInput(),
                originLongitudeInput = activeRoute.origin.longitude.toCoordinateInput(),
                destinationLatitudeInput = activeRoute.destination.latitude.toCoordinateInput(),
                destinationLongitudeInput = activeRoute.destination.longitude.toCoordinateInput(),
                baseRoute = activeRoute.asRoutePreview(),
                intermediateStopEnabled = activeRoute.intermediateStops.isNotEmpty(),
                plannedIntermediateStops = activeRoute.intermediateStops.map { stop ->
                    PlannedIntermediateStop(
                        id = "restored-${stop.sequence}",
                        location = stop.location,
                        locationMethod = RouteLocationMethod.COORDINATES,
                        privateDisplayName = "Tappa intermedia",
                    )
                },
            ).withVehicleProfiles(initialVehicleProfiles)
                .withServerConnection(initialServerConnection)
        } ?: if (startInFollowMode) {
            RoutePlannerUiState(
                activeOrigin = initialOrigin,
                activeDestination = initialDestination,
            )
        } else {
            RoutePlannerUiState(
                stage = PlannerStage.PREVIEW,
                operation = PlannerOperation.BASE_ROUTE,
                activeOrigin = initialOrigin,
                activeDestination = initialDestination,
                originLatitudeInput = initialOrigin.latitude.toCoordinateInput(),
                originLongitudeInput = initialOrigin.longitude.toCoordinateInput(),
                destinationLatitudeInput = initialDestination.latitude.toCoordinateInput(),
                destinationLongitudeInput = initialDestination.longitude.toCoordinateInput(),
            )
        }.withVehicleProfiles(initialVehicleProfiles)
            .withServerConnection(initialServerConnection)
            .let { state ->
                if (
                    startInFollowMode &&
                    restoredNavigation.route == null &&
                    !initialServerConnection.hasCredentials
                ) {
                    state.copy(
                        stage = PlannerStage.SERVER_CONNECTION,
                        message = "Configura il server Compass per continuare.",
                    )
                } else {
                    state
                }
            }
    )
    val uiState: StateFlow<RoutePlannerUiState> = mutableUiState.asStateFlow()

    private var requestJob: Job? = null
    private var destinationSearchJob: Job? = null
    private var pendingIntermediateResolution: ResolvedDestination? = null
    private var pendingIntermediateDraft: PlannedIntermediateStop? = null
    private var serverReturnStage: PlannerStage = PlannerStage.FOLLOW

    init {
        if (restoredNavigation.route == null && !startInFollowMode) loadBaseRoute()
    }

    fun retryBaseRoute() = loadBaseRoute(
        successStage = if (mutableUiState.value.stage == PlannerStage.CONFIGURE_ROUTE) {
            PlannerStage.CONFIGURE_ROUTE
        } else {
            PlannerStage.PREVIEW
        },
    )

    fun openRouteConfiguration() {
        if (!mutableUiState.value.isBusy) {
            mutableUiState.value = mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                message = null,
            )
        }
    }

    fun updateFollowLocation(location: NavigationLocation) {
        mutableUiState.value = mutableUiState.value.copy(followLocation = location)
    }

    fun selectCoordinateInput(endpoint: RouteEndpoint) {
        if (mutableUiState.value.isBusy) return
        mutableUiState.value = when (endpoint) {
            RouteEndpoint.ORIGIN -> mutableUiState.value.copy(
                originLocationMethod = RouteLocationMethod.COORDINATES,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                originAttributions = emptyList(),
                routeInputsDirty = true,
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> mutableUiState.value.copy(
                intermediateStopLocationMethod = RouteLocationMethod.COORDINATES,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                intermediateStopAttributions = emptyList(),
                routeInputsDirty = true,
                message = null,
            )
            RouteEndpoint.DESTINATION -> mutableUiState.value.copy(
                destinationLocationMethod = RouteLocationMethod.COORDINATES,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                destinationAttributions = emptyList(),
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun openMapPointPicker(endpoint: RouteEndpoint) {
        val state = mutableUiState.value
        if (state.isBusy) return
        val existing = when (endpoint) {
            RouteEndpoint.ORIGIN -> state.originCoordinateOrNull()
            RouteEndpoint.INTERMEDIATE_STOP -> state.editingIntermediateStopId?.let { id ->
                state.plannedIntermediateStops.firstOrNull { it.id == id }?.location
            }
            RouteEndpoint.DESTINATION -> state.destinationCoordinateOrNull()
        }
        mutableUiState.value = state.copy(
            stage = PlannerStage.MAP_POINT_PICKER,
            mapPickerTarget = endpoint,
            mapPickerCoordinate = existing ?: state.followLocation?.coordinate ?: state.activeOrigin,
            message = null,
        )
    }

    fun updateMapPickerCoordinate(coordinate: Coordinate) {
        if (mutableUiState.value.stage != PlannerStage.MAP_POINT_PICKER) return
        mutableUiState.value = mutableUiState.value.copy(
            mapPickerCoordinate = coordinate,
            message = null,
        )
    }

    fun confirmMapPointPicker() {
        val state = mutableUiState.value
        val target = state.mapPickerTarget ?: return
        val coordinate = state.mapPickerCoordinate ?: return
        when (target) {
            RouteEndpoint.ORIGIN -> mutableUiState.value = state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                originLatitudeInput = coordinate.latitude.toCoordinateInput(),
                originLongitudeInput = coordinate.longitude.toCoordinateInput(),
                originDisplayName = "Punto selezionato sulla mappa",
                originAttributions = emptyList(),
                originLocationMethod = RouteLocationMethod.COORDINATES,
                routeInputsDirty = true,
                mapPickerTarget = null,
                message = null,
            )
            RouteEndpoint.DESTINATION -> mutableUiState.value = state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                destinationLatitudeInput = coordinate.latitude.toCoordinateInput(),
                destinationLongitudeInput = coordinate.longitude.toCoordinateInput(),
                destinationDisplayName = "Punto selezionato sulla mappa",
                destinationAttributions = emptyList(),
                destinationLocationMethod = RouteLocationMethod.COORDINATES,
                routeInputsDirty = true,
                mapPickerTarget = null,
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> previewIntermediateCoordinate(
                coordinate = coordinate,
                method = RouteLocationMethod.COORDINATES,
                privateDisplayName = "Punto selezionato sulla mappa",
            )
        }
    }

    fun openPlaceSearch(endpoint: RouteEndpoint) {
        if (!mutableUiState.value.isBusy) {
            val state = mutableUiState.value
            pendingIntermediateResolution = null
            mutableUiState.value = state.copy(
                stage = PlannerStage.DESTINATION_SEARCH,
                placeSearchTarget = endpoint,
                originCurrentLocationStatus = if (endpoint == RouteEndpoint.ORIGIN) {
                    CurrentLocationAcquisitionStatus.IDLE
                } else {
                    state.originCurrentLocationStatus
                },
                destinationCurrentLocationStatus = if (endpoint == RouteEndpoint.DESTINATION) {
                    CurrentLocationAcquisitionStatus.IDLE
                } else {
                    state.destinationCurrentLocationStatus
                },
                intermediateStopCurrentLocationStatus =
                    if (endpoint == RouteEndpoint.INTERMEDIATE_STOP) {
                        CurrentLocationAcquisitionStatus.IDLE
                    } else {
                        state.intermediateStopCurrentLocationStatus
                    },
                placeSearchQuery = "",
                placeSearchResults = emptyList(),
                destinationSuggestions = emptyList(),
                destinationSearchSessionId = UUID.randomUUID().toString(),
                destinationSearchRevision = 0,
                pendingResolvedDestination = null,
                pendingDestinationSuggestion = null,
                pendingIntermediateStopRoute = null,
                placeSearchSource = PlaceSearchSource.LIVE,
                placeSearchCachedAtEpochMillis = null,
                message = null,
            )
        }
    }

    fun openDestinationSearch() = openPlaceSearch(RouteEndpoint.DESTINATION)

    fun updatePlaceSearchQuery(value: String) {
        if (value.length <= 200 && value != mutableUiState.value.placeSearchQuery) {
            val nextRevision = mutableUiState.value.destinationSearchRevision + 1
            mutableUiState.value = mutableUiState.value.copy(
                placeSearchQuery = value,
                destinationSearchRevision = nextRevision,
                destinationSuggestions = emptyList(),
                pendingResolvedDestination = null,
                pendingDestinationSuggestion = null,
                pendingIntermediateStopRoute = null,
                message = null,
            )
            scheduleDestinationSuggestions(immediate = false)
        }
    }

    fun searchDestinations() {
        scheduleDestinationSuggestions(immediate = true)
    }

    private fun scheduleDestinationSuggestions(immediate: Boolean) {
        destinationSearchJob?.cancel()
        val snapshot = mutableUiState.value
        val query = snapshot.placeSearchQuery.trim()
        if (query.count { !it.isWhitespace() } < BuildConfig.DESTINATION_SEARCH_MIN_CHARS) {
            mutableUiState.value = snapshot.copy(
                operation = null,
                destinationSuggestions = emptyList(),
                message = if (query.isEmpty()) {
                    null
                } else {
                    "Digita almeno ${BuildConfig.DESTINATION_SEARCH_MIN_CHARS} caratteri."
                },
            )
            return
        }
        val sessionId = snapshot.destinationSearchSessionId ?: return
        val revision = snapshot.destinationSearchRevision
        destinationSearchJob = viewModelScope.launch {
            if (!immediate) delay(BuildConfig.DESTINATION_SEARCH_DEBOUNCE_MS)
            val current = mutableUiState.value
            if (
                current.destinationSearchSessionId != sessionId ||
                current.destinationSearchRevision != revision ||
                current.placeSearchQuery.trim() != query
            ) return@launch
            mutableUiState.value = current.copy(
                operation = PlannerOperation.PLACE_SEARCH,
                destinationSuggestions = emptyList(),
                message = null,
            )
            try {
                val contextLocation = current.followLocation?.coordinate
                    ?: selectedTerritorialContext(current)
                val results = routingRepository.suggestDestinations(
                    DestinationSuggestRequest(
                        query = query,
                        sessionId = sessionId,
                        revision = revision,
                        context = DestinationSearchContext(
                            location = contextLocation,
                            biasRadiusMeters = contextLocation?.let { location ->
                                if (current.placeSearchTarget == RouteEndpoint.INTERMEDIATE_STOP) {
                                    null
                                } else {
                                    25_000.0
                                }
                            },
                            routeBounds = if (
                                current.placeSearchTarget == RouteEndpoint.INTERMEDIATE_STOP
                            ) {
                                current.intermediateStopSearchBounds()
                            } else {
                                null
                            },
                        ),
                    ),
                )
                val latest = mutableUiState.value
                if (
                    latest.destinationSearchSessionId != results.sessionId ||
                    latest.destinationSearchRevision != results.revision
                ) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    destinationSuggestions = results.results,
                    message = if (results.results.isEmpty()) "Nessun luogo trovato." else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                if (error.failure.requiresServerConfiguration()) {
                    openServerConnectionForFailure(
                        message = error.failure.placeSearchMessage(),
                        returnStage = PlannerStage.CONFIGURE_ROUTE,
                    )
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = error.failure.placeSearchMessage(),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = "Ricerca destinazione non disponibile.",
                )
            }
        }
    }

    fun selectDestinationSuggestion(suggestion: DestinationSuggestion) {
        val snapshot = mutableUiState.value
        if (snapshot.operation != null || suggestion !in snapshot.destinationSuggestions) return
        val sessionId = snapshot.destinationSearchSessionId ?: return
        val revision = snapshot.destinationSearchRevision
        destinationSearchJob?.cancel()
        destinationSearchJob = viewModelScope.launch {
            mutableUiState.value = snapshot.copy(
                operation = PlannerOperation.PLACE_RESOLUTION,
                pendingDestinationSuggestion = suggestion,
                message = null,
            )
            try {
                val resolved = routingRepository.resolveDestination(
                    sessionId,
                    revision,
                    suggestion,
                )
                val latest = mutableUiState.value
                if (
                    latest.destinationSearchSessionId != resolved.sessionId ||
                    latest.destinationSearchRevision != resolved.revision ||
                    latest.pendingDestinationSuggestion?.id != suggestion.id
                ) return@launch
                if (resolved.selection.formattedAddress == null) {
                    mutableUiState.value = latest.copy(
                        operation = null,
                        pendingResolvedDestination = resolved,
                        message = "Indirizzo completo non disponibile. Conferma l'uso delle sole coordinate.",
                    )
                } else {
                    acceptResolvedDestination(resolved)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.placeSearchMessage(),
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = "Impossibile risolvere la destinazione selezionata.",
                )
            }
        }
    }

    fun confirmCoordinateOnlyDestination() {
        mutableUiState.value.pendingResolvedDestination?.let(::acceptResolvedDestination)
    }

    private fun acceptResolvedDestination(resolved: ResolvedDestination) {
        if (mutableUiState.value.placeSearchTarget == RouteEndpoint.INTERMEDIATE_STOP) {
            previewIntermediateStop(resolved)
        } else {
            applyResolvedDestination(resolved)
        }
    }

    private fun previewIntermediateStop(resolved: ResolvedDestination) {
        val coordinate = resolved.navigationTarget.location
        previewIntermediateCoordinate(
            coordinate = coordinate,
            method = RouteLocationMethod.SEARCH,
            privateDisplayName = resolved.selection.formattedAddress
                ?: "Tappa selezionata",
            resolved = resolved,
        )
    }

    private fun previewIntermediateCoordinate(
        coordinate: Coordinate,
        method: RouteLocationMethod,
        privateDisplayName: String,
        resolved: ResolvedDestination? = null,
    ) {
        val state = mutableUiState.value
        val directRoute = state.baseRoute ?: return
        val maximumDeviationKm = state.intermediateStopMaximumDeviationKmInput.parseDecimal()
        if (maximumDeviationKm == null || maximumDeviationKm < 0.0) {
            mutableUiState.value = state.copy(
                operation = null,
                message = "Inserisci una deviazione massima valida per la tappa.",
            )
            return
        }
        val draft = PlannedIntermediateStop(
            id = state.editingIntermediateStopId ?: UUID.randomUUID().toString(),
            location = coordinate,
            locationMethod = method,
            privateDisplayName = privateDisplayName,
        )
        val proposedStops = state.plannedIntermediateStops.toMutableList().also { stops ->
            val editingIndex = stops.indexOfFirst { it.id == state.editingIntermediateStopId }
            if (editingIndex >= 0) stops[editingIndex] = draft else stops += draft
        }
        destinationSearchJob = viewModelScope.launch {
            mutableUiState.value = state.copy(
                operation = PlannerOperation.INTERMEDIATE_STOP_ROUTE,
                pendingResolvedDestination = null,
                pendingDestinationSuggestion = null,
                pendingIntermediateStopCoordinate = coordinate,
                message = null,
            )
            try {
                val candidateRoute = routingRepository.routeWithIntermediateStops(
                    origin = directRoute.origin,
                    intermediateStops = proposedStops.map(PlannedIntermediateStop::location),
                    destination = directRoute.destination,
                )
                val extraDistanceMeters =
                    (candidateRoute.distanceMeters - directRoute.distanceMeters).coerceAtLeast(0.0)
                if (extraDistanceMeters > maximumDeviationKm * 1_000.0) {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = intermediateStopDeviationMessage(
                            extraDistanceMeters = extraDistanceMeters,
                            maximumDeviationKm = maximumDeviationKm,
                        ),
                    )
                    return@launch
                }
                pendingIntermediateResolution = resolved
                pendingIntermediateDraft = draft
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.INTERMEDIATE_STOP_PREVIEW,
                    operation = null,
                pendingIntermediateStopsRoute = candidateRoute,
                pendingIntermediateStopCoordinate = coordinate,
                    destinationSuggestions = emptyList(),
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.baseRouteMessage(),
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.baseRouteMessage(),
                )
            }
        }
    }

    fun chooseIntermediateStop() {
        val state = mutableUiState.value
        val draft = pendingIntermediateDraft ?: return
        val route = state.pendingIntermediateStopsRoute ?: return
        if (state.stage != PlannerStage.INTERMEDIATE_STOP_PREVIEW || state.isBusy) return
        val stops = state.plannedIntermediateStops.toMutableList().also { current ->
            val editingIndex = current.indexOfFirst { it.id == state.editingIntermediateStopId }
            if (editingIndex >= 0) current[editingIndex] = draft else current += draft
        }
        pendingIntermediateResolution = null
        pendingIntermediateDraft = null
        mutableUiState.value = state.copy(
            stage = PlannerStage.INTERMEDIATE_STOPS,
            intermediateStopEnabled = true,
            plannedIntermediateStops = stops,
            intermediateStopsRoute = route,
            editingIntermediateStopId = null,
            pendingIntermediateStopsRoute = null,
            pendingIntermediateStopCoordinate = null,
            mapPickerTarget = null,
            routeInputsDirty = false,
            destinationSearchSessionId = null,
            destinationSuggestions = emptyList(),
            message = null,
        ).withMapSafeSearchLabels()
    }

    private fun applyResolvedDestination(resolved: ResolvedDestination) {
        val state = mutableUiState.value
        val coordinate = resolved.navigationTarget.location
        val selectionLabel = resolved.selection.formattedAddress
            ?: "${coordinate.latitude.toCoordinateInput()}, ${coordinate.longitude.toCoordinateInput()}"
        mutableUiState.value = when (state.placeSearchTarget) {
            RouteEndpoint.ORIGIN -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                operation = null,
                originLatitudeInput = coordinate.latitude.toCoordinateInput(),
                originLongitudeInput = coordinate.longitude.toCoordinateInput(),
                originDisplayName = selectionLabel,
                originAttributions = resolved.selection.attribution,
                originLocationMethod = RouteLocationMethod.SEARCH,
                routeInputsDirty = true,
                destinationSuggestions = emptyList(),
                pendingResolvedDestination = null,
                pendingDestinationSuggestion = null,
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                operation = null,
                intermediateStopLatitudeInput = coordinate.latitude.toCoordinateInput(),
                intermediateStopLongitudeInput = coordinate.longitude.toCoordinateInput(),
                intermediateStopDisplayName = selectionLabel,
                intermediateStopAttributions = resolved.selection.attribution,
                intermediateStopLocationMethod = RouteLocationMethod.SEARCH,
                routeInputsDirty = true,
                destinationSuggestions = emptyList(),
                pendingResolvedDestination = null,
                pendingDestinationSuggestion = null,
                message = null,
            )
            RouteEndpoint.DESTINATION -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                operation = null,
                destinationLatitudeInput = coordinate.latitude.toCoordinateInput(),
                destinationLongitudeInput = coordinate.longitude.toCoordinateInput(),
                destinationDisplayName = selectionLabel,
                destinationAttributions = resolved.selection.attribution,
                destinationLocationMethod = RouteLocationMethod.SEARCH,
                routeInputsDirty = true,
                destinationSuggestions = emptyList(),
                pendingResolvedDestination = null,
                pendingDestinationSuggestion = null,
                message = null,
            )
        }
    }

    private fun selectedTerritorialContext(state: RoutePlannerUiState): Coordinate? =
        when (state.placeSearchTarget) {
            RouteEndpoint.ORIGIN -> state.activeDestination.takeIf {
                state.destinationLocationMethod != null
            }
            RouteEndpoint.INTERMEDIATE_STOP -> state.baseRoute?.geometry
                ?.getOrNull(state.baseRoute.geometry.size / 2)
                ?: state.activeOrigin.takeIf { state.originLocationMethod != null }
            RouteEndpoint.DESTINATION -> state.activeOrigin.takeIf {
                state.originLocationMethod != null
            }
        }

    fun selectDestination(result: PlaceSearchResult) {
        val state = mutableUiState.value
        val draftOrigin = parseCoordinate(
            latitudeInput = state.originLatitudeInput,
            longitudeInput = state.originLongitudeInput,
            label = "partenza",
        ).coordinate ?: state.activeOrigin
        loadBaseRoute(
            origin = draftOrigin,
            destination = result.location,
            originDisplayName = state.originDisplayName,
            destinationDisplayName = result.displayName,
        )
    }

    fun selectPlace(result: PlaceSearchResult) {
        val state = mutableUiState.value
        mutableUiState.value = when (state.placeSearchTarget) {
            RouteEndpoint.ORIGIN -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                originLatitudeInput = result.location.latitude.toCoordinateInput(),
                originLongitudeInput = result.location.longitude.toCoordinateInput(),
                originDisplayName = result.displayName,
                originAttributions = emptyList(),
                originLocationMethod = RouteLocationMethod.SEARCH,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                placeSearchResults = emptyList(),
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                intermediateStopLatitudeInput = result.location.latitude.toCoordinateInput(),
                intermediateStopLongitudeInput = result.location.longitude.toCoordinateInput(),
                intermediateStopDisplayName = result.displayName,
                intermediateStopAttributions = emptyList(),
                intermediateStopLocationMethod = RouteLocationMethod.SEARCH,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                placeSearchResults = emptyList(),
                message = null,
            )
            RouteEndpoint.DESTINATION -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                destinationLatitudeInput = result.location.latitude.toCoordinateInput(),
                destinationLongitudeInput = result.location.longitude.toCoordinateInput(),
                destinationDisplayName = result.displayName,
                destinationAttributions = emptyList(),
                destinationLocationMethod = RouteLocationMethod.SEARCH,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                placeSearchResults = emptyList(),
                message = null,
            )
        }
    }

    fun currentLocationRequested(endpoint: RouteEndpoint = RouteEndpoint.ORIGIN) {
        val state = mutableUiState.value
        mutableUiState.value = when (endpoint) {
            RouteEndpoint.ORIGIN -> state.copy(
                currentLocationTarget = endpoint,
                originLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.ACQUIRING,
                routeInputsDirty = true,
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> state.copy(
                currentLocationTarget = endpoint,
                intermediateStopLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.ACQUIRING,
                routeInputsDirty = true,
                message = null,
            )
            RouteEndpoint.DESTINATION -> state.copy(
                currentLocationTarget = endpoint,
                destinationLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.ACQUIRING,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun useCurrentLocation(coordinate: Coordinate) {
        requestJob?.cancel()
        val state = mutableUiState.value
        if (state.currentLocationTarget == RouteEndpoint.INTERMEDIATE_STOP) {
            mutableUiState.value = state.copy(
                currentLocationTarget = null,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.SUCCESS,
                operation = null,
            )
            previewIntermediateCoordinate(
                coordinate = coordinate,
                method = RouteLocationMethod.CURRENT_LOCATION,
                privateDisplayName = "Posizione attuale",
            )
            return
        }
        mutableUiState.value = when (state.currentLocationTarget ?: RouteEndpoint.ORIGIN) {
            RouteEndpoint.ORIGIN -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                operation = null,
                originLatitudeInput = coordinate.latitude.toCoordinateInput(),
                originLongitudeInput = coordinate.longitude.toCoordinateInput(),
                originDisplayName = "Posizione attuale",
                originAttributions = emptyList(),
                originLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.SUCCESS,
                routeInputsDirty = true,
                currentLocationTarget = null,
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> error("handled before endpoint mapping")
            RouteEndpoint.DESTINATION -> state.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                operation = null,
                destinationLatitudeInput = coordinate.latitude.toCoordinateInput(),
                destinationLongitudeInput = coordinate.longitude.toCoordinateInput(),
                destinationDisplayName = "Posizione attuale",
                destinationAttributions = emptyList(),
                destinationLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.SUCCESS,
                routeInputsDirty = true,
                currentLocationTarget = null,
                message = null,
            )
        }
    }

    fun useCurrentLocationAsOrigin(coordinate: Coordinate) {
        currentLocationRequested(RouteEndpoint.ORIGIN)
        useCurrentLocation(coordinate)
    }

    fun currentLocationUnavailable() {
        val state = mutableUiState.value
        mutableUiState.value = when (state.currentLocationTarget) {
            RouteEndpoint.ORIGIN -> state.copy(
                originLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.FAILURE,
                currentLocationTarget = null,
                message = null,
            )
            RouteEndpoint.INTERMEDIATE_STOP -> state.copy(
                intermediateStopLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.FAILURE,
                currentLocationTarget = null,
                message = null,
            )
            RouteEndpoint.DESTINATION -> state.copy(
                destinationLocationMethod = RouteLocationMethod.CURRENT_LOCATION,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.FAILURE,
                currentLocationTarget = null,
                message = null,
            )
            null -> state.copy(
                message = "Impossibile ottenere la posizione attuale. Verifica GPS e permessi.",
            )
        }
    }

    fun updateOriginLatitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                originLatitudeInput = value,
                originDisplayName = "Coordinate personalizzate",
                originLocationMethod = RouteLocationMethod.COORDINATES,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun updateOriginLongitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                originLongitudeInput = value,
                originDisplayName = "Coordinate personalizzate",
                originLocationMethod = RouteLocationMethod.COORDINATES,
                originCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun updateDestinationLatitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                destinationLatitudeInput = value,
                destinationDisplayName = "Coordinate personalizzate",
                destinationLocationMethod = RouteLocationMethod.COORDINATES,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun updateDestinationLongitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                destinationLongitudeInput = value,
                destinationDisplayName = "Coordinate personalizzate",
                destinationLocationMethod = RouteLocationMethod.COORDINATES,
                destinationCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun updateIntermediateStopLatitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                intermediateStopLatitudeInput = value,
                intermediateStopDisplayName = "Coordinate personalizzate",
                intermediateStopLocationMethod = RouteLocationMethod.COORDINATES,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun updateIntermediateStopLongitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                intermediateStopLongitudeInput = value,
                intermediateStopDisplayName = "Coordinate personalizzate",
                intermediateStopLocationMethod = RouteLocationMethod.COORDINATES,
                intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun updateIntermediateStopMaximumDeviationKm(value: String) {
        if (value.length <= 8 && value.all { it.isDigit() || it == ',' || it == '.' }) {
            mutableUiState.value = mutableUiState.value.copy(
                intermediateStopMaximumDeviationKmInput = value,
                routeInputsDirty = true,
                message = null,
            )
        }
    }

    fun addIntermediateStop() {
        val state = mutableUiState.value
        val route = state.baseRoute ?: return
        if (state.isBusy) return
        mutableUiState.value = state.copy(
            stage = PlannerStage.INTERMEDIATE_STOPS,
            intermediateStopEnabled = true,
            intermediateStopMaximumDeviationKmInput = state
                .intermediateStopMaximumDeviationKmInput.ifBlank {
                    (route.distanceMeters * DEFAULT_INTERMEDIATE_STOP_DEVIATION_FRACTION / 1_000.0)
                        .toOneDecimalInput()
                },
            editingIntermediateStopId = null,
            message = null,
        )
    }

    fun editIntermediateStop(id: String) {
        val state = mutableUiState.value
        if (state.isBusy || state.plannedIntermediateStops.none { it.id == id }) return
        mutableUiState.value = state.copy(
            stage = PlannerStage.INTERMEDIATE_STOPS,
            editingIntermediateStopId = id,
            message = null,
        )
    }

    fun moveIntermediateStop(fromIndex: Int, toIndex: Int) {
        val state = mutableUiState.value
        if (
            state.isBusy || fromIndex !in state.plannedIntermediateStops.indices ||
            toIndex !in state.plannedIntermediateStops.indices || fromIndex == toIndex
        ) return
        val reordered = state.plannedIntermediateStops.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        mutableUiState.value = state.copy(
            plannedIntermediateStops = reordered,
            intermediateStopsRoute = null,
            message = null,
        )
    }

    fun deleteIntermediateStop(id: String) {
        val state = mutableUiState.value
        if (state.isBusy) return
        val remaining = state.plannedIntermediateStops.filterNot { it.id == id }
        if (state.stage == PlannerStage.NAVIGATION_PREVIEW) {
            recalculateIntermediateStopsAfterEdit(remaining)
            return
        }
        mutableUiState.value = state.copy(
            plannedIntermediateStops = remaining,
            intermediateStopsRoute = null,
            editingIntermediateStopId = null,
            intermediateStopEnabled = remaining.isNotEmpty(),
            message = null,
        )
    }

    private fun recalculateIntermediateStopsAfterEdit(
        remaining: List<PlannedIntermediateStop>,
    ) {
        val state = mutableUiState.value
        val directRoute = state.baseRoute ?: return
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            mutableUiState.value = state.copy(
                operation = PlannerOperation.INTERMEDIATE_STOP_ROUTE,
                message = null,
            )
            try {
                if (remaining.isEmpty()) {
                    val refreshedDirectRoute = routingRepository.previewRoute(
                        directRoute.origin,
                        directRoute.destination,
                    )
                    navigationSession.preview(refreshedDirectRoute.toNavigationRoute())
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = PlannerStage.NAVIGATION_PREVIEW,
                        operation = null,
                        plannedIntermediateStops = emptyList(),
                        baseRoute = refreshedDirectRoute,
                        intermediateStopsRoute = null,
                        intermediateStopEnabled = false,
                        message = null,
                    )
                } else {
                    val route = routingRepository.routeWithIntermediateStops(
                        origin = directRoute.origin,
                        intermediateStops = remaining.map(PlannedIntermediateStop::location),
                        destination = directRoute.destination,
                    )
                    navigationSession.preview(route.toNavigationRoute())
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = PlannerStage.NAVIGATION_PREVIEW,
                        operation = null,
                        plannedIntermediateStops = remaining,
                        intermediateStopsRoute = route,
                        intermediateStopEnabled = true,
                        message = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.baseRouteMessage(),
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.baseRouteMessage(),
                )
            }
        }
    }

    fun calculateIntermediateStopsRoute() {
        val state = mutableUiState.value
        val directRoute = state.baseRoute ?: return
        if (state.isBusy) return
        if (state.plannedIntermediateStops.isEmpty()) {
            navigationSession.preview(directRoute.toNavigationRoute())
            mutableUiState.value = state.withMapSafeSearchLabels().copy(
                stage = PlannerStage.NAVIGATION_PREVIEW,
                intermediateStopsRoute = null,
                intermediateStopEnabled = false,
                message = null,
            )
            return
        }
        val maximumDeviationKm = state.intermediateStopMaximumDeviationKmInput.parseDecimal()
        if (maximumDeviationKm == null || maximumDeviationKm < 0.0) {
            mutableUiState.value = state.copy(
                message = "Inserisci una deviazione massima valida per le tappe.",
            )
            return
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            mutableUiState.value = state.copy(
                operation = PlannerOperation.INTERMEDIATE_STOP_ROUTE,
                message = null,
            )
            try {
                val route = routingRepository.routeWithIntermediateStops(
                    origin = directRoute.origin,
                    intermediateStops = state.plannedIntermediateStops.map {
                        it.location
                    },
                    destination = directRoute.destination,
                )
                val extraDistanceMeters =
                    (route.distanceMeters - directRoute.distanceMeters).coerceAtLeast(0.0)
                if (extraDistanceMeters > maximumDeviationKm * 1_000.0) {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = intermediateStopDeviationMessage(
                            extraDistanceMeters,
                            maximumDeviationKm,
                        ),
                    )
                    return@launch
                }
                navigationSession.preview(route.toNavigationRoute())
                mutableUiState.value = mutableUiState.value.withMapSafeSearchLabels().copy(
                    stage = PlannerStage.NAVIGATION_PREVIEW,
                    operation = null,
                    intermediateStopsRoute = route,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.baseRouteMessage(),
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.baseRouteMessage(),
                )
            }
        }
    }

    fun removeIntermediateStop() {
        val state = mutableUiState.value
        if (state.isBusy) return
        pendingIntermediateResolution = null
        mutableUiState.value = state.copy(
            intermediateStopEnabled = false,
            intermediateStopLatitudeInput = "",
            intermediateStopLongitudeInput = "",
            intermediateStopDisplayName = "Non selezionata",
            intermediateStopAttributions = emptyList(),
            intermediateStopLocationMethod = null,
            intermediateStopCurrentLocationStatus = CurrentLocationAcquisitionStatus.IDLE,
            intermediateStopMaximumDeviationKmInput = "",
            intermediateStopRoute = null,
            pendingIntermediateStopRoute = null,
            plannedIntermediateStops = emptyList(),
            intermediateStopsRoute = null,
            editingIntermediateStopId = null,
            routeInputsDirty = true,
            message = null,
        )
    }

    fun applyRouteInputs() {
        val state = mutableUiState.value
        if (
            state.originLatitudeInput.isBlank() ||
            state.originLongitudeInput.isBlank() ||
            state.destinationLatitudeInput.isBlank() ||
            state.destinationLongitudeInput.isBlank()
        ) {
            mutableUiState.value = state.copy(message = null)
            return
        }
        val parsedOrigin = parseCoordinate(
            latitudeInput = state.originLatitudeInput,
            longitudeInput = state.originLongitudeInput,
            label = "partenza",
        )
        val parsedDestination = parseCoordinate(
            latitudeInput = state.destinationLatitudeInput,
            longitudeInput = state.destinationLongitudeInput,
            label = "destinazione",
        )
        val validationMessage = parsedOrigin.message ?: parsedDestination.message
        if (validationMessage != null) {
            mutableUiState.value = state.copy(message = validationMessage)
            return
        }
        val origin = requireNotNull(parsedOrigin.coordinate)
        val destination = requireNotNull(parsedDestination.coordinate)
        if (origin == destination) {
            mutableUiState.value = state.copy(
                message = "Partenza e destinazione devono essere coordinate diverse.",
            )
            return
        }

        loadBaseRoute(
            origin = origin,
            destination = destination,
            originDisplayName = state.originDisplayName,
            destinationDisplayName = state.destinationDisplayName,
            intermediateStop = null,
            successStage = PlannerStage.PREVIEW,
        )
    }

    fun openAddStop() {
        if (
            mutableUiState.value.baseRoute != null &&
            mutableUiState.value.plannedIntermediateStops.isEmpty() &&
            !mutableUiState.value.routeInputsDirty &&
            !mutableUiState.value.isBusy
        ) {
            mutableUiState.value = mutableUiState.value.withMapSafeSearchLabels().copy(
                stage = PlannerStage.CONFIGURE_CNG,
                workflowMode = CngWorkflowMode.MANUAL,
                message = null,
            )
        }
    }

    fun openTripOptions() {
        val state = mutableUiState.value
        if (state.baseRoute == null || state.routeInputsDirty || state.isBusy) return
        mutableUiState.value = state.withMapSafeSearchLabels().copy(
            stage = PlannerStage.PREVIEW,
            message = null,
        )
    }

    fun openPredictiveRange() {
        if (
            mutableUiState.value.baseRoute != null &&
            mutableUiState.value.plannedIntermediateStops.isEmpty() &&
            !mutableUiState.value.routeInputsDirty &&
            !mutableUiState.value.isBusy
        ) {
            mutableUiState.value = mutableUiState.value.withMapSafeSearchLabels().copy(
                stage = PlannerStage.CONFIGURE_PREDICTIVE,
                workflowMode = CngWorkflowMode.PREDICTIVE,
                message = null,
            )
        }
    }

    fun openVehicleProfiles() {
        if (!mutableUiState.value.isBusy) {
            mutableUiState.value = mutableUiState.value.copy(
                stage = PlannerStage.VEHICLE_PROFILES,
                editingVehicleProfileId = null,
                vehicleProfileNameInput = "",
                vehicleProfileCngRangeInput = "",
                vehicleProfileCngReserveInput = "",
                vehicleProfileGasolineRangeInput = "",
                vehicleProfileGasolineReserveInput = "",
                message = null,
            )
        }
    }

    fun openServerConnection() {
        requestJob?.cancel()
        if (mutableUiState.value.stage != PlannerStage.SERVER_CONNECTION) {
            serverReturnStage = mutableUiState.value.stage
        }
        val connection = serverConnectionRepository.load()
        mutableUiState.value = mutableUiState.value
            .withServerConnection(connection)
            .copy(
                stage = PlannerStage.SERVER_CONNECTION,
                operation = null,
                message = null,
            )
    }

    fun updateServerBaseUrl(value: String) {
        if (value.length <= 500) {
            mutableUiState.value = mutableUiState.value.copy(
                serverBaseUrlInput = value,
                serverAllowInsecureHttp = if (value == mutableUiState.value.serverBaseUrlInput) {
                    mutableUiState.value.serverAllowInsecureHttp
                } else {
                    false
                },
                message = null,
            )
        }
    }

    fun updateServerUsername(value: String) {
        if (value.length <= 200 && '\n' !in value && '\r' !in value) {
            mutableUiState.value = mutableUiState.value.copy(
                serverUsernameInput = value,
                message = null,
            )
        }
    }

    fun updateServerPassword(value: String) {
        if (value.length <= 500 && '\n' !in value && '\r' !in value) {
            mutableUiState.value = mutableUiState.value.copy(
                serverPasswordInput = value,
                message = null,
            )
        }
    }

    fun updateServerAllowInsecureHttp(value: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(
            serverAllowInsecureHttp = value,
            message = null,
        )
    }

    fun saveServerConnection() {
        val state = mutableUiState.value
        try {
            val connection = ServerConnection.create(
                baseUrl = state.serverBaseUrlInput,
                username = state.serverUsernameInput,
                password = state.serverPasswordInput,
                allowInsecureHttp = state.serverAllowInsecureHttp,
                requireCredentials = true,
            )
            serverConnectionRepository.save(connection)
            mutableUiState.value = state.withServerConnection(connection).copy(
                stage = serverReturnStage,
                message = null,
            )
        } catch (error: IllegalArgumentException) {
            mutableUiState.value = state.copy(
                message = error.message ?: "Configurazione server non valida.",
            )
        } catch (_: Exception) {
            mutableUiState.value = state.copy(
                message = "Impossibile salvare la configurazione server.",
            )
        }
    }

    fun editVehicleProfile(profile: VehicleProfile?) {
        mutableUiState.value = mutableUiState.value.copy(
            editingVehicleProfileId = profile?.id,
            vehicleProfileNameInput = profile?.name.orEmpty(),
            vehicleProfileCngRangeInput = profile?.effectiveCngRangeKm?.toInput().orEmpty(),
            vehicleProfileCngReserveInput = profile?.cngReserveKm?.toInput().orEmpty(),
            vehicleProfileGasolineRangeInput = (
                profile?.effectiveGasolineRangeKm?.toInput().orEmpty()
            ),
            vehicleProfileGasolineReserveInput = profile?.gasolineReserveKm?.toInput().orEmpty(),
            message = null,
        )
    }

    fun updateVehicleProfileName(value: String) {
        if (value.length <= 60) {
            mutableUiState.value = mutableUiState.value.copy(
                vehicleProfileNameInput = value,
                message = null,
            )
        }
    }

    fun updateVehicleProfileCngRange(value: String) = updateProfileDecimal(value) {
        copy(vehicleProfileCngRangeInput = value, message = null)
    }

    fun updateVehicleProfileCngReserve(value: String) = updateProfileDecimal(value) {
        copy(vehicleProfileCngReserveInput = value, message = null)
    }

    fun updateVehicleProfileGasolineRange(value: String) = updateProfileDecimal(value) {
        copy(vehicleProfileGasolineRangeInput = value, message = null)
    }

    fun updateVehicleProfileGasolineReserve(value: String) = updateProfileDecimal(value) {
        copy(vehicleProfileGasolineReserveInput = value, message = null)
    }

    fun saveVehicleProfile() {
        val state = mutableUiState.value
        val name = state.vehicleProfileNameInput.trim()
        val cngRange = state.vehicleProfileCngRangeInput.parseDecimal()
        val cngReserve = state.vehicleProfileCngReserveInput.parseDecimal()
        val gasolineRange = state.vehicleProfileGasolineRangeInput.parseDecimal()
        val gasolineReserve = state.vehicleProfileGasolineReserveInput.parseDecimal()
        val error = when {
            name.isEmpty() -> "Inserisci un nome per il mezzo."
            cngRange == null || cngRange <= 0 || cngRange > 2_000 ->
                "Inserisci un'autonomia CNG piena tra 0 e 2.000 km."
            cngReserve == null || cngReserve < 0 || cngReserve >= cngRange ->
                "La riserva CNG deve essere inferiore all'autonomia piena."
            gasolineRange == null || gasolineRange <= 0 || gasolineRange > 2_000 ->
                "Inserisci un'autonomia benzina piena tra 0 e 2.000 km."
            gasolineReserve == null || gasolineReserve < 0 || gasolineReserve >= gasolineRange ->
                "La riserva benzina deve essere inferiore all'autonomia piena."
            else -> null
        }
        if (error != null) {
            mutableUiState.value = state.copy(message = error)
            return
        }
        try {
            val profile = VehicleProfile(
                id = state.editingVehicleProfileId ?: UUID.randomUUID().toString(),
                name = name,
                effectiveCngRangeKm = requireNotNull(cngRange),
                cngReserveKm = requireNotNull(cngReserve),
                effectiveGasolineRangeKm = requireNotNull(gasolineRange),
                gasolineReserveKm = requireNotNull(gasolineReserve),
            )
            vehicleProfileRepository.save(profile)
            val profiles = vehicleProfileRepository.select(profile.id)
            mutableUiState.value = state.withVehicleProfiles(profiles).copy(
                editingVehicleProfileId = null,
                vehicleProfileNameInput = "",
                vehicleProfileCngRangeInput = "",
                vehicleProfileCngReserveInput = "",
                vehicleProfileGasolineRangeInput = "",
                vehicleProfileGasolineReserveInput = "",
                message = "Profilo ${profile.name} salvato e selezionato.",
            )
        } catch (_: Exception) {
            mutableUiState.value = state.copy(message = "Impossibile salvare il profilo del mezzo.")
        }
    }

    fun selectVehicleProfile(profileId: String) {
        try {
            mutableUiState.value = mutableUiState.value
                .withVehicleProfiles(vehicleProfileRepository.select(profileId))
                .copy(message = null)
        } catch (_: Exception) {
            mutableUiState.value = mutableUiState.value.copy(message = "Profilo mezzo non valido.")
        }
    }

    fun clearVehicleProfileSelection() {
        try {
            mutableUiState.value = mutableUiState.value
                .withVehicleProfiles(vehicleProfileRepository.clearSelection())
                .copy(message = null)
        } catch (_: Exception) {
            mutableUiState.value = mutableUiState.value.copy(
                message = "Impossibile usare valori personalizzati.",
            )
        }
    }

    fun deleteVehicleProfile(profileId: String) {
        try {
            mutableUiState.value = mutableUiState.value
                .withVehicleProfiles(vehicleProfileRepository.delete(profileId))
                .copy(message = null)
        } catch (_: Exception) {
            mutableUiState.value = mutableUiState.value.copy(
                message = "Impossibile eliminare il profilo del mezzo.",
            )
        }
    }

    private fun updateProfileDecimal(
        value: String,
        transform: RoutePlannerUiState.() -> RoutePlannerUiState,
    ) {
        if (value.isDecimalInput()) mutableUiState.value = mutableUiState.value.transform()
    }

    fun updateEffectiveRange(value: String) {
        if (value.isDecimalInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                effectiveRangeKmInput = value,
                message = null,
            )
        }
    }

    fun updateMaximumDetour(value: String) {
        if (value.isDecimalInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                maximumDetourMinutesInput = value,
                message = null,
            )
        }
    }

    fun updateEstimatedRemainingRange(value: String) {
        if (value.isDecimalInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                estimatedRemainingRangeKmInput = value,
                message = null,
            )
        }
    }

    fun updateReserveRange(value: String) {
        if (value.isDecimalInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                reserveRangeKmInput = value,
                message = null,
            )
        }
    }

    fun updateEstimatedRemainingGasolineRange(value: String) {
        if (value.isDecimalInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                estimatedRemainingGasolineRangeKmInput = value,
                message = null,
            )
        }
    }

    fun searchCngStations() {
        val state = mutableUiState.value
        val rangeKm = state.effectiveRangeKmInput.parseDecimal()
        val detourMinutes = state.maximumDetourMinutesInput.parseDecimal()
        val validationMessage = when {
            rangeKm == null || rangeKm <= 0 || rangeKm > 2_000 -> {
                "Inserisci un'autonomia effettiva maggiore di 0 e fino a 2.000 km."
            }
            detourMinutes == null || detourMinutes < 0 || detourMinutes > 240 -> {
                "Inserisci un tempo massimo di deviazione tra 0 e 240 minuti."
            }
            else -> null
        }
        if (validationMessage != null) {
            mutableUiState.value = state.copy(message = validationMessage)
            return
        }

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val route = requireNotNull(state.baseRoute)
            mutableUiState.value = state.copy(
                operation = PlannerOperation.CNG_CANDIDATES,
                message = null,
                pendingStation = null,
                selectedRoute = null,
                selectedItineraryRoute = null,
                predictiveSuggestion = null,
                workflowMode = CngWorkflowMode.MANUAL,
            )
            try {
                val ranked = routingRepository.rankedCngStations(
                    origin = route.origin,
                    destination = route.destination,
                    effectiveCngRangeKm = requireNotNull(rangeKm),
                    maximumDetourMinutes = requireNotNull(detourMinutes),
                    departureAt = OffsetDateTime.now(clock),
                )
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.CNG_CANDIDATES,
                    operation = null,
                    baseRoute = ranked.baseRoute,
                    rankedStations = ranked,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                if (error.failure.requiresServerConfiguration()) {
                    openServerConnectionForFailure(
                        message = error.failure.candidateMessage(),
                        returnStage = PlannerStage.CONFIGURE_CNG,
                    )
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = error.failure.candidateMessage(),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.candidateMessage(),
                )
            }
        }
    }

    fun evaluatePredictiveRange() {
        val state = mutableUiState.value
        val effectiveRangeKm = state.effectiveRangeKmInput.parseDecimal()
        val remainingRangeKm = state.estimatedRemainingRangeKmInput.parseDecimal()
        val reserveRangeKm = state.reserveRangeKmInput.parseDecimal()
        val detourMinutes = state.maximumDetourMinutesInput.parseDecimal()
        val gasolineInputPresent = state.estimatedRemainingGasolineRangeKmInput.isNotBlank()
        val remainingGasolineRangeKm = state.estimatedRemainingGasolineRangeKmInput.parseDecimal()
        val effectiveGasolineRangeKm = state.effectiveGasolineRangeKmInput.parseDecimal()
        val gasolineReserveRangeKm = state.gasolineReserveRangeKmInput.parseDecimal()
        val validationMessage = when {
            effectiveRangeKm == null || effectiveRangeKm <= 0 || effectiveRangeKm > 2_000 -> {
                "Inserisci un'autonomia effettiva maggiore di 0 e fino a 2.000 km."
            }
            remainingRangeKm == null || remainingRangeKm <= 0 -> {
                "Inserisci l'autonomia CNG residua stimata, maggiore di 0 km."
            }
            remainingRangeKm > effectiveRangeKm -> {
                "L'autonomia residua non può superare l'autonomia effettiva."
            }
            reserveRangeKm == null || reserveRangeKm < 0 -> {
                "Inserisci una riserva CNG non negativa."
            }
            reserveRangeKm >= remainingRangeKm -> {
                "La riserva deve essere inferiore all'autonomia residua."
            }
            gasolineInputPresent && state.vehicleProfiles.selectedProfile == null -> {
                "Seleziona un profilo mezzo per usare il fallback benzina."
            }
            gasolineInputPresent && (
                remainingGasolineRangeKm == null || remainingGasolineRangeKm <= 0
            ) -> "Inserisci l'autonomia benzina residua stimata, maggiore di 0 km."
            gasolineInputPresent && (
                effectiveGasolineRangeKm == null ||
                    requireNotNull(remainingGasolineRangeKm) > effectiveGasolineRangeKm
            ) -> "L'autonomia benzina residua supera il massimo del profilo selezionato."
            gasolineInputPresent && (
                gasolineReserveRangeKm == null ||
                    gasolineReserveRangeKm >= requireNotNull(remainingGasolineRangeKm)
            ) -> "La riserva benzina deve essere inferiore all'autonomia residua."
            detourMinutes == null || detourMinutes < 0 || detourMinutes > 240 -> {
                "Inserisci un tempo massimo di deviazione tra 0 e 240 minuti."
            }
            else -> null
        }
        if (validationMessage != null) {
            mutableUiState.value = state.copy(message = validationMessage)
            return
        }

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val route = requireNotNull(state.baseRoute)
            mutableUiState.value = state.copy(
                operation = PlannerOperation.PREDICTIVE_CANDIDATES,
                workflowMode = CngWorkflowMode.PREDICTIVE,
                message = null,
                rankedStations = null,
                predictiveSuggestion = null,
                pendingStation = null,
                selectedRoute = null,
                selectedItineraryRoute = null,
            )
            try {
                val suggestion = routingRepository.predictiveCngStations(
                    origin = route.origin,
                    destination = route.destination,
                    effectiveCngRangeKm = requireNotNull(effectiveRangeKm),
                    estimatedRemainingCngRangeKm = requireNotNull(remainingRangeKm),
                    reserveCngRangeKm = requireNotNull(reserveRangeKm),
                    maximumDetourMinutes = requireNotNull(detourMinutes),
                    departureAt = OffsetDateTime.now(clock),
                    estimatedRemainingGasolineRangeKm = if (gasolineInputPresent) {
                        remainingGasolineRangeKm
                    } else {
                        null
                    },
                    reserveGasolineRangeKm = if (gasolineInputPresent) {
                        gasolineReserveRangeKm
                    } else {
                        null
                    },
                )
                mutableUiState.value = mutableUiState.value.copy(
                    stage = if (suggestion.state == PredictiveSuggestionState.SUGGESTED) {
                        PlannerStage.PREDICTIVE_ITINERARY
                    } else {
                        PlannerStage.PREDICTIVE_STATUS
                    },
                    operation = null,
                    baseRoute = suggestion.baseRoute,
                    rankedStations = null,
                    predictiveSuggestion = suggestion,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                if (error.failure.requiresServerConfiguration()) {
                    openServerConnectionForFailure(
                        message = error.failure.predictiveMessage(),
                        returnStage = PlannerStage.CONFIGURE_PREDICTIVE,
                    )
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = error.failure.predictiveMessage(),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.predictiveMessage(),
                )
            }
        }
    }

    fun selectStation(station: RankedCngStation) {
        val state = mutableUiState.value
        if (state.stage != PlannerStage.CNG_CANDIDATES || state.isBusy) return
        check(state.rankedStations?.candidates?.any { it.mimitStationId == station.mimitStationId } == true) {
            "station must belong to the active ranked response"
        }

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val routeBase = requireNotNull(state.baseRoute)
            mutableUiState.value = state.copy(
                operation = PlannerOperation.SELECTED_ROUTE,
                pendingStation = station,
                message = null,
            )
            try {
                val route = routingRepository.routeWithCngStop(
                    origin = routeBase.origin,
                    destination = routeBase.destination,
                    mimitStationId = station.mimitStationId,
                )
                require(route.selectedStop.mimitStationId == station.mimitStationId) {
                    "selected station does not match route response"
                }
                val navigationReadyRoute = route.copy(
                    selectedStop = route.selectedStop.withNavigationDetailsFrom(station),
                )
                navigationSession.preview(navigationReadyRoute.toNavigationRoute())
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.NAVIGATION_PREVIEW,
                    operation = null,
                    pendingStation = null,
                    selectedRoute = navigationReadyRoute,
                    message = null,
                ).withMapSafeSearchLabels()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                if (error.failure.requiresServerConfiguration()) {
                    mutableUiState.value = mutableUiState.value.copy(pendingStation = null)
                    openServerConnectionForFailure(
                        message = error.failure.selectedRouteMessage(),
                        returnStage = PlannerStage.CNG_CANDIDATES,
                    )
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        pendingStation = null,
                        message = error.failure.selectedRouteMessage(),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    pendingStation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.selectedRouteMessage(),
                )
            }
        }
    }

    fun acceptPredictiveItinerary() {
        val state = mutableUiState.value
        val suggestion = state.predictiveSuggestion
        val itinerary = suggestion?.itinerary
        if (
            state.stage != PlannerStage.PREDICTIVE_ITINERARY ||
            state.isBusy ||
            suggestion?.state != PredictiveSuggestionState.SUGGESTED ||
            itinerary == null
        ) {
            return
        }

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val routeBase = requireNotNull(state.baseRoute)
            mutableUiState.value = state.copy(
                operation = PlannerOperation.SELECTED_ROUTE,
                message = null,
            )
            try {
                val route = routingRepository.routeWithCngItinerary(
                    origin = routeBase.origin,
                    destination = routeBase.destination,
                    mimitStationIds = itinerary.stops.map { it.station.mimitStationId },
                    effectiveCngRangeKm = suggestion.rangeBasis.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm = (
                        suggestion.rangeBasis.estimatedRemainingCngRangeKm
                    ),
                    reserveCngRangeKm = suggestion.rangeBasis.reserveCngRangeKm,
                )
                val detailsByStationId = itinerary.stops.associateBy {
                    it.station.mimitStationId
                }
                val navigationReadyRoute = route.copy(
                    selectedStops = route.selectedStops.map { selectedStop ->
                        detailsByStationId[selectedStop.mimitStationId]?.let { plannedStop ->
                            selectedStop.withNavigationDetailsFrom(plannedStop)
                        } ?: selectedStop
                    },
                )
                navigationSession.preview(
                    navigationReadyRoute.toNavigationRoute(
                        maximumDetourMinutes = suggestion.maximumDetourMinutes,
                    ),
                )
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.NAVIGATION_PREVIEW,
                    operation = null,
                    selectedRoute = null,
                    selectedItineraryRoute = navigationReadyRoute,
                    message = null,
                ).withMapSafeSearchLabels()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                if (error.failure.requiresServerConfiguration()) {
                    openServerConnectionForFailure(
                        message = error.failure.selectedItineraryRouteMessage(),
                        returnStage = PlannerStage.PREDICTIVE_ITINERARY,
                    )
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = error.failure.selectedItineraryRouteMessage(),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.selectedItineraryRouteMessage(),
                )
            }
        }
    }

    fun navigateBack() {
        if (mutableUiState.value.isBusy) return
        if (mutableUiState.value.stage == PlannerStage.INTERMEDIATE_STOP_PREVIEW) {
            pendingIntermediateResolution = null
        }
        mutableUiState.value = when (mutableUiState.value.stage) {
            PlannerStage.FOLLOW -> mutableUiState.value
            PlannerStage.PREVIEW -> mutableUiState.value
            PlannerStage.CONFIGURE_ROUTE -> mutableUiState.value.copy(
                stage = if (mutableUiState.value.baseRoute == null) {
                    PlannerStage.FOLLOW
                } else {
                    PlannerStage.PREVIEW
                },
                message = null,
            )
            PlannerStage.DESTINATION_SEARCH -> mutableUiState.value.copy(
                stage = if (
                    mutableUiState.value.placeSearchTarget == RouteEndpoint.INTERMEDIATE_STOP
                ) {
                    PlannerStage.INTERMEDIATE_STOPS
                } else {
                    PlannerStage.CONFIGURE_ROUTE
                },
                message = null,
            )
            PlannerStage.MAP_POINT_PICKER -> mutableUiState.value.copy(
                stage = if (
                    mutableUiState.value.mapPickerTarget == RouteEndpoint.INTERMEDIATE_STOP
                ) {
                    PlannerStage.INTERMEDIATE_STOPS
                } else {
                    PlannerStage.CONFIGURE_ROUTE
                },
                mapPickerTarget = null,
                mapPickerCoordinate = null,
                message = null,
            )
            PlannerStage.INTERMEDIATE_STOP_PREVIEW -> mutableUiState.value.copy(
                stage = PlannerStage.INTERMEDIATE_STOPS,
                pendingIntermediateStopRoute = null,
                pendingIntermediateStopsRoute = null,
                pendingIntermediateStopCoordinate = null,
                message = null,
            )
            PlannerStage.INTERMEDIATE_STOPS -> mutableUiState.value.copy(
                stage = PlannerStage.PREVIEW,
                editingIntermediateStopId = null,
                message = null,
            )
            PlannerStage.CONFIGURE_CNG -> mutableUiState.value.copy(
                stage = PlannerStage.PREVIEW,
                message = null,
            )
            PlannerStage.CONFIGURE_PREDICTIVE -> mutableUiState.value.copy(
                stage = PlannerStage.PREVIEW,
                message = null,
            )
            PlannerStage.VEHICLE_PROFILES -> mutableUiState.value.copy(
                stage = PlannerStage.PREVIEW,
                message = null,
            )
            PlannerStage.SERVER_CONNECTION -> mutableUiState.value.copy(
                stage = serverReturnStage,
                message = null,
            )
            PlannerStage.CNG_CANDIDATES -> mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_CNG,
                message = null,
            )
            PlannerStage.PREDICTIVE_ITINERARY -> mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_PREDICTIVE,
                message = null,
            )
            PlannerStage.PREDICTIVE_STATUS -> mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_PREDICTIVE,
                message = null,
            )
            PlannerStage.NAVIGATION_PREVIEW -> {
                navigationSession.clear()
                when {
                    mutableUiState.value.selectedItineraryRoute != null -> {
                        mutableUiState.value.copy(
                            stage = PlannerStage.PREDICTIVE_ITINERARY,
                            selectedItineraryRoute = null,
                            message = null,
                        )
                    }
                    mutableUiState.value.selectedRoute != null -> {
                        mutableUiState.value.copy(
                            stage = PlannerStage.CNG_CANDIDATES,
                            selectedRoute = null,
                            message = null,
                        )
                    }
                    else -> mutableUiState.value.copy(
                        stage = PlannerStage.PREVIEW,
                        message = null,
                    )
                }
            }
        }
    }

    fun openNavigationPreview() {
        if (
            mutableUiState.value.isBusy ||
            mutableUiState.value.stage == PlannerStage.CONFIGURE_ROUTE &&
            mutableUiState.value.routeInputsDirty
        ) return
        val predictive = mutableUiState.value.predictiveSuggestion
        val route = mutableUiState.value.selectedItineraryRoute?.toNavigationRoute(
            maximumDetourMinutes = predictive?.maximumDetourMinutes,
        )
            ?: mutableUiState.value.selectedRoute?.toNavigationRoute()
            ?: mutableUiState.value.intermediateStopsRoute?.toNavigationRoute()
            ?: mutableUiState.value.intermediateStopRoute?.toNavigationRoute()
            ?: mutableUiState.value.baseRoute?.toNavigationRoute()
            ?: return
        navigationSession.preview(route)
        mutableUiState.value = mutableUiState.value.withMapSafeSearchLabels().copy(
            stage = PlannerStage.NAVIGATION_PREVIEW,
            message = null,
        )
    }

    fun openGasolineFallbackNavigation() {
        val state = mutableUiState.value
        val fallback = state.predictiveSuggestion?.gasolineFallback
        if (
            state.stage != PlannerStage.PREDICTIVE_STATUS ||
            state.isBusy ||
            fallback == null
        ) return
        val route = state.baseRoute?.toNavigationRoute(gasolineFallback = fallback) ?: return
        navigationSession.preview(route)
        mutableUiState.value = state.withMapSafeSearchLabels().copy(
            stage = PlannerStage.NAVIGATION_PREVIEW,
            message = null,
        )
    }

    fun startNavigation() {
        if (mutableUiState.value.stage != PlannerStage.NAVIGATION_PREVIEW) return
        navigationSession.start()
        mutableUiState.value = mutableUiState.value.copy(message = null)
    }

    fun stopNavigation() {
        navigationSession.clear()
        pendingIntermediateResolution = null
        mutableUiState.value = mutableUiState.value.copy(
            stage = PlannerStage.FOLLOW,
            operation = null,
            baseRoute = null,
            intermediateStopRoute = null,
            pendingIntermediateStopRoute = null,
            plannedIntermediateStops = emptyList(),
            intermediateStopsRoute = null,
            pendingIntermediateStopsRoute = null,
            pendingIntermediateStopCoordinate = null,
            editingIntermediateStopId = null,
            mapPickerTarget = null,
            mapPickerCoordinate = null,
            rankedStations = null,
            predictiveSuggestion = null,
            workflowMode = null,
            pendingStation = null,
            selectedRoute = null,
            selectedItineraryRoute = null,
            message = null,
        )
    }

    fun navigationPermissionDenied(
        locationGranted: Boolean,
        notificationsGranted: Boolean,
    ) {
        mutableUiState.value = mutableUiState.value.copy(
            message = when {
                !locationGranted && !notificationsGranted -> {
                    "Posizione e notifiche sono necessarie per la navigazione in background."
                }
                !locationGranted -> "La posizione è necessaria per iniziare la navigazione."
                else -> {
                    "Autorizza le notifiche per mantenere visibile la navigazione in background."
                }
            },
        )
    }

    fun removeCngStop() {
        if (mutableUiState.value.isBusy) return
        mutableUiState.value = mutableUiState.value.copy(
            stage = PlannerStage.PREVIEW,
            rankedStations = null,
            predictiveSuggestion = null,
            workflowMode = null,
            pendingStation = null,
            selectedRoute = null,
            selectedItineraryRoute = null,
            message = null,
        )
    }

    fun editCngPlan() {
        val state = mutableUiState.value
        if (state.isBusy) return
        navigationSession.clear()
        mutableUiState.value = state.copy(
            stage = when (state.workflowMode) {
                CngWorkflowMode.MANUAL -> if (state.rankedStations != null) {
                    PlannerStage.CNG_CANDIDATES
                } else {
                    PlannerStage.CONFIGURE_CNG
                }
                CngWorkflowMode.PREDICTIVE -> PlannerStage.CONFIGURE_PREDICTIVE
                null -> PlannerStage.PREVIEW
            },
            message = null,
        )
    }

    fun deleteCngStopFromSummary(mimitStationId: String) {
        val state = mutableUiState.value
        val directRoute = state.baseRoute ?: return
        if (state.isBusy) return
        val remainingIds = navigationSession.state.value.route?.fuelStops
            ?.map { it.mimitStationId }
            ?.filterNot { it == mimitStationId }
            .orEmpty()
        if (remainingIds.isEmpty()) {
            navigationSession.preview(directRoute.toNavigationRoute())
            mutableUiState.value = state.copy(
                stage = PlannerStage.NAVIGATION_PREVIEW,
                rankedStations = null,
                predictiveSuggestion = null,
                workflowMode = null,
                pendingStation = null,
                selectedRoute = null,
                selectedItineraryRoute = null,
                message = null,
            )
            return
        }
        val suggestion = state.predictiveSuggestion
        if (suggestion == null) {
            mutableUiState.value = state.copy(
                message = "Il piano CNG va riaperto per eliminare questa sosta in sicurezza.",
            )
            return
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            mutableUiState.value = state.copy(
                operation = PlannerOperation.SELECTED_ROUTE,
                message = null,
            )
            try {
                val route = routingRepository.routeWithCngItinerary(
                    origin = directRoute.origin,
                    destination = directRoute.destination,
                    mimitStationIds = remainingIds,
                    effectiveCngRangeKm = suggestion.rangeBasis.effectiveCngRangeKm,
                    estimatedRemainingCngRangeKm =
                        suggestion.rangeBasis.estimatedRemainingCngRangeKm,
                    reserveCngRangeKm = suggestion.rangeBasis.reserveCngRangeKm,
                )
                navigationSession.preview(
                    route.toNavigationRoute(
                        maximumDetourMinutes = suggestion.maximumDetourMinutes,
                    ),
                )
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.NAVIGATION_PREVIEW,
                    operation = null,
                    selectedItineraryRoute = route,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.selectedItineraryRouteMessage(),
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.selectedItineraryRouteMessage(),
                )
            }
        }
    }

    private fun loadBaseRoute(
        origin: Coordinate = mutableUiState.value.activeOrigin,
        destination: Coordinate = mutableUiState.value.activeDestination,
        originDisplayName: String = mutableUiState.value.originDisplayName,
        destinationDisplayName: String = mutableUiState.value.destinationDisplayName,
        intermediateStop: Coordinate? = mutableUiState.value.selectedIntermediateStopCoordinate(),
        successStage: PlannerStage = PlannerStage.PREVIEW,
    ) {
        requestJob?.cancel()
        navigationSession.clear()
        val maximumIntermediateDeviationKm = mutableUiState.value
            .intermediateStopMaximumDeviationKmInput.parseDecimal()
        requestJob = viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                stage = successStage,
                operation = PlannerOperation.BASE_ROUTE,
                activeOrigin = origin,
                activeDestination = destination,
                originLatitudeInput = origin.latitude.toCoordinateInput(),
                originLongitudeInput = origin.longitude.toCoordinateInput(),
                destinationLatitudeInput = destination.latitude.toCoordinateInput(),
                destinationLongitudeInput = destination.longitude.toCoordinateInput(),
                originDisplayName = originDisplayName,
                destinationDisplayName = destinationDisplayName,
                originLocationMethod = mutableUiState.value.originLocationMethod,
                destinationLocationMethod = mutableUiState.value.destinationLocationMethod,
                placeSearchQuery = "",
                placeSearchResults = emptyList(),
                baseRoute = null,
                intermediateStopRoute = null,
                plannedIntermediateStops = emptyList(),
                intermediateStopsRoute = null,
                pendingIntermediateStopsRoute = null,
                editingIntermediateStopId = null,
                rankedStations = null,
                predictiveSuggestion = null,
                workflowMode = null,
                pendingStation = null,
                selectedRoute = null,
                selectedItineraryRoute = null,
                routeInputsDirty = true,
                message = null,
            )
            try {
                val directRoute = routingRepository.previewRoute(origin, destination)
                val viaRoute = intermediateStop?.let { stop ->
                    val maximumDeviationKm = maximumIntermediateDeviationKm
                    if (maximumDeviationKm == null || maximumDeviationKm < 0.0) {
                        mutableUiState.value = mutableUiState.value.copy(
                            operation = null,
                            baseRoute = directRoute,
                            message = "Inserisci una deviazione massima valida per la tappa.",
                        )
                        return@launch
                    }
                    routingRepository.routeWithIntermediateStop(origin, stop, destination).also {
                        val extraDistanceMeters =
                            (it.distanceMeters - directRoute.distanceMeters).coerceAtLeast(0.0)
                        if (extraDistanceMeters > maximumDeviationKm * 1_000.0) {
                            mutableUiState.value = mutableUiState.value.copy(
                                operation = null,
                                baseRoute = directRoute,
                                message = intermediateStopDeviationMessage(
                                    extraDistanceMeters = extraDistanceMeters,
                                    maximumDeviationKm = maximumDeviationKm,
                                ),
                            )
                            return@launch
                        }
                    }
                }
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    baseRoute = viaRoute?.asRoutePreview() ?: directRoute,
                    intermediateStopRoute = viaRoute,
                    routeInputsDirty = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                if (error.failure.requiresServerConfiguration()) {
                    openServerConnectionForFailure(
                        message = error.failure.baseRouteMessage(),
                        returnStage = successStage,
                    )
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        operation = null,
                        message = error.failure.baseRouteMessage(),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = RoutePreviewFailure.INVALID_RESPONSE.baseRouteMessage(),
                )
            }
        }
    }

    private fun openServerConnectionForFailure(message: String, returnStage: PlannerStage) {
        serverReturnStage = returnStage
        mutableUiState.value = mutableUiState.value
            .withServerConnection(serverConnectionRepository.load())
            .copy(
                stage = PlannerStage.SERVER_CONNECTION,
                operation = null,
                message = message,
            )
    }

    class Factory(
        private val routingRepository: RoutingRepository,
        private val navigationSession: NavigationSession,
        private val vehicleProfileRepository: VehicleProfileRepository,
        private val serverConnectionRepository: ServerConnectionRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RoutePlannerViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return RoutePlannerViewModel(
                routingRepository = routingRepository,
                navigationSession = navigationSession,
                vehicleProfileRepository = vehicleProfileRepository,
                serverConnectionRepository = serverConnectionRepository,
                startInFollowMode = true,
            ) as T
        }
    }

    companion object {
        val MILAN: Coordinate = RoutePlannerUiState.DEFAULT_ORIGIN
        val BOLOGNA: Coordinate = RoutePlannerUiState.DEFAULT_DESTINATION
    }
}

private fun RoutePreviewFailure.placeSearchMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Ricerca non disponibile: controlla la connessione."
    RoutePreviewFailure.AUTHENTICATION -> "Credenziali server non valide. Apri Server e correggile."
    RoutePreviewFailure.SERVER -> "Il servizio di ricerca non è disponibile."
    else -> "La risposta del servizio di ricerca non è valida."
}

private fun RoutePreviewFailure.requiresServerConfiguration(): Boolean =
    this == RoutePreviewFailure.NETWORK || this == RoutePreviewFailure.AUTHENTICATION

private data class ParsedCoordinate(
    val coordinate: Coordinate?,
    val message: String?,
)

private fun parseCoordinate(
    latitudeInput: String,
    longitudeInput: String,
    label: String,
): ParsedCoordinate {
    val latitude = latitudeInput.parseDecimal()
    val longitude = longitudeInput.parseDecimal()
    return when {
        latitude == null || latitude < -90 || latitude > 90 -> ParsedCoordinate(
            null,
            "Inserisci una latitudine valida per la $label, tra -90 e 90.",
        )
        longitude == null || longitude < -180 || longitude > 180 -> ParsedCoordinate(
            null,
            "Inserisci una longitudine valida per la $label, tra -180 e 180.",
        )
        else -> ParsedCoordinate(Coordinate(latitude, longitude), null)
    }
}

private fun String.isDecimalInput(): Boolean = matches(Regex("^[0-9]{0,4}([.,][0-9]{0,2})?$"))

private fun String.isCoordinateInput(): Boolean = matches(Regex("^-?[0-9]{0,3}([.,][0-9]{0,6})?$"))

private fun String.parseDecimal(): Double? = replace(',', '.').toDoubleOrNull()

private fun Double.toCoordinateInput(): String = "%.6f".format(java.util.Locale.US, this)

private fun Double.toOneDecimalInput(): String = "%.1f".format(java.util.Locale.ITALIAN, this)

private fun intermediateStopDeviationMessage(
    extraDistanceMeters: Double,
    maximumDeviationKm: Double,
): String = "La tappa richiede una deviazione di " +
    "${(extraDistanceMeters / 1_000.0).toOneDecimalInput()} km, " +
    "oltre il limite di ${maximumDeviationKm.toOneDecimalInput()} km."

private fun RoutePlannerUiState.selectedIntermediateStopCoordinate(): Coordinate? =
    if (!intermediateStopEnabled) {
        null
    } else {
        parseCoordinate(
            intermediateStopLatitudeInput,
            intermediateStopLongitudeInput,
            "tappa",
        ).coordinate
    }

private fun RoutePlannerUiState.originCoordinateOrNull(): Coordinate? = parseCoordinate(
    originLatitudeInput,
    originLongitudeInput,
    "partenza",
).coordinate

private fun RoutePlannerUiState.destinationCoordinateOrNull(): Coordinate? = parseCoordinate(
    destinationLatitudeInput,
    destinationLongitudeInput,
    "destinazione",
).coordinate

private fun RoutePlannerUiState.intermediateStopSearchBounds(): DestinationSearchBounds? {
    val geometry = baseRoute?.geometry?.takeIf { it.isNotEmpty() } ?: return null
    val maximumDeviationKm = intermediateStopMaximumDeviationKmInput.parseDecimal()
        ?.takeIf { it >= 0.0 } ?: return null
    val searchPaddingKm = maximumDeviationKm.coerceAtLeast(MINIMUM_ROUTE_SEARCH_PADDING_KM)
    val middleLatitude = geometry.map(Coordinate::latitude).average()
    val latitudePadding = searchPaddingKm / 111.0
    val longitudeScale = kotlin.math.cos(Math.toRadians(middleLatitude))
        .coerceAtLeast(0.15)
    val longitudePadding = searchPaddingKm / (111.0 * longitudeScale)
    return DestinationSearchBounds(
        southWest = Coordinate(
            latitude = (geometry.minOf(Coordinate::latitude) - latitudePadding)
                .coerceAtLeast(-90.0),
            longitude = (geometry.minOf(Coordinate::longitude) - longitudePadding)
                .coerceAtLeast(-180.0),
        ),
        northEast = Coordinate(
            latitude = (geometry.maxOf(Coordinate::latitude) + latitudePadding)
                .coerceAtMost(90.0),
            longitude = (geometry.maxOf(Coordinate::longitude) + longitudePadding)
                .coerceAtMost(180.0),
        ),
    )
}

private const val DEFAULT_INTERMEDIATE_STOP_DEVIATION_FRACTION = 0.30
private const val MINIMUM_ROUTE_SEARCH_PADDING_KM = 0.1

private fun Double.toInput(): String = if (this % 1.0 == 0.0) {
    toInt().toString()
} else {
    toString()
}

private fun RoutePlannerUiState.withVehicleProfiles(
    profiles: VehicleProfiles,
): RoutePlannerUiState {
    val selected = profiles.selectedProfile
    return copy(
        vehicleProfiles = profiles,
        effectiveRangeKmInput = selected?.effectiveCngRangeKm?.toInput()
            ?: effectiveRangeKmInput,
        reserveRangeKmInput = selected?.cngReserveKm?.toInput() ?: reserveRangeKmInput,
        effectiveGasolineRangeKmInput = selected?.effectiveGasolineRangeKm?.toInput().orEmpty(),
        gasolineReserveRangeKmInput = selected?.gasolineReserveKm?.toInput().orEmpty(),
    )
}

private fun RoutePlannerUiState.withServerConnection(
    connection: ServerConnection,
): RoutePlannerUiState = copy(
    serverBaseUrlInput = connection.baseUrl,
    serverUsernameInput = connection.username,
    serverPasswordInput = connection.password,
    serverAllowInsecureHttp = connection.allowInsecureHttp,
)

private fun RoutePlannerUiState.withMapSafeSearchLabels(): RoutePlannerUiState = copy(
    originDisplayName = if (originLocationMethod == RouteLocationMethod.SEARCH) {
        "Partenza selezionata"
    } else {
        originDisplayName
    },
    destinationDisplayName = if (destinationLocationMethod == RouteLocationMethod.SEARCH) {
        "Destinazione selezionata"
    } else {
        destinationDisplayName
    },
    intermediateStopDisplayName = if (
        intermediateStopLocationMethod == RouteLocationMethod.SEARCH
    ) {
        "Tappa intermedia"
    } else {
        intermediateStopDisplayName
    },
    intermediateStopAttributions = if (
        intermediateStopLocationMethod == RouteLocationMethod.SEARCH
    ) {
        emptyList()
    } else {
        intermediateStopAttributions
    },
)

private fun RoutePreviewFailure.baseRouteMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile contattare il server Compass."
    RoutePreviewFailure.AUTHENTICATION -> "Credenziali server non valide. Apri Server e correggile."
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile tra le coordinate impostate."
    RoutePreviewFailure.SERVER -> "Il servizio di routing non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito un percorso non valido."
    RoutePreviewFailure.STATION_NOT_FOUND,
    RoutePreviewFailure.STATION_UNAVAILABLE,
    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE,
    -> "La risposta del server non è valida."
}

private fun RoutePreviewFailure.candidateMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile cercare le stazioni: server non raggiungibile."
    RoutePreviewFailure.AUTHENTICATION -> "Credenziali server non valide. Apri Server e correggile."
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile per la ricerca delle stazioni."
    RoutePreviewFailure.SERVER -> "La ricerca delle stazioni non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito stazioni non valide."
    RoutePreviewFailure.STATION_NOT_FOUND,
    RoutePreviewFailure.STATION_UNAVAILABLE,
    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE,
    -> "La ricerca delle stazioni non è più valida."
}

private fun RoutePreviewFailure.selectedRouteMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile ricalcolare il percorso: server non raggiungibile."
    RoutePreviewFailure.AUTHENTICATION -> "Credenziali server non valide. Apri Server e correggile."
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile attraverso questa stazione."
    RoutePreviewFailure.STATION_NOT_FOUND -> "La stazione selezionata non esiste più."
    RoutePreviewFailure.STATION_UNAVAILABLE -> "La stazione selezionata non è raggiungibile."
    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE -> {
        "Il percorso non conserva la riserva CNG richiesta."
    }
    RoutePreviewFailure.SERVER -> "Il ricalcolo del percorso non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito un percorso non valido."
}

private fun RoutePreviewFailure.predictiveMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile valutare l'autonomia: server non raggiungibile."
    RoutePreviewFailure.AUTHENTICATION -> "Credenziali server non valide. Apri Server e correggile."
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile per valutare l'autonomia."
    RoutePreviewFailure.SERVER -> "La valutazione predittiva non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito una previsione non valida."
    RoutePreviewFailure.STATION_NOT_FOUND,
    RoutePreviewFailure.STATION_UNAVAILABLE,
    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE,
    -> "La valutazione delle stazioni non è più valida."
}

private fun RoutePreviewFailure.selectedItineraryRouteMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile calcolare l'itinerario: server non raggiungibile."
    RoutePreviewFailure.AUTHENTICATION -> "Credenziali server non valide. Apri Server e correggile."
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile attraverso tutte le stazioni."
    RoutePreviewFailure.STATION_NOT_FOUND -> "Una stazione del piano non esiste più."
    RoutePreviewFailure.STATION_UNAVAILABLE -> "Una stazione del piano non è raggiungibile."
    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE -> {
        "Il percorso reale non conserva la riserva su tutte le tratte. Ricalcola il piano."
    }
    RoutePreviewFailure.SERVER -> "Il calcolo dell'itinerario CNG non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito un itinerario non valido."
}
