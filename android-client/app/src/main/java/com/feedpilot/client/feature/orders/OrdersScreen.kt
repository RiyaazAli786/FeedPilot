package com.feedpilot.client.feature.orders

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.feature.common.BalanceViewModel
import com.feedpilot.client.feature.history.OrderHistoryContent
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.ui.theme.AppTheme

enum class OrderSubTab(val title: String, val icon: ImageVector) {
    MAKE_ORDER("Make Order", Icons.Filled.AddShoppingCart),
    ORDER_HISTORY("Order History", Icons.Filled.History)
}

@Composable
fun OrdersScreen(
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onWithdraw: (() -> Unit)? = null,
    viewModel: OrdersViewModel = hiltViewModel(),
    balanceViewModel: BalanceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val balanceState by balanceViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedSubTab by remember { mutableStateOf(OrderSubTab.MAKE_ORDER) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.orderPlacedSuccess) {
        if (state.orderPlacedSuccess) {
            Toast.makeText(context, "Order Placed Successfully!", Toast.LENGTH_SHORT).show()
            selectedSubTab = OrderSubTab.ORDER_HISTORY
            viewModel.consumeOrderPlacedSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        AppHeader(
            coins = balanceState.coins,
            plan = balanceState.plan,
            latestVersionName = balanceState.latestVersionName,
            onSettings = onSettings,
            onWithdraw = onWithdraw
        )

        // Sub-Tab Switcher (Make Order vs Order History)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OrderSubTab.entries.forEach { subTab ->
                    val isSelected = selectedSubTab == subTab
                    Surface(
                        onClick = { selectedSubTab = subTab },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = subTab.icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = subTab.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        when (selectedSubTab) {
            OrderSubTab.MAKE_ORDER -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Order Type Segmented Switcher
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "1. Select Activity Type",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(OrderType.entries) { type ->
                                    val isSelected = state.orderType == type
                                    val icon = when (type) {
                                        OrderType.FOLLOWERS -> Icons.Filled.People
                                        OrderType.LIKES -> Icons.Filled.Favorite
                                        OrderType.COMMENTS -> Icons.AutoMirrored.Filled.Comment
                                        OrderType.REPOSTS -> Icons.Filled.Repeat
                                        OrderType.SAVE_POSTS -> Icons.Filled.Bookmark
                                        OrderType.STORY_VIEWS -> Icons.Filled.Visibility
                                    }

                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.setOrderType(type) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text(type.title, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }
                        }
                    }

                    // Target Input Section
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "2. Enter Target Link / Handle",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            OutlinedTextField(
                                value = state.targetInput,
                                onValueChange = viewModel::setTargetInput,
                                label = { Text(state.orderType.inputLabel) },
                                placeholder = { Text(state.orderType.inputPlaceholder) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        imageVector = if (state.orderType == OrderType.FOLLOWERS) Icons.Filled.PersonSearch else Icons.Filled.Link,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }

                    // Custom Comments Input Section (Only for Comments type)
                    if (state.orderType.isCommentType) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "3. Custom Comments (one per line)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (state.parsedComments.isNotEmpty()) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "${state.parsedComments.size} comments",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = state.commentsInput,
                                    onValueChange = viewModel::setCommentsInput,
                                    label = { Text("Comments (one per line)") },
                                    placeholder = { Text("Great post!\nAwesome picture 🔥\nLove this content!") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Quantity Selection & Custom Input Section
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = if (state.orderType.isCommentType) "4. Order Quantity" else "3. Select or Enter Custom Quantity",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Custom Quantity Number Input
                            OutlinedTextField(
                                value = state.quantityInput,
                                onValueChange = viewModel::setQuantityInput,
                                label = { Text("Quantity") },
                                placeholder = { Text("Enter quantity e.g. 10, 100, 500") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = state.quantityError != null,
                                supportingText = {
                                    Text(
                                        state.quantityError
                                            ?: "Allowed range: ${state.effectiveMin}–${state.effectiveMax}",
                                        fontSize = 11.sp,
                                        color = if (state.quantityError != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Filled.FormatListNumbered, contentDescription = null)
                                }
                            )

                            // Quick Preset Chips
                            val packages = if (state.orderType.isCommentType) listOf(5, 10, 25, 50, 100) else listOf(100, 250, 500, 1000, 2500, 5000)
                            Text("Quick Presets:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                packages.take(4).forEach { qty ->
                                    FilterChip(
                                        selected = state.quantityInput == qty.toString(),
                                        onClick = { viewModel.setPresetQuantity(qty) },
                                        label = { Text("$qty", fontSize = 12.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (packages.size > 4) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    packages.drop(4).forEach { qty ->
                                        FilterChip(
                                            selected = state.quantityInput == qty.toString(),
                                            onClick = { viewModel.setPresetQuantity(qty) },
                                            label = { Text("$qty", fontSize = 12.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Summary & Direct Confirm Button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Quantity", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${state.quantity} ${state.orderType.title}", fontSize = 18.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Cost", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        state.quotedCoins?.let { "${it.formatCoins()} coins" } ?: "—",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (state.canAfford) AppTheme.brand.orange else MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        "Balance ${state.coins.formatCoins()}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!state.canAfford) {
                                Text(
                                    "Not enough coins — this order needs ${state.quotedCoins?.formatCoins()}, " +
                                        "you have ${state.coins.formatCoins()}.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Button(
                                onClick = viewModel::placeOrder,
                                enabled = state.canPlace,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                if (state.isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Placing Order...", color = Color.Black, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Color.Black)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Place ${state.orderType.title} Order", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            }
            OrderSubTab.ORDER_HISTORY -> {
                OrderHistoryContent(
                    showHeader = false,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
