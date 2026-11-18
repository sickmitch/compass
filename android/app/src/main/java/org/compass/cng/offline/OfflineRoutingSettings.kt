package org.compass.cng.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    val controller = (context.applicationContext as CompassApplication).container.offlineRouting
    val state by controller.status.collectAsState()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { controller.importPackage(uri) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Routing offline", style = MaterialTheme.typography.titleMedium)
            Text(if (state.ready) "${state.label} · ${state.sourceDate}" else "Nessuna area installata")
            Text("Usato automaticamente per il ricalcolo auto se Compass non è raggiungibile. Il pacchetto stradale non include la cartografia visuale.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(enabled = !state.busy, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                Text("Importa pacchetto offline")
            }
            if (state.ready) Text("© OpenStreetMap contributors — ODbL 1.0", style = MaterialTheme.typography.labelSmall)
        }
    }
}
