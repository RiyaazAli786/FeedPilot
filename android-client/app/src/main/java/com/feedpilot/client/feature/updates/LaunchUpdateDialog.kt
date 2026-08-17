package com.feedpilot.client.feature.updates

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.ui.theme.AppTheme

@Composable
fun LaunchUpdateDialog(
    updatesViewModel: UpdatesViewModel = hiltViewModel()
) {
    val state by updatesViewModel.state.collectAsStateWithLifecycle()
    val release = state.release

    // Display dialog only if a new update release is available and hasn't been skipped
    if (release != null && !state.skipped) {
        AlertDialog(
            onDismissRequest = {
                if (!release.forceUpdate && !state.downloading) {
                    updatesViewModel.skip()
                }
            },
            title = {
                Column {
                    Text(
                        text = "🚀 New Update Available",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Version ${release.versionName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.brand.orange
                    )
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (!release.releaseNotes.isNullOrBlank()) {
                        Text(
                            text = release.releaseNotes,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    if (release.forceUpdate) {
                        Text(
                            text = "⚠️ This is a required update to continue using the app.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    if (state.downloading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(
                                progress = { state.downloadPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = AppTheme.brand.orange
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Downloading update... ${state.downloadPercent}%",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (state.readyFile != null) {
                        Text(
                            text = "✓ Update downloaded and verified. Ready to install!",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    state.error?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Error: $err",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (state.readyFile != null) {
                            updatesViewModel.install()
                        } else if (!state.downloading) {
                            updatesViewModel.download()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
                ) {
                    Text(
                        text = when {
                            state.readyFile != null -> "Install Now"
                            state.downloading -> "Downloading..."
                            else -> "Update Now"
                        },
                        color = androidx.compose.ui.graphics.Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                if (!release.forceUpdate && !state.downloading) {
                    TextButton(onClick = updatesViewModel::skip) {
                        Text(
                            text = "Skip for Now",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}
