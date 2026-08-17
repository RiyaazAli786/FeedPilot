package com.feedpilot.client.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.ui.theme.AppTheme

/**
 * The adaptive header shown on every main screen:
 * - Coin balance pill (center)
 * - Settings gear (top-right)
 */
@Composable
fun AppHeader(
    coins: Long,
    onSettings: () -> Unit,
    onWithdraw: (() -> Unit)? = null,
    plan: String? = null,
    latestVersionName: String? = null,
    modifier: Modifier = Modifier,
    slot: (@Composable () -> Unit)? = null
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AppTheme.brand.headerGradient)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val isOnline by com.feedpilot.client.common.rememberConnectivityState()
        Box(Modifier.fillMaxWidth()) {
            // Single Unified Version & Connectivity Badge (Top Left)
            if (!latestVersionName.isNullOrBlank()) {
                val dotColor = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isOnline) Color(0xFF4CAF50).copy(alpha = 0.6f) else Color(0xFFF44336).copy(alpha = 0.6f)
                    ),
                    shadowElevation = 1.dp,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = "v$latestVersionName",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Balance Pills (Center)
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BalancePill(icon = { CoinDot() }, value = coins, onClick = onWithdraw)
                if (!plan.isNullOrBlank() && plan != "Free") PlanBadge(plan)
            }

            // Settings Button (Top Right)
            CircleIconButton(
                onClick = onSettings,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AppTheme.brand.headerContentColor)
            }
        }

        if (slot != null) {
            Spacer(Modifier.height(14.dp))
            slot()
        }
    }
}

@Composable
private fun BalancePill(icon: @Composable () -> Unit, value: Long, onClick: (() -> Unit)? = null) {
    // Count up to the new balance rather than snapping, so earning a coin is visible.
    val animatedValue by animateIntAsState(
        targetValue = value.toInt(),
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "balanceCount"
    )

    // A brief swell on the way up draws the eye; it must not fire when the balance drops,
    // which happens routinely when an order is paid for.
    var previous by remember { mutableLongStateOf(value) }
    val gained = value > previous
    LaunchedEffect(value) { previous = value }

    val scale = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (gained) {
            scale.animateTo(1.18f, tween(160, easing = FastOutSlowInEasing))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Row(
        Modifier
            .scale(scale.value)
            .clip(CircleShape)
            .background(AppTheme.brand.pillBackground)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon()
        // "Wallet" distinguishes this spendable total from the per-account lifetime "Earned"
        // figure on the Tasks list — the two are separate ledgers (spending/withdrawing only
        // moves this one) and looked like a bug when shown as two unlabeled numbers side by side.
        Text(
            "Wallet",
            color = AppTheme.brand.headerContentColor.copy(alpha = 0.75f),
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp
        )
        Text(
            animatedValue.formatCoins(),
            color = AppTheme.brand.headerContentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/** Small premium badge showing the active paid tier (Silver / Gold / Platinum). */
@Composable
private fun PlanBadge(plan: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(AppTheme.brand.coinGold)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Filled.WorkspacePremium,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Text(plan, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun CoinDot(size: Int = 18) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(AppTheme.brand.coinGold),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size((size - 6).dp))
    }
}

@Composable
fun CircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AppTheme.brand.pillBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
