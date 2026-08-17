package com.feedpilot.client.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.data.repository.ThemeMode
import com.feedpilot.client.feature.updates.UpdatesViewModel
import com.feedpilot.client.ui.components.PurpleTopBar
import com.feedpilot.client.ui.components.RestoreCodeDialog

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onHistory: () -> Unit = {},
    onTransfer: () -> Unit = {},
    onReferral: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    updatesViewModel: UpdatesViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val referralCode by viewModel.referralCode.collectAsStateWithLifecycle()
    val referralBonusCoins by viewModel.referralBonusCoins.collectAsStateWithLifecycle()
    val updatesState by updatesViewModel.state.collectAsStateWithLifecycle()
    val isOnline by com.feedpilot.client.common.rememberConnectivityState()
    var userTriggeredCheck by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    val backupCode by viewModel.backupCode.collectAsStateWithLifecycle()
    var showBackupCodeDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var inputSetting by remember { mutableStateOf<NumericSetting?>(null) }
    val context = LocalContext.current

    LaunchedEffect(updatesState.checking, updatesState.upToDate) {
        if (userTriggeredCheck && !updatesState.checking) {
            if (updatesState.upToDate && updatesState.release == null) {
                Toast.makeText(context, "App is up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
            }
            userTriggeredCheck = false
        }
    }

    // Every setting below writes straight to DataStore the moment it's changed (see
    // SettingsRepository) — nothing here batches into a separate "apply" step, so a change is
    // already live everywhere that reads it (the theme via RootViewModel, the batch size via
    // TaskRepository's next claim, providers via their own Flow) as soon as it's saved. This
    // toast is purely confirmation that the write actually happened.
    fun confirm(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // Support/Telegram links are dashboard-configurable (RunnerSettings.SupportContactUrl /
    // TelegramChannelUrl) and synced into `settings` — a no-op if the admin hasn't set one yet.
    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    // Declared at this level (not nested in the scrollable Column below) so it's also reachable
    // from the Backup Code dialogs, which render outside that Column.
    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun openInputSetting(setting: NumericSetting) {
        inputSetting = setting
    }

    inputSetting?.let { setting ->
        NumericSettingDialog(
            setting = setting,
            onDismiss = { inputSetting = null },
            onSave = { value ->
                setting.onSave(value)
                inputSetting = null
                confirm("${setting.label} updated")
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        PurpleTopBar(title = "Settings", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Read-only account identity. Sign-in is automatic and passwordless (each install
            // and clone gets its own account via device auth), so there is no sign-in or
            // sign-out here — a device account is bound to the install for support/dashboard.
            if (isLoggedIn) {
                val isDeviceAccount = userEmail?.endsWith("@device.feedpilot") != false
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Account", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (isDeviceAccount) "This device" else (userEmail ?: "This device"),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ----- Device Information (Model & Name) -----
            val deviceModel = Build.MODEL
            val rawBrand = Build.MANUFACTURER
            val deviceName = "${rawBrand.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} $deviceModel"

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Device Information",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Device Name Row
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Device Name",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                deviceName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard("Device Name", deviceName) }
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy Device Name",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Device Model Row
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Device Model",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                deviceModel,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard("Device Model", deviceModel) }
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy Device Model",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Device ID Row — the same hardware-based id support uses to look up this
                    // device/clone, so it's worth having on hand when reporting an issue.
                    val deviceId = remember {
                        com.feedpilot.client.common.DeviceIdentity(context).hardwareDeviceId
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Device ID",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                deviceId,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard("Device ID", deviceId) }
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy Device ID",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // ----- Backup & Restore -----
            // The account this install auto-signs into is otherwise tied only to this device's
            // hardware/clone fingerprint, which a data clear or reinstall can start fresh from —
            // this is the one-tap way to protect against that without ever asking for an email
            // or password: a single generated code stands in for both.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Backup & Restore",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        if (backupCode != null)
                            "Your coins are protected. Save this code somewhere safe — it's the only way to get them back after a reinstall or on a new clone."
                        else
                            "Protect your coins before a data clear, reinstall, or clone wipes them — one tap generates a code, no email or password needed.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (backupCode != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                backupCode!!,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { copyToClipboard("Backup Code", backupCode!!) }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copy Backup Code",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Once an account is backed up, the backend's Claim endpoint won't
                        // re-claim it (it only converts a still device-bound account) — so there
                        // is no "generate a new code" for an already-secured account, only the
                        // existing code shown above.
                        if (backupCode == null) {
                            Button(
                                onClick = {
                                    isBackingUp = true
                                    viewModel.generateBackupCode { success, codeOrMessage ->
                                        isBackingUp = false
                                        if (success) {
                                            showBackupCodeDialog = true
                                        } else {
                                            confirm(codeOrMessage)
                                        }
                                    }
                                },
                                enabled = !isBackingUp,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (isBackingUp) "Generating…" else "Backup My Coins")
                            }
                        }
                        OutlinedButton(
                            onClick = { showRestoreDialog = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Restore")
                        }
                    }
                }
            }

            SettingsRow("History", Icons.Filled.History, onClick = onHistory)
            SettingsRow("Transfer Coins", Icons.Filled.SwapHoriz, onClick = onTransfer)
            SettingsRow(
                label = "Refer & Earn",
                subtitle = "Share app & get $referralBonusCoins bonus coins",
                icon = Icons.Filled.Share,
                onClick = onReferral
            )
            SettingsRow(
                label = if (updatesState.checking) "Checking for updates..." else "Check for Updates",
                subtitle = "Current version v${BuildConfig.VERSION_NAME} • ${if (isOnline) "Online 🟢" else "Offline 🔴"}",
                icon = Icons.Filled.SystemUpdate,
                onClick = {
                    userTriggeredCheck = true
                    updatesViewModel.check()
                }
            )
            SettingsRow(
                label = "Support / Contact Us",
                icon = Icons.Filled.SupportAgent,
                onClick = { openUrl(settings.supportContactUrl) }
            )
            SettingsRow(
                label = "Telegram Channel",
                icon = Icons.AutoMirrored.Filled.Send,
                // Falls back to the last-known default so the row still works before the very
                // first backend sync completes (e.g. cold start with no cache yet).
                onClick = { openUrl(settings.telegramChannelUrl ?: "https://t.me/+0RumB_V8jRMxODc5") }
            )
            SettingsRow("Delete Account", Icons.Filled.DeleteForever, danger = true, onClick = { showDeleteDialog = true })

            Spacer(Modifier.height(8.dp))

            // ----- Theme (light / dark) -----
            Text("Theme", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = {
                            viewModel.setTheme(mode)
                            confirm("Theme set to ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}")
                        },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Random Activity", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    SettingInputRow(
                        label = "Min action delay",
                        display = "${settings.actionDelayMinMs / 1000.0}s",
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Min action delay",
                                value = settings.actionDelayMinMs / 1000.0,
                                suffix = "seconds",
                                min = 0.5,
                                max = 120.0,
                                allowDecimal = true,
                                onSave = { seconds -> viewModel.setActionDelayRange((seconds * 1000).toLong(), settings.actionDelayMaxMs) }
                            ))
                        }
                    )
                    SettingInputRow(
                        label = "Max action delay",
                        display = "${settings.actionDelayMaxMs / 1000.0}s",
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Max action delay",
                                value = settings.actionDelayMaxMs / 1000.0,
                                suffix = "seconds",
                                min = 0.5,
                                max = 120.0,
                                allowDecimal = true,
                                onSave = { seconds -> viewModel.setActionDelayRange(settings.actionDelayMinMs, (seconds * 1000).toLong()) }
                            ))
                        }
                    )
                    SettingInputRow(
                        label = "Idle fetch delay",
                        display = "${settings.fetchDelayMs / 1000}s",
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Idle fetch delay",
                                value = settings.fetchDelayMs / 1000.0,
                                suffix = "seconds",
                                min = 3.0,
                                max = 60.0,
                                allowDecimal = false,
                                onSave = { seconds -> viewModel.setFetchDelay((seconds * 1000).toLong()) }
                            ))
                        }
                    )
                    SettingInputRow(
                        label = "Switch cooldown",
                        display = "${settings.cooldownSeconds}s",
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Switch cooldown",
                                value = settings.cooldownSeconds.toDouble(),
                                suffix = "seconds",
                                min = 1.0,
                                max = 3600.0,
                                allowDecimal = false,
                                onSave = { seconds -> viewModel.setCooldownSeconds(seconds.toInt()) }
                            ))
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingInputRow(
                        label = "Follow streak",
                        display = settings.followStreakCount.toString(),
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Follow streak",
                                value = settings.followStreakCount.toDouble(),
                                suffix = "actions",
                                min = 1.0,
                                max = null,
                                allowDecimal = false,
                                onSave = { count -> viewModel.setRandomStreakCounts(count.toInt(), settings.likeStreakCount, settings.commentStreakCount, settings.repostStreakCount, settings.savePostStreakCount, settings.storyViewStreakCount) }
                            ))
                        }
                    )
                    SettingInputRow(
                        label = "Like streak",
                        display = settings.likeStreakCount.toString(),
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Like streak",
                                value = settings.likeStreakCount.toDouble(),
                                suffix = "actions",
                                min = 1.0,
                                max = null,
                                allowDecimal = false,
                                onSave = { count -> viewModel.setRandomStreakCounts(settings.followStreakCount, count.toInt(), settings.commentStreakCount, settings.repostStreakCount, settings.savePostStreakCount, settings.storyViewStreakCount) }
                            ))
                        }
                    )
                    SettingInputRow(
                        label = "Comment/Repost streak",
                        display = settings.commentStreakCount.toString(),
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Comment/Repost streak",
                                value = settings.commentStreakCount.toDouble(),
                                suffix = "actions",
                                min = 1.0,
                                max = null,
                                allowDecimal = false,
                                onSave = { value ->
                                    val count = value.toInt()
                                    viewModel.setRandomStreakCounts(settings.followStreakCount, settings.likeStreakCount, count, count, settings.savePostStreakCount, settings.storyViewStreakCount)
                                }
                            ))
                        }
                    )
                    SettingInputRow(
                        label = "Save/Story streak",
                        display = settings.savePostStreakCount.toString(),
                        onClick = {
                            openInputSetting(NumericSetting(
                                label = "Save/Story streak",
                                value = settings.savePostStreakCount.toDouble(),
                                suffix = "actions",
                                min = 1.0,
                                max = null,
                                allowDecimal = false,
                                onSave = { value ->
                                    val count = value.toInt()
                                    viewModel.setRandomStreakCounts(settings.followStreakCount, settings.likeStreakCount, settings.commentStreakCount, settings.repostStreakCount, count, count)
                                }
                            ))
                        }
                    )
                }
            }

        }
    }

    com.feedpilot.client.feature.updates.LaunchUpdateDialog(updatesViewModel)

    if (showBackupCodeDialog && backupCode != null) {
        BackupCodeDialog(
            code = backupCode!!,
            onCopy = { copyToClipboard("Backup Code", backupCode!!) },
            onDismiss = { showBackupCodeDialog = false }
        )
    }

    if (showRestoreDialog) {
        RestoreCodeDialog(
            isRestoring = isRestoring,
            onDismiss = { if (!isRestoring) showRestoreDialog = false },
            onRestore = { code ->
                isRestoring = true
                viewModel.restoreWithBackupCode(code) { success, message ->
                    isRestoring = false
                    confirm(message)
                    if (success) showRestoreDialog = false
                }
            }
        )
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            isDeleting = isDeletingAccount,
            onDismiss = { if (!isDeletingAccount) showDeleteDialog = false },
            onConfirm = {
                isDeletingAccount = true
                viewModel.deleteAccount { success, message ->
                    isDeletingAccount = false
                    confirm(message)
                    if (success) {
                        showDeleteDialog = false
                        onLogin()
                    }
                }
            }
        )
    }
}

