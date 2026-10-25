package com.feedpilot.client.feature.bridge

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ExceptionUsersPanel(
    modifier: Modifier = Modifier,
    viewModel: ExceptionUsersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fetchState by viewModel.fetchDialogState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var username by rememberSaveable { mutableStateOf("") }
    var removingUsername by rememberSaveable { mutableStateOf<String?>(null) }
    val actionsEnabled = !state.loading && !state.saving

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("These usernames use the device bridge first, with pool fallback.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::search,
            enabled = !state.saving,
            label = { Text("Search saved exception users") },
            singleLine = true,
            trailingIcon = {
                if (state.search.isNotEmpty()) TextButton(enabled = !state.saving, onClick = { viewModel.search("") }) { Text("Clear") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.loading) "Loading..." else if (state.error != null) "Unavailable" else "${state.total} users",
                modifier = Modifier.weight(1f))
            TextButton(enabled = actionsEnabled, onClick = viewModel::refresh) { Text("Refresh") }
            TextButton(enabled = !state.saving, onClick = {
                viewModel.clearActionError()
                editingUsername = null
                username = ""
                showEditor = true
            }) { Text("Add user") }
        }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh) { Text("Retry") }
            }
            state.users.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.search.isBlank()) "No exception users saved yet." else "No exception users match your search.")
            }
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.users, key = { it }) { user ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("@$user", style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(enabled = actionsEnabled, onClick = {
                                    viewModel.fetchUser(user)
                                }) { Text("Fetch") }
                                TextButton(enabled = actionsEnabled, onClick = {
                                    viewModel.clearActionError()
                                    editingUsername = user
                                    username = user
                                    showEditor = true
                                }) { Text("Edit") }
                                TextButton(enabled = actionsEnabled, onClick = {
                                    viewModel.clearActionError()
                                    removingUsername = user
                                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = actionsEnabled && state.error == null && state.page > 1,
                onClick = { viewModel.page(state.page - 1) }) { Text("Previous") }
            Text(if (state.loading || state.error != null) "25 per page" else "Page ${state.page} of ${state.pages}")
            TextButton(enabled = actionsEnabled && state.error == null && state.page < state.pages,
                onClick = { viewModel.page(state.page + 1) }) { Text("Next") }
        }
    }

    if (showEditor) AlertDialog(
        onDismissRequest = { if (!state.saving) showEditor = false },
        title = { Text(if (editingUsername == null) "Add exception user" else "Edit exception user") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = username, onValueChange = { username = it; viewModel.clearActionError() },
                    label = { Text("Instagram username") }, singleLine = true, enabled = !state.saving,
                    isError = state.actionError != null)
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !state.saving && username.isNotBlank(), onClick = {
            viewModel.save(editingUsername, username) { showEditor = false }
        }) { Text(if (state.saving) "Saving..." else "Save") } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = { showEditor = false }) { Text("Cancel") } }
    )

    removingUsername?.let { user ->
        AlertDialog(onDismissRequest = { if (!state.saving) removingUsername = null },
            title = { Text("Remove exception user?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remove @$user from the saved exception list on feed.reelsflow.io?")
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !state.saving, onClick = {
                viewModel.remove(user) { removingUsername = null }
            }) { Text(if (state.saving) "Removing..." else "Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { removingUsername = null }) { Text("Cancel") } }
        )
    }

    fetchState?.let { currentFetch ->
        AlertDialog(
            onDismissRequest = {
                if (!currentFetch.loading) viewModel.dismissFetchDialog()
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fetch @${currentFetch.target}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    currentFetch.accountUsername?.let { account ->
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "@$account",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        currentFetch.loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        "Fetching feed for @${currentFetch.target}...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        if (currentFetch.accountUsername != null)
                                            "Using active account: @${currentFetch.accountUsername}"
                                        else
                                            "Selecting random active account...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        currentFetch.error != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = currentFetch.error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                currentFetch.accountUsername?.let { account ->
                                    Text(
                                        "Attempted with account: @$account",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        currentFetch.formattedResponse != null -> {
                            currentFetch.summary?.let { summary ->
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 340.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(8.dp)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = currentFetch.formattedResponse,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentFetch.formattedResponse != null) {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(currentFetch.formattedResponse))
                                Toast.makeText(context, "Response copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Copy")
                        }
                    } else if (currentFetch.error != null) {
                        Button(
                            onClick = { viewModel.fetchUser(currentFetch.target) }
                        ) {
                            Text("Retry")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissFetchDialog,
                    enabled = !currentFetch.loading
                ) {
                    Text("Close")
                }
            }
        )
    }
}
