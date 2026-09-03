package org.compass.cng

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.compass.cng.domain.model.Coordinate
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
            vehicleProfileRepository = application.container.vehicleProfileRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navigationPermissions = remember {
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            }
            val locationPermissions = remember {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            }
            val navigationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                val locationGranted = hasLocationPermission()
                val notificationsGranted = hasNotificationPermission()
                if (locationGranted && notificationsGranted) {
                    startNavigationService()
                } else {
                    routePlannerViewModel.navigationPermissionDenied(
                        locationGranted = locationGranted,
                        notificationsGranted = notificationsGranted,
                    )
                }
            }
            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (locationGranted) useCurrentLocationAsOrigin()
                else routePlannerViewModel.currentLocationUnavailable()
            }
            CompassTheme {
                RoutePlannerScreen(
                    viewModel = routePlannerViewModel,
                    onStartNavigation = {
                        if (hasNavigationPermissions()) {
                            startNavigationService(NavigationForegroundService.ACTION_START)
                        } else {
                            pendingStartAction = NavigationForegroundService.ACTION_START
                            navigationPermissionLauncher.launch(navigationPermissions)
                        }
                    },
                    onStartNavigationReplay = {
                        if (hasNavigationPermissions()) {
                            startNavigationService(NavigationForegroundService.ACTION_START_REPLAY)
                        } else {
                            pendingStartAction = NavigationForegroundService.ACTION_START_REPLAY
                            navigationPermissionLauncher.launch(navigationPermissions)
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
                    onUseCurrentLocation = {
                        routePlannerViewModel.currentLocationRequested()
                        if (hasLocationPermission()) {
                            useCurrentLocationAsOrigin()
                        } else {
                            locationPermissionLauncher.launch(locationPermissions)
                        }
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

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNavigationPermissions(): Boolean =
        hasLocationPermission() && hasNotificationPermission()

    private var pendingStartAction: String = NavigationForegroundService.ACTION_START

    @SuppressLint("MissingPermission")
    private fun useCurrentLocationAsOrigin() {
        if (!hasLocationPermission()) {
            routePlannerViewModel.currentLocationUnavailable()
            return
        }
        val manager = getSystemService(LocationManager::class.java)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter(manager::isProviderEnabled)
        if (providers.isEmpty()) {
            routePlannerViewModel.currentLocationUnavailable()
            return
        }
        val delivered = AtomicBoolean(false)
        val pending = AtomicInteger(providers.size)
        val cancellationSignals = providers.associateWith { CancellationSignal() }
        try {
            providers.forEach { provider ->
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    requireNotNull(cancellationSignals[provider]),
                    ContextCompat.getMainExecutor(this),
                ) { location ->
                    if (location != null && delivered.compareAndSet(false, true)) {
                        cancellationSignals.values.forEach(CancellationSignal::cancel)
                        routePlannerViewModel.useCurrentLocationAsOrigin(
                            Coordinate(location.latitude, location.longitude),
                        )
                    } else if (pending.decrementAndGet() == 0 && !delivered.get()) {
                        routePlannerViewModel.currentLocationUnavailable()
                    }
                }
            }
        } catch (_: SecurityException) {
            // Runtime permission may be revoked between the explicit check and this call.
            routePlannerViewModel.currentLocationUnavailable()
        }
    }

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
