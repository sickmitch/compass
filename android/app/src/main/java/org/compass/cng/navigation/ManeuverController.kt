package org.compass.cng.navigation

import java.util.Locale
import kotlin.math.roundToInt

enum class AnnouncementStage {
    EARLY,
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
    val earlySeconds: Double = 40.0,
    val prepareSeconds: Double = 14.0,
    val immediateSeconds: Double = 4.0,
    val earlyMinimumDistanceMeters: Double = 700.0,
    val prepareMinimumDistanceMeters: Double = 180.0,
    val immediateMinimumDistanceMeters: Double = 35.0,
    val minimumTimingSpeedMetersPerSecond: Double = 4.0,
)

/** Selects speed-aware announcements and guarantees one utterance per route/stage/event. */
class ManeuverController(
    private val policy: ManeuverAnnouncementPolicy = ManeuverAnnouncementPolicy(),
) {
    private val spoken = mutableSetOf<String>()
    private var activeRouteId: String? = null

    fun nextAnnouncement(state: NavigationState): VoiceAnnouncement? {
        val route = state.route ?: return null
        if (route.routeId != activeRouteId) {
            activeRouteId = route.routeId
            spoken.clear()
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
        val stage = stageFor(
            distanceMeters = distance,
            speedMetersPerSecond = state.currentSpeedMetersPerSecond,
        ) ?: return null
        val id = "${route.routeId}:maneuver:$maneuverIndex:${stage.name}"
        val text = when (stage) {
            AnnouncementStage.EARLY -> maneuver.verbalTransitionAlertInstruction
                ?: "Tra ${spokenDistance(distance)}, ${maneuver.instruction.lowercaseFirst()}"
            AnnouncementStage.PREPARE -> maneuver.verbalPreTransitionInstruction
                ?: maneuver.instruction
            AnnouncementStage.NOW -> maneuver.instruction
        }.trim()
        if (text.isBlank()) return null
        return emitOnce(
            VoiceAnnouncement(
                id = id,
                text = text,
                stage = stage,
                kind = AnnouncementKind.MANEUVER,
            ),
        )
    }

    fun reset() {
        activeRouteId = null
        spoken.clear()
    }

    private fun stageFor(distanceMeters: Double, speedMetersPerSecond: Double): AnnouncementStage? {
        val timingSpeed = maxOf(speedMetersPerSecond, policy.minimumTimingSpeedMetersPerSecond)
        val seconds = distanceMeters / timingSpeed
        return when {
            distanceMeters <= policy.immediateMinimumDistanceMeters ||
                seconds <= policy.immediateSeconds -> AnnouncementStage.NOW
            distanceMeters <= policy.prepareMinimumDistanceMeters ||
                seconds <= policy.prepareSeconds -> AnnouncementStage.PREPARE
            distanceMeters <= policy.earlyMinimumDistanceMeters ||
                seconds <= policy.earlySeconds -> AnnouncementStage.EARLY
            else -> null
        }
    }

    private fun emitOnce(announcement: VoiceAnnouncement): VoiceAnnouncement? =
        announcement.takeIf { spoken.add(it.id) }
}

private fun NavigationFuelStop.displayName(): String = name ?: "MIMIT $mimitStationId"

private fun spokenDistance(distanceMeters: Double): String = if (distanceMeters >= 1_000) {
    String.format(Locale.ITALIAN, "%.1f chilometri", distanceMeters / 1_000)
} else {
    "${(distanceMeters / 10).roundToInt() * 10} metri"
}

private fun String.lowercaseFirst(): String = replaceFirstChar {
    if (it.isUpperCase()) it.lowercaseChar() else it
}
