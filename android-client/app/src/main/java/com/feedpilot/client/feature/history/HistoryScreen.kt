package com.feedpilot.client.feature.history

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.data.local.OrderHistoryEntity
import com.feedpilot.client.data.local.OrderSource
import com.feedpilot.client.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    OrderHistoryContent(
        onBack = onBack,
        showHeader = true,
        viewModel = viewModel
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderHistoryContent(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var orderToCancel by remember { mutableStateOf<OrderHistoryEntity?>(null) }
    var orderToDelete by remember { mutableStateOf<OrderHistoryEntity?>(null) }
    var activeWebViewUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (showHeader) {
            TopAppBar(
                title = { Text("Order History Logs", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refreshStatuses,
                        enabled = !state.pageLoading
                    ) {
                        if (state.pageLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload this page")
                        }
                    }
                }
            )
        }

        // Date Range Filters Row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DateRangeFilter.entries) { range ->
                FilterChip(
                    selected = state.dateRange == range,
                    onClick = { viewModel.setDateRangeFilter(range) },
                    label = { Text(range.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        // Profile search
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search by profile or provider", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        PagerControls(state = state, viewModel = viewModel)

        PullToRefreshBox(
            isRefreshing = state.pageLoading,
            onRefresh = viewModel::refreshStatuses,
            modifier = Modifier.weight(1f)
        ) {
            if (state.filteredLogs.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    val searching = state.searchQuery.isNotBlank()
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (searching) Icons.Filled.SearchOff else Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            if (searching) "No orders match \"${state.searchQuery}\"" else "No Order History Logs Found",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            if (searching) "Try a different profile handle or provider name."
                            else "Orders placed via SMM providers will automatically be logged here profile-wise.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.filteredLogs, key = { it.id }) { log ->
                        OrderHistoryCard(
                            log = log,
                            onCancel = { orderToCancel = log },
                            onView = {
                                activeWebViewUrl = if (log.targetUsername.startsWith("http://") || log.targetUsername.startsWith("https://")) {
                                    log.targetUsername
                                } else if (log.targetUsername.startsWith("post_") || log.orderType.uppercase() in listOf("LIKE", "LIKES", "COMMENT", "COMMENTS", "REPOST", "REPOSTS")) {
                                    "https://www.instagram.com/p/${log.targetUsername.removePrefix("post_").trimStart('@')}/"
                                } else {
                                    "https://www.instagram.com/${log.targetUsername.trimStart('@')}/"
                                }
                            },
                            onDelete = { orderToDelete = log }
                        )
                    }
                }
            }
        }
    }

    // Cancel Order Confirmation Dialog
    orderToCancel?.let { log ->
        AlertDialog(
            onDismissRequest = { orderToCancel = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = AppTheme.brand.orange) },
            title = { Text("Cancel SMM Order?") },
            text = { Text("Are you sure you want to cancel this order (${log.orderType}) for ${log.targetUsername}? A cancel request will be sent to ${log.providerNickname}.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelOrder(log)
                        orderToCancel = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Confirm Cancel", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { orderToCancel = null }) { Text("Dismiss") }
            }
        )
    }

    // Delete Order Log Confirmation Dialog
    orderToDelete?.let { log ->
        AlertDialog(
            onDismissRequest = { orderToDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Order Log?") },
            text = { Text("Are you sure you want to delete this order log from history? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLog(log.id)
                        orderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { orderToDelete = null }) { Text("Dismiss") }
            }
        )
    }

    // Order Target WebView Dialog (seeded with active account session cookies)
    activeWebViewUrl?.let { targetUrl ->
        OrderLogWebViewDialog(
            url = targetUrl,
            viewModel = viewModel,
            onDismiss = { activeWebViewUrl = null }
        )
    }
}

@Composable
private fun PagerControls(state: HistoryUiState, viewModel: HistoryViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Show",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PAGE_SIZE_OPTIONS.forEach { size ->
                FilterChip(
                    selected = state.pageSize == size,
                    onClick = { viewModel.setPageSize(size) },
                    label = { Text("$size", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = viewModel::prevPage,
                enabled = state.page > 1 && !state.pageLoading,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
            }
            Text(
                "Page ${state.page} / ${state.totalPages}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = viewModel::nextPage,
                enabled = state.page < state.totalPages && !state.pageLoading,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
            }
        }
    }
}

@Composable
private fun SourceBadge(source: OrderSource) {
    val isExternal = source == OrderSource.EXTERNAL
    val tint = if (isExternal) AppTheme.brand.orange else MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isExternal) Icons.Filled.CloudDownload else Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = source.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
    }
}

