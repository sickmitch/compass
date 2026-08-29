package org.compass.cng.ui.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.compass.cng.domain.RoutePreviewException
import org.compass.cng.domain.RoutePreviewFailure
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.RoutePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutePreviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesLoadedRoute() = runTest {
        val route = sampleRoute()
        val viewModel = RoutePreviewViewModel(FakeRoutingRepository(result = Result.success(route)))

        assertEquals(RoutePreviewUiState.Content(route), viewModel.uiState.value)
    }

    @Test
    fun mapsNetworkFailureToStableUserMessage() = runTest {
        val viewModel = RoutePreviewViewModel(
            FakeRoutingRepository(
                result = Result.failure(RoutePreviewException(RoutePreviewFailure.NETWORK)),
            ),
        )

        assertEquals(
            RoutePreviewUiState.Error("Impossibile contattare il server Compass."),
            viewModel.uiState.value,
        )
    }

    private class FakeRoutingRepository(
        private val result: Result<RoutePreview>,
    ) : RoutingRepository {
        override suspend fun previewRoute(
            origin: Coordinate,
            destination: Coordinate,
        ): RoutePreview = result.getOrThrow()
    }

    private fun sampleRoute() = RoutePreview(
        origin = RoutePreviewViewModel.MILAN,
        destination = RoutePreviewViewModel.BOLOGNA,
        distanceMeters = 210_925.0,
        durationSeconds = 6_773.406,
        geometry = listOf(RoutePreviewViewModel.MILAN, RoutePreviewViewModel.BOLOGNA),
        maneuvers = listOf(
            Maneuver(
                type = 1,
                instruction = "Parti verso sud.",
                distanceMeters = 100.0,
                durationSeconds = 18.0,
                beginShapeIndex = 0,
                endShapeIndex = 1,
                streetNames = listOf("Via Roma"),
                travelMode = "drive",
                travelType = "car",
            ),
        ),
        provider = "valhalla",
    )
}
