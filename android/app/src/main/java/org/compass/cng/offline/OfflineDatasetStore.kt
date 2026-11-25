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
    val graphFamily: String? = null,
    val tiles: List<OfflineTile> = emptyList(),
) {
    fun validate() {
        require(schemaVersion in 1..2 && engineVersion == "3.9.0") { "Versione del pacchetto non compatibile" }
        require(label.length in 1..120 && sourceDate.length in 1..40)
        require(sha256.matches(Regex("[0-9a-f]{64}")) && bytes in 1..MAX_GRAPH_BYTES)
        require(south.isFinite() && north.isFinite() && south >= -90 && north <= 90 && south < north)
        require(west.isFinite() && east.isFinite() && west >= -180 && east <= 180 && west < east)
        require(probe.size == 2 && probe.all { it.size == 2 && contains(Coordinate(it[0], it[1])) })
        require(attribution == "© OpenStreetMap contributors — ODbL 1.0")
        if (schemaVersion == 1) {
            require(graphFamily == null && tiles.isEmpty())
        } else {
            require(graphFamily?.matches(Regex("[0-9a-f]{64}")) == true)
            require(tiles.isNotEmpty() && tiles.map { it.path }.distinct().size == tiles.size)
            tiles.forEach { it.validate() }
            require(tiles.sumOf { it.bytes } == bytes && offlineInventoryDigest(tiles) == sha256)
        }
    }
    fun contains(point: Coordinate) = point.latitude in south..north && point.longitude in west..east
    fun probePoints() = probe.map { Coordinate(it[0], it[1]) }
    companion object { const val MAX_GRAPH_BYTES = 8L * 1024 * 1024 * 1024 }
}

