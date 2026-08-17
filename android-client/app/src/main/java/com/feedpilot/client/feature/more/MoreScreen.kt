package com.feedpilot.client.feature.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.feature.common.BalanceViewModel
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.ui.theme.AppTheme

import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.AccountBalanceWallet

@Composable
fun MoreScreen(
    onSettings: () -> Unit,
    onWithdraw: () -> Unit = {},
    onReferral: () -> Unit = {},
    onDailyBonus: () -> Unit = {},
    onCodes: () -> Unit = {},
    onCoupons: () -> Unit = {},
    viewModel: BalanceViewModel = hiltViewModel()
) {
    val balanceState by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppHeader(
            coins = balanceState.coins,
            plan = balanceState.plan,
            latestVersionName = balanceState.latestVersionName,
            onSettings = onSettings,
            onWithdraw = onWithdraw
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = AppTheme.brand.magenta, modifier = Modifier.size(18.dp))
                    Text("Request Seen Story", color = AppTheme.brand.magenta, fontWeight = FontWeight.Bold)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MoreCard("Refer & Earn (Bonus Coins)", Icons.Filled.Group, Color(0xFF673AB7), onReferral)
            MoreCard("Withdraw Funds (5 Coins = ₹1 INR)", Icons.Filled.AccountBalanceWallet, Color(0xFF4CAF50), onWithdraw)
            MoreCard("Daily bonus", Icons.Filled.CardGiftcard, AppTheme.brand.orange, onDailyBonus)
            MoreCard("Codes", Icons.Filled.Verified, Color(0xFF2196F3), onCodes)
            MoreCard("Coupons", Icons.AutoMirrored.Filled.ReceiptLong, Color(0xFF3F51B5), onCoupons)
        }
    }
}

@Composable
private fun MoreCard(label: String, icon: ImageVector, iconColor: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickableCard(onClick)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = iconColor) {
                Box(Modifier.padding(10.dp)) {
                    Icon(icon, contentDescription = null, tint = Color.White)
                }
            }
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

private fun Modifier.clickableCard(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