@Composable
private fun OrderHistoryCard(
    log: OrderHistoryEntity,
    onCancel: () -> Unit,
    onView: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(log.timestamp))

    val isCompleted = log.completedCount >= log.quantity || log.status.uppercase() in listOf("SUCCESS", "COMPLETED")
    val isCancelled = log.status.uppercase() in listOf("CANCELED", "CANCELLED", "FAILED")
    val displayStatus = when {
        isCompleted -> "Completed"
        isCancelled -> log.status.uppercase().replace("CANCELLED", "CANCELED")
        else -> "${log.completedCount}/${log.quantity}"
    }
    val statusColor = when {
        isCompleted -> Color(0xFF4CAF50)
        isCancelled -> MaterialTheme.colorScheme.error
        else -> AppTheme.brand.orange
    }
    val progressFraction = if (log.quantity > 0) (log.completedCount.toFloat() / log.quantity.toFloat()).coerceIn(0f, 1f) else 0f

    // Extract clean display target: shortcode for post URLs, handle for everything else
    val cleanTarget = run {
        val raw = log.targetUsername.trimStart('@').removePrefix("post_")
        when {
            // Instagram post URL → extract shortcode: /p/CODE/ or /reel/CODE/
            raw.contains("/p/") || raw.contains("/reel/") -> {
                val segment = if (raw.contains("/p/")) raw.substringAfter("/p/") else raw.substringAfter("/reel/")
                segment.substringBefore("/").substringBefore("?").take(20)
            }
            // Story URL → extract username only
            raw.contains("/stories/") -> raw.substringAfter("/stories/").substringBefore("/").substringBefore("?")
            // Full profile URL → extract username
            raw.startsWith("http") -> raw.substringAfter("instagram.com/").trimEnd('/').substringBefore("/").substringBefore("?")
            // Already a plain handle or ID
            else -> raw.take(24)
        }
    }
    val isPostTarget = log.targetUsername.startsWith("post_") ||
            log.targetUsername.contains("/p/") ||
            log.targetUsername.contains("/reel/") ||
            log.orderType.uppercase() in listOf("LIKE", "LIKES", "COMMENT", "COMMENTS", "REPOST", "REPOSTS")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Row 1: source badge + order type + status chip
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceBadge(log.orderSource)
                Text(
                    text = "${log.orderType} · Qty ${log.quantity}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(shape = RoundedCornerShape(6.dp), color = statusColor.copy(alpha = 0.15f)) {
                    Text(
                        text = displayStatus,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            // Row 2: progress bar (compact, no label)
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = statusColor,
                trackColor = statusColor.copy(alpha = 0.12f)
            )

            // Row 3: target (handle or shortcode) + copy + provider
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = cleanTarget,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!isPostTarget) {
                    com.feedpilot.client.ui.components.CopyUsernameButton(
                        cleanTarget,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Row 4: date + cancel + view + delete (all in one row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formattedDate, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val isCancellable = log.orderSource == OrderSource.APP &&
                        !isCompleted &&
                        log.status.uppercase() !in listOf("CANCELED", "CANCELLED", "FAILED")
                    if (isCancellable) {
                        OutlinedButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(Icons.Filled.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Cancel", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onView, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Visibility, contentDescription = "View", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OrderLogWebViewDialog(
    url: String,
    viewModel: HistoryViewModel,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var activeCookies by remember { mutableStateOf<String?>(null) }
    var targetAccountName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val (username, cookies) = viewModel.getActiveAccountSession()
        targetAccountName = username
        activeCookies = cookies
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Order Target Web Viewer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        targetAccountName?.let {
                            Text(
                                "Active Account: @$it",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Resolve the final URL to navigate to
                val targetUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                    url
                } else if (url.startsWith("post_")) {
                    "https://www.instagram.com/p/${url.removePrefix("post_").trimStart('@')}/"
                } else {
                    "https://www.instagram.com/${url.trimStart('@')}/"
                }

                if (activeCookies == null) {
                    // Still loading the active session — show spinner
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Loading account session...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    key(activeCookies) {
                    AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    @Suppress("DEPRECATION")
                                    databaseEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                }

                                // Seed the active account cookies BEFORE loading the URL
                                val cookieManager = android.webkit.CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                cookieManager.removeAllCookies(null)

                                if (!activeCookies.isNullOrBlank()) {
                                    activeCookies!!.split(";").forEach { pair ->
                                        val trimmed = pair.trim()
                                        if (trimmed.isNotEmpty()) {
                                            cookieManager.setCookie("https://www.instagram.com", "$trimmed; Domain=.instagram.com; Path=/")
                                        }
                                    }
                                    cookieManager.flush()
                                }

                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                    }
                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        isLoading = false
                                    }
                                }

                                loadUrl(targetUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    } // end key(activeCookies)
                }
            }
        }
    }
}
