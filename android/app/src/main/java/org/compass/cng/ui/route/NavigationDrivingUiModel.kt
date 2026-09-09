package org.compass.cng.ui.route

import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import org.compass.cng.navigation.GpsStatus
import org.compass.cng.navigation.NavigationConnectivity
import org.compass.cng.navigation.NavigationFuelStop
import org.compass.cng.navigation.NavigationFuelStopLifecycle
import org.compass.cng.navigation.NavigationFuelStopProgress
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
    val cngStops: List<NavigationCngUiModel>,
    val isRouteRecalculationInProgress: Boolean,
    val statusMessages: List<NavigationStatusUiModel>,
)

internal data class NavigationJunctionSignUiModel(
    val exitNumber: String?,
    val branches: String?,
    val toward: String?,
    val exitName: String?,
)

internal data class NavigationCngUiModel(
    val stationId: String,
    val name: String,
    val locationLabel: String?,
    val operatorLabel: String?,
    val distance: String,
    val arrivalTime: String?,
    val lifecycle: NavigationFuelStopLifecycle,
    val lifecycleLabel: String,
    val availabilityLabel: String?,
    val availabilityIsWarning: Boolean,
    val openingHours: String?,
    val price: String?,
    val phone: String?,
    val dwellDuration: String,
    val refuelingRemainingDuration: String?,
    val refuelingPlannedDurationElapsed: Boolean,
    val reason: String,
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

internal fun NavigationState.toDrivingUiModel(
    displayZone: ZoneId = ZoneId.systemDefault(),
): NavigationDrivingUiModel {
    val activeRoute = requireNotNull(route)
    val maneuver = currentManeuver
    val junctionSign = maneuver?.sign?.let { sign ->
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
    }
    val maneuverRoad = currentRoadName ?: maneuver?.streetNames?.firstOrNull()
    val refuellingVisit = activeFuelStopVisit
    return NavigationDrivingUiModel(
        maneuverVisual = if (refuellingVisit != null) {
            maneuverVisual(4, "Rifornimento CNG")
        } else {
            maneuverVisual(maneuver?.type, maneuver?.instruction)
        },
        roundaboutExitCount = if (refuellingVisit == null) {
            maneuver?.roundaboutExitCount?.takeIf { it > 0 }
        } else {
            null
        },
        distanceToManeuver = if (refuellingVisit != null) {
            "Sosta CNG"
        } else {
            distanceToNextManeuverMeters?.let(::formatDistance) ?: "—"
        },
        primaryInstruction = if (refuellingVisit != null) {
            "Rifornimento in corso"
        } else {
            maneuver?.instruction ?: "Prosegui sul percorso"
        },
        targetRoad = refuellingVisit?.stop?.displayName()
            ?: maneuverRoad?.takeUnless { junctionSign?.repeats(it) == true },
        junctionSign = junctionSign.takeIf { refuellingVisit == null },
        followingInstruction = if (refuellingVisit != null) {
            "Conferma quando hai terminato"
        } else {
            nextManeuver?.instruction
        },
        followingManeuverVisual = if (refuellingVisit == null) {
            nextManeuver?.let { maneuverVisual(it.type, it.instruction) }
        } else {
            null
        },
        remainingDistance = distanceRemainingMeters?.let(::formatDistance) ?: "—",
        remainingDuration = totalDurationRemainingSeconds?.let(::formatDuration) ?: "—",
        arrivalTime = estimatedArrivalAt?.let {
            NAVIGATION_CLOCK_FORMATTER.format(it.atZone(displayZone))
        } ?: "—",
        progress = routeProgressFraction.coerceIn(0.0, 1.0).toFloat(),
        currentSpeedLimitKph = currentSpeedLimitKph,
        nextCngStop = nextFuelStop?.toCngUiModel(
            activeRoute,
            routeSource,
            connectivity,
            displayZone,
            activeFuelStopVisit?.takeIf { visit -> visit.stop.sequence == nextFuelStop.stop.sequence },
        ),
        cngStops = fuelStopProgress.map {
            it.toCngUiModel(
                activeRoute,
                routeSource,
                connectivity,
                displayZone,
                activeFuelStopVisit?.takeIf { visit -> visit.stop.sequence == it.stop.sequence },
            )
        },
        isRouteRecalculationInProgress = reroutingStatus == ReroutingStatus.IN_PROGRESS,
        statusMessages = buildList {
            add(NavigationStatusUiModel(gpsStatusText(gpsStatus), NavigationStatusLevel.NORMAL))
            activeFuelStopVisit?.let { visit ->
                add(
                    NavigationStatusUiModel(
                        if (visit.plannedDurationElapsed) {
                            "Tempo di rifornimento previsto concluso: conferma per riprendere."
                        } else {
                            "Rifornimento CNG in corso · " +
                                "${formatRemainingDuration(visit.remainingDwellSeconds)} rimanenti."
                        },
                        NavigationStatusLevel.POSITIVE,
                    ),
                )
            }
            if (routeSource == NavigationRouteSource.CACHE) {
                add(
                    NavigationStatusUiModel(
                        "Navigazione disponibile sulla rotta salvata nel dispositivo.",
                        NavigationStatusLevel.POSITIVE,
                    ),
                )
            }
            if (connectivity == NavigationConnectivity.ONLINE) {
                add(trafficStatusUiModel(activeRoute.timing, displayZone))
            } else {
                add(
                    NavigationStatusUiModel(
                        "Traffico non aggiornabile: durata ed ETA continuano dalla rotta locale.",
                        NavigationStatusLevel.WARNING,
                    ),
                )
            }
            if (connectivity != NavigationConnectivity.ONLINE) {
                add(
                    NavigationStatusUiModel(
                        when (connectivity) {
                            NavigationConnectivity.OFFLINE ->
                                "Rete assente: guida locale attiva sulla rotta scaricata."
                            NavigationConnectivity.RECOVERING ->
                                "Connessione ripristinata: aggiorno traffico e percorso in sicurezza…"
                            NavigationConnectivity.REROUTING_UNAVAILABLE ->
                                "Compass non raggiungibile: guida locale attiva, ricalcolo non disponibile."
                            NavigationConnectivity.ONLINE -> error("handled above")
                        },
                        NavigationStatusLevel.WARNING,
                    ),
                )
            }
            if ((routeSource == NavigationRouteSource.CACHE ||
                    connectivity != NavigationConnectivity.ONLINE) &&
                activeRoute.fuelStops.isNotEmpty()
            ) {
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

private fun NavigationFuelStopProgress.toCngUiModel(
    route: org.compass.cng.navigation.NavigationRoute,
    routeSource: NavigationRouteSource,
    connectivity: NavigationConnectivity,
    displayZone: ZoneId,
    activeVisit: org.compass.cng.navigation.NavigationFuelStopVisit?,
): NavigationCngUiModel {
    val dynamicDetailsAllowed = routeSource == NavigationRouteSource.LIVE &&
        connectivity == NavigationConnectivity.ONLINE
    val arrivalForFreshness = estimatedArrivalAt?.atOffset(ZoneOffset.UTC)
        ?: stop.expectedArrivalAt
    val openingIsApplicable = dynamicDetailsAllowed && stop.opening?.let { opening ->
        val arrival = arrivalForFreshness ?: return@let false
        opening.validation == org.compass.cng.domain.model.OpeningValidation.VALID &&
            kotlin.math.abs(Duration.between(opening.evaluatedAt, arrival).toMinutes()) <=
            OPENING_ETA_TOLERANCE_MINUTES
    } == true
    val availabilityLabel = if (openingIsApplicable) {
        when (stop.opening.state) {
            org.compass.cng.domain.model.OpeningState.OPEN -> "Aperto all'arrivo"
            org.compass.cng.domain.model.OpeningState.CLOSED -> "Chiuso all'arrivo"
            else -> null
        }
    } else {
        null
    }
    val visiblePrice = stop.price?.takeIf { price ->
        val evaluatedAt = price.ageSeconds?.takeIf(Double::isFinite)?.let { ageSeconds ->
            price.observedAt.plusNanos((ageSeconds * NANOS_PER_SECOND).toLong())
        }
        val arrival = arrivalForFreshness
        dynamicDetailsAllowed &&
            price.freshness == org.compass.cng.domain.model.PriceFreshness.FRESH &&
            evaluatedAt != null &&
            arrival != null &&
            kotlin.math.abs(Duration.between(evaluatedAt, arrival).toMinutes()) <=
            PRICE_ETA_TOLERANCE_MINUTES
    }
    return NavigationCngUiModel(
        stationId = stop.mimitStationId,
        name = stop.displayName(),
        locationLabel = listOfNotNull(stop.municipality, stop.province)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .ifBlank { null },
        operatorLabel = stop.operator ?: stop.brand,
        distance = formatDistance(distanceRemainingMeters),
        arrivalTime = estimatedArrivalAt?.let {
            NAVIGATION_CLOCK_FORMATTER.format(it.atZone(displayZone))
        } ?: stop.expectedArrivalAt?.let {
            NAVIGATION_CLOCK_FORMATTER.format(it.atZoneSameInstant(displayZone))
        },
        lifecycle = lifecycle,
        lifecycleLabel = when (lifecycle) {
            NavigationFuelStopLifecycle.PLANNED -> "Pianificata"
            NavigationFuelStopLifecycle.APPROACHING -> "In avvicinamento"
            NavigationFuelStopLifecycle.ARRIVED -> "Sei arrivato"
            NavigationFuelStopLifecycle.REFUELING -> "Rifornimento"
            NavigationFuelStopLifecycle.COMPLETED -> "Completata"
            NavigationFuelStopLifecycle.SKIPPED -> "Saltata"
            NavigationFuelStopLifecycle.REPLACED -> "Sostituita"
        },
        availabilityLabel = availabilityLabel,
        availabilityIsWarning = availabilityLabel == "Chiuso all'arrivo",
        openingHours = stop.opening?.openingHours?.takeIf { openingIsApplicable },
        price = visiblePrice?.let {
            String.format(Locale.ITALY, "%.3f %s/%s", it.unitPrice, it.currency, it.unit)
        },
        phone = stop.phone,
        dwellDuration = formatDuration(stop.dwellTimeSeconds.toDouble()),
        refuelingRemainingDuration = activeVisit?.remainingDwellSeconds
            ?.let(::formatRemainingDuration),
        refuelingPlannedDurationElapsed = activeVisit?.plannedDurationElapsed == true,
        reason = if (route.fuelPlan != null) {
            "Tappa prevista per rispettare autonomia e riserva CNG"
        } else {
            "Tappa CNG scelta per questo viaggio"
        },
    )
}

private fun NavigationJunctionSignUiModel.repeats(label: String): Boolean {
    val labelKey = label.signComparisonKey()
    return labelKey.isNotEmpty() && listOf(branches, toward, exitName)
        .filterNotNull()
        .any { it.signComparisonKey() == labelKey }
}

private fun String.signComparisonKey(): String = lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

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

internal fun trafficTimingText(
    timing: NavigationTiming,
    displayZone: ZoneId = ZoneId.systemDefault(),
): String = when {
    timing.trafficAware -> buildString {
        append("Traffico live incluso")
        timing.trafficDelaySeconds?.let { append(" · ritardo ${formatDuration(it)}") }
        timing.trafficObservedAt?.let {
            append(
                " · aggiornato ${NAVIGATION_CLOCK_FORMATTER.format(it.atZoneSameInstant(displayZone))}",
            )
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

private fun trafficStatusUiModel(
    timing: NavigationTiming,
    displayZone: ZoneId,
) = NavigationStatusUiModel(
    text = trafficTimingText(timing, displayZone),
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

private fun formatRemainingDuration(seconds: Double): String {
    val totalMinutes = ceil(seconds.coerceAtLeast(0.0) / 60.0).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} h ${minutes} min" else "$minutes min"
}

private val NAVIGATION_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val OPENING_ETA_TOLERANCE_MINUTES = 30L
private const val PRICE_ETA_TOLERANCE_MINUTES = 30L
private const val NANOS_PER_SECOND = 1_000_000_000.0
