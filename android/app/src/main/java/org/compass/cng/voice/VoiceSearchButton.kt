package org.compass.cng.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.compass.cng.CompassApplication

@Composable
fun VoiceSearchButton(enabled: Boolean, onQuery: (String) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val application = context.applicationContext as? CompassApplication ?: return
    val controller = application.container.voiceSearch
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var open by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var showLicenses by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf("") }
    LaunchedEffect(showLicenses) {
        if (showLicenses && licenses.isEmpty()) licenses = withContext(Dispatchers.IO) {
            context.assets.open("voice-licenses.txt").bufferedReader().use { it.readText() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (open && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) controller.start() else controller.permissionDenied()
        }
    }
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            controller.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { controller.cancel(); open = false; consent = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.cancel() }
    }
    LaunchedEffect(state.text) { draft = state.text }
    IconButton(enabled = enabled && !state.busy, onClick = {
        focus.clearFocus(force = true)
        keyboard?.hide()
        open = true
        controller.checkModel()
    }) {
        Icon(Icons.Default.Mic, contentDescription = "Ricerca vocale offline")
    }
    if (open) AlertDialog(onDismissRequest = { controller.cancel(); open = false },
        title = { Text("Ricerca vocale") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("La voce viene trascritta sul telefono. La ricerca dei luoghi esterni richiede una connessione.")
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.message.isNotBlank()) Text(state.message)
                state.downloadPercent?.let { Text("Download: $it%") }
                if (!state.hasModel && !state.busy) Text("Modello vocale: circa 162 MB. Il download è necessario solo la prima volta.")
                if (state.phase == VoicePhase.SUCCESS) {
                    OutlinedTextField(value = draft, onValueChange = { if (it.length <= 200) draft = it },
                        label = { Text("Richiesta riconosciuta") }, modifier = Modifier.fillMaxWidth())
                    Text("Controlla il testo prima di usarlo. I preferiti hanno precedenza sui luoghi esterni.")
                }
                if (state.hasModel && !state.busy && state.phase != VoicePhase.SUCCESS)
                    Text("Con auricolari Bluetooth usa comunque il microfono del telefono.")
                if (state.phase == VoicePhase.ERROR) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }) { Text("Impostazioni app") }
                TextButton(onClick = { showLicenses = true }) { Text("Licenze") }
                if (state.hasModel && !state.busy) TextButton(onClick = controller::removeModel) { Text("Rimuovi modello scaricato") }
            }
        },
        confirmButton = {
            when {
                state.phase == VoicePhase.LISTENING -> TextButton(onClick = controller::finishSpeech) { Text("Fine") }
                state.busy -> Unit
                !state.hasModel -> TextButton(onClick = { consent = true }) { Text("Scarica modello") }
                state.phase == VoicePhase.SUCCESS -> TextButton(enabled = draft.isNotBlank(), onClick = {
                    open = false; onQuery(draft)
                }) { Text("Usa testo") }
                else -> TextButton(onClick = { start() }) { Text("Parla") }
            }
        },
        dismissButton = { TextButton(onClick = { controller.cancel(); open = false }) { Text("Chiudi") } })
    if (showLicenses) AlertDialog(onDismissRequest = { showLicenses = false },
        title = { Text("Licenze ricerca vocale") },
        text = { Text(licenses.ifEmpty { "Caricamento…" }, modifier = Modifier.heightIn(max = 350.dp)
            .verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Chiudi") } })
    if (consent) AlertDialog(onDismissRequest = { consent = false },
        title = { Text("Scaricare il modello vocale?") },
        text = { Text("Scaricherò circa 162 MB da Hugging Face e GitHub. Potrebbero essere usati i dati mobili. Il modello resta nello spazio privato dell’app.") },
        confirmButton = { TextButton(onClick = { consent = false; controller.download() }) { Text("Scarica") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Annulla") } })
}
