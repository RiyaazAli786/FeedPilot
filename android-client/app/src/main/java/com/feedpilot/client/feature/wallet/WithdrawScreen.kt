package com.feedpilot.client.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithdrawScreen(
    onBack: () -> Unit,
    onTransfer: () -> Unit,
    viewModel: WithdrawViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var coinsInput by remember { mutableStateOf("") }
    var selectedMethod by remember { mutableStateOf("UPI") }
    var upiId by remember { mutableStateOf("") }
    var bankDetails by remember { mutableStateOf("") }
    var usdtAddress by remember { mutableStateOf("") }

    // Method availability is dashboard-controlled and can change while this screen is open
    // (settings resync every ~60s) — if the currently selected method gets disabled, or the
    // default ("UPI") was never enabled to begin with, fall back to whatever is enabled.
    val enabledMethods = remember(state.upiEnabled, state.bankEnabled, state.usdtBep20Enabled) {
        buildList {
            if (state.upiEnabled) add("UPI")
            if (state.bankEnabled) add("Bank")
            if (state.usdtBep20Enabled) add("UsdtBep20")
        }
    }
    LaunchedEffect(enabledMethods) {
        if (selectedMethod !in enabledMethods && enabledMethods.isNotEmpty()) {
            selectedMethod = enabledMethods.first()
        }
    }

    val isUsdtSelected = selectedMethod == "UsdtBep20"
    val inputCoins = coinsInput.toLongOrNull() ?: 0L
    val calculatedInr = inputCoins / if (state.coinsPerInr > 0) state.coinsPerInr.toDouble() else 5.0
    val calculatedAmount = if (isUsdtSelected) {
        inputCoins / if (state.coinsPerUsdt > 0) state.coinsPerUsdt.toDouble() else 400.0
    } else {
        calculatedInr
    }
    val amountText = if (isUsdtSelected) "${"%.2f".format(calculatedAmount)} USDT" else "₹${"%.2f".format(calculatedAmount)}"

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Custom Top Bar Header
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
                    Text("Withdraw Funds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Tab Navigation: Withdraw vs Withdraw History
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
                            "Withdraw",
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
                            "Withdraw History",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 1) AppTheme.brand.magenta else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            if (selectedTab == 0) {
                // Tab 1: Withdraw Screen
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Compressed Wallet Balance Header Card
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = AppTheme.brand.navBar,
                            shadowElevation = 3.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Total Balance", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0x33FFFFFF)
                                    ) {
                                        Text(
                                            if (isUsdtSelected) "Rate: ${state.coinsPerUsdt} Coins = 1 USDT"
                                            else "Rate: ${state.coinsPerInr} Coins = ₹1 INR",
                                            color = AppTheme.brand.coinGold,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            state.totalCoins.formatCoins(),
                                            color = AppTheme.brand.coinGold,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Coins",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }
                                    Text(
                                        "≈ ₹${"%.2f".format(state.inrEquivalent)} INR",
                                        color = Color(0xFF4CAF50),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Peer-to-Peer Transfer Option Card
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Peer-to-Peer Transfer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Send coins directly to another wallet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = onTransfer,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.magenta),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Transfer", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                }
                            }
                        }
                    }

                    // Message Banner
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

                    // Withdrawal Form Card
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("Payout Request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                                if (enabledMethods.isEmpty()) {
                                    Text(
                                        "No withdrawal methods are currently available. Please check back later.",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp
                                    )
                                } else {
                                    // Payment Method Selector — driven entirely by which methods the
                                    // dashboard currently has enabled.
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (state.upiEnabled) {
                                            PaymentMethodChip(
                                                label = "UPI / Paytm",
                                                icon = Icons.Filled.QrCode,
                                                selected = selectedMethod == "UPI",
                                                onClick = { selectedMethod = "UPI" },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (state.bankEnabled) {
                                            PaymentMethodChip(
                                                label = "Bank Transfer",
                                                icon = Icons.Filled.AccountBalanceWallet,
                                                selected = selectedMethod == "Bank",
                                                onClick = { selectedMethod = "Bank" },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (state.usdtBep20Enabled) {
                                            PaymentMethodChip(
                                                label = "USDT BEP20",
                                                icon = Icons.Filled.MonetizationOn,
                                                selected = selectedMethod == "UsdtBep20",
                                                onClick = { selectedMethod = "UsdtBep20" },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    when (selectedMethod) {
                                        "UPI" -> OutlinedTextField(
                                            value = upiId,
                                            onValueChange = { upiId = it },
                                            label = { Text("UPI ID (e.g. name@upi)") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        "Bank" -> OutlinedTextField(
                                            value = bankDetails,
                                            onValueChange = { bankDetails = it },
                                            label = { Text("Account No., IFSC Code & Name") },
                                            maxLines = 3,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        "UsdtBep20" -> OutlinedTextField(
                                            value = usdtAddress,
                                            onValueChange = { usdtAddress = it },
                                            label = { Text("USDT BEP20 Wallet Address (0x...)") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    OutlinedTextField(
                                        value = coinsInput,
                                        onValueChange = { coinsInput = it.filter { c -> c.isDigit() } },
                                        label = {
                                            Text(
                                                if (isUsdtSelected) {
                                                    "Coins to Withdraw (Min: ${state.minWithdrawalUsdtCoins.formatCoins()} " +
                                                        "Coins = ${state.minWithdrawalUsdt} USDT)"
                                                } else {
                                                    "Coins to Withdraw (Min: ${state.minWithdrawalCoins.formatCoins()} " +
                                                        "Coins = ₹${state.minWithdrawalInr})"
                                                }
                                            )
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(12.dp),
                                        trailingIcon = {
                                            Text(
                                                "= $amountText",
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32),
                                                modifier = Modifier.padding(end = 12.dp)
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Button(
                                        onClick = {
                                            viewModel.submitWithdrawal(
                                                coinsInput = coinsInput,
                                                paymentMethod = selectedMethod,
                                                upiId = upiId,
                                                bankDetails = bankDetails,
                                                usdtAddress = usdtAddress,
                                                onSuccess = { coinsInput = "" }
                                            )
                                        },
                                        enabled = !state.isSubmitting && inputCoins >= 5,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.magenta),
                                        modifier = Modifier.fillMaxWidth().height(50.dp)
                                    ) {
                                        if (state.isSubmitting) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                        } else {
                                            Icon(
                                                if (isUsdtSelected) Icons.Filled.MonetizationOn else Icons.Filled.CurrencyRupee,
                                                contentDescription = null
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Withdraw $amountText",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Tab 2: Withdraw History Screen
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
                                        "No withdrawal history yet.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    } else {
                        items(state.history) { item ->
                            WithdrawalHistoryRow(item)
                        }
                    }
                }
            }
        }

        // Full Screen Loading Overlay for Withdrawal Submission
        if (state.isSubmitting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = AppTheme.brand.magenta)
                        Column {
                            Text("Processing Withdrawal...", fontWeight = FontWeight.Bold)
                            Text("Submitting payout request", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun PaymentMethodChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) AppTheme.brand.magenta else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val bgColor = if (selected) AppTheme.brand.magenta.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = modifier
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) AppTheme.brand.magenta else Color.Gray, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
        }
    }
}

/** Normalizes both the request-side ("UPI"/"Bank"/"UsdtBep20") and server-returned
 *  ("Upi"/"Bank"/"UsdtBep20") method spellings to one friendly label. */
private fun methodDisplayName(raw: String): String = when (raw.trim().lowercase()) {
    "upi" -> "UPI"
    "bank" -> "Bank Transfer"
    "usdtbep20" -> "USDT BEP20"
    else -> raw
}

@Composable
private fun WithdrawalHistoryRow(item: com.feedpilot.client.data.local.WithdrawalEntity) {
    val isUsdt = item.paymentMethod.equals("UsdtBep20", ignoreCase = true)
    val amountText = if (isUsdt) "${"%.2f".format(item.amount)} USDT" else "₹${"%.2f".format(item.amount)} INR"
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${item.coins.formatCoins()} Coins → $amountText", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Method: ${methodDisplayName(item.paymentMethod)} · ${item.createdAt.take(10)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (item.status.equals("Completed", ignoreCase = true)) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
            ) {
                Text(
                    item.status,
                    color = if (item.status.equals("Completed", ignoreCase = true)) Color(0xFF2E7D32) else Color(0xFFE65100),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

