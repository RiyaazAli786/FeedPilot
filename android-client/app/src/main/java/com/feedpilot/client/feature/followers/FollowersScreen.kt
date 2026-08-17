package com.feedpilot.client.feature.followers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.feature.common.BalanceViewModel
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.ui.components.Avatar
import com.feedpilot.client.ui.components.CoinDot
import com.feedpilot.client.ui.components.InputDialog
import com.feedpilot.client.ui.components.SelectTargetButton
import com.feedpilot.client.ui.theme.AppTheme

@Composable
fun FollowersScreen(
    onSettings: () -> Unit,
    onWithdraw: (() -> Unit)? = null,
    viewModel: FollowersViewModel = hiltViewModel(),
    balanceViewModel: BalanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceState by balanceViewModel.state.collectAsStateWithLifecycle()
    var showTargetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Ordering reports every outcome — including refusals like a missing target or too few
    // coins — through this message. Without surfacing it, tapping Get looks like a no-op.
    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppHeader(
            coins = state.coins,
            plan = balanceState.plan,
            latestVersionName = balanceState.latestVersionName,
            onSettings = onSettings,
            onWithdraw = onWithdraw
        ) {
            SelectTargetButton(target = state.targetUsername, onClick = { showTargetDialog = true })
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (state.loadingTarget) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.targetSummary?.let { summary ->
                item { TargetCard(summary) }
            }
            items(state.packages) { pkg ->
                PackageRow(
                    followers = pkg.followers,
                    coins = pkg.coins,
                    onGet = { viewModel.order(pkg) }
                )
            }
        }
    }

    if (showTargetDialog) {
        InputDialog(
            title = "Enter Target Username",
            icon = Icons.Filled.PersonPin,
            placeholder = "username",
            prefix = "@",
            onDismiss = { showTargetDialog = false },
            onConfirm = {
                viewModel.setTarget(it)
                showTargetDialog = false
            }
        )
    }
}

@Composable
private fun TargetCard(summary: TargetSummary) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Avatar(summary.avatarUrl, size = 48)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "@${summary.username}",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    com.feedpilot.client.ui.components.CopyUsernameButton(summary.username)
                }
                Text(
                    "${summary.followers} followers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PackageRow(followers: Int, coins: Long, onGet: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PersonAddAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("$followers", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))

            CoinDot(size = 20)
            Spacer(Modifier.width(6.dp))
            Text("$coins", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))

            OutlinedButton(
                onClick = onGet,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.brand.orange),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.brand.orange)
            ) {
                Text("Get", fontWeight = FontWeight.Bold)
            }
        }
    }
}
