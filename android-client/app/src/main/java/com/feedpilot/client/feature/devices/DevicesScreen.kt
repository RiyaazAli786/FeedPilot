package com.feedpilot.client.feature.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.data.remote.dto.DeviceListItemDto
import com.feedpilot.client.feature.common.BalanceViewModel
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.ui.theme.AppTheme

@Composable
fun DevicesScreen(
    onSettings: () -> Unit = {},
    balanceViewModel: BalanceViewModel = hiltViewModel(),
    viewModel: DevicesViewModel = hiltViewModel()
) {
    val balanceState by balanceViewModel.state.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App header with live coins and settings navigation
        AppHeader(
            coins = balanceState.coins,
            onSettings = onSettings,
            onWithdraw = null
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Screen Title & Summary Row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Registered Devices",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Quick Stats Row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBadge(
                    label = "Total",
                    value = "${uiState.total}",
                    modifier = Modifier.weight(1f)
                )
                StatBadge(
                    label = "Online",
                    value = "${uiState.onlineCount}",
                    color = Color(0xFF70E3AB),
                    modifier = Modifier.weight(1f)
                )
                StatBadge(
                    label = "Bridge ON",
                    value = "${uiState.bridgeOnCount}",
                    color = Color(0xFFFFA726),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Search model, ID, account...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearSearch) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(10.dp))

            // Content Area (Loading / Error / Empty / List)
            Box(Modifier.weight(1f)) {
                when {
                    uiState.isLoading && uiState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.errorMessage != null && uiState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    uiState.errorMessage ?: "Failed to load devices",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = viewModel::refresh) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    uiState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.PhoneAndroid,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (uiState.searchQuery.isNotBlank()) "No matching devices found"
                                    else "No registered devices yet",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.items, key = { it.id }) { device ->
                                DeviceListItemCard(device)
                            }
                        }
                    }
                }
            }

            // Pagination Controls Bar
            if (uiState.totalPages > 1 || uiState.total > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = viewModel::prevPage,
                            enabled = uiState.page > 1 && !uiState.isLoading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous")
                            Spacer(Modifier.width(4.dp))
                            Text("Prev", fontSize = 12.sp)
                        }

                        Text(
                            text = "Page ${uiState.page} of ${uiState.totalPages} (${uiState.total} total)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedButton(
                            onClick = viewModel::nextPage,
                            enabled = uiState.page < uiState.totalPages && !uiState.isLoading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Next", fontSize = 12.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Next")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceListItemCard(device: DeviceListItemDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Device Model + Online Status + Bridge Status
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = device.deviceModel?.takeIf { it.isNotBlank() } ?: "Android Device",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Badges
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Online / Offline Badge
                    val onlineColor = if (device.isOnline) Color(0xFF70E3AB) else Color(0xFFEF5350)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = onlineColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, onlineColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(onlineColor)
                            )
                            Text(
                                if (device.isOnline) "Online" else "Offline",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onlineColor
                            )
                        }
                    }

                    // Bridge ON / OFF Badge
                    val bridgeColor = if (device.bridgeEnabled) Color(0xFFFFA726) else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = bridgeColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, bridgeColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(bridgeColor)
                            )
                            Text(
                                if (device.bridgeEnabled) "Bridge ON" else "Bridge OFF",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = bridgeColor
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Active Account / Instagram Info
            if (!device.activeAccount.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active account: @${device.activeAccount.removePrefix("@")}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val extraAccounts = (device.loggedInAccounts?.size ?: 1) - 1
                        if (extraAccounts > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "(+$extraAccounts more)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Specs Row (Android version, App version, Battery, Network, Clones)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!device.androidVersion.isNullOrBlank()) {
                    Text(
                        "Android ${device.androidVersion}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!device.appVersion.isNullOrBlank()) {
                    Text(
                        "v${device.appVersion}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (device.batteryLevel != null) {
                    Text(
                        "🔋 ${device.batteryLevel}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!device.networkType.isNullOrBlank()) {
                    Text(
                        "📶 ${device.networkType}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (device.cloneCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "${device.cloneCount} clones",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Device IDs & Timestamps
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ID: ${device.deviceId.take(18)}${if (device.deviceId.length > 18) "..." else ""}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Text(
                    text = "Last seen: ${formatTimestamp(device.lastSeenAt)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: String): String {
    if (timestamp.isBlank()) return "Unknown"
    return try {
        // Simple human friendly extraction from ISO string (e.g. 2026-10-29T21:40:00Z -> 21:40 or Oct 29)
        val clean = timestamp.removeSuffix("Z")
        if (clean.contains("T")) {
            val parts = clean.split("T")
            val date = parts[0]
            val time = parts[1].take(5)
            "$date $time"
        } else {
            clean.take(16)
        }
    } catch (_: Exception) {
        timestamp.take(16)
    }
}
