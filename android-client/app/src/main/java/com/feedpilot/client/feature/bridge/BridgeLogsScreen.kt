package com.feedpilot.client.feature.bridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.bridge.BridgeLogStore
import androidx.hilt.navigation.compose.hiltViewModel
import com.feedpilot.client.feature.tasks.TasksViewModel
import com.feedpilot.client.feature.tasks.ProfileUpdateDialog
import com.feedpilot.client.data.local.AccountEntity
import kotlinx.coroutines.launch

@Composable
fun BridgeLogsScreen(onAddAccount: () -> Unit, viewModel: TasksViewModel = hiltViewModel()) {
    val accountState by viewModel.state.collectAsStateWithLifecycle()
    val profileProgress by viewModel.profileUpdateState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedAccount = accountState.accounts.firstOrNull { it.id == selectedAccountId }
        ?: accountState.accounts.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    var accountToEdit by remember { mutableStateOf<AccountEntity?>(null) }
    var accountToRemove by remember { mutableStateOf<AccountEntity?>(null) }
    val entries by BridgeLogStore.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var requestedPage by rememberSaveable { mutableIntStateOf(0) }
    val filtered = remember(entries, query) { entries.filter { it.text().contains(query.trim(), ignoreCase = true) } }
    val pages = maxOf(1, (filtered.size + 24) / 25)
    val page = requestedPage.coerceIn(0, pages - 1)
    val visible = filtered.drop(page * 25).take(25)
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bridge logs", style = MaterialTheme.typography.headlineSmall)
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Logs") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Exception users") })
            }
            if (selectedTab == 1) {
                ExceptionUsersPanel(Modifier.weight(1f).fillMaxWidth())
            } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Accounts (${accountState.accounts.size})", modifier = Modifier.padding(top = 12.dp))
                        TextButton(onClick = onAddAccount) { Text("Add account") }
                    }
                    if (selectedAccount == null) {
                        Text("Add an Instagram account to use bridge mode.")
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { expanded = true }) {
                                    Text("@${selectedAccount.username} ▾", maxLines = 1)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    accountState.accounts.forEach { account ->
                                        DropdownMenuItem(text = { Text("@${account.username}") }, onClick = {
                                            selectedAccountId = account.id
                                            expanded = false
                                        })
                                    }
                                }
                            }
                            TextButton(onClick = { accountToEdit = selectedAccount }, enabled = !profileProgress.running) { Text("Edit") }
                            TextButton(onClick = { accountToRemove = selectedAccount }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            OutlinedTextField(query, onValueChange = { query = it; requestedPage = 0 }, label = { Text("Search account, target, job or message") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${filtered.size} logs", modifier = Modifier.padding(top = 12.dp))
                TextButton(enabled = filtered.isNotEmpty(), onClick = {
                    clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.text() }))
                    scope.launch { snackbar.showSnackbar("Copied ${filtered.size} matching logs") }
                }) { Text("Copy logs") }
                TextButton(enabled = entries.isNotEmpty(), onClick = {
                    BridgeLogStore.clear()
                    requestedPage = 0
                    scope.launch { snackbar.showSnackbar("Bridge logs cleared") }
                }) { Text("Clear logs") }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.weight(1f)) { Text(if (query.isBlank()) "Bridge activity will appear here." else "No matching bridge logs.") }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.status.pillLabel, style = MaterialTheme.typography.titleSmall)
                                Text(entry.text(), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(entry.text()))
                                    scope.launch { snackbar.showSnackbar("Log copied") }
                                }) { Text("Copy log") }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page > 0, onClick = { requestedPage = page - 1 }) { Text("Previous") }
                Text("Page ${page + 1} of $pages", modifier = Modifier.padding(top = 12.dp))
                TextButton(enabled = page + 1 < pages, onClick = { requestedPage = page + 1 }) { Text("Next") }
            }
            }
        }
    }
    accountToEdit?.let { account ->
        ProfileUpdateDialog(account, profileProgress, onDismiss = {
            if (!profileProgress.running) {
                viewModel.clearProfileUpdateMessage()
                accountToEdit = null
            }
        }, onSubmit = { bio, profile, story, feed, caption ->
            viewModel.runProfileUpdate(account.id, bio, profile, story, feed, caption)
        })
    }
    accountToRemove?.let { account ->
        AlertDialog(onDismissRequest = { accountToRemove = null },
            title = { Text("Remove account?") },
            text = { Text("Remove @${account.username} from this device? You can add it back anytime.") },
            confirmButton = { TextButton(onClick = {
                viewModel.removeAccount(account.id)
                accountToRemove = null
            }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { accountToRemove = null }) { Text("Cancel") } })
    }
}
