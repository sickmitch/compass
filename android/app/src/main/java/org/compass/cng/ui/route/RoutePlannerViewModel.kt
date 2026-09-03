package org.compass.cng.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.PlaceSearchResult
import org.compass.cng.domain.model.PlaceSearchSource
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary
import org.compass.cng.navigation.NavigationSession
import org.compass.cng.navigation.toNavigationRoute
import org.compass.cng.domain.vehicle.InMemoryVehicleProfileRepository
import org.compass.cng.domain.vehicle.VehicleProfile
import org.compass.cng.domain.vehicle.VehicleProfileRepository
import org.compass.cng.domain.vehicle.VehicleProfiles

enum class PlannerStage {
    CONFIGURE_ROUTE,
    DESTINATION_SEARCH,
    PREVIEW,
    CONFIGURE_CNG,
    CONFIGURE_PREDICTIVE,
    VEHICLE_PROFILES,
    CNG_CANDIDATES,
    PREDICTIVE_ITINERARY,
    PREDICTIVE_STATUS,
    SELECTED_ROUTE,
    NAVIGATION_PREVIEW,
}

enum class PlannerOperation {
    BASE_ROUTE,
    PLACE_SEARCH,
    CNG_CANDIDATES,
    PREDICTIVE_CANDIDATES,
    SELECTED_ROUTE,
}

enum class CngWorkflowMode {
    MANUAL,
    PREDICTIVE,
}

