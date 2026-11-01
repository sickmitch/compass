package org.compass.cng.navigation

import java.util.Locale
import kotlin.math.roundToInt
import org.compass.cng.domain.model.RouteTravelMode

enum class AnnouncementStage {
    INITIAL,
    PREPARE,
    NOW,
}

enum class AnnouncementKind {
    MANEUVER,
    FUEL_STOP,
    ARRIVAL,
}

data class VoiceAnnouncement(
    val id: String,
    val text: String,
    val stage: AnnouncementStage,
    val kind: AnnouncementKind,
)

data class ManeuverAnnouncementPolicy(
    val advanceSeconds: Double = 30.0,
    val prepareSeconds: Double = 12.0,
    val immediateSeconds: Double = 3.0,
)

/** Three progressive stages at most; skipped stages are never replayed after GPS/speed changes. */
class ManeuverController(
    private val policy: ManeuverAnnouncementPolicy = ManeuverAnnouncementPolicy(),
) {
    private val spoken = mutableSetOf<String>()
    private val maneuverStages = mutableMapOf<String, Int>()
    private var activeRouteId: String? = null

    fun nextAnnouncement(state: NavigationState): VoiceAnnouncement? {
        val route = state.route ?: return null
        if (route.routeId != activeRouteId) {
            activeRouteId = route.routeId
            spoken.clear()
            maneuverStages.clear()
        }
        if (state.phase == NavigationPhase.ARRIVED) {
            return emitOnce(
                VoiceAnnouncement(
                    id = "${route.routeId}:arrival",
                    text = "Sei arrivato a destinazione.",
                    stage = AnnouncementStage.NOW,
                    kind = AnnouncementKind.ARRIVAL,
                ),
            )
        }
        state.lastCompletedFuelStop?.let { stop ->
            emitOnce(
                VoiceAnnouncement(
                    id = "${route.routeId}:fuel:${stop.sequence}:completed",
                    text = "Rifornimento completato. Riprendi il percorso.",
                    stage = AnnouncementStage.NOW,
                    kind = AnnouncementKind.FUEL_STOP,
                ),
            )?.let { return it }
        }
        state.lastCompletedIntermediateStop?.let { stop ->
            emitOnce(
                VoiceAnnouncement(
                    id = "${route.routeId}:intermediate:${stop.sequence}:completed",
                    text = "Tappa terminata. Riprendi il percorso.",
                    stage = AnnouncementStage.NOW,
                    kind = AnnouncementKind.ARRIVAL,
                ),
            )?.let { return it }
        }
        state.activeIntermediateStopVisit?.let { visit ->
            return emitOnce(
                VoiceAnnouncement(
                    id = "${route.routeId}:intermediate:${visit.stop.sequence}:arrived",
                    text = "Tappa intermedia raggiunta.",
                    stage = AnnouncementStage.NOW,
                    kind = AnnouncementKind.ARRIVAL,
                ),
            )
        }
        state.nextFuelStop?.let { fuel ->
            if (state.phase == NavigationPhase.AT_FUEL_STOP) {
                emitOnce(
                    VoiceAnnouncement(
                        id = "${route.routeId}:fuel:${fuel.stop.mimitStationId}:arrived",
                        text = "Sei arrivato al rifornimento ${fuel.stop.displayName()}.",
                        stage = AnnouncementStage.NOW,
                        kind = AnnouncementKind.FUEL_STOP,
                    ),
                )?.let { return it }
            }
            if (state.phase == NavigationPhase.APPROACHING_FUEL_STOP) {
                emitOnce(
                    VoiceAnnouncement(
                        id = "${route.routeId}:fuel:${fuel.stop.mimitStationId}:approach",
                        text = "Tra ${spokenDistance(fuel.distanceRemainingMeters)}, " +
                            "raggiungerai il rifornimento ${fuel.stop.displayName()}.",
                        stage = AnnouncementStage.PREPARE,
                        kind = AnnouncementKind.FUEL_STOP,
                    ),
                )?.let { return it }
            }
        }
        val maneuver = state.currentManeuver ?: return null
        val maneuverIndex = route.maneuvers.indexOfFirst {
            it.type == maneuver.type &&
                it.beginShapeIndex == maneuver.beginShapeIndex &&
                it.endShapeIndex == maneuver.endShapeIndex
        }.takeIf { it >= 0 } ?: return null
        val distance = state.distanceToNextManeuverMeters ?: return null
        val maneuverId = "${route.routeId}:maneuver:$maneuverIndex"
        if (!distance.isFinite() || distance < 0.0) return null
        val thresholds = maneuverThresholds(route.travelMode, state.currentSpeedMetersPerSecond)
        val stageIndex = thresholds.indexOfLast { distance <= it.distanceMeters }
        if (stageIndex < 0 || stageIndex <= (maneuverStages[maneuverId] ?: -1)) return null
        val threshold = thresholds[stageIndex]
        val text = maneuverAnnouncementText(distance, maneuver.instruction)
        if (text.isBlank()) return null
        maneuverStages[maneuverId] = stageIndex
        return emitOnce(
            VoiceAnnouncement(
                id = "$maneuverId:${threshold.stage.name}",
                text = text,
                stage = threshold.stage,
                kind = AnnouncementKind.MANEUVER,
            ),
        )
    }

    fun reset() {
        activeRouteId = null
        maneuverStages.clear()
        spoken.clear()
    }

    private fun maneuverThresholds(
        travelMode: RouteTravelMode,
        speedMetersPerSecond: Double,
    ): List<ManeuverThreshold> {
        val speed = speedMetersPerSecond.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val maximumAdvance = when (travelMode) {
            RouteTravelMode.DRIVING -> 1_200.0
            RouteTravelMode.CYCLING -> 300.0
            RouteTravelMode.WALKING -> 100.0
        }
        return listOf(
            ManeuverThreshold(
                (speed * policy.advanceSeconds).coerceIn(80.0, maximumAdvance),
                AnnouncementStage.INITIAL,
            ),
            ManeuverThreshold(
                (speed * policy.prepareSeconds).coerceIn(35.0, maximumAdvance * 0.4),
                AnnouncementStage.PREPARE,
            ),
            ManeuverThreshold(
                (speed * policy.immediateSeconds).coerceIn(10.0, maximumAdvance * 0.1),
                AnnouncementStage.NOW,
            ),
        )
    }

    private fun emitOnce(announcement: VoiceAnnouncement): VoiceAnnouncement? =
        announcement.takeIf { spoken.add(it.id) }
}

private data class ManeuverThreshold(
    val distanceMeters: Double,
    val stage: AnnouncementStage,
)

private fun NavigationFuelStop.displayName(): String = name ?: "MIMIT $mimitStationId"

private fun maneuverAnnouncementText(
    distanceMeters: Double,
    instruction: String,
): String {
    val normalizedInstruction = instruction.trim()
    if (normalizedInstruction.isEmpty()) return ""
    val distanceLead = if (
        distanceMeters <= 50.0 && distanceMeters > 10.0
    ) {
        "A breve"
    } else {
        "Tra ${spokenDistance(distanceMeters)}"
    }
    return "$distanceLead, ${normalizedInstruction.lowercaseFirst()}"
}

private fun spokenDistance(distanceMeters: Double): String = if (distanceMeters >= 1_000) {
    String.format(Locale.ITALIAN, "%.1f chilometri", distanceMeters / 1_000)
} else {
    val stepMeters = if (distanceMeters > 50.0) 50 else 10
    "${(distanceMeters / stepMeters).roundToInt() * stepMeters} metri"
}

private fun String.lowercaseFirst(): String = replaceFirstChar {
    if (it.isUpperCase()) it.lowercaseChar() else it
}
