package org.compass.cng.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.compass.cng.CompassApplication
import org.compass.cng.MainActivity
import org.compass.cng.R
import org.compass.cng.domain.model.Coordinate

/** Owns device location updates while the UI is recreated, backgrounded or the screen is off. */
class NavigationForegroundService : Service(), LocationListener {
    private lateinit var session: NavigationSession
    private lateinit var routeRecalculator: NavigationRouteRecalculator
    private lateinit var voiceGuidance: VoiceGuidance
    private lateinit var locationManager: LocationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val maneuverController = ManeuverController()
    private val routeUpdateController = RouteUpdateController()
    private val handler = Handler(Looper.getMainLooper())
    private val replayLifecycle = ReplayLifecycleController()
    private var replayRunnable: Runnable? = null
    private var routeUpdateJob: Job? = null
    private var updateControllerStarted = false
    private val gpsLossCheck = object : Runnable {
        override fun run() {
            session.tick(System.currentTimeMillis())
            processNavigationState(System.currentTimeMillis())
            handler.postDelayed(this, GPS_LOSS_CHECK_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as CompassApplication).container
        session = container.navigationSession
        routeRecalculator = container.navigationRouteRecalculator
        voiceGuidance = AndroidTextToSpeechVoiceGuidance(this)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        if (session.state.value.route == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (intent?.action == ACTION_TRIGGER_ROUTE_UPDATE) {
            requestRouteUpdate(RouteUpdateReason.MANUAL_DEBUG, System.currentTimeMillis())
            return START_STICKY
        }
        if (intent?.action == ACTION_REPLACE_UNAVAILABLE_FUEL_STOP) {
            requestFuelStopReplacement(System.currentTimeMillis())
            return START_STICKY
        }
        if (intent?.action == ACTION_SIMULATE_OFF_ROUTE) {
            simulateOffRoute()
            return START_STICKY
        }
        session.start()
        if (!updateControllerStarted) {
            routeUpdateController.navigationStarted(System.currentTimeMillis())
            updateControllerStarted = true
        }
        if (intent?.action == ACTION_START_REPLAY) {
            replayLifecycle.navigationStarted(replay = true)
            startRouteReplay()
        } else {
            replayLifecycle.navigationStarted(replay = false)
            startLocationUpdates()
        }
        handler.removeCallbacks(gpsLossCheck)
        handler.post(gpsLossCheck)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            session.stopToPreview()
            stopSelf()
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.filter(locationManager::isProviderEnabled).forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_UPDATE_INTERVAL_MILLIS,
                0f,
                this,
                Looper.getMainLooper(),
            )
        }
    }

