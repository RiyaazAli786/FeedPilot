package com.feedpilot.client.feature.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.ui.components.PurpleTopBar

@Composable
fun UpdatesScreen(
    onBack: () -> Unit,
    viewModel: UpdatesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PurpleTopBar(title = "Updates", onBack = onBack)

        Column(Modifier.weight(1f).padding(16.dp)) {
            when {
                state.checking -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Checking for updates…")
                }
                state.upToDate -> Text("You're on the latest version 🎉")
                state.release != null -> {
                    val release = state.release!!
                    Text(
                        "Version ${release.versionName} available",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    release.releaseNotes?.let { Text(it) }
                    if (release.forceUpdate) {
                        Spacer(Modifier.height(4.dp))
                        Text("This is a required update.", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))

                    if (state.downloading) {
                        LinearProgressIndicator(
                            progress = { state.downloadPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("${state.downloadPercent}%")
                    } else if (state.readyFile != null) {
                        Button(onClick = viewModel::install, modifier = Modifier.fillMaxWidth()) {
                            Text("Install")
                        }
                    } else {
                        Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) {
                            Text("Download update")
                        }
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text("Error: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
