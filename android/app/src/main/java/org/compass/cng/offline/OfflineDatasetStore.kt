package org.compass.cng.offline

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.compass.cng.domain.model.Coordinate

/** Only graph data is imported, never executable configuration, URLs or native code. */
@Serializable
data class OfflineManifest(
    val schemaVersion: Int,
    val engineVersion: String,
    val label: String,
    val sourceDate: String,
    val sha256: String,
    val bytes: Long,
    val south: Double, val west: Double, val north: Double, val east: Double,
    val probe: List<List<Double>>,
    val attribution: String,
) {
    fun validate() {
        require(schemaVersion == 1 && engineVersion == "3.9.0") { "Versione del pacchetto non compatibile" }
        require(label.length in 1..120 && sourceDate.length in 1..40)
        require(sha256.matches(Regex("[0-9a-f]{64}")) && bytes in 1..MAX_GRAPH_BYTES)
        require(south.isFinite() && north.isFinite() && south >= -90 && north <= 90 && south < north)
        require(west.isFinite() && east.isFinite() && west >= -180 && east <= 180 && west < east)
        require(probe.size == 2 && probe.all { it.size == 2 && contains(Coordinate(it[0], it[1])) })
        require(attribution == "© OpenStreetMap contributors — ODbL 1.0")
    }
    fun contains(point: Coordinate) = point.latitude in south..north && point.longitude in west..east
    fun probePoints() = probe.map { Coordinate(it[0], it[1]) }
    companion object { const val MAX_GRAPH_BYTES = 8L * 1024 * 1024 * 1024 }
}

data class InstalledOfflineDataset(val directory: File, val manifest: OfflineManifest)

/** Called only on an IO dispatcher, serialized with routing by the owning controller. */
class OfflineDatasetStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = false }
    private val datasetName = Regex("dataset-[a-f0-9-]{36}")

    /** The catalog is authoritative. Migrate the old pointer before reclaiming abandoned imports. */
    fun recover(): List<InstalledOfflineDataset> {
        val names = readNames()
        if (!File(root, "catalog.json").exists() && File(root, "active.txt").exists()) {
            publish(names)
        }
        root.listFiles()?.filter {
            it.isDirectory && datasetName.matches(it.name) && it.name !in names
        }?.forEach { it.deleteRecursively() }
        File(root, "catalog.tmp").delete()
        File(root, "active.tmp").delete()
        return all()
    }

    fun all(): List<InstalledOfflineDataset> = readNames().mapNotNull { name ->
        // One missing/corrupt package must not disable the other verified areas.
        runCatching {
            val folder = File(root, name)
            val manifest = json.decodeFromString<OfflineManifest>(File(folder, "manifest.json").readText())
            manifest.validate()
            require(File(folder, "tiles.tar").length() == manifest.bytes)
            InstalledOfflineDataset(folder, manifest)
        }.getOrNull()
    }

    fun unavailableIds(): List<String> {
        val readable = all().map { it.directory.name }.toSet()
        return readNames().filterNot { it in readable }
    }

    fun remove(id: String): List<InstalledOfflineDataset> {
        require(datasetName.matches(id))
        val names = readNames()
        require(id in names) { "Pacchetto non installato" }
        publish(names.filterNot { it == id })
        File(root, id).deleteRecursively()
        return all()
    }

    private fun readNames(): List<String> {
        val catalog = File(root, "catalog.json")
        val names = if (catalog.exists()) {
            json.decodeFromString<OfflineCatalog>(catalog.readText()).also {
                require(it.schemaVersion == 1) { "Catalogo offline non compatibile" }
            }.datasets
        } else {
            File(root, "active.txt").takeIf { it.exists() }?.readText()?.trim()?.let(::listOf).orEmpty()
        }
        require(names.all(datasetName::matches) && names.distinct().size == names.size) {
            "Catalogo offline non valido"
        }
        return names
    }

    private fun publish(names: List<String>) {
        root.mkdirs()
        val temporary = File(root, "catalog.tmp")
        temporary.outputStream().use {
            it.write(json.encodeToString(OfflineCatalog.serializer(), OfflineCatalog(datasets = names)).toByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), File(root, "catalog.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        File(root, "active.txt").delete()
    }

    fun install(
        input: InputStream,
        checkCancelled: () -> Unit = {},
        probe: (InstalledOfflineDataset) -> Unit,
    ): InstalledOfflineDataset {
        val previous = readNames()
        root.mkdirs()
        val folder = File(root, "dataset-${UUID.randomUUID()}")
        check(folder.mkdir())
        var published = false
        try {
            ZipInputStream(input).use { zip ->
                // Manifest first provides explicit bounds before streaming a potentially large graph.
                require(zip.nextEntry?.name == "manifest.json") { "Manifest del pacchetto mancante" }
                val headerBuffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1024)
                while (true) {
                    checkCancelled()
                    val count = zip.read(chunk)
                    if (count < 0) break
                    require(headerBuffer.size() + count <= 16_384) { "Manifest troppo grande" }
                    headerBuffer.write(chunk, 0, count)
                }
                val header = headerBuffer.toByteArray()
                val manifest = json.decodeFromString<OfflineManifest>(header.decodeToString())
                manifest.validate()
                check(folder.usableSpace > manifest.bytes + 64L * 1024 * 1024) { "Spazio insufficiente" }
                require(zip.nextEntry?.name == "tiles.tar") { "Grafo del pacchetto mancante" }
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                File(folder, "tiles.tar").outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled()
                        val n = zip.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= manifest.bytes) { "Dimensione del grafo non valida" }
                        output.write(buffer, 0, n); hash.update(buffer, 0, n)
                    }
                    output.fd.sync()
                }
                require(count == manifest.bytes && hash.digest().joinToString("") { "%02x".format(it) } == manifest.sha256) {
                    "Verifica SHA-256 del grafo fallita"
                }
                require(zip.nextEntry == null) { "File inattesi nel pacchetto" }
                File(folder, "manifest.json").outputStream().use { it.write(header); it.fd.sync() }
                val dataset = InstalledOfflineDataset(folder, manifest)
                checkCancelled()
                all().firstOrNull { it.manifest.sha256 == manifest.sha256 }?.let { return it }
                probe(dataset) // Native route verification before registering the package.
                checkCancelled()
                publish(previous + folder.name)
                published = true
                return dataset
            }
        } finally {
            if (!published) folder.deleteRecursively()
        }
    }
}

@Serializable
private data class OfflineCatalog(val schemaVersion: Int = 1, val datasets: List<String>)
