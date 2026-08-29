package org.compass.cng.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import org.compass.cng.domain.model.RoutePreview

sealed interface RoutePreviewUiState {
    data object Loading : RoutePreviewUiState
    data class Content(val route: RoutePreview) : RoutePreviewUiState
    data class Error(val message: String) : RoutePreviewUiState
}

class RoutePreviewViewModel(
    private val routingRepository: RoutingRepository,
    private val origin: Coordinate = MILAN,
    private val destination: Coordinate = BOLOGNA,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<RoutePreviewUiState>(RoutePreviewUiState.Loading)
    val uiState: StateFlow<RoutePreviewUiState> = mutableUiState.asStateFlow()

    private var routeJob: Job? = null

    init {
        loadRoute()
    }

    fun retry() = loadRoute()

    private fun loadRoute() {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            mutableUiState.value = RoutePreviewUiState.Loading
            try {
                mutableUiState.value = RoutePreviewUiState.Content(
                    routingRepository.previewRoute(origin, destination),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RoutePreviewException) {
                mutableUiState.value = RoutePreviewUiState.Error(error.failure.userMessage())
            } catch (_: Exception) {
                mutableUiState.value = RoutePreviewUiState.Error(
                    RoutePreviewFailure.INVALID_RESPONSE.userMessage(),
                )
            }
        }
    }

    class Factory(
        private val routingRepository: RoutingRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RoutePreviewViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return RoutePreviewViewModel(routingRepository) as T
        }
    }

    companion object {
        val MILAN = Coordinate(latitude = 45.4642, longitude = 9.1900)
        val BOLOGNA = Coordinate(latitude = 44.4949, longitude = 11.3426)
    }
}

private fun RoutePreviewFailure.userMessage(): String = when (this) {
    RoutePreviewFailure.NETWORK -> "Impossibile contattare il server Compass."
    RoutePreviewFailure.NO_ROUTE -> "Nessun percorso disponibile tra Milano e Bologna."
    RoutePreviewFailure.SERVER -> "Il servizio di routing non è disponibile."
    RoutePreviewFailure.INVALID_RESPONSE -> "Il server ha restituito un percorso non valido."
}