data class RoutePlannerUiState(
    val stage: PlannerStage = PlannerStage.PREVIEW,
    val operation: PlannerOperation? = PlannerOperation.BASE_ROUTE,
    val activeOrigin: Coordinate = DEFAULT_ORIGIN,
    val activeDestination: Coordinate = DEFAULT_DESTINATION,
    val originLatitudeInput: String = DEFAULT_ORIGIN_LATITUDE,
    val originLongitudeInput: String = DEFAULT_ORIGIN_LONGITUDE,
    val destinationLatitudeInput: String = DEFAULT_DESTINATION_LATITUDE,
    val destinationLongitudeInput: String = DEFAULT_DESTINATION_LONGITUDE,
    val originDisplayName: String = "Milano",
    val destinationDisplayName: String = "Bologna",
    val placeSearchQuery: String = "",
    val placeSearchResults: List<PlaceSearchResult> = emptyList(),
    val placeSearchSource: PlaceSearchSource = PlaceSearchSource.LIVE,
    val placeSearchCachedAtEpochMillis: Long? = null,
    val baseRoute: RoutePreview? = null,
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
) {
    val isBusy: Boolean get() = operation != null

    companion object {
        val DEFAULT_ORIGIN = Coordinate(latitude = 45.4642, longitude = 9.1900)
        val DEFAULT_DESTINATION = Coordinate(latitude = 44.4949, longitude = 11.3426)
        const val DEFAULT_ORIGIN_LATITUDE = "45.4642"
        const val DEFAULT_ORIGIN_LONGITUDE = "9.1900"
        const val DEFAULT_DESTINATION_LATITUDE = "44.4949"
        const val DEFAULT_DESTINATION_LONGITUDE = "11.3426"
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
) : ViewModel() {
    val navigationState = navigationSession.state
    private val restoredNavigation = navigationSession.state.value
    private val initialVehicleProfiles = vehicleProfileRepository.load()
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
            ).withVehicleProfiles(initialVehicleProfiles)
        } ?: RoutePlannerUiState(
            activeOrigin = initialOrigin,
            activeDestination = initialDestination,
            originLatitudeInput = initialOrigin.latitude.toCoordinateInput(),
            originLongitudeInput = initialOrigin.longitude.toCoordinateInput(),
            destinationLatitudeInput = initialDestination.latitude.toCoordinateInput(),
            destinationLongitudeInput = initialDestination.longitude.toCoordinateInput(),
        ).withVehicleProfiles(initialVehicleProfiles)
    )
    val uiState: StateFlow<RoutePlannerUiState> = mutableUiState.asStateFlow()

    private var requestJob: Job? = null

    init {
        if (restoredNavigation.route == null) loadBaseRoute()
    }

    fun retryBaseRoute() = loadBaseRoute()

    fun openRouteConfiguration() {
        if (!mutableUiState.value.isBusy) {
            mutableUiState.value = mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
                message = null,
            )
        }
    }

    fun openDestinationSearch() {
        if (!mutableUiState.value.isBusy) {
            mutableUiState.value = mutableUiState.value.copy(
                stage = PlannerStage.DESTINATION_SEARCH,
                placeSearchQuery = "",
                placeSearchResults = emptyList(),
                placeSearchSource = PlaceSearchSource.LIVE,
                placeSearchCachedAtEpochMillis = null,
                message = null,
            )
        }
    }

    fun updatePlaceSearchQuery(value: String) {
        if (value.length <= 200) {
            mutableUiState.value = mutableUiState.value.copy(
                placeSearchQuery = value,
                message = null,
            )
        }
    }

    fun searchDestinations() {
        val state = mutableUiState.value
        val query = state.placeSearchQuery.trim()
        if (query.isEmpty()) {
            mutableUiState.value = state.copy(message = "Inserisci un indirizzo, luogo o coordinate.")
            return
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            mutableUiState.value = state.copy(
                operation = PlannerOperation.PLACE_SEARCH,
                placeSearchResults = emptyList(),
                message = null,
            )
            try {
                val results = routingRepository.searchPlaces(query)
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    placeSearchResults = results.results,
                    placeSearchSource = results.source,
                    placeSearchCachedAtEpochMillis = results.cachedAtEpochMillis,
                    message = if (results.results.isEmpty()) "Nessun luogo trovato." else null,
                )
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
                    message = "Ricerca destinazione non disponibile.",
                )
            }
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

    fun currentLocationRequested() {
        mutableUiState.value = mutableUiState.value.copy(
            message = "Acquisizione della posizione attuale…",
        )
    }

    fun useCurrentLocationAsOrigin(coordinate: Coordinate) {
        requestJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            stage = PlannerStage.CONFIGURE_ROUTE,
            operation = null,
            originLatitudeInput = coordinate.latitude.toCoordinateInput(),
            originLongitudeInput = coordinate.longitude.toCoordinateInput(),
            originDisplayName = "Posizione attuale",
            message = "Posizione acquisita. Tocca Calcola percorso per applicarla.",
        )
    }

    fun currentLocationUnavailable() {
        mutableUiState.value = mutableUiState.value.copy(
            message = "Impossibile ottenere la posizione attuale. Verifica GPS e permessi.",
        )
    }

    fun updateOriginLatitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                originLatitudeInput = value,
                originDisplayName = "Coordinate personalizzate",
                message = null,
            )
        }
    }

    fun updateOriginLongitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                originLongitudeInput = value,
                originDisplayName = "Coordinate personalizzate",
                message = null,
            )
        }
    }

    fun updateDestinationLatitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                destinationLatitudeInput = value,
                destinationDisplayName = "Coordinate personalizzate",
                message = null,
            )
        }
    }

    fun updateDestinationLongitude(value: String) {
        if (value.isCoordinateInput()) {
            mutableUiState.value = mutableUiState.value.copy(
                destinationLongitudeInput = value,
                destinationDisplayName = "Coordinate personalizzate",
                message = null,
            )
        }
    }

    fun applyRouteInputs() {
        val state = mutableUiState.value
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
        )
    }

    fun openAddStop() {
        if (mutableUiState.value.baseRoute != null && !mutableUiState.value.isBusy) {
            mutableUiState.value = mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_CNG,
                workflowMode = CngWorkflowMode.MANUAL,
                message = null,
            )
        }
    }

    fun openPredictiveRange() {
        if (mutableUiState.value.baseRoute != null && !mutableUiState.value.isBusy) {
            mutableUiState.value = mutableUiState.value.copy(
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
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.candidateMessage(),
                )
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
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    message = error.failure.predictiveMessage(),
                )
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
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.SELECTED_ROUTE,
                    operation = null,
                    pendingStation = null,
                    selectedRoute = route,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    pendingStation = null,
                    message = error.failure.selectedRouteMessage(),
                )
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
                mutableUiState.value = mutableUiState.value.copy(
                    stage = PlannerStage.SELECTED_ROUTE,
                    operation = null,
                    selectedRoute = null,
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

    fun navigateBack() {
        if (mutableUiState.value.isBusy) return
        mutableUiState.value = when (mutableUiState.value.stage) {
            PlannerStage.PREVIEW -> mutableUiState.value
            PlannerStage.CONFIGURE_ROUTE -> mutableUiState.value.copy(
                stage = PlannerStage.PREVIEW,
                message = null,
            )
            PlannerStage.DESTINATION_SEARCH -> mutableUiState.value.copy(
                stage = PlannerStage.CONFIGURE_ROUTE,
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
            PlannerStage.SELECTED_ROUTE -> mutableUiState.value.copy(
                stage = if (mutableUiState.value.selectedItineraryRoute != null) {
                    PlannerStage.PREDICTIVE_ITINERARY
                } else {
                    PlannerStage.CNG_CANDIDATES
                },
                selectedRoute = null,
                selectedItineraryRoute = null,
                message = null,
            )
            PlannerStage.NAVIGATION_PREVIEW -> {
                navigationSession.clear()
                mutableUiState.value.copy(
                    stage = if (
                        mutableUiState.value.selectedRoute != null ||
                        mutableUiState.value.selectedItineraryRoute != null
                    ) {
                        PlannerStage.SELECTED_ROUTE
                    } else {
                        PlannerStage.PREVIEW
                    },
                    message = null,
                )
            }
        }
    }

    fun openNavigationPreview() {
        if (mutableUiState.value.isBusy) return
        val predictive = mutableUiState.value.predictiveSuggestion
        val route = mutableUiState.value.selectedItineraryRoute?.toNavigationRoute(
            maximumDetourMinutes = predictive?.maximumDetourMinutes,
        )
            ?: mutableUiState.value.selectedRoute?.toNavigationRoute()
            ?: mutableUiState.value.baseRoute?.toNavigationRoute()
            ?: return
        navigationSession.preview(route)
        mutableUiState.value = mutableUiState.value.copy(
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
        mutableUiState.value = state.copy(stage = PlannerStage.NAVIGATION_PREVIEW, message = null)
    }

    fun startNavigation() {
        if (mutableUiState.value.stage != PlannerStage.NAVIGATION_PREVIEW) return
        navigationSession.start()
        mutableUiState.value = mutableUiState.value.copy(message = null)
    }

    fun stopNavigation() {
        navigationSession.stopToPreview()
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

    private fun loadBaseRoute(
        origin: Coordinate = mutableUiState.value.activeOrigin,
        destination: Coordinate = mutableUiState.value.activeDestination,
        originDisplayName: String = mutableUiState.value.originDisplayName,
        destinationDisplayName: String = mutableUiState.value.destinationDisplayName,
    ) {
        requestJob?.cancel()
        navigationSession.clear()
        requestJob = viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                stage = PlannerStage.PREVIEW,
                operation = PlannerOperation.BASE_ROUTE,
                activeOrigin = origin,
                activeDestination = destination,
                originLatitudeInput = origin.latitude.toCoordinateInput(),
                originLongitudeInput = origin.longitude.toCoordinateInput(),
                destinationLatitudeInput = destination.latitude.toCoordinateInput(),
                destinationLongitudeInput = destination.longitude.toCoordinateInput(),
                originDisplayName = originDisplayName,
                destinationDisplayName = destinationDisplayName,
                placeSearchQuery = "",
                placeSearchResults = emptyList(),
                baseRoute = null,
                rankedStations = null,
                predictiveSuggestion = null,
                workflowMode = null,
                pendingStation = null,
                selectedRoute = null,
                selectedItineraryRoute = null,
                message = null,
            )
            try {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = null,
                    baseRoute = routingRepository.previewRoute(origin, destination),
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

    class Factory(
        private val routingRepository: RoutingRepository,
        private val navigationSession: NavigationSession,
        private val vehicleProfileRepository: VehicleProfileRepository,
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
    RoutePreviewFailure.SERVER -> "Il servizio di ricerca non è disponibile."
    else -> "La risposta del servizio di ricerca non è valida."
}

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

private fun RoutePreviewFailure.baseRouteMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile contattare il server Compass."
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
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile attraverso tutte le stazioni."
    RoutePreviewFailure.STATION_NOT_FOUND -> "Una stazione del piano non esiste più."
    RoutePreviewFailure.STATION_UNAVAILABLE -> "Una stazione del piano non è raggiungibile."
    RoutePreviewFailure.CNG_ITINERARY_OUT_OF_RANGE -> {
        "Il percorso reale non conserva la riserva su tutte le tratte. Ricalcola il piano."
    }
    RoutePreviewFailure.SERVER -> "Il calcolo dell'itinerario CNG non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito un itinerario non valido."
}
