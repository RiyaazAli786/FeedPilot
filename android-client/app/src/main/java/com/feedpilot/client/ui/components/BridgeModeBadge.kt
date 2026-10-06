package com.feedpilot.client.ui.components

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.bridge.BridgeConnectionState
import com.feedpilot.client.bridge.BridgeStatusStore
import com.feedpilot.client.common.Constants
import com.feedpilot.client.ui.theme.AppTheme

@Composable
fun BridgeModeBadge() {
    val status by BridgeStatusStore.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    val color = when (status.state) {
        BridgeConnectionState.Starting, BridgeConnectionState.RunningJob,
        BridgeConnectionState.Finished, BridgeConnectionState.SendingResult -> AppTheme.brand.orange
        BridgeConnectionState.JobFailed, BridgeConnectionState.ResultFailed,
        BridgeConnectionState.Error, BridgeConnectionState.Offline -> MaterialTheme.colorScheme.error
        else -> Color(0xFF18B26B)
    }
    Row(
        Modifier.fillMaxWidth().background(AppTheme.brand.headerGradient)
            .statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(onClick = { showDetails = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Text(status.pillLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Bridge mode") },
            text = { Text(status.message + (status.jobId?.let { "\nJob: $it" } ?: "") +
                "\n\nTo hide the bridge notification while it keeps running, turn off the Feed bridge notification in Android settings.") },
            confirmButton = {
                TextButton(onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Constants.FEED_BRIDGE_CHANNEL_ID)
                    } else {
                        Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                            .putExtra("app_package", context.packageName)
                            .putExtra("app_uid", context.applicationInfo.uid)
                    }
                    runCatching { context.startActivity(intent) }
                    showDetails = false
                }) { Text("Notification settings") }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } }
        )
    }
}
