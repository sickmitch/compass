package org.compass.cng.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*

/** Only while the activity is visible: one readiness check every 30 seconds, no extra GPS. */
class BackendAvailabilityMonitor(
    context: Context,
    private val availability: BackendAvailability,
    private val probe: suspend () -> Boolean,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateNetwork()
    }
    private fun updateNetwork() {
        // A LAN-only connection can still reach a self-hosted Compass server.
        availability.networkChanged(manager.activeNetwork != null)
    }
    fun start() {
        if (job != null) return
        updateNetwork()
        manager.registerDefaultNetworkCallback(callback)
        job = scope.launch {
            while (isActive) {
                updateNetwork()
                if (manager.activeNetwork != null) {
                    val ready = try { withTimeoutOrNull(5_000) { probe() } ?: false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                    availability.serverResponded(ready)
                }
                delay(30_000)
            }
        }
    }
    fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
        manager.unregisterNetworkCallback(callback)
    }
}
