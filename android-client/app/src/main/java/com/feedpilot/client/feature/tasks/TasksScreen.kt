package com.feedpilot.client.feature.tasks

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.feedpilot.client.common.isUpgradedWithin24h
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.local.AccountLogEntity
import com.feedpilot.client.data.repository.AppSettings
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.ui.components.Avatar
import com.feedpilot.client.ui.components.CoinDot
import com.feedpilot.client.ui.theme.AppTheme
import com.feedpilot.client.feature.common.BalanceViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(
    onSettings: () -> Unit,
    onUpgrade: () -> Unit,
    onAddAccount: () -> Unit,
    onLeaderboard: () -> Unit,
    onWithdraw: (() -> Unit)? = null,
    onOpenSession: (accountId: String) -> Unit = {},
    viewModel: TasksViewModel = hiltViewModel(),
    balanceViewModel: BalanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceState by balanceViewModel.state.collectAsStateWithLifecycle()
    val profileUpdateState by viewModel.profileUpdateState.collectAsStateWithLifecycle()
    val loginMessage by viewModel.loginMessage.collectAsStateWithLifecycle()
    var accountToDelete by remember { mutableStateOf<AccountEntity?>(null) }
    var accountToLogin by remember { mutableStateOf<AccountEntity?>(null) }
    var accountToUpdateProfile by remember { mutableStateOf<AccountEntity?>(null) }
    val context = LocalContext.current

    if (!loginMessage.isNullOrBlank()) {
        val warningText = loginMessage!!
        AlertDialog(
            onDismissRequest = { viewModel.consumeLoginMessage() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Task Start Validation Warning",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = warningText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.consumeLoginMessage() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Ticks once a minute so an account's crown badge clears itself 24h after its last upgrade
    // even if this screen is left open the whole time, instead of only refreshing on navigation.
    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppHeader(
            coins = state.coins,
            plan = balanceState.plan,
            latestVersionName = balanceState.latestVersionName,
            onSettings = onSettings,
            onWithdraw = onWithdraw
        )

        Column(
            Modifier
                .weight(1f)
                .padding(16.dp)
        ) {
            // ----- Action tiles -----
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionTile("Upgrade Account", Icons.Filled.WorkspacePremium, onClick = onUpgrade)
                    ActionTile("Add Account", Icons.Filled.PersonAdd, onClick = onAddAccount)
                    ActionTile("LeaderBoard", Icons.Filled.EmojiEvents, badge = "New", onClick = onLeaderboard)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Battery optimization warning — shown only while the runner is active and the
            // app is not yet excluded from Doze mode, which cuts network access during long
            // screen-lock periods and causes all account loops to stall in waitForNetwork().
            val pm = context.getSystemService(PowerManager::class.java)
            if (state.running && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Runner may stop on screen lock",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Tap to allow unrestricted background access so tasks continue while the screen is off.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ----- Select All Accounts Card -----
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { viewModel.toggleSelectAll() }
                    ) {
                        Checkbox(
                            checked = state.accounts.isNotEmpty() && state.enabledAccountIds.size == state.accounts.size,
                            onCheckedChange = { viewModel.toggleSelectAll() },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.scale(0.85f)
                        )
                        Text("Select All", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ----- Accounts Cards List -----
            if (state.accounts.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 40.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        "No accounts yet. Tap \"Add Account\" to begin.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.accounts, key = { it.id }) { account ->
                        val isAccountEnabled = account.id in state.enabledAccountIds
                        var logLimit by remember(account.id) { mutableIntStateOf(50) }
                        val logs by viewModel.logsForAccount(account.id, logLimit).collectAsStateWithLifecycle(initialValue = emptyList())
                        // Each card reports its own account's progress. Showing the shared
                        // runner totals here made every account read the same line, including
                        // the status of an order it never ran.
                        val accountStats = state.runner.forAccount(account.id)
                        AccountTaskCard(
                            account = account,
                            enabled = isAccountEnabled,
                            isRunning = state.runner.isParticipating(account.id),
                            isValidating = account.id in state.validatingAccountIds,
                            isVerifying = account.id in state.verifyingAccountIds,
                            isUpgraded = isUpgradedWithin24h(account.upgradedAt, nowMillis),
                            settings = state.settings,
                            runnerMessage = accountStats.message,
                            runnerWaitUntilMs = accountStats.waitUntilMs,
                            runnerCompleted = accountStats.completed,
                            runnerCoinsEarned = accountStats.coinsEarned,
                            taskMode = state.getTaskModeForAccount(account.id),
                            logs = logs,
                            hasMoreLogs = logs.size >= logLimit,
                            onSelectMode = { mode -> viewModel.setAccountTaskMode(account.id, mode) },
                            onToggle = { viewModel.toggleAccount(account.id) },
                            onStartSingle = { viewModel.startSingleAccount(account.id) },
                            onClearLogs = { viewModel.clearLogsForAccount(account.id) },
                            onRetryLog = { log, onDone -> viewModel.retryFailedActionLog(log, onDone) },
                            onLoadMoreLogs = { logLimit += 50 },
                            onRemove = { accountToDelete = account },
                            onRefreshProfile = { viewModel.refreshAccountProfile(account.id) },
                            onUpdateProfile = { accountToUpdateProfile = account },
                            onOpenSession = { onOpenSession(account.id) },
                            onLogin = { accountToLogin = account }
                        )
                    }
                }
            }
        }

        accountToDelete?.let { account ->
            AlertDialog(
                onDismissRequest = { accountToDelete = null },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text("Remove Account?", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        "Are you sure you want to remove @${account.username} from your active accounts list? You can add it back anytime.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.removeAccount(account.id)
                            accountToDelete = null
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Remove", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { accountToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        accountToUpdateProfile?.let { account ->
            ProfileUpdateDialog(
                account = account,
                progress = profileUpdateState,
                onDismiss = {
                    if (!profileUpdateState.running) {
                        viewModel.clearProfileUpdateMessage()
                        accountToUpdateProfile = null
                    }
                },
                onSubmit = { bio, profileBytes, storyBytes, feedBytes, caption ->
                    viewModel.runProfileUpdate(account.id, bio, profileBytes, storyBytes, feedBytes, caption)
                }
            )
        }

        accountToLogin?.let { account ->
            AlertDialog(
                onDismissRequest = { accountToLogin = null },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = AppTheme.brand.orange
                        )
                        Text("Activate Account?", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        "Log in and make @${account.username} the active account? This will verify the saved Instagram session and move the green active status to this account.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.verifyAccountLogin(account.id)
                            accountToLogin = null
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
                    ) {
                        Text("Activate", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { accountToLogin = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // ----- Start / Stop Button -----
        Button(
            onClick = {
                when {
                    state.running -> viewModel.stop()
                    state.enabledAccountIds.isEmpty() ->
                        Toast.makeText(context, "Select at least one account first", Toast.LENGTH_SHORT).show()
                    else -> viewModel.start()
                }
            },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.running) MaterialTheme.colorScheme.error else AppTheme.brand.orange
            ),
            modifier = Modifier
                .width(160.dp)
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
                .height(40.dp)
        ) {
            Text(
                text = if (state.running) "Stop" else "Start",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun RowScope.ActionTile(label: String, icon: ImageVector, badge: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickableSurface(onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(42.dp)
            ) {
                Box(Modifier.padding(10.dp)) {
                    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (badge != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        badge,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun getRewardString(mode: TaskMode, isUpgraded: Boolean, settings: AppSettings): String {
    val rewardCoins = when (mode) {
        TaskMode.LIKE -> if (isUpgraded) settings.likeCoinsUpgraded else settings.likeCoinsNormal
        TaskMode.FOLLOW -> if (isUpgraded) settings.followCoinsUpgraded else settings.followCoinsNormal
        TaskMode.COMMENT -> if (isUpgraded) settings.commentCoinsUpgraded else settings.commentCoinsNormal
        TaskMode.COMMENT_CUSTOM -> if (isUpgraded) settings.commentCoinsUpgraded else settings.commentCoinsNormal
        TaskMode.REPOST -> if (isUpgraded) settings.repostCoinsUpgraded else settings.repostCoinsNormal
        TaskMode.SAVE_POST -> if (isUpgraded) settings.savePostCoinsUpgraded else settings.savePostCoinsNormal
        TaskMode.STORY_VIEW -> if (isUpgraded) settings.storyViewCoinsUpgraded else settings.storyViewCoinsNormal
        TaskMode.RANDOM -> {
            val list = listOf(
                if (isUpgraded) settings.likeCoinsUpgraded else settings.likeCoinsNormal,
                if (isUpgraded) settings.followCoinsUpgraded else settings.followCoinsNormal,
                if (isUpgraded) settings.commentCoinsUpgraded else settings.commentCoinsNormal,
                if (isUpgraded) settings.repostCoinsUpgraded else settings.repostCoinsNormal,
                if (isUpgraded) settings.savePostCoinsUpgraded else settings.savePostCoinsNormal,
                if (isUpgraded) settings.storyViewCoinsUpgraded else settings.storyViewCoinsNormal
            )
            val min = list.minOrNull() ?: 1
            val max = list.maxOrNull() ?: 1
            if (min == max) return "+$min Coin${if (min > 1) "s" else ""}"
            else return "+$min-$max Coins"
        }
    }
    return "+$rewardCoins Coin${if (rewardCoins > 1) "s" else ""}"
}

@Composable
private fun ModeSelector(
    selected: TaskMode,
    isUpgraded: Boolean,
    settings: AppSettings,
    onSelect: (TaskMode) -> Unit,
    locked: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    // Dismiss the menu the moment the account starts running, in case it was open.
    LaunchedEffect(locked) { if (locked) expanded = false }
    val selectorSurface = MaterialTheme.colorScheme.surface
    val selectorContent = MaterialTheme.colorScheme.onSurface
    val selectorAccent = AppTheme.brand.coinGold
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (locked)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else
                selectorSurface,
            border = if (locked) null else androidx.compose.foundation.BorderStroke(
                1.dp,
                selectorAccent.copy(alpha = 0.45f)
            ),
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
        ) {
            Row(
                Modifier
                    .then(
                        if (!locked) Modifier.clickableSurface { expanded = true }
                        else Modifier  // no-op — can't change mode while running
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    selected.label,
                    color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant
                            else selectorContent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1
                )
                CoinDot(size = 14)
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.ArrowDropDown,
                    contentDescription = if (locked) "Mode locked while running" else "Change task mode",
                    tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant
                           else selectorAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (!locked) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = selectorSurface,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp
            ) {
                TaskMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(mode.label, color = selectorContent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(getRewardString(mode, isUpgraded, settings), color = selectorAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                CoinDot(size = 14)
                            }
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = selectorContent,
                            leadingIconColor = selectorAccent,
                            trailingIconColor = selectorAccent
                        ),
                        onClick = { onSelect(mode); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountActionButton(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    tint: Color,
    size: Int = 34,
    iconSize: Int = 17,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = color,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize.dp)
            )
        }
    }
}

@Composable
private fun ProfileUpdateDialog(
    account: AccountEntity,
    progress: ProfileUpdateState,
    onDismiss: () -> Unit,
    onSubmit: (
        bio: String,
        profilePicBytes: ByteArray?,
        storyImages: List<ByteArray>,
        feedImages: List<ByteArray>,
        feedCaption: String
    ) -> Unit
) {
    val context = LocalContext.current
    var bio by remember(account.id) { mutableStateOf("") }
    var caption by remember(account.id) { mutableStateOf("") }
    var profilePicUri by remember(account.id) { mutableStateOf<Uri?>(null) }
    var storyUris by remember(account.id) { mutableStateOf<List<Uri>>(emptyList()) }
    var feedUris by remember(account.id) { mutableStateOf<List<Uri>>(emptyList()) }

    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        profilePicUri = uri
    }
    val storyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        storyUris = uris
    }
    val feedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        feedUris = uris
    }

    fun readBytes(uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    fun submitProfilePic() {
        val uri = profilePicUri
        if (uri == null) {
            Toast.makeText(context, "Select a profile picture first.", Toast.LENGTH_LONG).show()
            return
        }
        val bytes = readBytes(uri)
        if (bytes == null) {
            Toast.makeText(context, "Could not read profile picture file.", Toast.LENGTH_LONG).show()
        } else {
            onSubmit("", bytes, emptyList(), emptyList(), "")
        }
    }

    fun submitStories() {
        if (storyUris.isEmpty()) {
            Toast.makeText(context, "Select story images first.", Toast.LENGTH_LONG).show()
            return
        }
        val bytes = storyUris.mapNotNull(::readBytes)
        if (bytes.size != storyUris.size) {
            Toast.makeText(context, "Some selected story files could not be read.", Toast.LENGTH_LONG).show()
        } else {
            onSubmit("", null, bytes, emptyList(), "")
        }
    }

    fun submitFeedPosts() {
        if (feedUris.isEmpty()) {
            Toast.makeText(context, "Select feed images first.", Toast.LENGTH_LONG).show()
            return
        }
        val bytes = feedUris.mapNotNull(::readBytes)
        if (bytes.size != feedUris.size) {
            Toast.makeText(context, "Some selected feed files could not be read.", Toast.LENGTH_LONG).show()
        } else {
            onSubmit("", null, emptyList(), bytes, caption)
        }
    }

    fun submitBio() {
        if (bio.trim().isBlank()) {
            Toast.makeText(context, "Enter bio first.", Toast.LENGTH_LONG).show()
            return
        }
        onSubmit(bio, null, emptyList(), emptyList(), "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Update @${account.username}", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Use each action button to update only that field.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { profilePicker.launch("image/*") },
                        enabled = !progress.running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (profilePicUri == null) "Profile Pic" else "1 Selected", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { submitProfilePic() },
                        enabled = !progress.running && profilePicUri != null,
                        modifier = Modifier.width(104.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Apply", fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { storyPicker.launch(arrayOf("image/*")) },
                        enabled = !progress.running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stories ${storyUris.size}", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { submitStories() },
                        enabled = !progress.running && storyUris.isNotEmpty(),
                        modifier = Modifier.width(104.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Apply", fontSize = 12.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { bio = it },
                        enabled = !progress.running,
                        label = { Text("Bio") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { submitBio() },
                        enabled = !progress.running && bio.trim().isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update Bio")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { feedPicker.launch(arrayOf("image/*")) },
                            enabled = !progress.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Feed Images: ${feedUris.size}", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { submitFeedPosts() },
                            enabled = !progress.running && feedUris.isNotEmpty(),
                            modifier = Modifier.width(104.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Post", fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        enabled = !progress.running,
                        label = { Text("Feed post caption") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (progress.running || progress.text != null || progress.error != null) {
                    val fraction = if (progress.total > 0) progress.done.toFloat() / progress.total.toFloat() else 0f
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppTheme.brand.orange
                    )
                    Text(
                        progress.text ?: progress.error ?: "",
                        color = if (progress.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (progress.total > 0) {
                        Text("${progress.done}/${progress.total}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !progress.running) {
                if (progress.running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("Close")
            }
        }
    )
}

@Composable
private fun AccountTaskCard(
    account: AccountEntity,
    enabled: Boolean,
    isRunning: Boolean,
    isValidating: Boolean = false,
    isVerifying: Boolean,
    isUpgraded: Boolean,
    settings: AppSettings,
    runnerMessage: String?,
    runnerWaitUntilMs: Long?,
    runnerCompleted: Int,
    runnerCoinsEarned: Int,
    taskMode: TaskMode,
    logs: List<AccountLogEntity>,
    hasMoreLogs: Boolean,
    onSelectMode: (TaskMode) -> Unit,
    onToggle: () -> Unit,
    onStartSingle: () -> Unit,
    onClearLogs: () -> Unit,
    onRetryLog: (AccountLogEntity, (() -> Unit)?) -> Unit,
    onLoadMoreLogs: () -> Unit,
    onRemove: () -> Unit,
    onRefreshProfile: () -> Unit,
    onUpdateProfile: () -> Unit,
    onOpenSession: () -> Unit,
    onLogin: () -> Unit
) {
    val isChallenged = account.status.contains("CHALLENGE", ignoreCase = true)
    val isSessionLoggedOut = account.status.contains("LOGGED_OUT", ignoreCase = true)
    val isRunningOrValidating = isRunning || isValidating
    val showBanner = isRunningOrValidating
    var showLogs by remember { mutableStateOf(false) }
    val loginClickModifier =
        if (!account.isLoggedIn && !isVerifying && !isValidating) {
            Modifier.clickable(onClick = onLogin)
        } else {
            Modifier
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(loginClickModifier)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.scale(0.85f)
                )
                Spacer(Modifier.width(2.dp))
                Box(contentAlignment = Alignment.Center) {
                    Avatar(
                        account.profilePictureUrl,
                        size = 36,
                        isActive = account.isLoggedIn,
                        isUpgraded = isUpgraded,
                        modifier = if (!account.isLoggedIn && !isVerifying && !isValidating) {
                            Modifier.clickable(onClick = onLogin)
                        } else Modifier
                    )
                    if (isVerifying || isValidating || isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 2.dp,
                            color = AppTheme.brand.orange
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            account.username,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(4.dp))
                        com.feedpilot.client.ui.components.CopyUsernameButton(account.username)
                        AccountActionButton(
                            icon = Icons.Filled.Edit,
                            contentDescription = "Update profile",
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            tint = AppTheme.brand.orange,
                            size = 24,
                            iconSize = 13,
                            onClick = onUpdateProfile
                        )
                        if (isChallenged) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable(onClick = onOpenSession)
                            ) {
                                Text(
                                    text = "Challenge Required",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (isSessionLoggedOut) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable(onClick = onOpenSession)
                            ) {
                                Text(
                                    text = "Log In",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (account.id.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            CoinDot(size = 11)
                            Text(account.coinsEarned.formatCoins(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }

                // Primary action: Play/Pause button on the top-right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AccountActionButton(
                        icon = if (isRunningOrValidating) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isRunningOrValidating) "Pause Task Runner" else "Start Task Runner",
                        color = if (isRunningOrValidating) AppTheme.brand.orange else MaterialTheme.colorScheme.primaryContainer,
                        tint = if (isRunningOrValidating) Color.White else MaterialTheme.colorScheme.primary,
                        size = 32,
                        iconSize = 16,
                        onClick = onStartSingle
                    )
                }
            }

            // Bottom Row: Mode Selector on the left, utility actions aligned on the right
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 50.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModeSelector(
                    selected = taskMode,
                    isUpgraded = isUpgraded,
                    settings = settings,
                    onSelect = onSelectMode,
                    locked = isRunning
                )

                Spacer(Modifier.weight(1f))

                // Utility buttons row
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccountActionButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "Refresh Profile Picture",
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 28,
                        iconSize = 14,
                        onClick = onRefreshProfile
                    )
                    AccountActionButton(
                        icon = Icons.Filled.Language,
                        contentDescription = "Open in browser",
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 28,
                        iconSize = 14,
                        onClick = onOpenSession
                    )
                    AccountActionButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        contentDescription = "View Action Log",
                        color = if (logs.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        tint = if (logs.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 28,
                        iconSize = 14,
                        onClick = { showLogs = true }
                    )
                    AccountActionButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Remove Account",
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 28,
                        iconSize = 14,
                        onClick = onRemove
                    )
                }
            }

            // Live Orange Status Banner or Challenge Warning Banner
            if (showBanner) {
                Surface(
                    color = AppTheme.brand.orange,
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        val remainingSeconds by produceState(initialValue = 0L, runnerWaitUntilMs) {
                            while (true) {
                                val target = runnerWaitUntilMs
                                val remaining = target?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0) } ?: 0L
                                value = remaining
                                if (target == null || remaining <= 0L) break
                                delay(1_000)
                            }
                        }
                        val actionStatus = when {
                            isValidating -> "Checking profile picture & posts…"
                            runnerWaitUntilMs != null && remainingSeconds > 0 -> "${runnerMessage ?: "Waiting…"} (${remainingSeconds}s)"
                            else -> runnerMessage ?: "${taskMode.label} account"
                        }
                        Text(
                            text = actionStatus,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = Color.White.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "$runnerCompleted completed",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            } else if (isChallenged) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSession)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Challenge Required",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Challenge Required — Tap browser (🌐) to resolve",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else if (isSessionLoggedOut) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSession)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Session Logged Out",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Session Logged Out — Log in via globe (🌐) icon",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    // Account Log Dialog
    if (showLogs) {
        AccountLogDialog(
            username = account.username,
            sessionCookies = account.sessionCookies,
            logs = logs,
            hasMoreLogs = hasMoreLogs,
            onClearLogs = onClearLogs,
            onRetryLog = onRetryLog,
            onLoadMoreLogs = onLoadMoreLogs,
            onDismiss = { showLogs = false }
        )
    }
}

@Composable
private fun AccountLogDialog(
    username: String,
    sessionCookies: String,
    logs: List<AccountLogEntity>,
    hasMoreLogs: Boolean,
    onClearLogs: () -> Unit,
    onRetryLog: (AccountLogEntity, (() -> Unit)?) -> Unit,
    onLoadMoreLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var selectedFilter by remember { mutableStateOf("All") }
    var confirmClear by remember { mutableStateOf(false) }
    var activeWebViewUrl by remember { mutableStateOf<String?>(null) }
    var retryingLogIds by remember { mutableStateOf(setOf<String>()) }

    fun normalizeActivity(action: String): String = when {
        action.contains("Follow", ignoreCase = true) -> "Follow"
        action.contains("Like", ignoreCase = true) -> "Like"
        action.contains("Repost", ignoreCase = true) || action.contains("Reshare", ignoreCase = true) -> "Repost"
        action.contains("Save", ignoreCase = true) -> "Save"
        action.contains("Comment", ignoreCase = true) -> "Comment"
        action.contains("Story", ignoreCase = true) -> "StoryView"
        else -> action.takeWhile { it != ' ' && it != '(' }.ifBlank { "Other" }
    }

    val activityCategories = remember(logs) {
        val present = logs.map { normalizeActivity(it.action) }.distinct()
        val standard = listOf("All", "Follow", "Like", "Repost", "Save", "Comment", "StoryView")
        listOf("All") + (standard.drop(1) + present).distinct().filter { cat ->
            cat == "All" || logs.any { normalizeActivity(it.action) == cat }
        }
    }

    val successCountMap = remember(logs) {
        val map = mutableMapOf<String, Int>()
        map["All"] = logs.count { it.success }
        logs.forEach { log ->
            if (log.success) {
                val cat = normalizeActivity(log.action)
                map[cat] = (map[cat] ?: 0) + 1
            }
        }
        map
    }

    val totalCountMap = remember(logs) {
        val map = mutableMapOf<String, Int>()
        map["All"] = logs.size
        logs.forEach { log ->
            val cat = normalizeActivity(log.action)
            map[cat] = (map[cat] ?: 0) + 1
        }
        map
    }

    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == "All") logs
        else logs.filter { normalizeActivity(it.action) == selectedFilter }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "@$username — Action Log",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Clear log",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                alpha = if (logs.isEmpty()) 0.38f else 1f
                            )
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                // Filter Bar by Activity with Success Count
                if (logs.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(activityCategories) { cat ->
                            val isSelected = selectedFilter == cat
                            val successCount = successCountMap[cat] ?: 0
                            val totalCount = totalCountMap[cat] ?: 0

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.clickable { selectedFilter = cat }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = cat,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f) else AppTheme.brand.orange.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "$successCount/$totalCount",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else AppTheme.brand.magenta,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (logs.isEmpty()) "No actions logged yet.\nStart the runner to see live results here."
                            else "No $selectedFilter actions logged yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { log ->
                            val surfaceColor = if (log.success) {
                                Color(0xFF4CAF50).copy(alpha = 0.05f)
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.05f)
                            }
                            val borderColor = if (log.success) {
                                Color(0xFF4CAF50).copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = surfaceColor,
                                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (log.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (log.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                log.action.replace(Regex("""\s*\(Service #\d+\)"""), ""),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                timeFmt.format(Date(log.timestampMs)),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }

                                        val targetUrl = remember(log.target, log.message, log.action) {
                                            resolveActionLogTargetUrl(log.action, log.target, log.message)
                                        }

                                        val cleanMessage = remember(log.message) {
                                            val rawMsg = log.message.replace(Regex("""\s*\(Service #\d+\)"""), "").trim()
                                            val urlRegex = Regex("""https?://(?:www\.)?instagram\.com/[^\s"'>]+""")
                                            val match = urlRegex.find(rawMsg)
                                            if (match != null) {
                                                rawMsg.replace(match.value, "").trim()
                                            } else {
                                                rawMsg
                                            }
                                        }

                                        val displayText = remember(log.target, cleanMessage, log.success) {
                                             if (log.success) {
                                                 log.target
                                             } else if (cleanMessage.isNotBlank()) {
                                                 "${log.target} · $cleanMessage"
                                             } else {
                                                 log.target
                                             }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Text(
                                                    text = displayText,
                                                    fontSize = 11.sp,
                                                    color = if (log.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )

                                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                                val context = androidx.compose.ui.platform.LocalContext.current
                                                Box(
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .clickable {
                                                            val urlToCopy = targetUrl ?: "https://www.instagram.com/${log.target.trimStart('@')}/"
                                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(urlToCopy))
                                                            android.widget.Toast.makeText(context, "Copied URL", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.ContentCopy,
                                                        contentDescription = "Copy Full URL",
                                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(10.dp)
                                                    )
                                                }
                                            }

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (!log.success) {
                                                    val isRetrying = log.id in retryingLogIds
                                                    OutlinedButton(
                                                        onClick = {
                                                            retryingLogIds = retryingLogIds + log.id
                                                            onRetryLog(log) {
                                                                retryingLogIds = retryingLogIds - log.id
                                                            }
                                                        },
                                                        enabled = !isRetrying,
                                                        shape = RoundedCornerShape(6.dp),
                                                        colors = ButtonDefaults.outlinedButtonColors(
                                                            contentColor = MaterialTheme.colorScheme.error,
                                                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                                        ),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(20.dp)
                                                    ) {
                                                        if (isRetrying) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(10.dp),
                                                                strokeWidth = 1.5.dp,
                                                                color = MaterialTheme.colorScheme.error
                                                            )
                                                            Spacer(Modifier.width(2.dp))
                                                            Text(
                                                                "Retrying…",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.error
                                                            )
                                                        } else {
                                                            Icon(
                                                                Icons.Filled.Refresh,
                                                                contentDescription = "Retry",
                                                                tint = MaterialTheme.colorScheme.error,
                                                                modifier = Modifier.size(11.dp)
                                                            )
                                                            Spacer(Modifier.width(2.dp))
                                                            Text(
                                                                "Retry",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.error
                                                            )
                                                        }
                                                    }
                                                }

                                                if (targetUrl != null) {
                                                    OutlinedButton(
                                                        onClick = { activeWebViewUrl = targetUrl },
                                                        shape = RoundedCornerShape(6.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(20.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.Visibility,
                                                            contentDescription = "View",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(11.dp)
                                                        )
                                                        Spacer(Modifier.width(2.dp))
                                                        Text(
                                                            "View",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (hasMoreLogs) {
                            item {
                                OutlinedButton(
                                    onClick = onLoadMoreLogs,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (activeWebViewUrl != null) {
        ActionLogWebViewDialog(
            url = activeWebViewUrl!!,
            sessionCookies = sessionCookies,
            onDismiss = { activeWebViewUrl = null }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("Clear Action Log?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "This permanently deletes all ${logs.size} logged action(s) for @$username. " +
                        "It also resets duplicate protection, so this account may engage with " +
                        "targets it has already completed.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearLogs()
                        confirmClear = false
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun resolveActionLogTargetUrl(action: String, target: String, message: String): String? {
    val actionType = com.feedpilot.client.task.EngagementTaskType.from(
        action.replace(Regex("""\s*\(Service #\d+\)"""), "").trim()
    )

    // A Follow's `target` is always the followed account's own username — authoritative and
    // unambiguous, unlike `message`, which is free text that can (and, per a real report, did)
    // end up mentioning some other @handle — e.g. the device's own account name via a stale
    // profile-fetch mismatch when building the success string. Scanning `message` for the first
    // @handle in that case opened the wrong profile entirely (the performer's, not the target's).
    // Trusting `target` directly for Follow sidesteps that regardless of what `message` contains.
    if (actionType == com.feedpilot.client.task.EngagementTaskType.FOLLOW) {
        val followTarget = target.trim().trimStart('@')
        if (followTarget.isNotBlank() && followTarget.matches(Regex("""[A-Za-z0-9_.-]+"""))) {
            return "https://www.instagram.com/$followTarget/"
        }
    }

    val combined = "$target $message".trim()
    val urlMatch = Regex("""https?://[^\s"'>]+""").find(combined)
    if (urlMatch != null) return urlMatch.value

    val scMatch = Regex("""(?:p|reel|tv)/([A-Za-z0-9_-]+)""").find(combined)
    if (scMatch != null) return "https://www.instagram.com/${scMatch.value}/"

    val handleMatch = Regex("""@([A-Za-z0-9_.]+)""").find(combined)
    if (handleMatch != null) return "https://www.instagram.com/${handleMatch.groupValues[1]}/"

    val cleanTarget = target.trim().trimStart('@')
    if (cleanTarget.isBlank() || cleanTarget.contains(" ") || !cleanTarget.matches(Regex("""[A-Za-z0-9_.-]+"""))) {
        return null
    }

    // No URL/handle pattern found in either field, so `target` is a bare stored value — its
    // shape differs by action type (a username for Follow, a case-sensitive media shortcode for
    // everything else), so which path segment it belongs under can't be guessed from the string
    // alone. Getting this wrong for a media action (treating the shortcode as a profile handle,
    // missing the "/p/" segment) is what made "View" on a Like/Comment/etc. entry 404 with
    // Instagram's "Sorry, this page isn't available".
    return when (actionType) {
        com.feedpilot.client.task.EngagementTaskType.FOLLOW, null ->
            "https://www.instagram.com/$cleanTarget/"
        // A story's canonical URL needs the poster's username, not just the story/media id this
        // target holds — nothing to build a correct link from here, so don't guess one.
        com.feedpilot.client.task.EngagementTaskType.STORY_VIEW -> null
        else ->
            "https://www.instagram.com/p/$cleanTarget/"
    }
}

@Composable
private fun ActionLogWebViewDialog(
    url: String,
    sessionCookies: String,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Action Log Web Viewer",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                            val cookieManager = android.webkit.CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            if (sessionCookies.isNotBlank()) {
                                sessionCookies.split(";").forEach { pair ->
                                    val trimmed = pair.trim()
                                    if (trimmed.isNotEmpty()) {
                                        cookieManager.setCookie(
                                            "https://www.instagram.com",
                                            "$trimmed; Domain=.instagram.com; Path=/"
                                        )
                                    }
                                }
                                cookieManager.flush()
                            }

                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    isLoading = true
                                }
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    isLoading = false
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun Modifier.clickableSurface(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
