package org.compass.cng.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.compass.cng.BuildConfig
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.CngRouteLeg
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.ui.map.RouteMap

@Composable
fun RoutePlannerScreen(
    viewModel: RoutePlannerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(
        enabled = state.baseRoute != null && state.stage != PlannerStage.PREVIEW && !state.isBusy,
        onBack = viewModel::navigateBack,
    )
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                stage = state.stage,
                canNavigateBack = state.baseRoute != null && state.stage != PlannerStage.PREVIEW,
                onNavigateBack = viewModel::navigateBack,
            )
            val baseRoute = state.baseRoute
            when {
                baseRoute == null && state.operation == PlannerOperation.BASE_ROUTE -> {
                    LoadingState("Calcolo del percorso…")
                }
                baseRoute == null -> ErrorState(
                    title = "Percorso non disponibile",
                    message = state.message ?: "Il percorso non è disponibile.",
                    actionLabel = "Riprova",
                    onAction = viewModel::retryBaseRoute,
                )
                state.operation == PlannerOperation.CNG_CANDIDATES -> LoadingRouteState(
                    route = baseRoute,
                    message = "Cerco e valuto le stazioni Metano…",
                )
                else -> when (state.stage) {
                    PlannerStage.PREVIEW -> PreviewContent(
                        route = baseRoute,
                        onAddStop = viewModel::openAddStop,
                    )
                    PlannerStage.CONFIGURE_CNG -> ConfigureCngContent(
                        route = baseRoute,
                        rangeInput = state.effectiveRangeKmInput,
                        detourInput = state.maximumDetourMinutesInput,
                        message = state.message,
                        onRangeChanged = viewModel::updateEffectiveRange,
                        onDetourChanged = viewModel::updateMaximumDetour,
                        onSearch = viewModel::searchCngStations,
                    )
                    PlannerStage.CNG_CANDIDATES -> CandidateContent(
                        rankedStations = requireNotNull(state.rankedStations),
                        pendingStation = state.pendingStation,
                        message = state.message,
                        onSelect = viewModel::selectStation,
                    )
                    PlannerStage.SELECTED_ROUTE -> SelectedRouteContent(
                        selectedRoute = requireNotNull(state.selectedRoute),
                        onChangeStation = viewModel::navigateBack,
                        onRemoveStop = viewModel::removeCngStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    stage: PlannerStage,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canNavigateBack) {
            TextButton(onClick = onNavigateBack) {
                Text("Indietro")
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Column {
            Text(
                text = "Compass",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (stage) {
                    PlannerStage.PREVIEW -> "Anteprima percorso · Milano → Bologna"
                    PlannerStage.CONFIGURE_CNG -> "Aggiungi tappa · Metano"
                    PlannerStage.CNG_CANDIDATES -> "Stazioni Metano lungo il percorso"
                    PlannerStage.SELECTED_ROUTE -> "Percorso con rifornimento"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
private fun LoadingRouteState(route: RoutePreview, message: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(message)
                Text(
                    "Il calcolo usa distanze e tempi della rete stradale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(message)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun PreviewContent(route: RoutePreview, onAddStop: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f),
        )
        RouteSummary(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Button(
            onClick = onAddStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("Aggiungi tappa")
        }
        Text(
            text = "Indicazioni principali",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ManeuverList(
            maneuvers = route.maneuvers,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.52f),
        )
    }
}

@Composable
private fun ConfigureCngContent(
    route: RoutePreview,
    rangeInput: String,
    detourInput: String,
    message: String?,
    onRangeChanged: (String) -> Unit,
    onDetourChanged: (String) -> Unit,
    onSearch: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = route,
                mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tipo di tappa", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Metano (CNG)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Le stazioni chiuse all'orario di arrivo vengono escluse; gli orari mancanti restano sconosciuti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = detourInput,
                    onValueChange = onDetourChanged,
                    label = { Text("Deviazione massima (minuti)") },
                    supportingText = { Text("Sono ammessi solo i risultati entro questo limite.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rangeInput,
                    onValueChange = onRangeChanged,
                    label = { Text("Autonomia CNG effettiva (km)") },
                    supportingText = { Text("Usata per costruire il corridoio di ricerca.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { InlineError(it) }
                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                    Text("Cerca stazioni Metano")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun CandidateContent(
    rankedStations: RankedCngStations,
    pendingStation: RankedCngStation?,
    message: String?,
    onSelect: (RankedCngStation) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = rankedStations.baseRoute,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            candidateStations = rankedStations.candidates,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.38f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "${rankedStations.candidates.size} stazioni idonee",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Deviazione ≤ ${formatMinutesLimit(rankedStations.maximumDetourMinutes)} · traffico ${trafficLabel(rankedStations.trafficState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message?.let {
            InlineError(
                message = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (rankedStations.candidates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f),
                contentAlignment = Alignment.Center,
            ) {
                Text("Nessuna stazione rispetta il limite impostato.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f),
            ) {
                itemsIndexed(
                    items = rankedStations.candidates,
                    key = { _, station -> station.mimitStationId },
                ) { _, station ->
                    CandidateCard(
                        station = station,
                        selecting = pendingStation?.mimitStationId == station.mimitStationId,
                        enabled = pendingStation == null,
                        onSelect = { onSelect(station) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    station: RankedCngStation,
    selecting: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    RankingBadge(station.ranking.rank)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            station.name ?: "Stazione MIMIT ${station.mimitStationId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            listOfNotNull(station.municipality, station.province).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OpeningBadge(station.opening.state)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CandidateMetric("Deviazione", formatDetour(station.detourMinutes))
                CandidateMetric("Da partenza", formatDistance(station.distanceFromPreviousWaypointMeters))
                CandidateMetric("Arrivo", formatTime(station.stationEta))
            }

            Text(
                station.opening.openingHours ?: "Orari non disponibili",
                style = MaterialTheme.typography.bodySmall,
            )
            station.price?.let { price ->
                Text(
                    "${formatPrice(price)} · rilevato ${formatDateTime(price.observedAt)} · ${freshnessLabel(price.freshness)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text(
                "Prezzo CNG non disponibile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Punteggio ${formatScore(station.ranking.totalScore)} · deviazione ${formatScore(station.ranking.detourScore)} · apertura ${formatScore(station.ranking.openingScore)} · prezzo ${formatScore(station.ranking.priceScore)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                station.phone?.let { phone ->
                    TextButton(onClick = { uriHandler.openUri("tel:${phone.filterPhoneCharacters()}") }) {
                        Text("Chiama")
                    }
                }
                Button(onClick = onSelect, enabled = enabled) {
                    if (selecting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(18.dp)
                                .height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Calcolo…")
                    } else {
                        Text("Scegli")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedRouteContent(
    selectedRoute: RouteWithCngStop,
    onChangeStation: () -> Unit,
    onRemoveStop: () -> Unit,
) {
    val route = selectedRoute.asRoutePreview()
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            cngStop = selectedRoute.selectedStop.location,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.42f),
        )
        RouteSummary(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tappa Metano", style = MaterialTheme.typography.labelMedium)
                Text(
                    selectedRoute.selectedStop.name
                        ?: "Stazione MIMIT ${selectedRoute.selectedStop.mimitStationId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    listOfNotNull(
                        selectedRoute.selectedStop.municipality,
                        selectedRoute.selectedStop.province,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRemoveStop, modifier = Modifier.weight(1f)) {
                Text("Rimuovi tappa")
            }
            Button(onClick = onChangeStation, modifier = Modifier.weight(1f)) {
                Text("Cambia stazione")
            }
        }
        SelectedRouteManeuvers(
            legs = selectedRoute.legs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.58f),
        )
    }
}

@Composable
private fun SelectedRouteManeuvers(
    legs: List<CngRouteLeg>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        legs.forEachIndexed { legIndex, leg ->
            item(key = "leg-$legIndex") {
                Text(
                    if (legIndex == 0) "Verso la stazione" else "Dalla stazione a Bologna",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(
                items = leg.route.maneuvers,
                key = { maneuverIndex, _ -> "$legIndex-$maneuverIndex" },
            ) { maneuverIndex, maneuver ->
                ManeuverRow(number = maneuverIndex + 1, maneuver = maneuver)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
private fun RouteSummary(route: RoutePreview, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryValue(label = "Distanza", value = formatDistance(route.distanceMeters))
            SummaryValue(label = "Durata", value = formatDuration(route.durationSeconds))
            SummaryValue(label = "Routing", value = route.provider.replaceFirstChar(Char::uppercase))
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManeuverList(maneuvers: List<Maneuver>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        itemsIndexed(maneuvers) { index, maneuver ->
            ManeuverRow(number = index + 1, maneuver = maneuver)
            if (index < maneuvers.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
private fun ManeuverRow(number: Int, maneuver: Maneuver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(maneuver.instruction, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${formatDistance(maneuver.distanceMeters)} · ${formatDuration(maneuver.durationSeconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RankingBadge(rank: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "#$rank",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun OpeningBadge(state: OpeningState) {
    val (label, color) = when (state) {
        OpeningState.OPEN -> "Aperto all'arrivo" to Color(0xFF146C3A)
        OpeningState.CLOSED -> "Chiuso all'arrivo" to MaterialTheme.colorScheme.error
        OpeningState.UNKNOWN -> "Orario sconosciuto" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun CandidateMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineError(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

internal fun formatDistance(distanceMeters: Double): String = if (distanceMeters >= 1_000) {
    String.format(Locale.ITALY, "%.1f km", distanceMeters / 1_000)
} else {
    "${distanceMeters.toInt()} m"
}

internal fun formatDuration(durationSeconds: Double): String {
    val totalMinutes = (durationSeconds / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} h ${minutes} min" else "$minutes min"
}

private fun formatDetour(minutes: Double): String = String.format(Locale.ITALY, "+%.1f min", minutes)

private fun formatMinutesLimit(minutes: Double): String = String.format(Locale.ITALY, "%.0f min", minutes)

private fun formatTime(value: OffsetDateTime): String = value.format(DateTimeFormatter.ofPattern("HH:mm"))

private fun formatDateTime(value: OffsetDateTime): String = value.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

private fun formatPrice(price: CngPrice): String = String.format(
    Locale.ITALY,
    "%.3f %s/%s",
    price.unitPrice,
    price.currency,
    price.unit,
)

private fun formatScore(score: Double): String = String.format(Locale.ITALY, "%.0f%%", score * 100)

private fun freshnessLabel(freshness: PriceFreshness): String = when (freshness) {
    PriceFreshness.FRESH -> "prezzo recente"
    PriceFreshness.STALE -> "prezzo non recente"
    PriceFreshness.FUTURE_OBSERVATION -> "data prezzo anomala"
    PriceFreshness.UNKNOWN -> "freschezza sconosciuta"
}

private fun trafficLabel(trafficState: String): String = when (trafficState) {
    "not_configured" -> "live non configurato"
    else -> trafficState
}

private fun String.filterPhoneCharacters(): String = filter { it.isDigit() || it == '+' }
