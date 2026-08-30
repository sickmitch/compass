package org.compass.cng.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import org.compass.cng.domain.model.CngItineraryRouteLeg
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.PredictiveCngStation
import org.compass.cng.domain.model.PredictiveCngSuggestion
import org.compass.cng.domain.model.PredictiveItineraryStop
import org.compass.cng.domain.model.PredictiveSuggestionState
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankedCngStations
import org.compass.cng.domain.model.RoutePreview
import org.compass.cng.domain.model.RouteWithCngStop
import org.compass.cng.domain.model.RouteWithCngItinerary
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
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
                state.operation == PlannerOperation.PREDICTIVE_CANDIDATES -> LoadingRouteState(
                    route = baseRoute,
                    message = "Valuto autonomia e stazioni raggiungibili…",
                )
                else -> when (state.stage) {
                    PlannerStage.PREVIEW -> PreviewContent(
                        route = baseRoute,
                        onAddStop = viewModel::openAddStop,
                        onEvaluateRange = viewModel::openPredictiveRange,
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
                    PlannerStage.CONFIGURE_PREDICTIVE -> ConfigurePredictiveContent(
                        route = baseRoute,
                        effectiveRangeInput = state.effectiveRangeKmInput,
                        remainingRangeInput = state.estimatedRemainingRangeKmInput,
                        reserveRangeInput = state.reserveRangeKmInput,
                        detourInput = state.maximumDetourMinutesInput,
                        message = state.message,
                        onEffectiveRangeChanged = viewModel::updateEffectiveRange,
                        onRemainingRangeChanged = viewModel::updateEstimatedRemainingRange,
                        onReserveRangeChanged = viewModel::updateReserveRange,
                        onDetourChanged = viewModel::updateMaximumDetour,
                        onEvaluate = viewModel::evaluatePredictiveRange,
                    )
                    PlannerStage.CNG_CANDIDATES -> CandidateContent(
                        rankedStations = requireNotNull(state.rankedStations),
                        predictiveSuggestion = state.predictiveSuggestion,
                        pendingStation = state.pendingStation,
                        message = state.message,
                        onSelect = viewModel::selectStation,
                    )
                    PlannerStage.PREDICTIVE_ITINERARY -> PredictiveItineraryContent(
                        suggestion = requireNotNull(state.predictiveSuggestion),
                        calculating = state.operation == PlannerOperation.SELECTED_ROUTE,
                        message = state.message,
                        onCalculateRoute = viewModel::acceptPredictiveItinerary,
                    )
                    PlannerStage.PREDICTIVE_STATUS -> PredictiveStatusContent(
                        suggestion = requireNotNull(state.predictiveSuggestion),
                        onChangeInputs = viewModel::navigateBack,
                    )
                    PlannerStage.SELECTED_ROUTE -> {
                        val itineraryRoute = state.selectedItineraryRoute
                        if (itineraryRoute != null) {
                            SelectedItineraryRouteContent(
                                selectedRoute = itineraryRoute,
                                onChangePlan = viewModel::navigateBack,
                                onRemoveStops = viewModel::removeCngStop,
                            )
                        } else {
                            SelectedRouteContent(
                                selectedRoute = requireNotNull(state.selectedRoute),
                                onChangeStation = viewModel::navigateBack,
                                onRemoveStop = viewModel::removeCngStop,
                            )
                        }
                    }
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
                    PlannerStage.CONFIGURE_PREDICTIVE -> "Valuta autonomia CNG"
                    PlannerStage.CNG_CANDIDATES -> "Stazioni Metano lungo il percorso"
                    PlannerStage.PREDICTIVE_ITINERARY -> "Piano rifornimenti CNG"
                    PlannerStage.PREDICTIVE_STATUS -> "Autonomia CNG"
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
private fun PreviewContent(
    route: RoutePreview,
    onAddStop: () -> Unit,
    onEvaluateRange: () -> Unit,
) {
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
        OutlinedButton(
            onClick = onEvaluateRange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Valuta autonomia CNG")
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
private fun ConfigurePredictiveContent(
    route: RoutePreview,
    effectiveRangeInput: String,
    remainingRangeInput: String,
    reserveRangeInput: String,
    detourInput: String,
    message: String?,
    onEffectiveRangeChanged: (String) -> Unit,
    onRemainingRangeChanged: (String) -> Unit,
    onReserveRangeChanged: (String) -> Unit,
    onDetourChanged: (String) -> Unit,
    onEvaluate: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            RouteMap(
                route = route,
                mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Rifornimento predittivo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Inserisci una stima reale dell'autonomia residua. Compass non legge ancora il livello del veicolo e non inventa questo dato.",
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
                    value = remainingRangeInput,
                    onValueChange = onRemainingRangeChanged,
                    label = { Text("Autonomia CNG residua stimata (km)") },
                    supportingText = { Text("Dato fornito dal conducente, non da telemetria.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reserveRangeInput,
                    onValueChange = onReserveRangeChanged,
                    label = { Text("Riserva di sicurezza (km)") },
                    supportingText = { Text("Non vengono suggerite stazioni oltre questa soglia.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = effectiveRangeInput,
                    onValueChange = onEffectiveRangeChanged,
                    label = { Text("Autonomia CNG effettiva a pieno (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = detourInput,
                    onValueChange = onDetourChanged,
                    label = { Text("Deviazione massima (minuti)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { InlineError(it) }
                Button(onClick = onEvaluate, modifier = Modifier.fillMaxWidth()) {
                    Text("Valuta e suggerisci una stazione")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
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
    predictiveSuggestion: PredictiveCngSuggestion?,
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
                    if (predictiveSuggestion == null) {
                        "${rankedStations.candidates.size} stazioni idonee"
                    } else {
                        "${rankedStations.candidates.size} stazioni raggiungibili"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Deviazione ≤ ${formatMinutesLimit(rankedStations.maximumDetourMinutes)} · traffico ${trafficLabel(rankedStations.trafficState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                predictiveSuggestion?.let { suggestion ->
                    Text(
                        "Utilizzabili ${formatKilometers(suggestion.rangeBasis.usableRangeBeforeReserveKm)} prima della riserva",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        predictiveStation = predictiveSuggestion
                            ?.candidates
                            ?.firstOrNull {
                                it.station.mimitStationId == station.mimitStationId
                            },
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
    predictiveStation: PredictiveCngStation?,
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

            predictiveStation?.let { predictive ->
                Text(
                    "All'arrivo: ${formatKilometers(predictive.estimatedRemainingRangeAtArrivalKm)} di autonomia · ${formatSignedKilometers(predictive.reserveMarginAtArrivalKm)} sulla riserva",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
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
private fun PredictiveItineraryContent(
    suggestion: PredictiveCngSuggestion,
    calculating: Boolean,
    message: String?,
    onCalculateRoute: () -> Unit,
) {
    val itinerary = requireNotNull(suggestion.itinerary)
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = suggestion.baseRoute,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            cngStops = itinerary.stops.map { it.station.location },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.36f),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.64f),
        ) {
            item(key = "plan-summary") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${itinerary.stops.size} rifornimenti pianificati",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Ogni tappa presume un pieno fino a ${formatKilometers(suggestion.rangeBasis.effectiveCngRangeKm)}; tutte le tratte conservano ${formatKilometers(suggestion.rangeBasis.reserveCngRangeKm)} di riserva.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Distanze stradali · deviazione per stazione ≤ ${formatMinutesLimit(suggestion.maximumDetourMinutes)} · traffico ${trafficLabel(suggestion.rangeBasis.trafficState)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let { InlineError(it) }
                }
            }
            itemsIndexed(
                items = itinerary.stops,
                key = { _, stop -> stop.station.mimitStationId },
            ) { _, stop ->
                PredictiveItineraryStopCard(stop)
            }
            item(key = "destination-leg") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Ultima tratta · Bologna",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${formatDistance(itinerary.destinationLeg.distanceMeters)} · arrivo ${formatTime(itinerary.destinationLeg.destinationEta)}",
                        )
                        Text(
                            "Autonomia stimata all'arrivo ${formatKilometers(itinerary.destinationLeg.estimatedRemainingRangeAtArrivalKm)} · ${formatSignedKilometers(itinerary.destinationLeg.reserveMarginAtArrivalKm)} sulla riserva",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item(key = "calculate-route") {
                Button(
                    onClick = onCalculateRoute,
                    enabled = !calculating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    if (calculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifico tutte le tratte…")
                    } else {
                        Text("Calcola percorso con ${itinerary.stops.size} soste")
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictiveItineraryStopCard(stop: PredictiveItineraryStop) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Rifornimento ${stop.sequence} · ${stop.station.name ?: "MIMIT ${stop.station.mimitStationId}"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(
                            stop.station.municipality,
                            stop.station.province,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OpeningBadge(stop.opening.state)
            }
            Text(
                "Tratta precedente ${formatDistance(stop.legDistanceMeters)} · arrivo ${formatTime(stop.arrivalAt)}",
            )
            Text(
                "Autonomia all'arrivo ${formatKilometers(stop.estimatedRemainingRangeAtArrivalKm)} · ${formatSignedKilometers(stop.reserveMarginAtArrivalKm)} sulla riserva",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stop.opening.openingHours ?: "Orari non disponibili",
                style = MaterialTheme.typography.bodySmall,
            )
            stop.price?.let { price ->
                Text(
                    "${formatPrice(price)} · rilevato ${formatDateTime(price.observedAt)} · ${freshnessLabel(price.freshness)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text(
                "Prezzo CNG non disponibile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stop.phone?.let { phone ->
                TextButton(
                    onClick = { uriHandler.openUri("tel:${phone.filterPhoneCharacters()}") },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Chiama")
                }
            }
        }
    }
}

@Composable
private fun PredictiveStatusContent(
    suggestion: PredictiveCngSuggestion,
    onChangeInputs: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = suggestion.baseRoute,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.52f)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val status = predictiveStatusCopy(suggestion.state)
                    Text(
                        status.first,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(status.second)
                    Text(
                        "Residua stimata ${formatKilometers(suggestion.rangeBasis.estimatedRemainingCngRangeKm)} · riserva ${formatKilometers(suggestion.rangeBasis.reserveCngRangeKm)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Percorso rimanente ${formatKilometers(suggestion.rangeBasis.remainingRouteDistanceKm)} · intervallo utilizzabile ${formatKilometers(suggestion.rangeBasis.usableRangeBeforeReserveKm)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Stima fornita dal conducente · traffico ${trafficLabel(suggestion.rangeBasis.trafficState)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onChangeInputs, modifier = Modifier.fillMaxWidth()) {
                        Text("Modifica autonomia")
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
            cngStops = listOf(selectedRoute.selectedStop.location),
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
private fun SelectedItineraryRouteContent(
    selectedRoute: RouteWithCngItinerary,
    onChangePlan: () -> Unit,
    onRemoveStops: () -> Unit,
) {
    val route = selectedRoute.asRoutePreview()
    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            mapStyleUrl = BuildConfig.COMPASS_MAP_STYLE_URL,
            cngStops = selectedRoute.selectedStops.map { it.location },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.38f),
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
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${selectedRoute.selectedStops.size} soste CNG verificate",
                    style = MaterialTheme.typography.labelMedium,
                )
                selectedRoute.selectedStops.forEachIndexed { index, stop ->
                    Text(
                        "${index + 1}. ${stop.name ?: "MIMIT ${stop.mimitStationId}"} · ${listOfNotNull(stop.municipality, stop.province).joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Ogni tratta conserva la riserva impostata.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRemoveStops, modifier = Modifier.weight(1f)) {
                Text("Rimuovi soste")
            }
            Button(onClick = onChangePlan, modifier = Modifier.weight(1f)) {
                Text("Rivedi piano")
            }
        }
        SelectedItineraryManeuvers(
            legs = selectedRoute.legs,
            stopCount = selectedRoute.selectedStops.size,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.62f),
        )
    }
}

@Composable
private fun SelectedItineraryManeuvers(
    legs: List<CngItineraryRouteLeg>,
    stopCount: Int,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        legs.forEachIndexed { legIndex, leg ->
            item(key = "itinerary-leg-$legIndex") {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        itineraryLegTitle(legIndex, stopCount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${formatDistance(leg.route.distanceMeters)} · autonomia all'arrivo ${formatKilometers(leg.estimatedRemainingRangeAtArrivalKm)} · ${formatSignedKilometers(leg.reserveMarginAtArrivalKm)} sulla riserva",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(
                items = leg.route.maneuvers,
                key = { maneuverIndex, _ -> "itinerary-$legIndex-$maneuverIndex" },
            ) { maneuverIndex, maneuver ->
                ManeuverRow(number = maneuverIndex + 1, maneuver = maneuver)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

private fun itineraryLegTitle(legIndex: Int, stopCount: Int): String = when {
    legIndex == 0 -> "Verso il rifornimento 1"
    legIndex == stopCount -> "Dall'ultimo rifornimento a Bologna"
    else -> "Dal rifornimento $legIndex al rifornimento ${legIndex + 1}"
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

private fun formatKilometers(kilometers: Double): String = String.format(
    Locale.ITALY,
    "%.1f km",
    kilometers,
)

private fun formatSignedKilometers(kilometers: Double): String = String.format(
    Locale.ITALY,
    "%+.1f km",
    kilometers,
)

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

private fun predictiveStatusCopy(state: PredictiveSuggestionState): Pair<String, String> = when (state) {
    PredictiveSuggestionState.NOT_NEEDED -> Pair(
        "Rifornimento non necessario",
        "La destinazione è raggiungibile conservando la riserva impostata.",
    )
    PredictiveSuggestionState.NO_REACHABLE_STATION -> Pair(
        "Nessuna stazione raggiungibile",
        "Con questa stima non risulta una stazione raggiungibile prima della riserva. Non proseguire facendo affidamento su questo itinerario.",
    )
    PredictiveSuggestionState.NO_ELIGIBLE_STATION -> Pair(
        "Nessuna stazione idonea",
        "Esistono stazioni raggiungibili, ma nessuna rispetta disponibilità e deviazione massima.",
    )
    PredictiveSuggestionState.NO_COMPLETE_ITINERARY -> Pair(
        "Viaggio CNG non completabile",
        "Esiste una prima stazione raggiungibile, ma non una catena completa di rifornimenti che conservi la riserva fino a Bologna. Non fare affidamento su questo itinerario.",
    )
    PredictiveSuggestionState.SUGGESTED -> error("suggested results use the itinerary screen")
}

private fun String.filterPhoneCharacters(): String = filter { it.isDigit() || it == '+' }
