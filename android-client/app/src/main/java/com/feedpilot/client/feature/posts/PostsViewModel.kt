package com.feedpilot.client.feature.posts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.repository.AppOrderRepository
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.OrderHistoryRepository
import com.feedpilot.client.data.remote.dto.TargetProfileDto
import com.feedpilot.client.data.repository.TargetRepository
import com.feedpilot.client.data.repository.WalletRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TargetProfile(
    val username: String,
    val fullName: String?,
    val avatarUrl: String?,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val isPrivate: Boolean
)

data class PostItem(
    val id: String,
    val imageUrl: String,
    val videoUrl: String? = null,
    val isVideo: Boolean = false,
    val likes: Long = 0,
    val comments: Long = 0,
    val reposts: Long = 0,
    val link: String = "",
    val caption: String? = null,
    /** Numeric media id used by the like/repost mutations. */
    val mediaId: String = "",
    /** Already liked — the Like action is hidden/disabled so it can't be repeated. */
    val hasLiked: Boolean = false,
    /** Already reposted in this session — same idea as [hasLiked]. */
    val hasReposted: Boolean = false,
    /** Instagram allows the viewer to reshare this media. */
    val canReshare: Boolean = true,
    /** Commenting is switched off for this post, so the comment box must not open. */
    val commentsDisabled: Boolean = false,
    /** An action (like/repost) is in flight for this post. */
    val busy: Boolean = false
)

/** Comment sheet state for one post. */
data class CommentSheetState(
    val post: PostItem,
    val comments: List<com.feedpilot.client.data.remote.InstagramComment> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Cursor for the next page; null when there is nothing more to load. */
    val endCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** Total comment count reported by Instagram. */
    val total: Long = 0,
    /** A comment of ours is being posted. */
    val posting: Boolean = false
)

data class PostsUiState(
    val coins: Long = 0,
    val targetUsername: String? = null,
    val profile: TargetProfile? = null,
    val posts: List<PostItem> = emptyList(),
    val loading: Boolean = false,
    val hasMorePosts: Boolean = false,
    val loadingMore: Boolean = false,
    val message: String? = null,
    val settings: AppSettings = AppSettings()
)

