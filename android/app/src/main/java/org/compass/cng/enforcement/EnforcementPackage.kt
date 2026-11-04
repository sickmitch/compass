package org.compass.cng.enforcement

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.compass.cng.domain.geometry.Polyline6Decoder
import org.compass.cng.domain.model.Coordinate

@Serializable
data class EnforcementPackage(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("graph_identity") val graphIdentity: String,
    @SerialName("generated_at") val generatedAt: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("radius_meters") val radiusMeters: Int,
    @SerialName("spatial_basis") val spatialBasis: String,
    val controls: List<EnforcementControl>,
    val truncated: Boolean,
    val attribution: String,
) {
    fun validated(): EnforcementPackage = apply {
        require(schemaVersion == 1 && graphIdentity.isNotBlank() && graphIdentity.length <= 200)
        require(spatialBasis == "control_location" && radiusMeters in 100..10_000)
        Coordinate(latitude, longitude)
        Instant.parse(generatedAt)
        require(controls.size <= 200 && attribution.isNotBlank() && attribution.length <= 500)
        require(controls.map { it.sourceKey }.distinct().size == controls.size)
        controls.forEach { it.validate() }
    }
}

@Serializable
data class EnforcementControl(
    @SerialName("source_key") val sourceKey: String,
    @SerialName("physical_control_key") val physicalControlKey: String,
    @SerialName("source_import_id") val sourceImportId: String,
    val kind: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("direction_degrees") val directionDegrees: Double?,
    @SerialName("speed_limit_kph") val speedLimitKph: Double?,
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("association_state") val associationState: String,
    @SerialName("association_reason") val associationReason: String,
    @SerialName("directed_path") val directedPath: EnforcementPath?,
    @SerialName("alert_eligible") val alertEligible: Boolean,
) {
    fun validate() {
        require(SOURCE_KEY.matches(sourceKey) && SOURCE_KEY.matches(physicalControlKey))
        require(ID.matches(sourceImportId) && kind in setOf("fixed", "average_speed"))
        Coordinate(latitude, longitude)
        require(directionDegrees == null || directionDegrees in 0.0..<360.0)
        require(speedLimitKph == null || (speedLimitKph.isFinite() && speedLimitKph > 0))
        Instant.parse(fetchedAt)
        require(associationState in setOf("matched", "ambiguous", "unmatched", "unknown_direction",
            "stale", "missing_source_geometry", "invalid_evidence"))
        require(associationReason.length <= 500 && !alertEligible)
        require((associationState == "matched") == (directedPath != null))
        directedPath?.validate()
    }
}

@Serializable
data class EnforcementPath(
    @SerialName("osm_way_id") val osmWayId: String,
    val forward: Boolean,
    @SerialName("from_node") val fromNode: String,
    @SerialName("to_node") val toNode: String,
    @SerialName("edge_ids") val edgeIds: List<String>,
    @SerialName("encoded_polyline") val encodedPolyline: String,
    @SerialName("length_meters") val lengthMeters: Double,
) {
    fun validate() {
        require(listOf(osmWayId, fromNode, toNode).all(ID::matches) && fromNode != toNode)
        require(edgeIds.size in 1..512 && edgeIds.all(ID::matches))
        require(lengthMeters.isFinite() && lengthMeters > 0 && lengthMeters <= 50_000)
        require(encodedPolyline.length in 1..50_000)
        require(Polyline6Decoder.decode(encodedPolyline).size in 2..5000)
    }
}

private val SOURCE_KEY = Regex("osm:(node|relation):[0-9]{1,20}")
private val ID = Regex("[0-9]{1,20}")

/** Deduplication for display only. All directional records remain in the package/cache. */
fun EnforcementPackage.mapControls(): List<EnforcementControl> = controls
    .groupBy { it.physicalControlKey to it.kind }
    .values.map { records ->
        val limits = records.mapNotNull { it.speedLimitKph }.distinct()
        records.first().copy(speedLimitKph = limits.singleOrNull())
    }
