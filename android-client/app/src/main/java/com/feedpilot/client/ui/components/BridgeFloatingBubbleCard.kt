package com.feedpilot.client.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedpilot.client.service.FloatingBridgeBubbleService

@Composable
fun BridgeFloatingBubbleCard(
    enabled: Boolean,
    onChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val hasPermission = remember(enabled) {
        FloatingBridgeBubbleService.isOverlayPermissionGranted(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Floating Bridge Bubble", fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled && hasPermission) {
                            "Floating bubble is active. Tap the bubble anytime over any app to expand bridge messages."
                        } else if (enabled && !hasPermission) {
                            "Permission required: allow FeedPilot to display over other apps."
                        } else {
                            "Show a floating bubble over other apps with live bridge activity."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled && hasPermission,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            if (!FloatingBridgeBubbleService.isOverlayPermissionGranted(context)) {
                                Toast.makeText(
                                    context,
                                    "Please allow 'Display over other apps' to enable the floating bubble",
                                    Toast.LENGTH_LONG
                                ).show()
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                } else {
                                    null
                                }
                                intent?.let { runCatching { context.startActivity(it) } }
                            } else {
                                onChanged(true)
                                FloatingBridgeBubbleService.start(context)
                            }
                        } else {
                            onChanged(false)
                            FloatingBridgeBubbleService.stop(context)
                        }
                    }
                )
            }

            if (enabled && !hasPermission) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant 'Display over other apps' Permission", fontSize = 12.sp)
                }
            }
        }
    }
}

