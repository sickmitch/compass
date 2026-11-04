package org.compass.cng.enforcement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.compass.cng.domain.geometry.Polyline6Decoder
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.navigation.*
import kotlin.math.*

@Serializable
data class ApproachTrace(val points: List<ApproachPoint>)
@Serializable
data class ApproachPoint(val latitude: Double, val longitude: Double,
    @SerialName("elapsed_seconds") val elapsedSeconds: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Double)
@Serializable
data class ApproachResult(val state: String, val reason: String,
    @SerialName("graph_identity") val graphIdentity: String?,
    @SerialName("observed_at") val observedAt: String, val alert: ApproachControl?)
@Serializable
data class ApproachControl(
    @SerialName("source_key") val sourceKey: String,
    @SerialName("physical_control_key") val physicalControlKey: String,
    val kind: String,
    @SerialName("osm_way_id") val osmWayId: String, val forward: Boolean,
    @SerialName("speed_limit_kph") val speedLimitKph: Double?,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("encoded_polyline") val encodedPolyline: String,
    @SerialName("target_latitude") val targetLatitude: Double,
    @SerialName("target_longitude") val targetLongitude: Double,
) {
    val key get() = "$physicalControlKey:$kind:$osmWayId:$forward"
    val target get() = Coordinate(targetLatitude, targetLongitude)
    fun shape(): List<Coordinate> {
        require(kind in setOf("fixed", "average_speed") && distanceMeters in 0.0..1200.0)
        require(sourceKey.length in 1..100 && physicalControlKey.length in 1..100)
        require(osmWayId.matches(Regex("[1-9][0-9]*")))
        require(speedLimitKph == null || speedLimitKph in 1.0..300.0)
        require(encodedPolyline.length <= 10_000)
        return Polyline6Decoder.decode(encodedPolyline).also {
            require(it.size in 2..512 && distanceMeters(it.last(), target) < 5)
        }
    }
}

data class ApproachNotice(val control: ApproachControl, val distanceMeters: Double) {
    val label get() = if (control.kind == "average_speed") "Inizio Tutor" else "Autovelox"
    val roundedDistance get() = (round(distanceMeters / 10) * 10).toInt().coerceAtLeast(10)
    val text get() = "$label tra $roundedDistance metri" +
        (control.speedLimitKph?.let { ". Limite ${it.toInt()} chilometri orari." } ?: ".")
}

internal data class RoadProjection(val lateral: Double, val along: Double,
    val remaining: Double, val heading: Double)
