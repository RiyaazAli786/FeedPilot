package com.feedpilot.client.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.ui.theme.AppTheme

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
            .padding(top = 14.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val isOnline by com.feedpilot.client.common.rememberConnectivityState()
        Box(Modifier.fillMaxWidth()) {
            if (!latestVersionName.isNullOrBlank()) {
                val dotColor = if (isOnline) Color(0xFF36C484) else Color(0xFFE85A5A)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppTheme.brand.premiumSurface.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, dotColor.copy(alpha = 0.45f)),
                    shadowElevation = 3.dp,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
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
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BalancePill(icon = { CoinDot() }, value = coins, onClick = onWithdraw)
                if (!plan.isNullOrBlank() && plan != "Free") PlanBadge(plan)
            }

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
    val animatedValue by animateIntAsState(
        targetValue = value.toInt(),
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "balanceCount"
    )

    var previous by remember { mutableLongStateOf(value) }
    val gained = value > previous
    LaunchedEffect(value) { previous = value }

    val scale = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (gained) {
            scale.animateTo(1.14f, tween(160, easing = FastOutSlowInEasing))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Row(
        Modifier
            .scale(scale.value)
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.brand.pillBackground)
            .border(1.dp, AppTheme.brand.hairline.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        icon()
        Text(
            "Wallet",
            color = AppTheme.brand.headerContentColor.copy(alpha = 0.68f),
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

@Composable
private fun PlanBadge(plan: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.brand.coinGold)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = Color(0xFF171007), modifier = Modifier.size(14.dp))
        Text(plan, color = Color(0xFF171007), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun CoinDot(size: Int = 18) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppTheme.brand.coinGold),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color(0xFF171007), modifier = Modifier.size((size - 6).dp))
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
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.brand.pillBackground)
            .border(1.dp, AppTheme.brand.hairline.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}