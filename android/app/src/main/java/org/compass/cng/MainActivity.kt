package org.compass.cng

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.compass.cng.ui.route.RoutePreviewScreen
import org.compass.cng.ui.route.RoutePreviewViewModel
import org.compass.cng.ui.theme.CompassTheme

class MainActivity : ComponentActivity() {
    private val routePreviewViewModel: RoutePreviewViewModel by viewModels {
        val application = application as CompassApplication
        RoutePreviewViewModel.Factory(application.container.routingRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompassTheme {
                RoutePreviewScreen(viewModel = routePreviewViewModel)
            }
        }
    }
}
