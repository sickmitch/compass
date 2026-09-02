package org.compass.cng

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import org.compass.cng.navigation.NavigationForegroundService
import org.compass.cng.ui.route.RoutePlannerScreen
import org.compass.cng.ui.route.RoutePlannerViewModel
import org.compass.cng.ui.theme.CompassTheme

class MainActivity : ComponentActivity() {
    private val routePlannerViewModel: RoutePlannerViewModel by viewModels {
        val application = application as CompassApplication
        RoutePlannerViewModel.Factory(
            routingRepository = application.container.routingRepository,
            navigationSession = application.container.navigationSession,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val requestedPermissions = remember {
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (locationGranted) startNavigationService() else {
                    routePlannerViewModel.navigationPermissionDenied()
                }
            }
            CompassTheme {
                RoutePlannerScreen(
                    viewModel = routePlannerViewModel,
                    onStartNavigation = {
                        if (hasLocationPermission()) {
                            startNavigationService(NavigationForegroundService.ACTION_START)
                        } else {
                            pendingStartAction = NavigationForegroundService.ACTION_START
                            permissionLauncher.launch(requestedPermissions)
                        }
                    },
                    onStartNavigationReplay = {
                        if (hasLocationPermission()) {
                            startNavigationService(NavigationForegroundService.ACTION_START_REPLAY)
                        } else {
                            pendingStartAction = NavigationForegroundService.ACTION_START_REPLAY
                            permissionLauncher.launch(requestedPermissions)
                        }
                    },
                    onRequestRouteUpdate = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, NavigationForegroundService::class.java).apply {
                                action = NavigationForegroundService.ACTION_TRIGGER_ROUTE_UPDATE
                            },
                        )
                    },
                    onSimulateOffRoute = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, NavigationForegroundService::class.java).apply {
                                action = NavigationForegroundService.ACTION_SIMULATE_OFF_ROUTE
                            },
                        )
                    },
                    onReplaceUnavailableFuelStop = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, NavigationForegroundService::class.java).apply {
                                action = NavigationForegroundService
                                    .ACTION_REPLACE_UNAVAILABLE_FUEL_STOP
                            },
                        )
                    },
                    onStopNavigation = ::stopNavigationService,
                )
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private var pendingStartAction: String = NavigationForegroundService.ACTION_START

    private fun startNavigationService(action: String = pendingStartAction) {
        pendingStartAction = NavigationForegroundService.ACTION_START
        routePlannerViewModel.startNavigation()
        ContextCompat.startForegroundService(
            this,
            Intent(this, NavigationForegroundService::class.java).apply {
                this.action = action
            },
        )
    }

    private fun stopNavigationService() {
        routePlannerViewModel.stopNavigation()
        stopService(Intent(this, NavigationForegroundService::class.java))
    }
}
