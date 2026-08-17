package com.feedpilot.client.feature.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.ui.components.Avatar
import com.feedpilot.client.ui.components.CoinDot
import com.feedpilot.client.ui.components.PurpleTopBar
import com.feedpilot.client.common.formatCoins
import com.feedpilot.client.ui.theme.AppTheme

@Composable
fun LeaderboardScreen(
    onBack: () -> Unit,
    viewModel: LeaderboardViewModel = hiltViewModel()
) {
    val leaders by viewModel.leaders.collectAsStateWithLifecycle()
    val currentOrder by viewModel.sortOrder.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PurpleTopBar(title = "LeaderBoard", onBack = onBack)

        if (leaders.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = AppTheme.brand.coinGold,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        "No Leaderboard Activity Yet",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Complete tasks to earn coins and claim top rankings on the leaderboard!",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val maxCoins = leaders.maxOfOrNull { it.account.coinsEarned } ?: 1L

            SortingFilterRow(
                selectedOrder = currentOrder,
                onOrderSelected = { viewModel.updateSortOrder(it) }
            )

            val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
            val isLastPage by viewModel.isLastPage.collectAsStateWithLifecycle()

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(leaders, key = { item -> item.account.id }) { leaderItem ->
                    LeaderCard(
                        rank = leaderItem.absoluteRank,
                        account = leaderItem.account,
                        maxCoins = maxCoins
                    )
                }

                if (!isLastPage) {
                    item {
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.loadNextPage() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Load More", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortingFilterRow(
    selectedOrder: SortOrder,
    onOrderSelected: (SortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Top 5 Users Leaderboard",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Descending (Highest First) Option with Arrow Icon
            val isDesc = selectedOrder == SortOrder.DESCENDING
            val descBg = if (isDesc) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            val descText = if (isDesc) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val descBorder = if (isDesc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

            Surface(
                onClick = { onOrderSelected(SortOrder.DESCENDING) },
                shape = RoundedCornerShape(20.dp),
                color = descBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, descBorder),
                modifier = Modifier.height(32.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Highest First",
                        tint = descText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Highest",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = descText
                    )
                }
            }

            // Ascending (Lowest First) Option with Arrow Icon
            val isAsc = selectedOrder == SortOrder.ASCENDING
            val ascBg = if (isAsc) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            val ascText = if (isAsc) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val ascBorder = if (isAsc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

            Surface(
                onClick = { onOrderSelected(SortOrder.ASCENDING) },
                shape = RoundedCornerShape(20.dp),
                color = ascBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, ascBorder),
                modifier = Modifier.height(32.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = "Lowest First",
                        tint = ascText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Lowest",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ascText
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaderCard(rank: Int, account: AccountEntity, maxCoins: Long) {
    val medal = when (rank) {
        1 -> AppTheme.brand.coinGold
        2 -> Color(0xFFB0BEC5)
        3 -> Color(0xFFCD7F32)
        else -> MaterialTheme.colorScheme.primary
    }

    val progress = if (maxCoins > 0) account.coinsEarned.toFloat() / maxCoins.toFloat() else 0f

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(medal),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Avatar(account.profilePictureUrl, size = 40)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        account.username,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Relative progress: ${(progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                com.feedpilot.client.ui.components.CopyUsernameButton(account.username)
                Spacer(Modifier.width(8.dp))
                CoinDot(size = 18)
                Spacer(Modifier.width(6.dp))
                Text(account.coinsEarned.formatCoins(), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            // Premium progress graph (styled horizontal bar with a gradient look)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                val brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        AppTheme.brand.magenta
                    )
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(brush)
                )
            }
        }
    }
}
