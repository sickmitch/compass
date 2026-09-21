package org.compass.cng.navigation

import java.util.Locale
import kotlin.math.roundToInt
import org.compass.cng.domain.model.RouteTravelMode

enum class AnnouncementStage {
    INITIAL,
    FIVE_HUNDRED_METERS,
    TWO_HUNDRED_FIFTY_METERS,
    ONE_HUNDRED_METERS,
    FIFTY_METERS,
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
    val fiveHundredMeters: Double = 500.0,
    val twoHundredFiftyMeters: Double = 250.0,
    val oneHundredMeters: Double = 100.0,
    val fiftyMeters: Double = 50.0,
    val immediateMeters: Double = 10.0,
)

/** Announces every new maneuver and fixed countdown thresholds once per route maneuver. */
class ManeuverController(
    private val policy: ManeuverAnnouncementPolicy = ManeuverAnnouncementPolicy(),
) {
    private val spoken = mutableSetOf<String>()
    private var activeRouteId: String? = null
    private var activeManeuverId: String? = null
    private var previousManeuverDistanceMeters: Double? = null

    fun nextAnnouncement(state: NavigationState): VoiceAnnouncement? {
        val route = state.route ?: return null
        if (route.routeId != activeRouteId) {
            activeRouteId = route.routeId
            spoken.clear()
            activeManeuverId = null
            previousManeuverDistanceMeters = null
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
        val thresholds = maneuverThresholds(route.travelMode)
        if (maneuverId != activeManeuverId) {
            activeManeuverId = maneuverId
            previousManeuverDistanceMeters = distance
            thresholds
                .filter { distance <= it.distanceMeters }
                .forEach { spoken.add("$maneuverId:${it.stage.name}") }
            val text = maneuverAnnouncementText(
                distanceMeters = distance,
                instruction = maneuver.instruction,
                policy = policy,
            )
            if (text.isBlank()) return null
            return emitOnce(
                VoiceAnnouncement(
                    id = "$maneuverId:${AnnouncementStage.INITIAL.name}",
                    text = text,
                    stage = AnnouncementStage.INITIAL,
                    kind = AnnouncementKind.MANEUVER,
                ),
            )
        }

        val previousDistance = previousManeuverDistanceMeters ?: distance
        previousManeuverDistanceMeters = distance
        val crossed = thresholds.filter { threshold ->
            previousDistance > threshold.distanceMeters &&
                distance <= threshold.distanceMeters &&
                "$maneuverId:${threshold.stage.name}" !in spoken
        }
        val threshold = crossed.lastOrNull() ?: return null
        crossed.dropLast(1).forEach { skipped ->
            spoken.add("$maneuverId:${skipped.stage.name}")
        }
        val id = "$maneuverId:${threshold.stage.name}"
        val text = maneuverAnnouncementText(
            distanceMeters = threshold.distanceMeters,
            instruction = maneuver.instruction,
            policy = policy,
        )
        if (text.isBlank()) return null
        return emitOnce(
            VoiceAnnouncement(
                id = id,
                text = text,
                stage = threshold.stage,
                kind = AnnouncementKind.MANEUVER,
            ),
        )
    }

    fun reset() {
        activeRouteId = null
        activeManeuverId = null
        previousManeuverDistanceMeters = null
        spoken.clear()
    }

    private fun maneuverThresholds(travelMode: RouteTravelMode): List<ManeuverThreshold> = listOf(
        ManeuverThreshold(policy.fiveHundredMeters, AnnouncementStage.FIVE_HUNDRED_METERS),
        ManeuverThreshold(
            policy.twoHundredFiftyMeters,
            AnnouncementStage.TWO_HUNDRED_FIFTY_METERS,
        ),
        ManeuverThreshold(policy.oneHundredMeters, AnnouncementStage.ONE_HUNDRED_METERS),
        ManeuverThreshold(policy.fiftyMeters, AnnouncementStage.FIFTY_METERS),
        ManeuverThreshold(policy.immediateMeters, AnnouncementStage.NOW),
    ).filterNot { threshold ->
        travelMode == RouteTravelMode.WALKING && threshold.stage in setOf(
            AnnouncementStage.FIVE_HUNDRED_METERS,
            AnnouncementStage.TWO_HUNDRED_FIFTY_METERS,
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
    policy: ManeuverAnnouncementPolicy,
): String {
    val normalizedInstruction = instruction.trim()
    if (normalizedInstruction.isEmpty()) return ""
    val distanceLead = if (
        distanceMeters <= policy.fiftyMeters && distanceMeters > policy.immediateMeters
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
