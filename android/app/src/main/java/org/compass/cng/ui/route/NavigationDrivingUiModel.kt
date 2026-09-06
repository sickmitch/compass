package org.compass.cng.ui.route

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.compass.cng.navigation.GpsStatus
import org.compass.cng.navigation.NavigationConnectivity
import org.compass.cng.navigation.NavigationFuelStop
import org.compass.cng.navigation.NavigationRouteSource
import org.compass.cng.navigation.NavigationState
import org.compass.cng.navigation.OffRouteStatus
import org.compass.cng.navigation.ReroutingStatus
import org.compass.cng.navigation.RouteUpdateFailure
import org.compass.cng.navigation.RouteUpdateReason
import org.compass.cng.domain.model.NavigationTiming

internal data class NavigationDrivingUiModel(
    val maneuverVisual: ManeuverVisual,
    val roundaboutExitCount: Int?,
    val distanceToManeuver: String,
    val primaryInstruction: String,
    val targetRoad: String?,
    val junctionSign: NavigationJunctionSignUiModel?,
    val followingInstruction: String?,
    val followingManeuverVisual: ManeuverVisual?,
    val remainingDistance: String,
    val remainingDuration: String,
    val arrivalTime: String,
    val progress: Float,
    val currentSpeedLimitKph: Int?,
    val nextCngStop: NavigationCngUiModel?,
    val statusMessages: List<NavigationStatusUiModel>,
)

internal data class NavigationJunctionSignUiModel(
    val exitNumber: String?,
    val branches: String?,
    val toward: String?,
    val exitName: String?,
)

internal data class NavigationCngUiModel(
    val name: String,
    val distance: String,
    val arrivalTime: String?,
)

internal data class NavigationStatusUiModel(
    val text: String,
    val level: NavigationStatusLevel,
)

internal enum class NavigationStatusLevel {
    NORMAL,
    POSITIVE,
    WARNING,
}

internal fun NavigationState.toDrivingUiModel(): NavigationDrivingUiModel {
    val activeRoute = requireNotNull(route)
    return NavigationDrivingUiModel(
        maneuverVisual = maneuverVisual(currentManeuver?.type, currentManeuver?.instruction),
        roundaboutExitCount = currentManeuver?.roundaboutExitCount?.takeIf { it > 0 },
        distanceToManeuver = distanceToNextManeuverMeters?.let(::formatDistance) ?: "—",
        primaryInstruction = currentManeuver?.instruction ?: "Prosegui sul percorso",
        targetRoad = currentRoadName
            ?: currentManeuver?.streetNames?.firstOrNull(),
        junctionSign = currentManeuver?.sign?.let { sign ->
            NavigationJunctionSignUiModel(
                exitNumber = sign.exitNumberElements.joinSignText(),
                branches = sign.exitBranchElements.joinSignText(),
                toward = sign.exitTowardElements.joinSignText(),
                exitName = sign.exitNameElements.joinSignText(),
            ).takeUnless { model ->
                listOf(model.exitNumber, model.branches, model.toward, model.exitName).all {
                    it.isNullOrBlank()
                }
            }
        },
        followingInstruction = nextManeuver?.instruction,
        followingManeuverVisual = nextManeuver?.let {
            maneuverVisual(it.type, it.instruction)
        },
        remainingDistance = distanceRemainingMeters?.let(::formatDistance) ?: "—",
        remainingDuration = totalDurationRemainingSeconds?.let(::formatDuration) ?: "—",
        arrivalTime = estimatedArrivalAt?.let {
            NAVIGATION_CLOCK_FORMATTER.format(it.atZone(ZoneId.systemDefault()))
        } ?: "—",
        progress = routeProgressFraction.coerceIn(0.0, 1.0).toFloat(),
        currentSpeedLimitKph = currentSpeedLimitKph,
        nextCngStop = nextFuelStop?.let { fuel ->
            NavigationCngUiModel(
                name = fuel.stop.displayName(),
                distance = formatDistance(fuel.distanceRemainingMeters),
                arrivalTime = fuel.stop.expectedArrivalAt?.format(NAVIGATION_CLOCK_FORMATTER),
            )
        },
        statusMessages = buildList {
            add(NavigationStatusUiModel(gpsStatusText(gpsStatus), NavigationStatusLevel.NORMAL))
            if (routeSource == NavigationRouteSource.CACHE) {
                add(
                    NavigationStatusUiModel(
                        "Navigazione disponibile sulla rotta salvata nel dispositivo.",
                        NavigationStatusLevel.POSITIVE,
                    ),
                )
            }
            add(trafficStatusUiModel(activeRoute.timing))
            if (connectivity == NavigationConnectivity.REROUTING_UNAVAILABLE) {
                add(
                    NavigationStatusUiModel(
                        "Connessione Compass assente: navigazione locale attiva, ricalcolo non disponibile.",
                        NavigationStatusLevel.WARNING,
                    ),
                )
            }
            if (routeSource == NavigationRouteSource.CACHE && activeRoute.fuelStops.isNotEmpty()) {
                add(
                    NavigationStatusUiModel(
                        "Dati CNG in cache: prezzi e orari non sono presentati come aggiornati.",
                        NavigationStatusLevel.WARNING,
                    ),
                )
            }
            activeRoute.gasolineFallback?.let { fallback ->
                add(
                    NavigationStatusUiModel(
                        "Fallback benzina attivo · uso stimato fino a " +
                            formatKilometersForNavigation(fallback.requiredGasolineRangeKm),
                        NavigationStatusLevel.POSITIVE,
                    ),
                )
            }
            if (offRouteStatus != OffRouteStatus.ON_ROUTE) {
                add(
                    NavigationStatusUiModel(
                        if (offRouteStatus == OffRouteStatus.OFF_ROUTE) {
                            "Fuori percorso confermato. Ricalcolo tramite Compass…"
                        } else {
                            "Verifica posizione rispetto al percorso…"
                        },
                        NavigationStatusLevel.WARNING,
                    ),
                )
            }
            when (reroutingStatus) {
                ReroutingStatus.IN_PROGRESS -> add(
                    NavigationStatusUiModel(
                        if (routeUpdateReason == RouteUpdateReason.FUEL_STOP_UNAVAILABLE) {
                            "Cerco una tappa CNG alternativa sicura…"
                        } else {
                            "Aggiornamento del percorso in corso…"
                        },
                        NavigationStatusLevel.POSITIVE,
                    ),
                )
                ReroutingStatus.FAILED -> add(
                    NavigationStatusUiModel(
                        when (routeUpdateFailure) {
                            RouteUpdateFailure.NO_SAFE_FUEL_ALTERNATIVE ->
                                "Nessuna alternativa CNG sicura: mantengo la tappa corrente."
                            RouteUpdateFailure.FUEL_RANGE_PLAN_REQUIRED ->
                                "Per sostituire questa tappa serve un piano autonomia predittivo."
                            RouteUpdateFailure.NETWORK_OR_SERVER,
                            null,
                            -> "Ricalcolo non disponibile: continuo sulla rotta scaricata."
                        },
                        NavigationStatusLevel.WARNING,
                    ),
                )
                ReroutingStatus.IDLE -> Unit
            }
        },
    )
}

