package org.compass.cng.enforcement

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RouteTravelMode
import org.compass.cng.navigation.*

/** Runs on Main; HTTP/cache work is dispatched separately. No GPS registration or stored trace. */
class ApproachCoordinator(
    private val repository: EnforcementRepository,
    private val fetch: suspend (ApproachTrace) -> ApproachResult,
    private val owner: () -> String,
    private val navigation: () -> NavigationState,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val engine = ApproachEngine()
    private val mutableNotice = MutableStateFlow<ApproachNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private var requestJob: Job? = null
    private var packageJob: Job? = null
    private var lastAttempt = Long.MIN_VALUE / 2
    private var lastPackageCheck = Long.MIN_VALUE / 2
    private var interval = 5_000L
    private var latest: NavigationLocation? = null

    fun update(fix: NavigationLocation?) {
        latest = fix
        val clock = now()
        val nav = navigation()
        val navigating = nav.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.ROUTE_PREVIEW)
        val allowed = allowed(nav)
        val identity = owner()
        val graph = repository.state.value.data?.graphIdentity.orEmpty()
        val context = "$identity:$graph:${if (navigating) nav.route?.routeId else "follow"}"
        engine.update(fix, clock, context, allowed)
        mutableNotice.value = engine.notice
        if (!allowed || fix == null) return
        if (clock - lastPackageCheck >= 5_000 && packageJob?.isActive != true) {
            lastPackageCheck = clock
            packageJob = scope.launch { withContext(ioDispatcher) { repository.update(fix) } }
        }
        val data = repository.state.value.data ?: return
        val nearby = data.controls.any { it.directedPath != null &&
            distanceMeters(fix.coordinate, Coordinate(it.latitude, it.longitude)) <= 1500 }
        if (!nearby || requestJob?.isActive == true || clock - lastAttempt < interval) return
        val trace = engine.request() ?: return
        val generation = engine.generation
        val routeId = if (navigating) nav.route?.routeId else null
        lastAttempt = clock
        requestJob = scope.launch {
            try {
                val result = fetch(trace)
                if (owner() != identity || repository.state.value.data?.graphIdentity != graph ||
                    now() - clock !in 0..10_000) { engine.invalidate(); return@launch }
                if (engine.generation != generation) return@launch
                val current = navigation()
                val currentRouteId = if (current.phase in setOf(NavigationPhase.IDLE,
                    NavigationPhase.ROUTE_PREVIEW)) null else current.route?.routeId
                if (!allowed(current) || currentRouteId != routeId) return@launch
                val routeAhead = if (routeId != null) current.route?.geometry?.let { geometry ->
                    var start = current.navigationPosition?.routeSegmentIndex ?: return@launch
                    var behind = 0.0
                    while (start > 0 && behind < 150) {
                        behind += distanceMeters(geometry[start], geometry[start - 1])
                        start--
                    }
                    // A short route window, not a full-route scan on every GPS fix.
                    val window = mutableListOf<Coordinate>()
                    var length = 0.0
                    for (i in start.coerceAtLeast(0)..geometry.lastIndex) {
                        if (window.isNotEmpty()) length += distanceMeters(window.last(), geometry[i])
                        window.add(geometry[i])
                        if (length > 2000) break
                    }
                    window
                } else null
                engine.accept(result, generation, graph, now(), routeAhead)
                interval = 5_000
                engine.update(latest, now(), context, allowed)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                // Only transient connectivity may retain a continuously followed, recently verified corridor.
                if (error !is ApiClientException.Network) engine.invalidate()
                interval = (interval * 2).coerceAtMost(60_000)
            } finally { mutableNotice.value = engine.notice }
        }
    }

    private fun allowed(state: NavigationState): Boolean =
        state.phase in setOf(NavigationPhase.IDLE, NavigationPhase.ROUTE_PREVIEW) ||
            (state.phase in setOf(NavigationPhase.NAVIGATING, NavigationPhase.APPROACHING_MANEUVER,
                NavigationPhase.APPROACHING_FUEL_STOP) && state.offRouteStatus == OffRouteStatus.ON_ROUTE &&
                state.route?.travelMode == RouteTravelMode.DRIVING)

    fun claimSpeech(enabled: Boolean): VoiceAnnouncement? = engine.claimSpeech(enabled)
}
