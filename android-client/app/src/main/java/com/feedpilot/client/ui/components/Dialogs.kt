package com.feedpilot.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.feedpilot.client.ui.theme.AppTheme

/**
 * Centered input dialog with a floating circular icon, bold title, a single field, and
 * OK (orange) / Cancel actions. Used for "Enter Target Username" and "Enter Target Post Link".
 */
@Composable
fun InputDialog(
    title: String,
    icon: ImageVector,
    placeholder: String,
    initial: String = "",
    prefix: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val prefixContent: (@Composable () -> Unit)? = prefix?.let { p -> { Text(p) } }
    Dialog(onDismissRequest = onDismiss) {
        Box(contentAlignment = Alignment.TopCenter) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .padding(top = 34.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(20.dp).padding(top = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(placeholder) },
                        prefix = prefixContent,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onConfirm(text) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
                        ) { Text("OK", fontWeight = FontWeight.Bold, color = Color.White) }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            // Floating circular icon overlapping the top of the card.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = AppTheme.brand.magenta, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

/**
 * Prompts for a Backup Code and hands it to [onRestore]. Shared by the Settings screen's
 * "Restore" action and StartScreen's brand-new-install prompt — the same code path (a fresh
 * install, a data clear, or a reinstall all look identical to the app, so both entry points need
 * this exact dialog) rather than two copies that could drift apart.
 */
@Composable
fun RestoreCodeDialog(
    isRestoring: Boolean,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore with Backup Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste the code you saved when you backed up this account. This replaces whatever account is currently active with the one the code protects.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Backup Code") },
                    singleLine = true,
                    enabled = !isRestoring,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRestore(code) },
                enabled = !isRestoring && code.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isRestoring) "Restoring..." else "Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRestoring) { Text("Cancel") }
        }
    )
}

/**
 * Header pill that shows "Select Target" when no target is set, or "@username" once chosen.
 */
@Composable
fun SelectTargetButton(target: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.PersonPin, contentDescription = null, tint = AppTheme.brand.magenta, modifier = Modifier.size(20.dp))
            Text(
                if (target.isNullOrBlank()) "Select Target" else "@$target",
                color = AppTheme.brand.magenta,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
