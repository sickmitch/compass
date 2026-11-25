package org.compass.cng.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.compass.cng.CompassApplication
import kotlinx.coroutines.launch

@Composable
fun OfflineRoutingSettings() {
    val context = LocalContext.current
    val container = (context.applicationContext as CompassApplication).container
    val controller = container.offlineRouting
    val downloads = container.offlineAssets
    val remote by downloads.status.collectAsState()
    LaunchedEffect(Unit) { downloads.refresh() }
    val state by controller.status.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingRemoval by remember { mutableStateOf<OfflineRoutingPackage?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { controller.importPackages(uris) }
    }
    remote.confirmation?.let { prompt ->
        AlertDialog(onDismissRequest = downloads::dismissMobileConfirmation,
            title = { Text("Scaricare usando i dati mobili?") },
            text = { Text("Il download richiede circa ${prompt.bytes / (1024 * 1024)} MB. Potrebbe consumare una parte significativa del tuo piano dati. Vuoi continuare?") },
            confirmButton = { TextButton(enabled = !remote.busy, onClick = downloads::confirmMobileDownload) { Text("Scarica") } },
            dismissButton = { TextButton(onClick = downloads::dismissMobileConfirmation) { Text("Annulla") } })
    }
    pendingRemoval?.let { selected ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Rimuovere ${selected.label}?") },
            text = { Text("Il pacchetto verrà eliminato dal telefono. Gli altri pacchetti resteranno disponibili.") },
            confirmButton = {
                TextButton(enabled = !state.busy && !remote.busy, onClick = {
                    pendingRemoval = null
                    scope.launch { controller.removePackage(selected.id); downloads.clearSubscriptionForRemovedPackage() }
                }) { Text("Rimuovi") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Annulla") } },
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Routing offline", style = MaterialTheme.typography.titleMedium)
            Text("Aree disponibili sul server", style = MaterialTheme.typography.titleSmall)
            Text("Scarica un’area e attiva gli aggiornamenti automatici. Dal lunedì il client cerca nuove versioni su reti non a consumo (Wi-Fi); Android può rinviare il controllo per risparmiare batteria.", style = MaterialTheme.typography.bodySmall)
            remote.catalog.packages.forEach { area ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(offlineAreaLabels.getValue(area.area))
                        Text("${area.bytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
                    }
                    if (area.area in remote.subscribed) {
                        TextButton(enabled = !remote.busy && !state.busy,
                            onClick = { downloads.subscribe(area.area, false) }) { Text("Disattiva aggiornamenti") }
                    } else {
                        TextButton(enabled = !remote.busy && !state.busy,
                            onClick = { downloads.subscribe(area.area, true) }) { Text("Scarica e sottoscrivi") }
                    }
                }
            }
            if (remote.catalog.packages.isEmpty() && !remote.busy) Text("Nessun pacchetto disponibile: aggiorna il catalogo.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !remote.busy && !state.busy, onClick = { downloads.refresh() }) { Text("Catalogo") }
                OutlinedButton(enabled = !remote.busy && !state.busy && remote.subscribed.isNotEmpty(),
                    onClick = { downloads.updateNow() }) { Text("Aggiorna aree") }
            }
            if (remote.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            remote.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(if (state.packages.isNotEmpty()) "Pacchetti installati: ${state.packages.size}" else "Nessuna area installata")
            Text("Puoi importare più ZIP, anche insieme. Il numero dei pacchetti non ha un limite fisso: serve spazio sul telefono.", style = MaterialTheme.typography.bodySmall)
            if (state.packages.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(state.packages, key = { it.id }) { dataset ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(dataset.label, style = MaterialTheme.typography.bodyMedium)
                                Text(dataset.graphSet?.let { "Area combinabile · set $it" } ?: "Pacchetto indipendente", style = MaterialTheme.typography.labelSmall)
                                Text(if (dataset.usable) "${dataset.sourceDate} · ${dataset.bytes / (1024 * 1024)} MB" else "Rimuovi e reimporta questo pacchetto", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = !state.busy && !remote.busy, onClick = { pendingRemoval = dataset }) { Text("Rimuovi") }
                        }
                    }
                }
            }
            Text("Le aree esportate dallo stesso set si usano insieme, anche per viaggi tra regioni. I vecchi ZIP indipendenti restano utilizzabili singolarmente: per combinarli occorre riesportarli da un grafo comune.", style = MaterialTheme.typography.bodySmall)
            Text("Usa preferiti, coordinate o punti sulla mappa. Ricerca indirizzi, piano metano e cartografia visuale non sono inclusi.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(enabled = !state.busy && !remote.busy, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                Text("Importa pacchetti offline")
            }
            if (state.ready) Text("© OpenStreetMap contributors — ODbL 1.0", style = MaterialTheme.typography.labelSmall)
        }
    }
}
