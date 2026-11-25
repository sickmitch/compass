package org.compass.cng.offline

import android.util.Log
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.compass.cng.domain.server.ServerConnection

data class OfflineDownloadStatus(val catalog: RemoteOfflineCatalog = RemoteOfflineCatalog(),
    val subscribed: Set<String> = emptySet(), val busy: Boolean = false, val message: String? = null,
    val confirmation: OfflineMobileConfirmation? = null)

class OfflineAssetManager(private val context: Context, private val routing: OfflineRoutingController,
    private val connection: () -> ServerConnection, private val client: OfflineAssetClient,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(OfflineDownloadStatus())
    val status = mutableStatus.asStateFlow()
    private var displayedOwner: String? = null
    private val preferences = context.getSharedPreferences("offline-download-schedule", Context.MODE_PRIVATE)

    fun refresh() { scope.launch { guarded { refreshLocked(connection()) } } }
    fun subscribe(area: String, enabled: Boolean) {
        if (enabled) manualUpdate(area) else scope.launch { guarded {
            routing.subscribe(offlineOwner(connection()), area, false)
            refreshLocked(connection())
        } }
    }
    fun updateNow() = manualUpdate(null)
    fun dismissMobileConfirmation() { mutableStatus.value = mutableStatus.value.copy(confirmation = null) }
    fun confirmMobileDownload() {
        val approval = mutableStatus.value.confirmation ?: return
        dismissMobileConfirmation()
        manualUpdate(approval.newArea, approval)
    }
    private fun mobileConfirmationRequired(bytes: Long): Boolean {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val mobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        return needsOfflineMobileConfirmation(wifi, network.isActiveNetworkMetered, bytes, mobile)
    }
    private fun manualUpdate(newArea: String?, approval: OfflineMobileConfirmation? = null) {
        scope.launch { guarded {
            val server = connection()
            val owner = offlineOwner(server)
            val catalog = refreshLocked(server)
            val subscriptions = routing.subscriptions().filter { it.owner == owner }
            val selected = subscriptions.map { it.area }.toSet() + listOfNotNull(newArea)
            val bytes = catalog.packages.filter { item -> item.area in selected && subscriptions.none {
                it.area == item.area && it.sha256 == item.sha256 && routing.status.value.packages.any { p -> p.id == it.datasetId && p.usable }
            } }.sumOf { it.bytes }
            val approved = approval?.takeIf { it.owner == owner && it.bytes >= bytes }
            if (mobileConfirmationRequired(bytes) && approved == null) {
                mutableStatus.value = mutableStatus.value.copy(confirmation = OfflineMobileConfirmation(owner, bytes, newArea))
                return@guarded
            }
            if (newArea != null) routing.subscribe(owner, newArea, true)
            syncLocked(server, approved)
        } }
    }
    fun clearSubscriptionForRemovedPackage() { refresh() }

    private suspend fun guarded(block: suspend () -> Unit) = mutex.withLock {
        mutableStatus.value = mutableStatus.value.copy(busy = true, message = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (_: LinkageError) { mutableStatus.value = mutableStatus.value.copy(message = "Motore offline non disponibile") }
        catch (error: Exception) { mutableStatus.value = mutableStatus.value.copy(message = error.message?.take(180) ?: "Aggiornamento non riuscito") }
        finally { mutableStatus.value = mutableStatus.value.copy(busy = false) }
    }

    private suspend fun refreshLocked(server: ServerConnection): RemoteOfflineCatalog {
        val owner = offlineOwner(server)
        if (displayedOwner != owner) {
            mutableStatus.value = OfflineDownloadStatus(busy = true)
            displayedOwner = owner
        }
        mutableStatus.value = mutableStatus.value.copy(subscribed = routing.subscriptions().filter { it.owner == owner }.map { it.area }.toSet())
        val catalog = client.catalog(server)
        check(offlineOwner(connection()) == owner) { "Server cambiato: ricarica il catalogo" }
        mutableStatus.value = mutableStatus.value.copy(catalog = catalog)
        return catalog
    }

    private suspend fun syncLocked(server: ServerConnection, approval: OfflineMobileConfirmation? = null, automatic: Boolean = false): Boolean {
        val owner = offlineOwner(server)
        val subscriptions = routing.subscriptions().filter { it.owner == owner }
        if (subscriptions.isEmpty()) return true
        val catalog = refreshLocked(server)
        check(catalog.packages.isNotEmpty()) { "Il server non ha ancora pubblicato i pacchetti offline" }
        val directory = File(context.noBackupFilesDir, "offline-downloads/$owner").apply { mkdirs() }
        val wanted = subscriptions.map { sub -> catalog.packages.single { it.area == sub.area } }
        val transferBytes = wanted.filter { item -> subscriptions.none { sub ->
            sub.area == item.area && sub.sha256 == item.sha256 && routing.status.value.packages.any { it.id == sub.datasetId && it.usable }
        } }.sumOf { it.bytes }
        fun checkTransfer() {
            check(offlineOwner(connection()) == owner) { "Server cambiato: riprova" }
            if (automatic) {
                val network = context.getSystemService(ConnectivityManager::class.java)
                check(!network.isActiveNetworkMetered && !mobileConfirmationRequired(Long.MAX_VALUE)) { "In attesa di rete Wi-Fi o non mobile" }
            } else if (mobileConfirmationRequired(transferBytes) && (approval?.owner != owner || approval.bytes < transferBytes)) {
                mutableStatus.value = mutableStatus.value.copy(confirmation = OfflineMobileConfirmation(owner, transferBytes))
                error("Download in pausa: conferma l’uso dei dati mobili")
            }
        }
        val filenames = wanted.map { it.sha256 + ".zip.part" }.toSet()
        directory.listFiles()?.filter { it.name !in filenames }?.forEach { it.delete() }
        val files = linkedMapOf<String, File>()
        for (item in wanted) {
            val sub = subscriptions.single { it.area == item.area }
            val installed = routing.status.value.packages.any { it.id == sub.datasetId && it.usable }
            if (sub.sha256 == item.sha256 && installed) continue
            check(offlineOwner(connection()) == owner) { "Server cambiato: riprova" }
            val label = offlineAreaLabels.getValue(item.area)
            mutableStatus.value = mutableStatus.value.copy(message = "Download $label…")
            files[item.area] = client.download(server, item, directory, checkTransfer = ::checkTransfer) { bytes, total ->
                mutableStatus.value = mutableStatus.value.copy(message = "$label · ${bytes * 100 / total}%")
            }
        }
        if (files.isNotEmpty()) {
            mutableStatus.value = mutableStatus.value.copy(message = "Verifica e importazione delle aree…")
            routing.replaceSubscribed(owner, subscriptions, catalog, files) { offlineOwner(connection()) == owner }
        }
        directory.listFiles()?.forEach { it.delete() }
        mutableStatus.value = mutableStatus.value.copy(message = "Aree sottoscritte aggiornate")
        // A delayed Sunday build is retried; an old publication never marks this week's check done.
        val week = offlineUpdateWeek(Instant.now())
        val sunday = week.atZone(java.time.ZoneId.of("Europe/Rome")).minusDays(1).withHour(1).toInstant()
        return Instant.parse(catalog.created_at) >= sunday
    }

    fun nextAutomaticCheck(now: Instant = Instant.now()): Instant {
        val owner = offlineOwner(connection())
        val week = offlineUpdateWeek(now)
        return if (preferences.getString(owner, null) == week.toString())
            week.atZone(java.time.ZoneId.of("Europe/Rome")).plusWeeks(1).toInstant() else now
    }

    suspend fun automaticUpdate(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        Log.i("CompassOfflineAssets", "automatic_check_started")
        val server = connection()
        mutableStatus.value = mutableStatus.value.copy(busy = true, message = null)
        try {
            if (syncLocked(server, automatic = true)) {
                check(preferences.edit().putString(offlineOwner(server), offlineUpdateWeek(Instant.now()).toString()).commit())
                Log.i("CompassOfflineAssets", "automatic_check_completed")
                true
            } else false
        } catch (error: CancellationException) { throw error }
        catch (_: LinkageError) { false }
        catch (error: Exception) {
            Log.w("CompassOfflineAssets", "automatic_check_failed kind=${error.javaClass.simpleName}")
            mutableStatus.value = mutableStatus.value.copy(message = "Aggiornamento automatico rinviato; aree precedenti conservate")
            false
        }
        finally { mutableStatus.value = mutableStatus.value.copy(busy = false) }
    } }
}
