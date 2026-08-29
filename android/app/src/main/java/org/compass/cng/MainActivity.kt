package org.compass.cng

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.compass.cng.ui.route.RoutePlannerScreen
import org.compass.cng.ui.route.RoutePlannerViewModel
import org.compass.cng.ui.theme.CompassTheme

class MainActivity : ComponentActivity() {
    private val routePlannerViewModel: RoutePlannerViewModel by viewModels {
        val application = application as CompassApplication
        RoutePlannerViewModel.Factory(application.container.routingRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompassTheme {
                RoutePlannerScreen(viewModel = routePlannerViewModel)
            }
        }
    }
}