@Serializable
data class OfflineTile(val path: String, val bytes: Long, val sha256: String) {
    fun validate() {
        require(path.matches(Regex("[0-2]/(?:[0-9]{3}/){1,2}[0-9]{3}\\.gph"))) { "Percorso tile non valido" }
        val level = path.first().digitToInt()
        val id = path.substring(2).removeSuffix(".gph").replace("/", "").toInt()
        val width = if (level == 2) 9 else 6
        val canonical = "$level/" + id.toString().padStart(width, '0').chunked(3).joinToString("/") + ".gph"
        require(path == canonical && id < listOf(4050, 64800, 1036800)[level]) { "Identificativo tile non valido" }
        require(bytes in 1..OfflineManifest.MAX_GRAPH_BYTES && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

internal fun offlineInventoryDigest(tiles: List<OfflineTile>): String = MessageDigest.getInstance("SHA-256")
    .digest(tiles.sortedBy { it.path }.joinToString("") { "${it.path}\t${it.bytes}\t${it.sha256}\n" }.toByteArray())
    .joinToString("") { "%02x".format(it) }

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
            if (manifest.schemaVersion == 1) require(File(folder, "tiles.tar").length() == manifest.bytes)
            else require(manifest.tiles.all { File(folder, "tiles/${it.path}").length() == it.bytes })
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

    private fun publish(names: List<String>, subscriptions: List<OfflineSubscription> = subscriptions()) {
        root.mkdirs()
        val temporary = File(root, "catalog.tmp")
        temporary.outputStream().use {
            it.write(json.encodeToString(OfflineCatalog.serializer(), OfflineCatalog(datasets = names, subscriptions = subscriptions.filter { it.datasetId == null || it.datasetId in names })).toByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), File(root, "catalog.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        File(root, "active.txt").delete()
    }

    fun install(
        input: InputStream,
        checkCancelled: () -> Unit = {},
        register: Boolean = true,
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
                    require(headerBuffer.size() + count <= 4 * 1024 * 1024) { "Manifest troppo grande" }
                    headerBuffer.write(chunk, 0, count)
                }
                val header = headerBuffer.toByteArray()
                val manifest = json.decodeFromString<OfflineManifest>(header.decodeToString())
                manifest.validate()
                check(folder.usableSpace > manifest.bytes + 64L * 1024 * 1024) { "Spazio insufficiente" }
                if (manifest.schemaVersion == 1) {
                    require(zip.nextEntry?.name == "tiles.tar") { "Grafo del pacchetto mancante" }
                    copyVerified(zip, File(folder, "tiles.tar"), manifest.bytes, manifest.sha256, checkCancelled)
                } else {
                    val remaining = manifest.tiles.associateBy { it.path }.toMutableMap()
                    repeat(manifest.tiles.size) {
                        checkCancelled()
                        val entry = requireNotNull(zip.nextEntry) { "Tile mancante" }
                        val tile = requireNotNull(remaining.remove(entry.name)) { "Tile inattesa o duplicata" }
                        require(!entry.isDirectory)
                        copyVerified(zip, File(folder, "tiles/${tile.path}"), tile.bytes, tile.sha256, checkCancelled)
                    }
                }
                require(zip.nextEntry == null) { "File inattesi nel pacchetto" }
                File(folder, "manifest.json").outputStream().use { it.write(header); it.fd.sync() }
                val dataset = InstalledOfflineDataset(folder, manifest)
                checkCancelled()
                all().firstOrNull { it.manifest.sha256 == manifest.sha256 && it.manifest.graphFamily == manifest.graphFamily &&
                    (manifest.schemaVersion == 1 || listOf(it.manifest.south, it.manifest.west, it.manifest.north, it.manifest.east) ==
                        listOf(manifest.south, manifest.west, manifest.north, manifest.east)) }?.let { return it }
                // Same family must never overwrite a tile with different bytes.
                if (manifest.graphFamily != null) {
                    val incoming = manifest.tiles.associateBy { it.path }
                    all().filter { it.manifest.graphFamily == manifest.graphFamily }.forEach { existing ->
                        existing.manifest.tiles.forEach { tile ->
                            require(incoming[tile.path]?.let { it == tile } != false) { "Tile incompatibili nello stesso set" }
                        }
                    }
                }
                probe(dataset) // Native route verification before registering the package.
                checkCancelled()
                if (register) publish(previous + folder.name)
                published = true
                return dataset
            }
        } finally {
            if (!published) folder.deleteRecursively()
        }
    }
    fun subscriptions(): List<OfflineSubscription> = File(root, "catalog.json").takeIf { it.exists() }
        ?.let { json.decodeFromString<OfflineCatalog>(it.readText()).subscriptions }.orEmpty()

    fun subscribe(owner: String, area: String, enabled: Boolean) {
        require(area in offlineAreaLabels)
        val existing = subscriptions()
        val updated = existing.filterNot { it.owner == owner && it.area == area }.toMutableList()
        if (enabled) updated += existing.firstOrNull { it.owner == owner && it.area == area }
            ?: OfflineSubscription(owner, area)
        publish(readNames(), updated)
    }

    /** One catalog commit for the complete subscription set. Old packages survive any failure. */
    fun replaceSubscribed(owner: String, expected: List<OfflineSubscription>, catalog: RemoteOfflineCatalog,
        files: Map<String, File>, checkCancelled: () -> Unit = {},
        probe: (InstalledOfflineDataset) -> Unit,
    ) {
        require(subscriptions().filter { it.owner == owner } == expected) { "Aree sottoscritte cambiate: riprova" }
        catalog.validate()
        val previousNames = readNames()
        val staged = mutableListOf<InstalledOfflineDataset>()
        var committed = false
        try {
            val replacements = expected.map { subscription ->
                val remote = catalog.packages.single { it.area == subscription.area }
                val file = files[subscription.area]
                if (file == null) {
                    require(subscription.sha256 == remote.sha256 && subscription.datasetId in previousNames)
                    subscription
                } else {
                    val candidate = file.inputStream().use { input ->
                        install(input, checkCancelled, register = false) { candidate ->
                            require(candidate.manifest.graphFamily == catalog.graph_family) { "Set offline non coerente" }
                            probe(candidate)
                        }
                    }
                    staged += candidate
                    val incoming = candidate.manifest.tiles.associateBy { it.path }
                    staged.filter { it.manifest.graphFamily == candidate.manifest.graphFamily }.forEach { other ->
                        other.manifest.tiles.forEach { tile ->
                            require(incoming[tile.path]?.let { it == tile } != false) { "Tile incompatibili nello stesso set" }
                        }
                    }
                    require(candidate.manifest.graphFamily == catalog.graph_family)
                    subscription.copy(datasetId = candidate.directory.name, sha256 = remote.sha256)
                }
            }
            checkCancelled()
            val otherReferences = subscriptions().filter { it.owner != owner }.mapNotNull { it.datasetId }.toSet()
            val obsolete = expected.mapNotNull { it.datasetId }.toSet() - otherReferences
            val keep = (previousNames.filterNot { it in obsolete } + replacements.mapNotNull { it.datasetId }).distinct()
            publish(keep, subscriptions().filterNot { it.owner == owner } + replacements)
            committed = true
            obsolete.filterNot { it in keep }.forEach { File(root, it).deleteRecursively() }
        } finally {
            if (!committed) staged.filter { it.directory.name !in previousNames }.forEach { it.directory.deleteRecursively() }
        }
    }

    private fun copyVerified(input: InputStream, file: File, bytes: Long, digest: String, checkCancelled: () -> Unit) {
        file.parentFile!!.mkdirs()
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                count += n
                require(count <= bytes) { "Dimensione del grafo non valida" }
                output.write(buffer, 0, n)
                hash.update(buffer, 0, n)
            }
            output.fd.sync()
        }
        require(count == bytes && hash.digest().joinToString("") { "%02x".format(it) } == digest) {
            "Verifica SHA-256 del grafo fallita"
        }
    }

}

@Serializable
private data class OfflineCatalog(val schemaVersion: Int = 1, val datasets: List<String>,
    val subscriptions: List<OfflineSubscription> = emptyList())
