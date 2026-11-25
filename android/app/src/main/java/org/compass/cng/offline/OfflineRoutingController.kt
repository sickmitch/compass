package org.compass.cng.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.valhalla.valhalla.Valhalla
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.compass.cng.domain.RouteOriginDirection
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RoutePreview

/** One native call at a time; cancellation discards its result, never frees a live native actor. */
class OfflineRoutingController(private val context: Context) : OfflineRouter {
    private val store = OfflineDatasetStore(File(context.noBackupFilesDir, "offline-routing"))
    private val assembly = OfflineGraphAssembly(File(context.noBackupFilesDir, "offline-routing-assembly"))
    private val mutex = Mutex()
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var installed: List<InstalledOfflineDataset> = emptyList()
    private val mutableStatus = MutableStateFlow(OfflineRoutingStatus())
    val status = mutableStatus.asStateFlow()
    override val available: Boolean get() = status.value.ready

    init {
        scope.launch { mutex.withLock {
            try {
                assembly.clear()
                installed = store.recover()
                publish()
            } catch (_: Exception) {
                mutableStatus.value = OfflineRoutingStatus(message = "Pacchetto non valido: importalo nuovamente.")
            }
        } }
    }

    suspend fun importPackages(uris: List<Uri>) = withContext(io) {
        mutex.withLock {
            var added = 0
            var duplicates = 0
            var failed = 0
            var lastError: String? = null
            var cancelled = false
            try {
                for ((index, uri) in uris.withIndex()) {
                    ensureActive()
                    publish("Importazione ${index + 1}/${uris.size}…", busy = true)
                    try {
                        val job = currentCoroutineContext()
                        val input = requireNotNull(context.contentResolver.openInputStream(uri))
                        val dataset = input.use {
                            store.install(it, checkCancelled = { job.ensureActive() }) { candidate ->
                                calculate(listOf(candidate), candidate.manifest.probePoints(), true, null) { job.ensureActive() }
                            }
                        }
                        if (installed.any { it.directory == dataset.directory }) duplicates++ else added++
                        installed = store.all()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failed++
                        lastError = error.message?.take(180) ?: "Pacchetto non valido"
                    } catch (_: LinkageError) {
                        failed++
                        lastError = "Motore offline non disponibile su questo dispositivo"
                    }
                }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } finally {
                assembly.clear()
                installed = runCatching { store.all() }.getOrDefault(installed)
                publish(listOfNotNull(
                    if (cancelled) "Importazione interrotta" else null,
                    "Importati: $added · già presenti: $duplicates · non riusciti: $failed",
                    lastError,
                ).joinToString("\n"))
            }
        }
    }

    suspend fun subscriptions(): List<OfflineSubscription> = withContext(io) { mutex.withLock { store.subscriptions() } }

    suspend fun subscribe(owner: String, area: String, enabled: Boolean) = withContext(io) {
        mutex.withLock { store.subscribe(owner, area, enabled); publish() }
    }

    suspend fun replaceSubscribed(owner: String, expected: List<OfflineSubscription>, catalog: RemoteOfflineCatalog,
        files: Map<String, File>, stillCurrent: () -> Boolean,
    ) = withContext(io) {
        mutex.withLock {
            val job = currentCoroutineContext()
            publish("Verifica e installazione aree…", busy = true)
            try {
                store.replaceSubscribed(owner, expected, catalog, files, checkCancelled = {
                    job.ensureActive()
                    check(stillCurrent()) { "Connessione al server cambiata: riprova" }
                }) { candidate ->
                    calculate(listOf(candidate), candidate.manifest.probePoints(), true, null) { job.ensureActive() }
                }
            } finally {
                assembly.clear()
                installed = store.all()
                publish()
            }
        }
    }

    suspend fun removePackage(id: String) = withContext(io) {
        mutex.withLock {
            ensureActive()
            publish("Rimozione pacchetto…", busy = true)
            try {
                assembly.clear()
                installed = store.remove(id)
                publish("Pacchetto rimosso")
            } catch (error: CancellationException) {
                publish(); throw error
            } catch (_: Exception) {
                publish("Impossibile rimuovere il pacchetto")
            }
        }
    }

    override suspend fun route(points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> =
        withContext(io) { mutex.withLock {
            val job = currentCoroutineContext()
            routeWithOfflinePackages(installed, points) { datasets ->
                calculate(datasets, points, allowHighways, direction) { job.ensureActive() }
            }
        } }

    private fun calculate(datasets: List<InstalledOfflineDataset>, points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?, checkCancelled: () -> Unit = {}): List<RoutePreview> {
        val dataset = datasets.first()
        require(points.all { point -> datasets.any { it.manifest.contains(point) } }) { "Percorso fuori dall’area offline scaricata" }
        val config = File(dataset.directory, "runtime.json")
        val template = Json.parseToJsonElement(context.assets.open("offline-valhalla.json").bufferedReader().use { it.readText() }).jsonObject
        val mjolnir = template.getValue("mjolnir").jsonObject.toMutableMap().apply {
            if (dataset.manifest.schemaVersion == 1) {
                require(datasets.size == 1)
                put("tile_extract", JsonPrimitive(File(dataset.directory, "tiles.tar").absolutePath))
                put("tile_dir", JsonPrimitive(File(dataset.directory, "no-loose-tiles").absolutePath))
            } else {
                remove("tile_extract")
                put("tile_dir", JsonPrimitive(assembly.prepare(datasets, checkCancelled).absolutePath))
            }
        }
        config.writeText(JsonObject(template.toMutableMap().apply { put("mjolnir", JsonObject(mjolnir)) }).toString())
        val start = System.nanoTime()
        val request = OfflineRouteCodec.request(points, allowHighways, direction)
        val result = Valhalla(config.absolutePath).use { engine ->
            val loaded = System.nanoTime()
            val response = engine.routeRaw(request)
            Log.i("CompassOfflineRouting", "load_ms=${(loaded - start) / 1_000_000} route_ms=${(System.nanoTime() - loaded) / 1_000_000}")
            OfflineRouteCodec.decode(response, points, allowHighways)
        }
        return result
    }

    private fun publish(message: String? = null, busy: Boolean = false) {
        val subscriptions = store.subscriptions()
        val unavailable = runCatching { store.unavailableIds() }.getOrDefault(emptyList())
        mutableStatus.value = OfflineRoutingStatus(
            packages = installed.map { OfflineRoutingPackage(
                it.directory.name, subscriptions.firstOrNull { sub -> sub.datasetId == it.directory.name }
                    ?.let { sub -> offlineAreaLabels[sub.area] } ?: offlineDisplayName(it.manifest.label), it.manifest.sourceDate, it.manifest.bytes,
                graphSet = it.manifest.graphFamily?.take(8),
            ) } + unavailable.map { OfflineRoutingPackage(it, "Pacchetto non leggibile", "", 0, usable = false) },
            busy = busy,
            message = message,
        )
    }
}

data class OfflineRoutingPackage(
    val id: String, val label: String, val sourceDate: String, val bytes: Long, val usable: Boolean = true,
    val graphSet: String? = null,
)

data class OfflineRoutingStatus(
    val packages: List<OfflineRoutingPackage> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    val ready: Boolean get() = packages.any { it.usable }
}
