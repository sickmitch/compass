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
    private val mutex = Mutex()
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var installed: InstalledOfflineDataset? = null
    private val mutableStatus = MutableStateFlow(OfflineRoutingStatus())
    val status = mutableStatus.asStateFlow()
    override val available: Boolean get() = status.value.ready

    init {
        scope.launch { mutex.withLock {
            try {
                installed = store.recover()
                publish()
            } catch (_: Exception) {
                mutableStatus.value = OfflineRoutingStatus(message = "Pacchetto non valido: importalo nuovamente.")
            }
        } }
    }

    suspend fun importPackage(uri: Uri) = withContext(io) {
        mutex.withLock {
            mutableStatus.value = mutableStatus.value.copy(busy = true, message = "Importazione e verifica…")
            try {
                val job = currentCoroutineContext()
                val input = requireNotNull(context.contentResolver.openInputStream(uri))
                installed = input.use {
                    store.install(it, checkCancelled = { job.ensureActive() }) { dataset ->
                        calculate(dataset, dataset.manifest.probePoints(), true, null)
                    }
                }
                publish("Pacchetto verificato. Ricalcolo offline disponibile nell’area scaricata.")
            } catch (error: CancellationException) {
                publish(); throw error
            } catch (error: Exception) {
                publish(error.message?.take(180) ?: "Importazione non riuscita")
            } catch (_: LinkageError) {
                publish("Motore offline non disponibile su questo dispositivo")
            }
        }
    }

    override suspend fun route(points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> =
        withContext(io) { mutex.withLock {
            ensureActive()
            val dataset = checkNotNull(installed) { "Scarica e importa un pacchetto di routing" }
            val result = calculate(dataset, points, allowHighways, direction)
            ensureActive()
            result
        } }

    private fun calculate(dataset: InstalledOfflineDataset, points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): List<RoutePreview> {
        require(points.all(dataset.manifest::contains)) { "Percorso fuori dall’area offline scaricata" }
        val config = File(dataset.directory, "runtime.json")
        val template = Json.parseToJsonElement(context.assets.open("offline-valhalla.json").bufferedReader().use { it.readText() }).jsonObject
        val mjolnir = template.getValue("mjolnir").jsonObject.toMutableMap().apply {
            put("tile_extract", JsonPrimitive(File(dataset.directory, "tiles.tar").absolutePath))
            put("tile_dir", JsonPrimitive(File(dataset.directory, "no-loose-tiles").absolutePath))
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

    private fun publish(message: String? = null) {
        mutableStatus.value = OfflineRoutingStatus(ready = installed != null,
            label = installed?.manifest?.label, sourceDate = installed?.manifest?.sourceDate,
            message = message)
    }
}

data class OfflineRoutingStatus(
    val ready: Boolean = false, val busy: Boolean = false,
    val label: String? = null, val sourceDate: String? = null, val message: String? = null,
)
