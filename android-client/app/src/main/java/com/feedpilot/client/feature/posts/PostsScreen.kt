package com.feedpilot.client.feature.posts

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
import com.feedpilot.client.feature.common.BalanceViewModel
import com.feedpilot.client.ui.components.AppHeader
import com.feedpilot.client.ui.components.Avatar
import com.feedpilot.client.ui.components.InputDialog
import com.feedpilot.client.ui.components.SelectTargetButton
import com.feedpilot.client.ui.theme.AppTheme
import com.feedpilot.client.data.repository.AppSettings

@Composable
fun PostsScreen(
    onSettings: () -> Unit,
    onWithdraw: (() -> Unit)? = null,
    viewModel: PostsViewModel = hiltViewModel(),
    balanceViewModel: BalanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceState by balanceViewModel.state.collectAsStateWithLifecycle()
    val commentSheet by viewModel.commentSheet.collectAsStateWithLifecycle()
    var showLinkDialog by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var activeReelPost by remember { mutableStateOf<PostItem?>(null) }
    var orderLikesPost by remember { mutableStateOf<PostItem?>(null) }
    var orderPostInitialTab by remember { mutableStateOf("Like") }

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

        // Fixed Non-Scrollable Header Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showLinkDialog = true },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Link, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Get post with link", fontWeight = FontWeight.Bold)
            }

            state.profile?.let { profile ->
                ProfileHeaderCard(profile = profile, loadedPostsCount = state.posts.size)
            }
        }

        // Scrollable Posts Grid Section
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.posts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = if (state.targetUsername == null)
                            "Select a target or paste a post link to load details & posts."
                        else "No posts to show.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            items(
                items = state.posts,
                key = { it.id },
                span = { post ->
                    if (state.posts.size == 1 && state.profile == null) GridItemSpan(maxLineSpan)
                    else GridItemSpan(1)
                }
            ) { post ->
                PostCard(
                    post = post,
                    onClick = { orderLikesPost = post; orderPostInitialTab = "Like" },
                    onPlayReel = { activeReelPost = post },
                    onLike = { orderLikesPost = post; orderPostInitialTab = "Like" },
                    onRepost = { orderLikesPost = post; orderPostInitialTab = "Repost" },
                    onComment = { orderLikesPost = post; orderPostInitialTab = "Comment" },
                    onSave = { orderLikesPost = post; orderPostInitialTab = "Save" }
                )
            }

            if (state.hasMorePosts) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(
                        onClick = viewModel::loadMorePosts,
                        enabled = !state.loadingMore,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 16.dp)
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AppTheme.brand.magenta
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Loading more posts...", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Load More Posts (${state.posts.size})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showTargetDialog) {
        InputDialog(
            title = "Enter Target Handle or Post Link",
            icon = Icons.Filled.PersonSearch,
            placeholder = "username or link",
            onDismiss = { showTargetDialog = false },
            onConfirm = {
                viewModel.setTarget(it)
                showTargetDialog = false
            }
        )
    }

    if (showLinkDialog) {
        InputDialog(
            title = "Enter Target Post Link",
            icon = Icons.Filled.Link,
            placeholder = "https://instagram.com/p/DBCWYNelxMs/",
            onDismiss = { showLinkDialog = false },
            onConfirm = {
                viewModel.submitLink(it)
                showLinkDialog = false
            }
        )
    }

    activeReelPost?.let { reel ->
        ReelPlayerDialog(
            post = reel,
            onDismiss = { activeReelPost = null },
            onOrderLikes = {
                activeReelPost = null
                orderLikesPost = reel
            }
        )
    }

    orderLikesPost?.let { post ->
        PlacePostOrderDialog(
            post = post,
            initialTab = orderPostInitialTab,
            availableCoins = balanceState.coins,
            settings = state.settings,
            onDismiss = { orderLikesPost = null },
            onConfirm = { orderType, count, comments ->
                viewModel.orderPostActivity(post, orderType, count, comments)
                orderLikesPost = null
            }
        )
    }

    // Only opened for posts that allow comments — the VM refuses otherwise.
    commentSheet?.let { sheet ->
        CommentsDialog(
            state = sheet,
            onDismiss = viewModel::closeComments,
            onLoadMore = viewModel::loadMoreComments,
            onPost = viewModel::postComment
        )
    }
}