private fun List<org.compass.cng.domain.model.ManeuverSignElement>.joinSignText(): String? =
    asSequence()
        .map { it.text.trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .take(3)
        .toList()
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(" / ")

internal fun NavigationFuelStop.displayName(): String = name ?: "MIMIT $mimitStationId"

internal fun trafficTimingText(timing: NavigationTiming): String = when {
    timing.trafficAware -> buildString {
        append("Traffico live incluso")
        timing.trafficDelaySeconds?.let { append(" · ritardo ${formatDuration(it)}") }
        timing.trafficObservedAt?.let {
            append(" · aggiornato ${NAVIGATION_CLOCK_FORMATTER.format(it)}")
        }
    }
    timing.trafficState in setOf("fresh", "mock") ->
        "Traffico aggiornato, ma questa rotta usa velocità standard."
    timing.trafficState == "configured" ->
        "Traffico configurato: attendo il primo aggiornamento."
    timing.trafficState == "stale" ->
        "Dati traffico scaduti: tempi di guida con velocità standard."
    else -> "Traffico live non disponibile: tempi di guida con velocità standard."
}

private fun trafficStatusUiModel(timing: NavigationTiming) = NavigationStatusUiModel(
    text = trafficTimingText(timing),
    level = if (timing.trafficAware) {
        NavigationStatusLevel.POSITIVE
    } else {
        NavigationStatusLevel.NORMAL
    },
)

private fun gpsStatusText(status: GpsStatus): String = when (status) {
    GpsStatus.UNAVAILABLE -> "GPS non disponibile"
    GpsStatus.ACQUIRING -> "Ricerca del segnale GPS…"
    GpsStatus.ACTIVE -> "GPS attivo · posizione agganciata al percorso"
    GpsStatus.LOST -> "Segnale GPS temporaneamente perso"
}

private fun formatKilometersForNavigation(kilometers: Double): String = String.format(
    Locale.ITALY,
    "%.1f km",
    kilometers,
)

private val NAVIGATION_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