@Composable
private fun DeleteAccountDialog(
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Account") },
        text = {
            Text(
                "All earned coin and loggedin account will be delete. Do you want to proceed?\n\n" +
                    "This will delete this device, wallet coin, transfer history, loggedin account, and release orders claimed by this device.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isDeleting) "Deleting..." else "Delete Account")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) { Text("Cancel") }
        }
    )
}

@Composable
private fun BackupCodeDialog(
    code: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your Backup Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Save this code somewhere safe — a notes app, a screenshot. It's the only way to get your coins back after a reinstall, a data clear, or on a new clone. Anyone with this code can restore your account, so don't share it.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "A copy was also saved to your device's Downloads folder as a text file, in case you lose this one.",
                    fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        code,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopy, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy Code")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun SettingsRow(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickableRow(onClick)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val rowColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Icon(icon, contentDescription = null, tint = rowColor)
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private data class NumericSetting(
    val label: String,
    val value: Double,
    val suffix: String,
    val min: Double,
    val max: Double?,
    val allowDecimal: Boolean,
    val onSave: (Double) -> Unit
)

@Composable
private fun SettingInputRow(
    label: String,
    display: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap to edit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
            }
            Text(display, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NumericSettingDialog(
    setting: NumericSetting,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var value by remember(setting) {
        mutableStateOf(
            if (setting.allowDecimal) setting.value.toString().trimEnd('0').trimEnd('.')
            else setting.value.toInt().toString()
        )
    }
    val parsed = value.toDoubleOrNull()
    val valid = parsed != null && parsed >= setting.min && (setting.max == null || parsed <= setting.max)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(setting.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        value = raw.filter { ch -> ch.isDigit() || (setting.allowDecimal && ch == '.') }
                    },
                    singleLine = true,
                    label = { Text(setting.suffix) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.allowDecimal) KeyboardType.Decimal else KeyboardType.Number
                    )
                )
                Text(
                    setting.max?.let { max ->
                        "Allowed: ${formatSettingLimit(setting.min, setting.allowDecimal)} - ${formatSettingLimit(max, setting.allowDecimal)} ${setting.suffix}"
                    } ?: "Allowed: ${formatSettingLimit(setting.min, setting.allowDecimal)}+ ${setting.suffix}",
                    color = if (valid || value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsed?.coerceAtLeast(setting.min)?.let(onSave) },
                enabled = valid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatSettingLimit(value: Double, allowDecimal: Boolean): String =
    if (allowDecimal) value.toString().trimEnd('0').trimEnd('.') else value.toInt().toString()