@HiltViewModel
class PostsViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val targetRepository: TargetRepository,
    private val authRepository: AuthRepository,
    private val postFetcherTestService: com.feedpilot.client.service.PostFetcherTestService,
    private val orderHistoryRepository: OrderHistoryRepository,
    private val appOrderRepository: AppOrderRepository,
    private val instagramRepository: com.feedpilot.client.data.repository.InstagramRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private data class Content(
        val targetUsername: String? = null,
        val profile: TargetProfile? = null,
        val posts: List<PostItem> = emptyList(),
        val loading: Boolean = false,
        val nextMaxId: String? = null,
        val hasMorePosts: Boolean = false,
        val loadingMore: Boolean = false,
        val message: String? = null
    )

    private val content = MutableStateFlow(Content())

    val state: StateFlow<PostsUiState> =
        combine(walletRepository.spendableWallet, settingsRepository.settings, content) { wallet, settings, c ->
            PostsUiState(
                coins = wallet?.totalCoins ?: 0,
                targetUsername = c.targetUsername,
                profile = c.profile,
                posts = c.posts,
                loading = c.loading,
                hasMorePosts = c.hasMorePosts,
                loadingMore = c.loadingMore,
                message = c.message,
                settings = settings
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PostsUiState())

    init { viewModelScope.launch { walletRepository.refresh() } }

    /** Fetches the target profile + media for the entered handle or post link. */
    fun setTarget(input: String) {
        val clean = input.trim()
        if (clean.isBlank()) {
            content.update { it.copy(message = "Enter a valid handle or post link") }
            return
        }

        if (clean.contains("/p/") || clean.contains("/reel/") || clean.contains("/tv/") || clean.contains("instagram.com/p/")) {
            submitLink(clean)
            return
        }

        val username = com.feedpilot.client.common.InstagramCrypto.parseUsername(clean) ?: clean.removePrefix("@")
        content.update { it.copy(targetUsername = username, profile = null, posts = emptyList(), loading = true, nextMaxId = null, hasMorePosts = false, loadingMore = false, message = null) }
        viewModelScope.launch {
            when (val r = targetRepository.fetchProfile(username)) {
                is Resource.Success -> {
                    val profileDto = r.data
                    val feedRes = targetRepository.fetchUserFeed(profileDto.username)
                    var nextMaxId: String? = null
                    var hasMore = false
                    val postsList: List<PostItem> = if (feedRes is Resource.Success && feedRes.data.items.isNotEmpty()) {
                        nextMaxId = feedRes.data.maxId
                        hasMore = feedRes.data.hasMore && !nextMaxId.isNullOrBlank()
                        feedRes.data.items.map { it.toPostItem() }
                    } else {
                        profileDto.toPosts()
                    }
                    content.update {
                        val baseProfile = profileDto.toProfile()
                        val updatedProfile = baseProfile.copy(posts = maxOf(baseProfile.posts, postsList.size.toLong()))
                        it.copy(
                            loading = false,
                            profile = updatedProfile,
                            posts = postsList,
                            nextMaxId = nextMaxId,
                            hasMorePosts = hasMore,
                            loadingMore = false
                        )
                    }
                }
                is Resource.Error -> content.update { it.copy(loading = false, profile = null, posts = emptyList(), hasMorePosts = false, message = r.message) }
                Resource.Loading -> Unit
            }
        }
    }

    /** Fetches next page of posts for current target handle. */
    fun loadMorePosts() {
        val curr = content.value
        val username = curr.targetUsername ?: return
        val cursor = curr.nextMaxId ?: return
        if (!curr.hasMorePosts || curr.loadingMore) return

        content.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val feedRes = targetRepository.fetchUserFeed(username, maxId = cursor)) {
                is Resource.Success -> {
                    val feedData = feedRes.data
                    val newItems = feedData.items.map { it.toPostItem() }
                    content.update { c ->
                        val combined = (c.posts + newItems).distinctBy { it.id }
                        val updatedProfile = c.profile?.let { p ->
                            p.copy(posts = maxOf(p.posts, combined.size.toLong()))
                        }
                        c.copy(
                            profile = updatedProfile,
                            posts = combined,
                            nextMaxId = feedData.maxId,
                            hasMorePosts = feedData.hasMore && !feedData.maxId.isNullOrBlank(),
                            loadingMore = false
                        )
                    }
                }
                is Resource.Error -> {
                    content.update { it.copy(loadingMore = false, message = feedRes.message) }
                }
                Resource.Loading -> Unit
            }
        }
    }

    /** Fetches post details by link, clearing user details to show only post details. */
    fun submitLink(link: String) {
        val clean = link.trim()
        if (clean.isBlank()) {
            content.update { it.copy(message = "Enter a valid post link") }
            return
        }
        content.update { it.copy(loading = true, nextMaxId = null, hasMorePosts = false, loadingMore = false, message = null) }
        viewModelScope.launch {
            postFetcherTestService.testFetchPost(clean)
            when (val r = targetRepository.fetchPost(clean)) {
                is Resource.Success -> {
                    val postDto = r.data
                    val item = PostItem(
                        id = postDto.id,
                        imageUrl = postDto.imageUrl,
                        videoUrl = postDto.videoUrl ?: postDto.link,
                        isVideo = postDto.isVideo,
                        likes = postDto.likes,
                        comments = postDto.comments,
                        reposts = postDto.reposts,
                        link = postDto.link ?: "",
                        caption = postDto.caption
                    )
                    content.update {
                        it.copy(
                            loading = false,
                            targetUsername = null,
                            profile = null,
                            posts = listOf(item),
                            nextMaxId = null,
                            hasMorePosts = false,
                            loadingMore = false,
                            message = "Post details loaded"
                        )
                    }
                }
                is Resource.Error -> content.update { it.copy(loading = false, message = r.message) }
                Resource.Loading -> Unit
            }
        }
    }

    /** Places a live Likes SMM order using the active provider's configured like service. */
    fun orderLikes(post: PostItem, likesCount: Int = 100, costInCoins: Long = 200L) {
        val targetUrl = if (!post.link.isNullOrBlank()) post.link else "https://www.instagram.com/p/${post.id}/"
        val displayTarget = state.value.targetUsername ?: "post_${post.id}"
        content.update { it.copy(message = "Submitting Likes order ($likesCount)...") }

        viewModelScope.launch {
            val result = appOrderRepository.placeOrder(
                orderType = "Like",
                targetUrl = targetUrl,
                targetUsername = displayTarget,
                quantity = likesCount,
                startCount = post.likes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            )

            val order = (result as? com.feedpilot.client.common.Resource.Success)?.data?.order
            val errMsg = (result as? com.feedpilot.client.common.Resource.Error)?.message

            orderHistoryRepository.logOrder(
                com.feedpilot.client.data.local.OrderHistoryEntity(
                    smmOrderId = order?.id,
                    providerNickname = "FeedPilot Backend",
                    providerUrl = "api/orders",
                    targetUsername = displayTarget,
                    orderType = "Likes",
                    quantity = likesCount,
                    coinsSpent = order?.coinsSpent ?: 0L,
                    status = order?.status ?: "FAILED",
                    timestamp = System.currentTimeMillis(),
                    errorMessage = errMsg
                )
            )

            if (order != null) {
                walletRepository.refresh(forceServer = true)
            }

            content.update {
                it.copy(
                    message = if (order != null) {
                        "Order placed — $likesCount likes (${order.coinsSpent} coins). Status: ${order.status}."
                    } else {
                        errMsg ?: "Could not place the order"
                    }
                )
            }
        }
    }

    fun resolveReelVideoUrl(post: PostItem, onResult: (String?) -> Unit) {
        if (!post.videoUrl.isNullOrBlank() && isPlayableVideoUrl(post.videoUrl)) {
            onResult(post.videoUrl)
            return
        }
        viewModelScope.launch {
            try {
                when (val r = targetRepository.fetchPost(post.link)) {
                    is Resource.Success -> {
                        val resolved = r.data.videoUrl
                        onResult(resolved)
                    }
                    else -> onResult(null)
                }
            } catch (_: Exception) {
                onResult(null)
            }
        }
    }

    /**
     * Maps a feed item to a card. The video url comes straight from the feed — resolving it
     * later via media-info returns nothing for anonymous callers, which left reels unplayable.
     */
    private fun com.feedpilot.client.data.remote.InstagramUserFeedItem.toPostItem(): PostItem {
        val postLink = "https://www.instagram.com/p/$code/"
        return PostItem(
            id = id,
            imageUrl = displayUrl ?: "",
            videoUrl = videoUrl,
            isVideo = mediaType == 2,
            likes = likeCount,
            comments = commentCount,
            reposts = repostCount,
            link = postLink,
            caption = caption,
            mediaId = mediaId,
            hasLiked = hasLiked,
            canReshare = canReshare,
            commentsDisabled = commentsDisabled
        )
    }

    private fun updatePost(id: String, change: (PostItem) -> PostItem) {
        content.update { c -> c.copy(posts = c.posts.map { if (it.id == id) change(it) else it }) }
    }

    /** Likes a post. Never runs for one already liked — the UI hides the action. */
    fun likePost(post: PostItem) {
        if (post.hasLiked || post.busy) return
        updatePost(post.id) { it.copy(busy = true) }
        viewModelScope.launch {
            val target = post.mediaId.ifBlank { post.link }
            val res = instagramRepository.like(target)
            if (res.ok) {
                updatePost(post.id) { it.copy(busy = false, hasLiked = true, likes = it.likes + 1) }
                content.update { it.copy(message = "Liked") }
            } else {
                updatePost(post.id) { it.copy(busy = false) }
                content.update { it.copy(message = res.reason ?: "Could not like this post") }
            }
        }
    }

    /** Reposts a post. Never runs for one already reposted, or one Instagram won't reshare. */
    fun repostPost(post: PostItem) {
        if (post.hasReposted || post.busy || !post.canReshare) return
        updatePost(post.id) { it.copy(busy = true) }
        viewModelScope.launch {
            val target = post.mediaId.ifBlank { post.link }
            val res = instagramRepository.repost(target)
            if (res.ok) {
                updatePost(post.id) { it.copy(busy = false, hasReposted = true) }
                content.update { it.copy(message = "Reposted") }
            } else {
                updatePost(post.id) { it.copy(busy = false) }
                content.update { it.copy(message = res.reason ?: "Could not repost this post") }
            }
        }
    }

    // ----- Comments -----
    private val _commentSheet = MutableStateFlow<CommentSheetState?>(null)
    val commentSheet: StateFlow<CommentSheetState?> = _commentSheet.asStateFlow()

    /** Opens the comment sheet — only for posts that actually allow comments. */
    fun openComments(post: PostItem) {
        if (post.commentsDisabled) {
            content.update { it.copy(message = "Comments are turned off for this post") }
            return
        }
        _commentSheet.value = CommentSheetState(post = post, loading = true)
        viewModelScope.launch {
            val page = instagramRepository.getComments(post.link)
            _commentSheet.update { s ->
                s?.copy(
                    loading = false,
                    comments = page?.comments.orEmpty(),
                    endCursor = page?.endCursor,
                    hasMore = page?.hasMore == true && !page.endCursor.isNullOrBlank(),
                    total = page?.total ?: 0,
                    error = when {
                        page == null -> "Couldn't load comments"
                        page.requiresLogin ->
                            "Instagram needs a signed-in account to show comments. Add one with its session in Add Account."
                        else -> null
                    }
                )
            }
        }
    }

    /** Loads the next page of comments, appending to what is already shown. */
    fun loadMoreComments() {
        val sheet = _commentSheet.value ?: return
        val cursor = sheet.endCursor
        if (!sheet.hasMore || sheet.loadingMore || cursor.isNullOrBlank()) return

        _commentSheet.update { it?.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = instagramRepository.getComments(sheet.post.link, maxId = cursor)
            _commentSheet.update { s ->
                if (s == null) return@update null
                if (page == null) {
                    s.copy(loadingMore = false, hasMore = false)
                } else {
                    // Dedupe by id: Instagram can repeat an edge across page boundaries.
                    val merged = (s.comments + page.comments).distinctBy { it.id }
                    s.copy(
                        loadingMore = false,
                        comments = merged,
                        endCursor = page.endCursor,
                        hasMore = page.hasMore && !page.endCursor.isNullOrBlank()
                    )
                }
            }
        }
    }

    /** Posts our own comment on the open post, then shows it at the top of the list. */
    fun postComment(text: String) {
        val sheet = _commentSheet.value ?: return
        if (text.isBlank() || sheet.posting) return

        _commentSheet.update { it?.copy(posting = true) }
        viewModelScope.launch {
            val target = sheet.post.mediaId.ifBlank { sheet.post.link }
            val res = instagramRepository.postComment(target, text.trim())
            if (res.ok) {
                // Re-read the first page so our comment appears with its real id and author.
                val page = instagramRepository.getComments(sheet.post.link)
                _commentSheet.update { s ->
                    s?.copy(
                        posting = false,
                        comments = page?.comments ?: s.comments,
                        endCursor = page?.endCursor ?: s.endCursor,
                        hasMore = page?.hasMore == true && !page.endCursor.isNullOrBlank(),
                        total = page?.total ?: (s.total + 1)
                    )
                }
                updatePost(sheet.post.id) { it.copy(comments = it.comments + 1) }
                content.update { it.copy(message = "Comment posted") }
            } else {
                _commentSheet.update { it?.copy(posting = false) }
                content.update { it.copy(message = res.reason ?: "Could not post the comment") }
            }
        }
    }

    fun orderPostActivity(post: PostItem, orderType: String, quantity: Int, comments: List<String>? = null) {
        if (quantity <= 0) return
        viewModelScope.launch {
            val targetUrl = post.link.ifBlank { "https://www.instagram.com/p/${post.id}/" }
            val username = content.value.profile?.username
            val res = appOrderRepository.placeOrder(
                orderType = orderType,
                targetUrl = targetUrl,
                targetUsername = username,
                quantity = quantity,
                startCount = when (orderType) {
                    "Like" -> post.likes.toInt()
                    "Comment" -> post.comments.toInt()
                    "Repost", "Reshare" -> post.reposts.toInt()
                    else -> 0
                },
                comments = comments
            )
            when (res) {
                is Resource.Success -> {
                    walletRepository.refresh(forceServer = true)
                    content.update { it.copy(message = "Order for $quantity $orderType(s) placed successfully!") }
                }
                is Resource.Error -> {
                    content.update { it.copy(message = res.message ?: "Failed to place order") }
                }
                else -> {}
            }
        }
    }

    fun closeComments() { _commentSheet.value = null }

    fun consumeMessage() { content.update { it.copy(message = null) } }

    private fun TargetProfileDto.toProfile() =
        TargetProfile(username, fullName, avatarUrl, followers, following, posts, isPrivate)

    private fun TargetProfileDto.toPosts() =
        media.map { PostItem(it.id, it.imageUrl, likes = it.likes, comments = it.comments, reposts = it.reposts, link = it.link ?: "") }
}

/**
 * True when a url can be handed straight to the player. Instagram serves reel media from
 * `*.fbcdn.net` as well as `cdninstagram.com`, so matching only the latter rejected perfectly
 * good feed urls and left reels stuck on the poster frame.
 */
internal fun isPlayableVideoUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    if (!url.startsWith("http", ignoreCase = true)) return false
    return url.contains(".mp4", ignoreCase = true) ||
        url.contains("cdninstagram", ignoreCase = true) ||
        url.contains("fbcdn.net", ignoreCase = true)
}
