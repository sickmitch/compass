package org.compass.cng.offline

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Derived, disposable tile view. All callers share the controller's routing/import mutex. */
internal class OfflineGraphAssembly(private val root: File) {
    fun clear() { root.deleteRecursively() }

    fun prepare(packages: List<InstalledOfflineDataset>, checkCancelled: () -> Unit = {}): File {
        require(packages.isNotEmpty())
        val family = requireNotNull(packages.first().manifest.graphFamily)
        require(packages.all { it.manifest.schemaVersion == 2 && it.manifest.graphFamily == family })
        if (packages.size == 1) return File(packages.single().directory, "tiles")
        val identity = packages.map { "${it.directory.name}:${it.manifest.sha256}" }.sorted().joinToString("\n")
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(root, key)
        if (target.isDirectory) return target
        root.mkdirs()
        val staging = File(root, "$key.partial")
        staging.deleteRecursively()
        check(staging.mkdir())
        try {
            val seen = mutableMapOf<String, OfflineTile>()
            packages.forEach { dataset ->
                dataset.manifest.tiles.forEach { tile ->
                    checkCancelled()
                    val previous = seen.putIfAbsent(tile.path, tile)
                    require(previous == null || previous == tile) { "Tile incompatibili nello stesso set" }
                    if (previous == null) {
                        val source = File(dataset.directory, "tiles/${tile.path}")
                        require(source.isFile && source.length() == tile.bytes) { "Pacchetto offline incompleto" }
                        val destination = File(staging, tile.path)
                        destination.parentFile!!.mkdirs()
                        // Same private filesystem: no second copy of graph bytes and no symlink traversal.
                        Files.createLink(destination.toPath(), source.toPath())
                    }
                }
            }
            checkCancelled()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally {
            staging.deleteRecursively()
        }
    }
}
