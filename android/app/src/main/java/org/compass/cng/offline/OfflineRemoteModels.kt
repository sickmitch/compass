package org.compass.cng.offline

import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

val offlineAreaLabels = linkedMapOf("nord-ovest" to "Nord-Ovest", "nord-est" to "Nord-Est",
    "centro" to "Centro", "sud-isole" to "Sud e isole", "italia" to "Italia")

fun offlineDisplayName(label: String): String = label.removeSuffix(".zip")
    .replace(Regex("_[0-9]{8}$"), "").let { offlineAreaLabels[it] ?: it }

@Serializable
data class RemoteOfflinePackage(val area: String, val label: String, val filename: String,
    val bytes: Long, val sha256: String) {
    fun validate() {
        require(area in offlineAreaLabels)
        require(filename.matches(Regex("${Regex.escape(area)}_[0-9]{8}\\.zip")))
        require(bytes in 1..9L * 1024 * 1024 * 1024 && sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

@Serializable
data class RemoteOfflineCatalog(val graph_family: String? = null, val created_at: String? = null,
    val packages: List<RemoteOfflinePackage> = emptyList()) {
    fun validate() {
        if (packages.isEmpty()) { require(graph_family == null && created_at == null); return }
        require(packages.size == 5 && packages.map { it.area }.toSet() == offlineAreaLabels.keys)
        require(graph_family?.matches(Regex("[a-f0-9]{64}")) == true)
        OffsetDateTime.parse(requireNotNull(created_at))
        packages.forEach { it.validate() }
        require(packages.map { it.filename.substringAfterLast('_') }.distinct().size == 1)
    }
}

@Serializable
data class OfflineSubscription(val owner: String, val area: String, val datasetId: String? = null,
    val sha256: String? = null)

/** Monday 06:00 Rome, then daily retries if Sunday's publication is late. */
fun offlineUpdateWeek(now: Instant): Instant = now.atZone(ZoneId.of("Europe/Rome")).let { local ->
    var monday = local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .toLocalDate().atTime(6, 0).atZone(local.zone)
    if (monday.toInstant() > now) monday = monday.minusWeeks(1)
    monday.toInstant()
}