@Composable
private fun CommentsDialog(
    state: com.feedpilot.client.feature.posts.CommentSheetState,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit = {},
    onPost: (String) -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.75f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ModeComment, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.total > 0) "Comments (${formatCount(state.total)})" else "Comments",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                var draft by remember { mutableStateOf("") }

                Box(Modifier.weight(1f)) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppTheme.brand.orange)
                    }
                    state.error != null || state.comments.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            state.error ?: "No comments yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.comments, key = { it.id }) { c ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(c.avatarUrl, size = 32)
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("@${c.username}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        com.feedpilot.client.ui.components.CopyUsernameButton(
                                            c.username,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Text(c.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                    if (c.likeCount > 0) {
                                        Text(
                                            "${formatCount(c.likeCount)} likes",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Pagination: fetch the next page on demand rather than auto-scrolling,
                        // so a long thread doesn't hammer Instagram unprompted.
                        if (state.hasMore) {
                            item {
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    enabled = !state.loadingMore,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AppTheme.brand.orange
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Loading…", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    } else {
                                        Text("Load more comments", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                }

                // Compose box. Posting requires a signed-in Instagram session; the result
                // message says so plainly when there isn't one.
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Add a comment…", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onPost(draft); draft = "" },
                        enabled = draft.isNotBlank() && !state.posting
                    ) {
                        if (state.posting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.brand.orange)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Post comment",
                                tint = if (draft.isNotBlank()) AppTheme.brand.orange else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(profile: TargetProfile, loadedPostsCount: Int = 0) {
    val igGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF833AB4), Color(0xFFFD1D1D), Color(0xFFF77737))
    )
    val displayPostsCount = maxOf(profile.posts, loadedPostsCount.toLong())

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Top Row: Avatar + Full Username + Copy Button + Full Name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .border(width = 2.dp, brush = igGradient, shape = CircleShape)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Avatar(profile.avatarUrl, size = 42)
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "@${profile.username}",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        com.feedpilot.client.ui.components.CopyUsernameButton(profile.username)
                        if (profile.isPrivate) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Private",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    if (!profile.fullName.isNullOrBlank()) {
                        Text(
                            text = profile.fullName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bottom Row: Evenly Distributed Stat Pills across the full card width
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill(label = "Followers", value = formatCount(profile.followers), modifier = Modifier.weight(1f))
                StatPill(label = "Following", value = formatCount(profile.following), modifier = Modifier.weight(1f))
                StatPill(label = "Posts", value = formatCount(displayPostsCount), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PostCard(
    post: PostItem,
    onClick: () -> Unit,
    onPlayReel: () -> Unit,
    onLike: () -> Unit = {},
    onRepost: () -> Unit = {},
    onComment: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clickable {
                        if (post.isVideo) onPlayReel() else onClick()
                    }
            ) {
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Video/Reel Indicator
                if (post.isVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play Reel",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }

                // Stats Overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CountBadge(icon = Icons.Filled.Favorite, countStr = formatCount(post.likes.toLong()), accentColor = Color(0xFFFF4D6D))
                    CountBadge(icon = Icons.Filled.ModeComment, countStr = formatCount(post.comments.toLong()), accentColor = Color(0xFF4EA8DE))
                }
            }

            // Caption / Get Likes Action
            Column(Modifier.padding(10.dp)) {
                if (!post.caption.isNullOrBlank()) {
                    Text(
                        text = post.caption,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Engage actions. Like and Repost are one-shot: once done (or when Instagram
                // disallows a reshare) the control becomes a non-clickable "done" state, so the
                // same action can't be fired twice. Comment is hidden when the post disables it.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PostAction(
                        icon = Icons.Filled.Favorite,
                        label = if (post.hasLiked) "Liked" else "Like",
                        done = post.hasLiked,
                        busy = post.busy,
                        accent = Color(0xFFFF4D6D),
                        modifier = Modifier.weight(1f),
                        onClick = onLike
                    )
                    if (!post.commentsDisabled) {
                        PostAction(
                            icon = Icons.Filled.ModeComment,
                            label = "Comment",
                            done = false,
                            busy = false,
                            accent = Color(0xFF4EA8DE),
                            modifier = Modifier.weight(1f),
                            onClick = onComment
                        )
                    }
                    if (post.canReshare) {
                        PostAction(
                            icon = Icons.Filled.Repeat,
                            label = if (post.hasReposted) "Reposted" else "Repost",
                            done = post.hasReposted,
                            busy = post.busy,
                            accent = Color(0xFF43C06E),
                            modifier = Modifier.weight(1f),
                            onClick = onRepost
                        )
                    }
                    PostAction(
                        icon = Icons.Filled.Bookmark,
                        label = "Save",
                        done = false,
                        busy = false,
                        accent = Color(0xFFFFA726),
                        modifier = Modifier.weight(1f),
                        onClick = onSave
                    )
                }

                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.magenta),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Get Likes",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PostAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    done: Boolean,
    busy: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val enabled = !done && !busy
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (done) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp, color = accent)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (done) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CountBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    countStr: String,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.55f)
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(12.dp))
            Text(countStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ReelPlayerDialog(
    post: PostItem,
    viewModel: PostsViewModel = hiltViewModel(),
    onDismiss: () -> Unit,
    onOrderLikes: () -> Unit
) {
    // The feed hands us a real CDN url (fbcdn.net / cdninstagram) — accept it as-is and play
    // straight away. Only fall back to resolving when we genuinely have no playable url.
    var resolvedVideoUrl by remember { mutableStateOf(post.videoUrl?.takeIf { isPlayableVideoUrl(it) }) }
    var isResolving by remember { mutableStateOf(resolvedVideoUrl == null) }
    var isFullScreen by remember { mutableStateOf(false) }

    LaunchedEffect(post) {
        if (resolvedVideoUrl == null) {
            viewModel.resolveReelVideoUrl(post) { url ->
                resolvedVideoUrl = url
                isResolving = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val containerModifier = if (isFullScreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(24.dp))
        }

        Surface(
            modifier = containerModifier,
            color = Color.Black,
            shadowElevation = if (isFullScreen) 0.dp else 8.dp,
            border = if (isFullScreen) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Box(Modifier.fillMaxSize()) {
                // Background Poster Image (ALWAYS visible as base layer so screen is NEVER black!)
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                val videoPath = resolvedVideoUrl
                if (!videoPath.isNullOrBlank()) {
                    AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                setVideoURI(android.net.Uri.parse(videoPath))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                                setOnErrorListener { _, _, _ -> true }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (!post.link.isNullOrBlank()) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                webChromeClient = WebChromeClient()
                                webViewClient = WebViewClient()
                                val cleanLink = post.link.trimEnd('/')
                                val targetUrl = "$cleanLink/embed"
                                val embedHtml = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                      <style>
                                        * { margin:0; padding:0; box-sizing:border-box; }
                                        html, body { width:100%; height:100%; background:#000; overflow:hidden; display:flex; justify-content:center; align-items:center; }
                                        iframe { border:none; width:100%; height:100%; object-fit:cover; }
                                      </style>
                                    </head>
                                    <body>
                                      <iframe src="$targetUrl" allowfullscreen></iframe>
                                    </body>
                                    </html>
                                """.trimIndent()
                                loadDataWithBaseURL("https://www.instagram.com", embedHtml, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isResolving && videoPath.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AppTheme.brand.magenta)
                    }
                }

                // Top Floating Toolbar (Fullscreen & Close Options)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        IconButton(onClick = { isFullScreen = !isFullScreen }) {
                            Icon(
                                imageVector = if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (isFullScreen) "Exit Fullscreen" else "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Bottom Controls & Caption
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                            )
                        )
                        .padding(16.dp)
                ) {
                    if (!post.caption.isNullOrBlank()) {
                        Text(
                            text = post.caption,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onOrderLikes,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange)
                        ) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Get Likes for Reel", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.clickable { isFullScreen = !isFullScreen }
                        ) {
                            Text(
                                text = if (isFullScreen) "Exit Full Screen" else "Full Screen",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacePostOrderDialog(
    post: PostItem,
    initialTab: String = "Like",
    availableCoins: Long,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onConfirm: (orderType: String, count: Int, comments: List<String>?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTab by remember { mutableStateOf(initialTab) }
    var selectedCategory by remember { mutableStateOf("My Comments") }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var customCommentInput by remember { mutableStateOf("") }
    val myCommentsList = remember { mutableStateListOf<String>() }
    val selectedCommentsSet = remember { mutableStateListOf<String>() }

    val categoryPresets = remember {
        mapOf(
            "greeting" to listOf(
                "Hi!", "Hello!", "Good day!", "Hey there!", "Nice to see you!",
                "Greetings!", "Hope you are having a wonderful day!", "Hello my friend!",
                "Wishing you a great day!", "Hey! How are you?", "Good morning!",
                "Hi there, awesome content!", "Hello everyone!", "Sending positive vibes!",
                "Hey hey!", "Warm greetings to you!", "Hope all is well!",
                "Have a fantastic day ahead!", "Nice to connect!", "Hello buddy!"
            ),
            "compliment" to listOf(
                "Amazing post!", "Love this!", "So cool!", "Fantastic!", "Superb!",
                "Incredible!", "Mind-blowing!", "Absolutely stunning!", "Top notch content!",
                "This is gold!", "Brilliant work!", "Spectacular photo!", "So aesthetic!",
                "Best post today!", "Truly impressive!", "You killed it!", "Outstanding effort!",
                "Perfection at its finest!", "Looking great!", "Masterpiece!"
            ),
            "Emoji" to listOf(
                "🔥", "👏", "❤️", "😍", "💯", "🙌", "✨", "👌", "🌟", "❤️‍🔥",
                "🔥🔥🔥", "❤️😍", "👏💯", "🙌✨", "🎉🥳", "🚀💎", "❤️‍🔥😍", "💪🔥", "🌟✨", "👑🔥",
                "💯💯", "😍❤️✨", "👍🔥", "🥳🎉", "🤩🔥", "✨💖", "🙌❤️", "😎🔥", "💪💯", "🔥❤️"
            ),
            "useful" to listOf(
                "Very helpful!", "Great info!", "Thanks for sharing!", "Informative!",
                "Extremely valuable advice!", "Learned something new today!", "Saving this for later!",
                "Such a helpful tip!", "Appreciate this information!", "Very insightful!",
                "Bookmark worthy!", "Super useful content!", "Thanks for explaining!",
                "Great guide!", "Needs to be shared everywhere!", "This is super practical!",
                "Awesome knowledge share!", "Thanks for breaking it down!", "Clear and useful!",
                "Super informative post!"
            ),
            "Congratulations" to listOf(
                "Congrats!", "Congratulations!", "So happy for you!", "Well deserved!",
                "Huge achievement!", "Proud of you!", "Way to go!", "Keep shining!",
                "Massive congratulations!", "Cheers to your success!", "Wishing you more success!",
                "You made it happen!", "Hard work pays off!", "Big milestone!",
                "So well earned!", "Bravo!", "Super happy for your success!",
                "Keep up the great work!", "Kudos to you!", "Glorious achievement!"
            )
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header Bar with Back Arrow and Title
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF6B1D90))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (selectedTab) {
                            "Repost" -> "Repost Order"
                            "Like" -> "Like Order"
                            "Save" -> "Save Post Order"
                            "Comment" -> "Comment Order"
                            else -> "Place Order"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Post Header Card Frame (Images 1, 2, 3, 4)
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(14.dp))
                    ) {
                        AsyncImage(
                            model = post.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Top-Left Badge (Images 1, 2, 3, 4)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (selectedTab) {
                                    "Repost" -> {
                                        Icon(Icons.Filled.Repeat, contentDescription = null, tint = Color(0xFF8E24AA), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("1168", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                    "Save" -> {
                                        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color(0xFF8E24AA), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("1891", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                    else -> {
                                        Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = Color(0xFFD81B60), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(formatCount(post.likes), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                            }
                        }

                        // Bottom-Right Badge (Images 1, 2, 3, 4)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (selectedTab) {
                                    "Comment", "Like" -> {
                                        Text(formatCount(post.comments), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Filled.ModeComment, contentDescription = null, tint = Color(0xFF8E24AA), modifier = Modifier.size(16.dp))
                                    }
                                    else -> {
                                        Text(formatCount(post.likes), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = Color(0xFFD81B60), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Tabs Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Like", "Comment", "Repost", "Save").forEach { tab ->
                            FilterChip(
                                selected = selectedTab == tab,
                                onClick = {
                                    selectedTab = tab
                                    selectedCommentsSet.clear()
                                },
                                label = {
                                    Text(
                                        text = tab,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Content Section based on selectedTab
                if (selectedTab == "Comment") {
                    Column(Modifier.fillMaxSize()) {
                        // Top bar: category dropdown + selected count badge
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Dropdown selector button
                            Box {
                                OutlinedButton(
                                    onClick = { categoryDropdownExpanded = true },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(selectedCategory, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = categoryDropdownExpanded,
                                    onDismissRequest = { categoryDropdownExpanded = false }
                                ) {
                                    listOf("My Comments", "greeting", "compliment", "Emoji", "useful", "Congratulations").forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat, fontWeight = if (cat == selectedCategory) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = {
                                                selectedCategory = cat
                                                categoryDropdownExpanded = false
                                                selectedCommentsSet.clear()
                                            }
                                        )
                                    }
                                }
                            }

                            // Display-only selected count badge
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedCommentsSet.isNotEmpty()) Color(0xFF1B998B).copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Selected (${selectedCommentsSet.size})",
                                    color = if (selectedCommentsSet.isNotEmpty()) Color(0xFF1B998B) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // Comment list (takes all remaining space)
                        Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            if (selectedCategory == "My Comments") {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = customCommentInput,
                                            onValueChange = { customCommentInput = it },
                                            placeholder = { Text("Enter your comment text", fontSize = 13.sp) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF1B998B),
                                            modifier = Modifier.size(44.dp).clickable {
                                                if (customCommentInput.isNotBlank()) {
                                                    val text = customCommentInput.trim()
                                                    if (!myCommentsList.contains(text)) {
                                                        myCommentsList.add(text)
                                                        selectedCommentsSet.add(text)
                                                    }
                                                    customCommentInput = ""
                                                }
                                            }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(myCommentsList) { comment ->
                                            val isSelected = selectedCommentsSet.contains(comment)
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) Color(0xFF1B998B).copy(alpha = 0.1f)
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    if (isSelected) selectedCommentsSet.remove(comment)
                                                    else selectedCommentsSet.add(comment)
                                                }
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = { checked ->
                                                            if (checked) selectedCommentsSet.add(comment)
                                                            else selectedCommentsSet.remove(comment)
                                                        }
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(comment, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val presetList = categoryPresets[selectedCategory] ?: emptyList()
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(presetList) { comment ->
                                        val isSelected = selectedCommentsSet.contains(comment)
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) Color(0xFF1B998B).copy(alpha = 0.1f)
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                if (isSelected) selectedCommentsSet.remove(comment)
                                                else selectedCommentsSet.add(comment)
                                            }
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        if (checked) selectedCommentsSet.add(comment)
                                                        else selectedCommentsSet.remove(comment)
                                                    }
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(comment, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom "Place Order" button — like Get on other tabs
                        HorizontalDivider()
                        Button(
                            onClick = {
                                if (selectedCommentsSet.isNotEmpty()) {
                                    val cost = selectedCommentsSet.size * 10L
                                    if (availableCoins < cost) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Not enough coins. This order costs $cost, your balance is $availableCoins.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        onConfirm("Comment", selectedCommentsSet.size, selectedCommentsSet.toList())
                                    }
                                }
                            },
                            enabled = selectedCommentsSet.isNotEmpty(),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.brand.orange,
                                disabledContainerColor = AppTheme.brand.orange.copy(alpha = 0.4f)
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.ModeComment, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (selectedCommentsSet.isEmpty()) "Select comments to place order"
                                       else "Place Comment Order (${selectedCommentsSet.size})",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    val rate = when (selectedTab) {
                        "Repost" -> settings.pricePerRepost.toLong()
                        "Save" -> settings.pricePerSavePost.toLong()
                        else -> settings.pricePerLike.toLong()
                    }
                    val options = listOf(100, 200, 400, 500, 1000, 2000, 5000)

                    LazyColumn(
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(options) { count ->
                            val cost = count * rate
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when (selectedTab) {
                                        "Repost" -> Icon(Icons.Filled.Repeat, contentDescription = null, tint = Color(0xFF8E24AA))
                                        "Save" -> Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color(0xFF8E24AA))
                                        else -> Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = Color(0xFFD81B60))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("$count", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                                    Spacer(Modifier.weight(1f))

                                    com.feedpilot.client.ui.components.CoinDot(size = 20)
                                    Spacer(Modifier.width(6.dp))
                                    Text("$cost", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                                    Spacer(Modifier.width(10.dp))
                                    Text(">>", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    Spacer(Modifier.width(10.dp))

                                    OutlinedButton(
                                        onClick = {
                                            if (availableCoins < cost) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Not enough coins. This order costs $cost, your balance is $availableCoins.",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                onConfirm(if (selectedTab == "Save") "SavePost" else selectedTab, count, null)
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.brand.orange),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.brand.orange)
                                    ) {
                                        Text("Get", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCount(count: Long): String {
    return java.text.NumberFormat.getInstance(java.util.Locale.US).format(count)
}