    override fun onLocationChanged(location: Location) {
        val speed = location.speed.takeIf { location.hasSpeed() && it >= 0f }?.toDouble()
        val bearing = location.bearing.takeIf { location.hasBearing() }?.toDouble()
        session.updateLocation(
            NavigationLocation(
                coordinate = Coordinate(location.latitude, location.longitude),
                accuracyMeters = location.accuracy.toDouble(),
                speedMetersPerSecond = speed,
                bearingDegrees = bearing,
                timestampEpochMillis = location.time,
            ),
        )
        processNavigationState(System.currentTimeMillis())
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(gpsLossCheck)
        replayRunnable?.let(handler::removeCallbacks)
        replayRunnable = null
        routeUpdateJob?.cancel()
        if (session.state.value.reroutingStatus == ReroutingStatus.IN_PROGRESS) {
            session.failRouteUpdate()
        }
        voiceGuidance.shutdown()
        serviceScope.cancel()
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    private fun startRouteReplay() {
        locationManager.removeUpdates(this)
        replayRunnable?.let(handler::removeCallbacks)
        val route = session.state.value.route ?: return
        val indexes = buildList {
            var index = 0
            while (index < route.geometry.lastIndex) {
                add(index)
                index += REPLAY_SHAPE_INDEX_STEP
            }
            if (lastOrNull() != route.geometry.lastIndex) add(route.geometry.lastIndex)
        }
        var position = 0
        var virtualTimestamp = System.currentTimeMillis()
        var previousCoordinate = route.geometry.first()
        val runnable = object : Runnable {
            override fun run() {
                if (position >= indexes.size) return
                val shapeIndex = indexes[position]
                val coordinate = route.geometry[shapeIndex]
                val next = route.geometry.getOrElse(shapeIndex + 1) { coordinate }
                if (position > 0) {
                    virtualTimestamp += maxOf(
                        1_000L,
                        (distanceMeters(previousCoordinate, coordinate) / REPLAY_SPEED_METERS_PER_SECOND *
                            1_000).toLong(),
                    )
                }
                session.updateLocation(
                    NavigationLocation(
                        coordinate = coordinate,
                        accuracyMeters = 4.0,
                        speedMetersPerSecond = REPLAY_SPEED_METERS_PER_SECOND,
                        bearingDegrees = if (coordinate == next) null else {
                            bearingDegrees(coordinate, next)
                        },
                        timestampEpochMillis = virtualTimestamp,
                    ),
                )
                processNavigationState(System.currentTimeMillis())
                previousCoordinate = coordinate
                position += 1
                if (position < indexes.size) handler.postDelayed(this, REPLAY_INTERVAL_MILLIS)
            }
        }
        replayRunnable = runnable
        handler.post(runnable)
    }

    private fun stopNavigation() {
        session.stopToPreview()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processNavigationState(nowEpochMillis: Long) {
        val state = session.state.value
        maneuverController.nextAnnouncement(state)?.let { announcement ->
            voiceGuidance.speak(announcement)
            session.recordSpokenInstruction(announcement.text)
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(),
        )
        if (!replayLifecycle.isReplayActive) {
            routeUpdateController.nextUpdate(state, nowEpochMillis)?.let { reason ->
                requestRouteUpdate(reason, nowEpochMillis)
            }
        }
    }

    private fun requestRouteUpdate(reason: RouteUpdateReason, nowEpochMillis: Long) {
        if (routeUpdateJob?.isActive == true) return
        val snapshot = session.state.value
        if (snapshot.route == null) return
        routeUpdateController.attemptStarted(nowEpochMillis)
        session.beginRouteUpdate(reason)
        Log.i(
            LOG_TAG,
            "route update started: $reason stops=${snapshot.route.fuelStopIdsForLog()}",
        )
        routeUpdateJob = serviceScope.launch {
            try {
                val route = routeRecalculator.recalculate(snapshot, reason)
                val completedAt = System.currentTimeMillis()
                session.replaceRoute(route, completedAt, snapshot.rawLocation)
                routeUpdateController.updateSucceeded(completedAt)
                maneuverController.reset()
                Log.i(
                    LOG_TAG,
                    "route update committed: $reason route=${route.routeId} " +
                        "stops=${route.fuelStopIdsForLog()}",
                )
                resumeReplayAfterRouteUpdateIfNeeded()
                processNavigationState(completedAt)
            } catch (error: CancellationException) {
                session.failRouteUpdate()
                throw error
            } catch (_: Exception) {
                session.failRouteUpdate()
                routeUpdateController.updateFailed()
                Log.w(LOG_TAG, "route update failed: $reason; continuing downloaded route")
                resumeReplayAfterRouteUpdateIfNeeded()
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(),
                )
            }
        }
    }

    private fun requestFuelStopReplacement(nowEpochMillis: Long) {
        if (routeUpdateJob?.isActive == true) return
        val snapshot = session.state.value
        val unavailable = snapshot.nextFuelStop?.stop ?: return
        routeUpdateController.attemptStarted(nowEpochMillis)
        session.beginRouteUpdate(RouteUpdateReason.FUEL_STOP_UNAVAILABLE)
        Log.i(LOG_TAG, "fuel stop replacement started: station=${unavailable.mimitStationId}")
        routeUpdateJob = serviceScope.launch {
            try {
                when (val result = routeRecalculator.replaceUnavailableFuelStop(snapshot)) {
                    is FuelStopReplacementResult.Replaced -> {
                        val completedAt = System.currentTimeMillis()
                        session.replaceRoute(result.route, completedAt, snapshot.rawLocation)
                        routeUpdateController.updateSucceeded(completedAt)
                        maneuverController.reset()
                        if (replayLifecycle.isReplayActive) startRouteReplay()
                        Log.i(
                            LOG_TAG,
                            "fuel stop replacement committed: excluded=" +
                                "${result.excludedMimitStationId} route=${result.route.routeId} " +
                                "stops=${result.route.fuelStops.joinToString(",") { stop ->
                                    stop.mimitStationId
                                }.ifEmpty { "direct" }}",
                        )
                        processNavigationState(completedAt)
                    }
                    FuelStopReplacementResult.NoSafeAlternative -> {
                        session.failRouteUpdate(RouteUpdateFailure.NO_SAFE_FUEL_ALTERNATIVE)
                        routeUpdateController.updateFailed()
                        Log.w(
                            LOG_TAG,
                            "fuel stop replacement unavailable: no complete safe itinerary",
                        )
                    }
                    FuelStopReplacementResult.RangePlanRequired -> {
                        session.failRouteUpdate(RouteUpdateFailure.FUEL_RANGE_PLAN_REQUIRED)
                        routeUpdateController.updateFailed()
                        Log.w(LOG_TAG, "fuel stop replacement unavailable: range plan required")
                    }
                }
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(),
                )
            } catch (error: CancellationException) {
                session.failRouteUpdate()
                throw error
            } catch (_: Exception) {
                session.failRouteUpdate()
                routeUpdateController.updateFailed()
                Log.w(
                    LOG_TAG,
                    "fuel stop replacement failed; retaining downloaded route",
                )
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(),
                )
            }
        }
    }

    private fun simulateOffRoute() {
        val state = session.state.value
        val reference = state.rawLocation ?: return
        replayLifecycle.simulatedOffRouteStarted()
        replayRunnable?.let(handler::removeCallbacks)
        replayRunnable = null
        repeat(3) { index ->
            val offset = 0.0015 + index * 0.0001
            val fix = NavigationLocation(
                coordinate = Coordinate(
                    latitude = reference.coordinate.latitude + offset,
                    longitude = reference.coordinate.longitude + offset,
                ),
                accuracyMeters = 4.0,
                speedMetersPerSecond = 12.0,
                bearingDegrees = normalizeBearing((reference.bearingDegrees ?: 0.0) + 180.0),
                timestampEpochMillis = reference.timestampEpochMillis + (index + 1) * 10_000L,
            )
            session.updateLocation(fix)
            processNavigationState(System.currentTimeMillis())
        }
    }

    private fun resumeReplayAfterRouteUpdateIfNeeded() {
        if (!replayLifecycle.routeUpdateFinished()) return
        Log.i(LOG_TAG, "demo replay resumed after route update")
        startRouteReplay()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.navigation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_compass)
            .setContentTitle(getString(R.string.navigation_notification_title))
            .setContentText(
                session.state.value.currentManeuver?.instruction
                    ?: getString(R.string.navigation_notification_text),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(0, getString(R.string.navigation_notification_stop), pendingStop)
            .build()
    }

    companion object {
        const val ACTION_START = "org.compass.cng.navigation.START"
        const val ACTION_START_REPLAY = "org.compass.cng.navigation.START_REPLAY"
        const val ACTION_TRIGGER_ROUTE_UPDATE = "org.compass.cng.navigation.TRIGGER_ROUTE_UPDATE"
        const val ACTION_REPLACE_UNAVAILABLE_FUEL_STOP =
            "org.compass.cng.navigation.REPLACE_UNAVAILABLE_FUEL_STOP"
        const val ACTION_SIMULATE_OFF_ROUTE = "org.compass.cng.navigation.SIMULATE_OFF_ROUTE"
        const val ACTION_STOP = "org.compass.cng.navigation.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "compass_navigation"
        private const val NOTIFICATION_ID = 4_201
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val GPS_LOSS_CHECK_INTERVAL_MILLIS = 5_000L
        private const val REPLAY_INTERVAL_MILLIS = 1_500L
        private const val REPLAY_SHAPE_INDEX_STEP = 1
        private const val REPLAY_SPEED_METERS_PER_SECOND = 22.0
        private const val LOG_TAG = "CompassNavigation"
    }
}

private fun NavigationRoute.fuelStopIdsForLog(): String =
    fuelStops.joinToString(",") { it.mimitStationId }.ifEmpty { "direct" }
