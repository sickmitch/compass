package org.compass.cng.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

@Composable
fun ClientUpdateDialog(viewModel: ClientUpdateViewModel, allowed: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var installerRequested by rememberSaveable(state.release?.fileName) { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.dismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.canInstall()) {
            try { installer.launch(viewModel.installIntent()) }
            catch (_: Exception) { viewModel.installationFailed() }
        }
    }
    val install = {
        try {
            if (viewModel.canInstall()) installer.launch(viewModel.installIntent())
            else permission.launch(viewModel.permissionIntent())
        } catch (_: Exception) { viewModel.installationFailed() }
    }
    LaunchedEffect(state.ready, allowed) {
        if (state.ready && allowed) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!installerRequested) {
                installerRequested = true
                install()
            }
            awaitCancellation()
        }
    }
    val release = state.release ?: return
    if (!allowed) return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Aggiornamento Compass ${release.version}") },
        text = {
            Column {
                when {
                    state.downloading -> {
                        Text("Download in corso${state.progress?.let { ": $it%" } ?: "…"}")
                        LinearProgressIndicator()
                    }
                    state.error != null -> Text(requireNotNull(state.error))
                    state.ready -> Text("APK verificato. Autorizza Compass a installare aggiornamenti e conferma nell'installer Android.")
                    else -> Text("È disponibile una versione più recente. Vuoi scaricarla e aggiornare l'app?")
                }
            }
        },
        confirmButton = {
            if (!state.downloading) TextButton(onClick = {
                if (state.ready) install() else viewModel.download()
            }) { Text(if (state.ready) "Installa" else if (state.error != null) "Riprova" else "Sì") }
        },
        dismissButton = {
            if (!state.downloading) TextButton(onClick = viewModel::dismiss) { Text("Non ora") }
        },
    )
}
