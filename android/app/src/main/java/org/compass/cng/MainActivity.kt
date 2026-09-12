package org.compass.cng

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.navigation.NavigationForegroundService
import org.compass.cng.navigation.FollowLocationPolicy
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.ui.route.PlannerStage
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
            serverConnectionRepository = application.container.serverConnectionRepository,
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var followPollingActive = false
    private var registeredFollowProviders: Set<String> = emptySet()
    private val followCurrentLocationSignals = mutableListOf<CancellationSignal>()
    private val followLocationPoll = object : Runnable {
        override fun run() {
            if (!followPollingActive) return
            refreshFollowProviderSubscriptions()
            requestFreshFollowLocation()
            mainHandler.postDelayed(this, FollowLocationPolicy.POLL_INTERVAL_MILLIS)
        }
    }

    private val followLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.isUsableFollowFix(System.currentTimeMillis())) {
                routePlannerViewModel.updateFollowLocation(location.toNavigationLocation())
            }
        }

        override fun onProviderEnabled(provider: String) {
            mainHandler.post { refreshFollowProviderSubscriptions() }
        }

        override fun onProviderDisabled(provider: String) {
            mainHandler.post { refreshFollowProviderSubscriptions() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val plannerState by routePlannerViewModel.uiState.collectAsStateWithLifecycle()
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
                if (locationGranted) {
                    startFollowLocationUpdates()
                    useCurrentLocation()
                }
                else routePlannerViewModel.currentLocationUnavailable()
            }
            val followPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (locationGranted) startFollowLocationUpdates()
                else routePlannerViewModel.currentLocationUnavailable()
            }
            LaunchedEffect(Unit) {
                if (routePlannerViewModel.shouldResumeRestoredNavigation) {
                    if (hasNavigationPermissions()) {
                        startNavigationService(NavigationForegroundService.ACTION_START)
                    } else {
                        pendingStartAction = NavigationForegroundService.ACTION_START
                        navigationPermissionLauncher.launch(navigationPermissions)
                    }
                }
            }
            LaunchedEffect(plannerState.stage) {
                if (
                    plannerState.stage == PlannerStage.FOLLOW &&
                    !routePlannerViewModel.shouldResumeRestoredNavigation
                ) {
                    if (hasLocationPermission()) startFollowLocationUpdates()
                    else followPermissionLauncher.launch(locationPermissions)
                }
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
                    onVoiceGuidanceEnabledChange = { enabled ->
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, NavigationForegroundService::class.java).apply {
                                action = NavigationForegroundService.ACTION_SET_VOICE_GUIDANCE
                                putExtra(
                                    NavigationForegroundService.EXTRA_VOICE_GUIDANCE_ENABLED,
                                    enabled,
                                )
                            },
                        )
                    },
                    onCompleteFuelStop = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, NavigationForegroundService::class.java).apply {
                                action = NavigationForegroundService.ACTION_COMPLETE_FUEL_STOP
                            },
                        )
                    },
                    onCompleteIntermediateStop = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, NavigationForegroundService::class.java).apply {
                                action = NavigationForegroundService
                                    .ACTION_COMPLETE_INTERMEDIATE_STOP
                            },
                        )
                    },
                    onUseCurrentLocation = { endpoint ->
                        routePlannerViewModel.currentLocationRequested(endpoint)
                        if (hasLocationPermission()) {
                            useCurrentLocation()
                        } else {
                            locationPermissionLauncher.launch(locationPermissions)
                        }
                    },
                    onStopNavigation = ::stopNavigationService,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (
            hasLocationPermission() &&
            routePlannerViewModel.uiState.value.stage == PlannerStage.FOLLOW
        ) {
            startFollowLocationUpdates()
        }
    }

    override fun onStop() {
        stopFollowLocationUpdates()
        super.onStop()
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
    private fun useCurrentLocation() {
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
                        routePlannerViewModel.useCurrentLocation(
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
        stopFollowLocationUpdates()
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
        startFollowLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startFollowLocationUpdates() {
        if (!hasLocationPermission()) return
        followPollingActive = true
        refreshFollowProviderSubscriptions()
        requestFreshFollowLocation(force = true)
        mainHandler.removeCallbacks(followLocationPoll)
        mainHandler.postDelayed(followLocationPoll, FollowLocationPolicy.POLL_INTERVAL_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun refreshFollowProviderSubscriptions() {
        if (!followPollingActive || !hasLocationPermission()) return
        val manager = getSystemService(LocationManager::class.java)
        val enabledProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter(manager::isProviderEnabled).toSet()
        if (enabledProviders.isEmpty()) {
            manager.removeUpdates(followLocationListener)
            registeredFollowProviders = emptySet()
            routePlannerViewModel.followLocationUnavailable()
            return
        }
        try {
            if (enabledProviders != registeredFollowProviders) {
                manager.removeUpdates(followLocationListener)
                enabledProviders.forEach { provider ->
                    manager.requestLocationUpdates(
                        provider,
                        FOLLOW_LOCATION_INTERVAL_MILLIS,
                        FOLLOW_LOCATION_MINIMUM_DISTANCE_METERS,
                        followLocationListener,
                        Looper.getMainLooper(),
                    )
                }
                registeredFollowProviders = enabledProviders
            }
            val now = System.currentTimeMillis()
            enabledProviders.mapNotNull(manager::getLastKnownLocation)
                .filter { it.isUsableFollowFix(now) }
                .maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })
                ?.let { routePlannerViewModel.updateFollowLocation(it.toNavigationLocation()) }
        } catch (_: SecurityException) {
            routePlannerViewModel.currentLocationUnavailable()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshFollowLocation(force: Boolean = false) {
        if (!followPollingActive || !hasLocationPermission()) return
        val current = routePlannerViewModel.uiState.value.followLocation
        if (!force && !FollowLocationPolicy.shouldRepoll(current, System.currentTimeMillis())) return
        val manager = getSystemService(LocationManager::class.java)
        val providers = registeredFollowProviders.ifEmpty {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter(manager::isProviderEnabled)
                .toSet()
        }
        followCurrentLocationSignals.forEach(CancellationSignal::cancel)
        followCurrentLocationSignals.clear()
        try {
            providers.forEach { provider ->
                val signal = CancellationSignal()
                followCurrentLocationSignals += signal
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(this),
                ) { location ->
                    if (location?.isUsableFollowFix(System.currentTimeMillis()) == true) {
                        routePlannerViewModel.updateFollowLocation(location.toNavigationLocation())
                    }
                }
            }
        } catch (_: SecurityException) {
            routePlannerViewModel.currentLocationUnavailable()
        }
    }

    private fun stopFollowLocationUpdates() {
        followPollingActive = false
        mainHandler.removeCallbacks(followLocationPoll)
        followCurrentLocationSignals.forEach(CancellationSignal::cancel)
        followCurrentLocationSignals.clear()
        getSystemService(LocationManager::class.java).removeUpdates(followLocationListener)
        registeredFollowProviders = emptySet()
    }

    private fun Location.toNavigationLocation() = NavigationLocation(
        coordinate = Coordinate(latitude, longitude),
        accuracyMeters = accuracy.toDouble(),
        speedMetersPerSecond = speed.takeIf { hasSpeed() }?.toDouble(),
        bearingDegrees = bearing.takeIf { hasBearing() }?.toDouble(),
        timestampEpochMillis = time,
        receivedAtEpochMillis = System.currentTimeMillis(),
    )

    private fun Location.isUsableFollowFix(nowEpochMillis: Long): Boolean =
        latitude.isFinite() && longitude.isFinite() && accuracy.isFinite() &&
            accuracy in 0f..FollowLocationPolicy.MAXIMUM_ACCURACY_METERS.toFloat() &&
            time in (nowEpochMillis - FollowLocationPolicy.SEARCH_ORIGIN_MAX_AGE_MILLIS)..
            (nowEpochMillis + FollowLocationPolicy.MAXIMUM_FUTURE_SKEW_MILLIS)

    private companion object {
        const val FOLLOW_LOCATION_INTERVAL_MILLIS = 1_000L
        const val FOLLOW_LOCATION_MINIMUM_DISTANCE_METERS = 0f
    }
}
