package com.feedpilot.client.feature.handles

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.feedpilot.client.data.local.WatchedHandleEntity
import com.feedpilot.client.data.remote.InstagramComment
import com.feedpilot.client.data.remote.InstagramSearchUser
import com.feedpilot.client.feature.common.BalanceViewModel
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.ui.components.Avatar
import com.feedpilot.client.ui.theme.AppTheme
import java.util.Locale

@Composable
fun HandleScreen(
    onSettings: () -> Unit,
    onWithdraw: (() -> Unit)? = null,
    viewModel: HandleViewModel = hiltViewModel(),
    balanceViewModel: BalanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceState by balanceViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    val detailHandle = state.handles.firstOrNull { it.id == state.detailHandleId }
    var pendingDelete by remember { mutableStateOf<WatchedHandleEntity?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var webHandle by remember { mutableStateOf<WatchedHandleEntity?>(null) }

    val rssFeedJson = state.rssFeedJson
    if (rssFeedJson != null) {
        RssFeedDialog(
            title = state.rssFeedTitle ?: "Latest feed JSON",
            json = rssFeedJson,
            onDismiss = viewModel::closeRssFeed
        )
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearMessage()
    }

    LaunchedEffect(state.error) {
        val text = state.error ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearMessage()
    }

    pendingDelete?.let { handle ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete handle?") },
            text = { Text("Remove @${handle.username} and its saved feed posts from this device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteHandle(handle)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (searchOpen) {
        HandleSearchDialog(
            query = username,
            defaultInterval = 60,
            results = state.searchResults,
            searching = state.searching,
            loading = state.loading,
            onSearch = { viewModel.searchInstagramUsers(username) },
            onSave = { selected ->
                viewModel.addHandles(selected)
                searchOpen = false
                username = ""
                viewModel.clearSearchResults()
            },
            onDismiss = {
                searchOpen = false
                viewModel.clearSearchResults()
            }
        )
    }

    webHandle?.let { handle ->
        HandleWebViewDialog(
            handle = handle,
            onDismiss = { webHandle = null }
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppHeader(
            coins = balanceState.coins,
            plan = balanceState.plan,
            latestVersionName = balanceState.latestVersionName,
            onSettings = onSettings,
            onWithdraw = onWithdraw
        )

        if (detailHandle != null) {
            val postDetail = state.postDetail
            if (postDetail != null) {
                BackHandler { viewModel.closePost() }
                HandlePostDetail(
                    detail = postDetail,
                    onBack = { viewModel.closePost() },
                    onLike = viewModel::likePost,
                    onRepost = viewModel::repostPost,
                    onSave = viewModel::savePost,
                    onPostComment = viewModel::postComment,
                    onLoadMoreComments = { viewModel.loadComments() }
                )
            } else {
                BackHandler { viewModel.closeHandle() }
                HandleFeedDetail(
                    handle = detailHandle,
                    state = state,
                    onBack = { viewModel.closeHandle() },
                    onRefresh = { viewModel.fetchLatestFeed(detailHandle) },
                    onLoadMore = { viewModel.loadMoreFeed(detailHandle) },
                    onSavePost = { post -> viewModel.saveFeedPost(detailHandle, post) },
                    onOpenPost = viewModel::openPost
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "Handle Watcher",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Add Instagram handles and save their latest feed post IDs on a schedule.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(10.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            placeholder = { Text("@username or profile URL") },
                            trailingIcon = {
                                IconButton(onClick = { searchOpen = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = "Search Instagram",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            },
                            singleLine = true
                        )
                    }
                }
            }

            item {
                if (state.handles.isEmpty()) {
                    EmptyHandles()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.handles.forEach { handle ->
                            HandleCard(
                                handle = handle,
                                onClick = { viewModel.openHandle(handle) },
                                onOpenWeb = { webHandle = handle },
                                onFetchRss = { viewModel.fetchRssFeed(handle) },
                                rssLoading = state.rssLoadingHandleId == handle.id,
                                onToggle = { viewModel.toggleWatch(handle, it) },
                                onDelete = { pendingDelete = handle }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHandles() {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Visibility, contentDescription = null, tint = AppTheme.brand.orange)
            Spacer(Modifier.height(8.dp))
            Text("No handles added", fontWeight = FontWeight.Bold)
            Text("Add a public Instagram handle to start saving feed post IDs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HandleSearchDialog(
    query: String,
    defaultInterval: Int,
    results: List<InstagramSearchUser>,
    searching: Boolean,
    loading: Boolean,
    onSearch: () -> Unit,
    onSave: (Map<String, Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(results) { mutableStateOf<Set<String>>(emptySet()) }
    val intervals = remember(results) {
        mutableStateMapOf<String, String>().apply {
            results.forEach { user -> put(user.username, defaultInterval.toString()) }
        }
    }
    LaunchedEffect(Unit) {
        if (query.trim().length >= 2) onSearch()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Instagram", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (query.isBlank()) "Enter a handle to search." else "Query: @$query",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onSearch, enabled = !searching && query.trim().length >= 2) {
                        if (searching) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                }
                if (searching) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AppTheme.brand.orange)
                        Spacer(Modifier.height(8.dp))
                        Text("Searching Instagram...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (results.isEmpty()) {
                    Text(
                        "No results loaded yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results, key = { it.username }) { user ->
                            val checked = user.username in selected
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = {
                                                selected = if (checked) selected - user.username else selected + user.username
                                            }
                                        )
                                        Avatar(user.profilePicUrl, size = 40)
                                        Column(
                                            Modifier
                                                .weight(1f)
                                                .clickable {
                                                    selected = if (checked) selected - user.username else selected + user.username
                                                }
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("@${user.username}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (user.isVerified) {
                                                    Spacer(Modifier.width(5.dp))
                                                    Text("Verified", color = AppTheme.brand.orange, fontSize = 11.sp)
                                                }
                                            }
                                            Text(
                                                user.fullName ?: if (user.isPrivate) "Private account" else "Instagram profile",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "Watch interval",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = intervals[user.username] ?: defaultInterval.toString(),
                                            onValueChange = { value ->
                                                intervals[user.username] = value.filter(Char::isDigit).take(4)
                                                if (!checked) selected = selected + user.username
                                            },
                                            modifier = Modifier.width(96.dp).height(52.dp),
                                            suffix = { Text("min", fontSize = 12.sp) },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "${selected.size} selected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        selected.associateWith { username ->
                            (intervals[username]?.toIntOrNull() ?: defaultInterval).coerceIn(15, 1440)
                        }
                    )
                },
                enabled = selected.isNotEmpty() && !loading && !searching
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun HandleCard(
    handle: WatchedHandleEntity,
    onClick: () -> Unit,
    onOpenWeb: () -> Unit,
    onFetchRss: () -> Unit,
    rssLoading: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (handle.profilePictureUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = handle.profilePictureUrl,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("@${handle.username}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(handle.fullName ?: "Instagram handle", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    onClick = onFetchRss,
                    enabled = !rssLoading,
                    modifier = Modifier.size(34.dp)
                ) {
                    if (rssLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.RssFeed,
                            contentDescription = "Load latest feed JSON",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString("@${handle.username}"))
                        Toast.makeText(context, "Copied @${handle.username}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy handle",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete")
                }
            }
            Text(
                text = "${formatHandleCount(handle.followerCount)} followers  |  " +
                    "${formatHandleCount(handle.followingCount)} following  |  " +
                    "${formatHandleCount(handle.mediaCount.coerceAtLeast(handle.savedPostCount.toLong()))} posts",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${handle.pollIntervalMinutes} min watcher", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = onOpenWeb, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "Open handle",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    SlimWatchToggle(checked = handle.watchEnabled, onCheckedChange = onToggle)
                }
            }
        }
    }
}

@Composable
private fun RssFeedDialog(
    title: String,
    json: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val horizontalScroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Surface(
                modifier = Modifier.fillMaxWidth().height(420.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                SelectionContainer {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        item {
                            Text(
                                json,
                                modifier = Modifier.horizontalScroll(horizontalScroll),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(json))
                    Toast.makeText(context, "JSON copied", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
        }
    )
}

@Composable
private fun SlimWatchToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .size(width = 50.dp, height = 28.dp)
            .clickable { onCheckedChange(!checked) },
        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

@Composable
private fun HandleWebViewDialog(
    handle: WatchedHandleEntity,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var webReady by remember { mutableStateOf(false) }
    val targetUrl = remember(handle.username) {
        "https://www.instagram.com/${handle.username.trim().trimStart('@')}/"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@${handle.username}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(targetUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                if (isLoading || !webReady) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize().alpha(if (webReady) 1f else 0f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    isLoading = true
                                    webReady = false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.collapseInstagramProfileChrome {
                                        isLoading = false
                                        webReady = true
                                    }
                                }
                            }
                            loadUrl(targetUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url != targetUrl) {
                            webReady = false
                            isLoading = true
                            webView.loadUrl(targetUrl)
                        } else if (!webReady) {
                            webView.collapseInstagramProfileChrome {
                                isLoading = false
                                webReady = true
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun WebView.collapseInstagramProfileChrome(onCollapsed: (() -> Unit)? = null) {
    fun inject(delayMs: Long, notify: Boolean = false) = postDelayed({
        evaluateJavascript(
            """
            (function() {
              if (window.__feedPilotProfileCollapse) {
                window.__feedPilotProfileCollapse();
                return;
              }
              window.__feedPilotProfileCollapse = function() {
                var styleId = 'feedpilot-profile-collapse-style';
                if (!document.getElementById(styleId)) {
                  var style = document.createElement('style');
                  style.id = styleId;
                  style.textContent = [
                    'main header{display:none!important}',
                    'main section header{display:none!important}',
                    '[aria-label="Profile picture"]{display:none!important}',
                    'main [role="presentation"] header{display:none!important}'
                  ].join('\n');
                  document.head.appendChild(style);
                }

                function hideOnboardingBlocks() {
                  var needles = ['Getting Started', 'Share Photos', 'Share your first photo', 'Add profile photo'];
                  var all = Array.prototype.slice.call(document.querySelectorAll('main section, main article, main div'));
                  all.forEach(function(el) {
                    var text = (el.innerText || '').trim();
                    if (!text || text.length > 600) return;
                    if (needles.some(function(n) { return text.indexOf(n) >= 0; })) {
                      var block = el.closest('section') || el.closest('article') || el;
                      block.style.display = 'none';
                    }
                  });
                }

                hideOnboardingBlocks();
                window.scrollTo(0, 0);
              };
              window.__feedPilotProfileCollapse();
              if (!window.__feedPilotProfileCollapseObserver) {
                window.__feedPilotProfileCollapseObserver = new MutationObserver(function() {
                  window.__feedPilotProfileCollapse();
                });
                window.__feedPilotProfileCollapseObserver.observe(document.body, { childList: true, subtree: true });
              }
            })();
            """.trimIndent(),
            { if (notify) onCollapsed?.invoke() }
        )
    }, delayMs)

    inject(500)
    inject(1400, notify = true)
    inject(2600)
}

@Composable
private fun HandleFeedDetail(
    handle: WatchedHandleEntity,
    state: HandleUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSavePost: (HandleFeedPost) -> Unit,
    onOpenPost: (HandleFeedPost) -> Unit
) {
    val savedPostIds = remember(state.posts) {
        state.posts.map { it.postId }.toSet()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("@${handle.username}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text("${handle.savedPostCount} saved posts", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = !state.refreshingFeed,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
                    ) {
                        if (state.refreshingFeed && state.feedPosts.isEmpty()) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.refreshingFeed) "Fetching" else "Fetch")
                    }
                }
                state.error?.let { errorText ->
                    Text(
                        errorText,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }

        if (state.feedLoading) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppTheme.brand.orange)
                    Spacer(Modifier.height(10.dp))
                    Text("Loading feed...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (state.feedPosts.isEmpty()) {
            item {
                Text(
                    "No feed posts loaded yet. Tap Fetch to load this handle's latest Instagram feed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            }
        } else {
            items(state.feedPosts, key = { it.postId }) { post ->
                WatchedPostCard(
                    post = post,
                    saved = post.postId in savedPostIds,
                    saving = state.savingPostId == post.postId,
                    onSave = { onSavePost(post) },
                    onClick = { onOpenPost(post) }
                )
            }
            item {
                Button(
                    onClick = onLoadMore,
                    enabled = state.feedHasMore && !state.feedLoadingMore,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
                ) {
                    if (state.feedLoadingMore) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.feedHasMore) "Load More" else "No More Posts")
                }
            }
        }
    }
}

@Composable
private fun WatchedPostCard(
    post: HandleFeedPost,
    saved: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val openUrl = post.permalink ?: post.code?.let { "https://www.instagram.com/p/$it/" }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = post.mediaUrl,
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(post.code?.let { "Post $it" } ?: "Post ${post.postId}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(post.caption ?: post.permalink ?: post.postId, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${formatHandleCount(post.likeCount)} likes  |  ${formatHandleCount(post.commentCount)} comments", color = AppTheme.brand.orange)
            }
            IconButton(
                onClick = {
                    if (openUrl == null) {
                        Toast.makeText(context, "Post link is not available.", Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)))
                        }.onFailure {
                            Toast.makeText(context, "Could not open post.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open post in web",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onSave,
                enabled = !saved && !saving,
                modifier = Modifier.size(40.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = if (saved) "Post saved" else "Save post to DB",
                        tint = if (saved) AppTheme.brand.orange else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HandlePostDetail(
    detail: HandlePostDetailState,
    onBack: () -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onSave: () -> Unit,
    onPostComment: (String) -> Unit,
    onLoadMoreComments: () -> Unit
) {
    var draft by remember(detail.post.postId) { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text(detail.post.code?.let { "Post $it" } ?: "Post ${detail.post.postId}", fontWeight = FontWeight.Bold)
                    Text("${formatHandleCount(detail.post.likeCount)} likes  |  ${formatHandleCount(detail.post.commentCount)} comments", color = AppTheme.brand.orange)
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = detail.post.mediaUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        detail.post.caption ?: detail.post.permalink ?: detail.post.postId,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    ActionRow(detail, onLike, onRepost, onSave)
                    detail.actionMessage?.let {
                        Text(it, color = AppTheme.brand.orange, fontWeight = FontWeight.Bold)
                    }
                    detail.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ModeComment, contentDescription = null, tint = AppTheme.brand.orange)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (detail.commentsTotal > 0) "Comments (${detail.commentsTotal})" else "Comments",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text("Add a comment") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                onPostComment(draft)
                                draft = ""
                            },
                            enabled = draft.isNotBlank() && !detail.postingComment
                        ) {
                            if (detail.postingComment) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.brand.orange)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post comment", tint = AppTheme.brand.orange)
                            }
                        }
                    }
                }
            }
        }

        when {
            detail.commentsLoading -> item {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppTheme.brand.orange)
                    Spacer(Modifier.height(8.dp))
                    Text("Loading comments...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            detail.comments.isEmpty() -> item {
                Text(
                    "No comments loaded yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(18.dp)
                )
            }
            else -> {
                items(detail.comments, key = { it.id }) { comment ->
                    CommentRow(comment)
                }
                item {
                    OutlinedButton(
                        onClick = onLoadMoreComments,
                        enabled = detail.commentsHasMore && !detail.commentsLoadingMore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (detail.commentsLoadingMore) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.brand.orange)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (detail.commentsHasMore) "Load more comments" else "No more comments")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    detail: HandlePostDetailState,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onSave: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Like", Icons.Filled.Favorite, detail.liked, detail.actionBusy == "Like", onLike, Modifier.weight(1f))
        ActionButton("Repost", Icons.Filled.Repeat, detail.reposted, detail.actionBusy == "Repost", onRepost, Modifier.weight(1f))
        ActionButton("Save", Icons.Filled.Bookmark, detail.saved, detail.actionBusy == "Save", onSave, Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    done: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !done && !busy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(if (done) "Done" else label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun CommentRow(comment: InstagramComment) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(comment.avatarUrl, size = 34)
            Column(Modifier.weight(1f)) {
                Text("@${comment.username}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(comment.text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                if (comment.likeCount > 0) {
                    Text("${formatHandleCount(comment.likeCount)} likes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun formatHandleCount(value: Long): String {
    return when {
        value >= 1_000_000_000L -> trimCount(value / 1_000_000_000.0, "B")
        value >= 1_000_000L -> trimCount(value / 1_000_000.0, "M")
        value >= 1_000L -> trimCount(value / 1_000.0, "K")
        else -> value.toString()
    }
}

private fun trimCount(value: Double, suffix: String): String {
    val formatted = String.format(Locale.US, "%.1f", value)
    return formatted.removeSuffix(".0") + suffix
}
