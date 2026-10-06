package com.feedpilot.client.feature.bridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ExceptionUsersPanel(
    modifier: Modifier = Modifier,
    viewModel: ExceptionUsersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
}
