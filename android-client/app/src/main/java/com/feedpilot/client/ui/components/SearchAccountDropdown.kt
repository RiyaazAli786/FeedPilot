package com.feedpilot.client.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedpilot.client.common.SearchAccountState

@Composable
fun SearchAccountDropdown(
    state: SearchAccountState,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.accounts.firstOrNull { it.id == state.selectedAccountId }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && state.accounts.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected?.let { "Search account: @${it.username}" }
                ?: if (state.accounts.isEmpty()) "Add an account with a session to search"
                else "Select search account", modifier = Modifier.weight(1f))
            Text("▾", modifier = Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("@${account.username}") },
                    onClick = { onSelect(account.id); expanded = false }
                )
            }
        }
    }
}
