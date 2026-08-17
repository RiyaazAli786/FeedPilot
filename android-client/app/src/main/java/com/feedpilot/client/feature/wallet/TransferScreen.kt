package com.feedpilot.client.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.data.remote.dto.CoinTransferDto
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onBack: () -> Unit,
    viewModel: TransferViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Transfer Coins", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Tab Navigation: Transfer vs Transfer History
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = AppTheme.brand.magenta
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Transfer",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 0) AppTheme.brand.magenta else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "Transfer History",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 1) AppTheme.brand.magenta else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            if (selectedTab == 0) {
                // Tab 1: Transfer Coins Form
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Compressed Balance Header Card
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = AppTheme.brand.navBar,
                            shadowElevation = 3.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Your Balance", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        state.totalCoins.formatCoins(),
                                        color = AppTheme.brand.coinGold,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        "Coins",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    state.message?.let { msg ->
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (state.isError) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    color = if (state.isError) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF2E7D32),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }

                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Send to a username", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                                var suggestionsExpanded by remember { mutableStateOf(false) }
                                LaunchedEffect(state.suggestions) {
                                    suggestionsExpanded = state.suggestions.isNotEmpty()
                                }
                                ExposedDropdownMenuBox(
                                    expanded = suggestionsExpanded,
                                    onExpandedChange = { suggestionsExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = state.receiverUsername,
                                        onValueChange = viewModel::setReceiverUsername,
                                        label = { Text("Recipient's Instagram username") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        trailingIcon = {
                                            when {
                                                state.searching -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                                state.resolvedReceiver != null -> Icon(
                                                    Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32)
                                                )
                                                else -> null
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = suggestionsExpanded && state.suggestions.isNotEmpty(),
                                        onDismissRequest = { suggestionsExpanded = false }
                                    ) {
                                        state.suggestions.forEach { suggestion ->
                                            DropdownMenuItem(
                                                text = { Text("@${suggestion.username}") },
                                                onClick = {
                                                    viewModel.selectSuggestion(suggestion.username)
                                                    suggestionsExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                when {
                                    state.resolvedReceiver != null ->
                                        Text("Found: @${state.resolvedReceiver}", color = Color(0xFF2E7D32), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    state.searchError != null ->
                                        Text(state.searchError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }

                                OutlinedTextField(
                                    value = state.coinsInput,
                                    onValueChange = viewModel::setCoinsInput,
                                    label = { Text("Coins to send (you have ${state.totalCoins.formatCoins()})") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = state.note,
                                    onValueChange = viewModel::setNote,
                                    label = { Text("Note (optional)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick = viewModel::submitTransfer,
                                    enabled = state.canSubmit,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.magenta),
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) {
                                    if (state.isSubmitting) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Send Coins", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Tab 2: Transfer History
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.history.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        "No transfers yet.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    } else {
                        items(state.history, key = { it.id }) { item -> TransferHistoryRow(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferHistoryRow(item: CoinTransferDto) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "@${item.senderUsername} → @${item.receiverUsername}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(item.createdAt.take(10), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("${item.coins.formatCoins()} Coins", color = AppTheme.brand.coinGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