internal fun projectRoad(point: Coordinate, shape: List<Coordinate>): RoadProjection? {
    var length = 0.0
    var best: RoadProjection? = null
    for ((a, b) in shape.zipWithNext()) {
        val scale = cos(Math.toRadians(point.latitude))
        val dx = (b.longitude - a.longitude) * scale
        val dy = b.latitude - a.latitude
        val segment = hypot(dx, dy) * 111_320
        if (segment == 0.0) continue
        val px = (point.longitude - a.longitude) * scale
        val py = point.latitude - a.latitude
        val t = ((px * dx + py * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val lateral = hypot(px - t * dx, py - t * dy) * 111_320
        if (best == null || lateral < best.lateral) best = RoadProjection(
            lateral, length + t * segment, 0.0, bearingDegrees(a, b))
        length += segment
    }
    return best?.copy(remaining = length - best.along)
}

/** Pure, app-scoped passage state: a route refresh never clears already spoken controls. */
class ApproachEngine {
    private val trace = ArrayDeque<NavigationLocation>()
    private var last: NavigationLocation? = null
    private var control: ApproachControl? = null
    private var shape = emptyList<Coordinate>()
    private var verifiedAt = 0L
    private var context: String? = null
    private val spoken = linkedMapOf<String, Pair<Coordinate, Long>>()
    var generation = 0L; private set
    var notice: ApproachNotice? = null; private set

    fun invalidate() {
        generation++
        trace.clear(); last = null; control = null; shape = emptyList(); notice = null
    }

    fun update(fix: NavigationLocation?, now: Long, contextKey: String, allowed: Boolean = true) {
        if (context != contextKey) { invalidate(); context = contextKey }
        if (!allowed || fix == null || fix.accuracyMeters !in 0.1..12.0 ||
            now - fix.timestampEpochMillis !in 0..5_000) { invalidate(); return }
        val previous = last
        if (previous != null && fix.timestampEpochMillis != previous.timestampEpochMillis) {
            val dt = (fix.timestampEpochMillis - previous.timestampEpochMillis) / 1000.0
            val moved = distanceMeters(previous.coordinate, fix.coordinate)
            if (dt !in 0.001..10.0 || moved > dt * 70 + previous.accuracyMeters + fix.accuracyMeters) {
                invalidate()
            }
        }
        last = fix
        // Sampling distance prevents very close/interpolated trace points and redundant requests.
        val sampled = trace.lastOrNull()
        if (sampled != null && fix.timestampEpochMillis - sampled.timestampEpochMillis > 10_000) {
            trace.clear(); control = null; notice = null; generation++
        }
        if (trace.isEmpty() || (fix.timestampEpochMillis > trace.last().timestampEpochMillis &&
                distanceMeters(trace.last().coordinate, fix.coordinate) >= 10)) trace.addLast(fix)
        while (trace.size > 6 || (trace.isNotEmpty() &&
                fix.timestampEpochMillis - trace.first().timestampEpochMillis > 30_000)) trace.removeFirst()
        spoken.entries.removeAll { (_, visit) -> now - visit.second > 120_000 &&
            distanceMeters(fix.coordinate, visit.first) > 1500 }
        while (spoken.size > 128) spoken.remove(spoken.keys.first())
        notice = null
        val active = control ?: return
        if (now - verifiedAt !in 0..60_000) { control = null; return }
        val projection = projectRoad(fix.coordinate, shape) ?: return
        val bearing = fix.bearingDegrees ?: trace.takeIf { it.size >= 3 }?.let {
            bearingDegrees(it.first().coordinate, it.last().coordinate)
        } ?: return
        if (!bearing.isFinite() || projection.lateral > min(10.0, fix.accuracyMeters) ||
            bearingDifference(projection.heading, bearing) > 25 || projection.remaining < 5) {
            control = null; generation++; return
        }
        val speed = fix.speedMetersPerSecond ?: trace.takeIf { it.size >= 3 }?.let {
            distanceMeters(it.first().coordinate, it.last().coordinate) /
                ((it.last().timestampEpochMillis - it.first().timestampEpochMillis) / 1000.0)
        } ?: 0.0
        if (trace.size >= 3 && speed in 2.0..70.0 && projection.remaining <= warningDistance(speed)) {
            notice = ApproachNotice(active, projection.remaining)
        }
    }

    fun request(): ApproachTrace? {
        if (trace.size < 3 || distanceMeters(trace.first().coordinate, trace.last().coordinate) < 20)
            return null
        val start = trace.first().timestampEpochMillis
        return ApproachTrace(trace.map { ApproachPoint(it.coordinate.latitude, it.coordinate.longitude,
            (it.timestampEpochMillis - start) / 1000.0, it.accuracyMeters) })
    }

    fun accept(result: ApproachResult, expectedGeneration: Long, graph: String, now: Long,
        routeAhead: List<Coordinate>? = null) {
        if (expectedGeneration != generation) return
        control = null; notice = null
        if (result.state != "approach" || result.graphIdentity != graph) return
        val next = result.alert ?: return
        val decoded = runCatching { next.shape() }.getOrNull() ?: return
        if (routeAhead != null) {
            val samples = listOf(decoded.first(), decoded[decoded.size / 2], decoded.last())
            val projections = samples.map { projectRoad(it, routeAhead) ?: return }
            if (projections.any { it.lateral > 15 } ||
                projections.zipWithNext().any { (a, b) -> b.along + 5 < a.along } ||
                bearingDifference(projections.last().heading,
                    bearingDegrees(decoded[decoded.lastIndex - 1], decoded.last())) > 30) return
        }
        shape = decoded; control = next; verifiedAt = now
    }

    /** A muted passage is consumed too: unmuting later must not play a stale warning. */
    fun claimSpeech(enabled: Boolean): VoiceAnnouncement? {
        val shown = notice ?: return null
        if (spoken.containsKey(shown.control.key)) return null
        spoken[shown.control.key] = shown.control.target to (last?.timestampEpochMillis ?: return null)
        if (!enabled) return null
        return VoiceAnnouncement("enforcement:${shown.control.key}:${last?.timestampEpochMillis}",
            shown.text, AnnouncementStage.PREPARE, AnnouncementKind.ENFORCEMENT)
    }

    companion object {
        fun warningDistance(speedMetersPerSecond: Double) = (speedMetersPerSecond * 15).coerceIn(100.0, 600.0)
    }
}
