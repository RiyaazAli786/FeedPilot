package com.feedpilot.client.feature.handles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.data.local.WatchedHandleEntity
import com.feedpilot.client.data.local.WatchedPostEntity
import com.feedpilot.client.data.remote.InstagramComment
import com.feedpilot.client.data.remote.InstagramSearchUser
import com.feedpilot.client.data.remote.InstagramUserFeedItem
import com.feedpilot.client.data.remote.InstagramUserProfileDetails
import com.feedpilot.client.data.remote.dto.SaveWatchedPostRequest
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.WatchedHandleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class HandleUiState(
    val handles: List<WatchedHandleEntity> = emptyList(),
    val posts: List<WatchedPostEntity> = emptyList(),
    val selectedHandleId: String? = null,
    val detailHandleId: String? = null,
    val feedPosts: List<HandleFeedPost> = emptyList(),
    val feedLoading: Boolean = false,
    val feedLoadingMore: Boolean = false,
    val feedHasMore: Boolean = false,
    val savingPostId: String? = null,
    val postDetail: HandlePostDetailState? = null,
    val loading: Boolean = false,
    val refreshingFeed: Boolean = false,
    val searching: Boolean = false,
    val searchResults: List<InstagramSearchUser> = emptyList(),
    val rssLoadingHandleId: String? = null,
    val rssFeedTitle: String? = null,
    val rssFeedJson: String? = null,
    val message: String? = null,
    val error: String? = null
)

data class HandleFeedPost(
    val postId: String,
    val code: String?,
    val caption: String?,
    val mediaUrl: String?,
    val mediaType: Int,
    val likeCount: Long,
    val commentCount: Long,
    val takenAt: Long,
    val permalink: String?
)

data class HandlePostDetailState(
    val post: HandleFeedPost,
    val comments: List<InstagramComment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsLoadingMore: Boolean = false,
    val commentsHasMore: Boolean = false,
    val commentsCursor: String? = null,
    val commentsTotal: Long = 0,
    val postingComment: Boolean = false,
    val actionBusy: String? = null,
    val actionMessage: String? = null,
    val error: String? = null,
    val liked: Boolean = false,
    val saved: Boolean = false,
    val reposted: Boolean = false
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HandleViewModel @Inject constructor(
    private val watchedHandles: WatchedHandleRepository,
    private val instagram: InstagramRepository
) : ViewModel() {
    private val selectedHandleId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val feedBusy = MutableStateFlow(false)
    private val detailHandleId = MutableStateFlow<String?>(null)
    private val feedPosts = MutableStateFlow<List<HandleFeedPost>>(emptyList())
    private val feedLoadingMore = MutableStateFlow(false)
    private val feedHasMore = MutableStateFlow(false)
    private val savingPostId = MutableStateFlow<String?>(null)
    private val postDetail = MutableStateFlow<HandlePostDetailState?>(null)
    private var nextMaxId: String? = null
    private var nextFeedTarget: String? = null
    private val message = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val searching = MutableStateFlow(false)
    private val searchResults = MutableStateFlow<List<InstagramSearchUser>>(emptyList())
    private val rssLoadingHandleId = MutableStateFlow<String?>(null)
    private val rssFeedTitle = MutableStateFlow<String?>(null)
    private val rssFeedJson = MutableStateFlow<String?>(null)

    private val handles = watchedHandles.observeHandles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val posts = selectedHandleId.flatMapLatest { id ->
        if (id.isNullOrBlank()) flowOf(emptyList()) else watchedHandles.observePosts(id)
    }

    private data class StatusFlags(
        val loading: Boolean,
        val refreshingFeed: Boolean,
        val searching: Boolean,
        val searchResults: List<InstagramSearchUser>,
        val rssLoadingHandleId: String?,
        val rssFeedTitle: String?,
        val rssFeedJson: String?,
        val message: String?,
        val error: String?
    )

    private data class ExternalFeedState(
        val searching: Boolean,
        val searchResults: List<InstagramSearchUser>,
        val rssLoadingHandleId: String?,
        val rssFeedTitle: String?,
        val rssFeedJson: String?
    )

    private val externalFeedState = combine(searching, searchResults, rssLoadingHandleId, rssFeedTitle, rssFeedJson) {
            isSearching, results, loadingHandleId, feedTitle, feedJson ->
        ExternalFeedState(isSearching, results, loadingHandleId, feedTitle, feedJson)
    }

    private val flags = combine(busy, feedBusy, externalFeedState, message, error) { loading, refreshingFeed, external, message, error ->
        StatusFlags(
            loading = loading,
            refreshingFeed = refreshingFeed,
            searching = external.searching,
            searchResults = external.searchResults,
            rssLoadingHandleId = external.rssLoadingHandleId,
            rssFeedTitle = external.rssFeedTitle,
            rssFeedJson = external.rssFeedJson,
            message = message,
            error = error
        )
    }

    private data class FeedState(
        val detailHandleId: String?,
        val posts: List<HandleFeedPost>,
        val loadingMore: Boolean,
        val hasMore: Boolean,
        val savingPostId: String?,
        val postDetail: HandlePostDetailState?
    )

    private data class FeedStatusState(
        val loadingMore: Boolean,
        val hasMore: Boolean,
        val savingPostId: String?
    )

    private val feedStatus = combine(feedLoadingMore, feedHasMore, savingPostId) { loadingMore, hasMore, savingId ->
        FeedStatusState(loadingMore, hasMore, savingId)
    }

    private val feedState = combine(detailHandleId, feedPosts, feedStatus, postDetail) { id, posts, status, detail ->
        FeedState(id, posts, status.loadingMore, status.hasMore, status.savingPostId, detail)
    }

    val state: StateFlow<HandleUiState> = combine(handles, posts, selectedHandleId, flags, feedState) { handleList, postList, selected, flags, feed ->
        HandleUiState(
            handles = handleList,
            posts = postList,
            selectedHandleId = selected,
            detailHandleId = feed.detailHandleId,
            feedPosts = feed.posts,
            feedLoading = flags.refreshingFeed && feed.posts.isEmpty(),
            feedLoadingMore = feed.loadingMore,
            feedHasMore = feed.hasMore,
            savingPostId = feed.savingPostId,
            postDetail = feed.postDetail,
            loading = flags.loading,
            refreshingFeed = flags.refreshingFeed,
            searching = flags.searching,
            searchResults = flags.searchResults,
            rssLoadingHandleId = flags.rssLoadingHandleId,
            rssFeedTitle = flags.rssFeedTitle,
            rssFeedJson = flags.rssFeedJson,
            message = flags.message,
            error = flags.error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HandleUiState())

    init {
        refreshHandles()
        viewModelScope.launch {
            handles.collect { handleList ->
                if (selectedHandleId.value == null && handleList.isNotEmpty()) {
                    selectedHandleId.value = handleList.first().id
                }
            }
        }
    }

    fun selectHandle(id: String) {
        selectedHandleId.value = id
        refreshPosts(id)
    }

    fun openHandle(handle: WatchedHandleEntity) {
        selectedHandleId.value = handle.id
        detailHandleId.value = handle.id
        postDetail.value = null
        feedPosts.value = emptyList()
        nextMaxId = null
        feedHasMore.value = false
        refreshPosts(handle.id)
        fetchLatestFeed(handle)
    }

    fun closeHandle() {
        postDetail.value = null
        detailHandleId.value = null
        feedPosts.value = emptyList()
        nextMaxId = null
        feedHasMore.value = false
        feedLoadingMore.value = false
    }

    fun refreshHandles() {
        viewModelScope.launch {
            busy.value = true
            runCatching { watchedHandles.refreshHandles() }
                .onFailure { error.value = it.message ?: "Could not refresh handles." }
            busy.value = false
        }
    }

    fun addHandle(rawUsername: String, pollIntervalMinutes: Int) {
        val username = normalizeUsername(rawUsername)
        if (username.isBlank()) {
            error.value = "Enter an Instagram handle."
            return
        }
        viewModelScope.launch {
            busy.value = true
            runCatching {
                val handle = watchedHandles.addHandle(username, pollIntervalMinutes.coerceIn(15, 1440))
                selectedHandleId.value = handle.id
                watchedHandles.refreshPosts(handle.id)
                message.value = "@${handle.username} added."
            }.onFailure {
                error.value = it.message ?: "Could not add handle."
            }
            busy.value = false
        }
    }

    fun searchInstagramUsers(query: String) {
        val clean = normalizeUsername(query)
        if (clean.length < 2) {
            error.value = "Enter at least 2 characters to search."
            return
        }
        viewModelScope.launch {
            searching.value = true
            error.value = null
            runCatching { instagram.searchUsers(clean) }
                .onSuccess {
                    searchResults.value = it
                    if (it.isEmpty()) message.value = "No Instagram users found for \"$clean\"."
                }
                .onFailure { error.value = it.message ?: "Could not search Instagram." }
            searching.value = false
        }
    }

    fun clearSearchResults() {
        searchResults.value = emptyList()
    }

    fun fetchRssFeed(handle: WatchedHandleEntity) {
        if (rssLoadingHandleId.value != null) return
        viewModelScope.launch {
            rssLoadingHandleId.value = handle.id
            error.value = null
            runCatching {
                val profile = instagram.getUserProfileDetails(handle.username)
                    ?: error("Instagram profile could not be loaded for @${handle.username}.")
                val profileFeed = instagram.getUserFeed(profile.id)
                val feed = profileFeed?.takeIf { it.items.isNotEmpty() }
                    ?: instagram.getUserFeed(handle.username)
                    ?: error("Instagram feed could not be loaded for @${handle.username}.")
                if (feed.items.isEmpty()) {
                    error("Instagram returned no feed posts for @${handle.username}.")
                }
                buildFeedJson(profile, feed.items)
            }.onSuccess { formatted ->
                rssFeedTitle.value = "@${handle.username} latest feed JSON"
                rssFeedJson.value = formatted
            }.onFailure {
                error.value = it.message ?: "Could not load RSS feed JSON."
            }
            rssLoadingHandleId.value = null
        }
    }

    fun closeRssFeed() {
        rssFeedTitle.value = null
        rssFeedJson.value = null
    }

    fun addHandles(usernames: Collection<String>, pollIntervalMinutes: Int) {
        val clean = usernames.map(::normalizeUsername).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (clean.isEmpty()) {
            error.value = "Select at least one handle."
            return
        }
        viewModelScope.launch {
            busy.value = true
            var added = 0
            var failed = 0
            clean.forEach { username ->
                runCatching {
                    watchedHandles.addHandle(username, pollIntervalMinutes.coerceIn(15, 1440))
                    added++
                }.onFailure {
                    failed++
                }
            }
            runCatching { watchedHandles.refreshHandles() }
            message.value = if (failed == 0) {
                "Added $added handle${if (added == 1) "" else "s"}."
            } else {
                "Added $added handle${if (added == 1) "" else "s"}; $failed failed."
            }
            busy.value = false
        }
    }

    fun toggleWatch(handle: WatchedHandleEntity, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { watchedHandles.updateHandle(handle.id, watchEnabled = enabled) }
                .onFailure { error.value = it.message ?: "Could not update watcher." }
        }
    }

    fun deleteHandle(handle: WatchedHandleEntity) {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                watchedHandles.deleteHandle(handle.id)
                if (selectedHandleId.value == handle.id) selectedHandleId.value = null
                message.value = "@${handle.username} removed."
            }.onFailure {
                error.value = it.message ?: "Could not remove handle."
            }
            busy.value = false
        }
    }

    fun refreshPosts(handleId: String? = selectedHandleId.value) {
        val id = handleId ?: return
        viewModelScope.launch {
            runCatching { watchedHandles.refreshPosts(id) }
                .onFailure { error.value = it.message ?: "Could not load saved posts." }
        }
    }

    fun fetchLatestFeed(handle: WatchedHandleEntity?) {
        if (handle == null) return
        viewModelScope.launch {
            feedBusy.value = true
            error.value = null
            message.value = null
            runCatching {
                val profile = instagram.getUserProfileDetails(handle.username)
                    ?: error("Instagram profile could not be loaded. Make sure one account is logged in.")
                val profileFeed = instagram.getUserFeed(profile.id)
                val feed = profileFeed?.takeIf { it.items.isNotEmpty() }
                    ?: instagram.getUserFeed(handle.username)
                    ?: error("Instagram feed could not be loaded.")
                if (feed.items.isEmpty()) {
                    error("Instagram returned no feed posts for @${handle.username}. Check that the handle is public and the logged-in account can view it.")
                }
                nextMaxId = feed.maxId
                nextFeedTarget = if (profileFeed === feed) profile.id else handle.username
                feedHasMore.value = feed.hasMore && !feed.maxId.isNullOrBlank()
                feedPosts.value = feed.items.map { it.toFeedPost() }
                message.value = "Loaded ${feed.items.size} latest posts for @${handle.username}."
            }.onFailure {
                error.value = it.message ?: "Could not fetch latest feed."
            }
            feedBusy.value = false
        }
    }

    fun loadMoreFeed(handle: WatchedHandleEntity?) {
        if (handle == null || feedLoadingMore.value || !feedHasMore.value) return
        val cursor = nextMaxId ?: return
        viewModelScope.launch {
            feedLoadingMore.value = true
            runCatching {
                val feed = instagram.getUserFeed(nextFeedTarget ?: handle.username, cursor)
                    ?: error("More feed posts could not be loaded.")
                nextMaxId = feed.maxId
                feedHasMore.value = feed.hasMore && !feed.maxId.isNullOrBlank()
                val newPosts = feed.items.map { it.toFeedPost() }
                feedPosts.value = (feedPosts.value + newPosts).distinctBy { it.postId }
            }.onFailure {
                error.value = it.message ?: "Could not load more feed posts."
            }
            feedLoadingMore.value = false
        }
    }

    fun saveFeedPost(handle: WatchedHandleEntity, post: HandleFeedPost) {
        if (savingPostId.value != null) return
        viewModelScope.launch {
            savingPostId.value = post.postId
            error.value = null
            runCatching {
                watchedHandles.saveFetchedFeed(handle.id, listOf(post.toSaveRequest()))
            }.onSuccess {
                message.value = "Saved post ${post.code ?: post.postId}."
                refreshPosts(handle.id)
            }.onFailure {
                error.value = it.message ?: "Could not save post."
            }
            savingPostId.value = null
        }
    }

    fun openPost(post: HandleFeedPost) {
        val detail = HandlePostDetailState(post = post, commentsLoading = true)
        postDetail.value = detail
        loadComments(reset = true)
    }

    fun closePost() {
        postDetail.value = null
    }

    fun likePost() {
        runPostAction("Like") { post ->
            val res = instagram.like(post.actionTarget())
            if (res.ok) {
                val updated = post.copy(likeCount = post.likeCount + 1)
                updateDetailPost(updated) { it.copy(liked = true, actionMessage = "Liked") }
            } else {
                postDetail.value = postDetail.value?.copy(error = res.reason ?: "Could not like this post")
            }
        }
    }

    fun repostPost() {
        runPostAction("Repost") { post ->
            val res = instagram.repost(post.actionTarget())
            if (res.ok) {
                updateDetailPost(post) { it.copy(reposted = true, actionMessage = "Reposted") }
            } else {
                postDetail.value = postDetail.value?.copy(error = res.reason ?: "Could not repost this post")
            }
        }
    }

    fun savePost() {
        runPostAction("Save") { post ->
            val res = instagram.savePost(post.actionTarget())
            if (res.ok) {
                updateDetailPost(post) { it.copy(saved = true, actionMessage = "Saved") }
            } else {
                postDetail.value = postDetail.value?.copy(error = res.reason ?: "Could not save this post")
            }
        }
    }

    private fun runPostAction(name: String, block: suspend (HandleFeedPost) -> Unit) {
        val state = postDetail.value ?: return
        if (state.actionBusy != null) return
        postDetail.value = state.copy(actionBusy = name, error = null, actionMessage = null)
        viewModelScope.launch {
            runCatching { block(state.post) }
                .onFailure { postDetail.value = postDetail.value?.copy(error = it.message ?: "Could not complete $name") }
            postDetail.value = postDetail.value?.copy(actionBusy = null)
        }
    }

    fun loadComments(reset: Boolean = false) {
        val state = postDetail.value ?: return
        val cursor = if (reset) null else state.commentsCursor
        if (!reset && (!state.commentsHasMore || state.commentsLoadingMore || cursor.isNullOrBlank())) return
        val link = state.post.commentLink() ?: run {
            postDetail.value = state.copy(commentsLoading = false, error = "This post has no permalink for loading comments.")
            return
        }

        postDetail.value = state.copy(
            commentsLoading = reset,
            commentsLoadingMore = !reset,
            error = null
        )
        viewModelScope.launch {
            val page = instagram.getComments(link, maxId = cursor)
            postDetail.value = postDetail.value?.let { current ->
                if (page == null) {
                    current.copy(
                        commentsLoading = false,
                        commentsLoadingMore = false,
                        error = "Could not load comments."
                    )
                } else {
                    val merged = if (reset) page.comments else (current.comments + page.comments).distinctBy { it.id }
                    current.copy(
                        comments = merged,
                        commentsLoading = false,
                        commentsLoadingMore = false,
                        commentsCursor = page.endCursor,
                        commentsHasMore = page.hasMore && !page.endCursor.isNullOrBlank(),
                        commentsTotal = page.total,
                        error = if (page.requiresLogin) "Instagram needs a signed-in account to show comments." else current.error
                    )
                }
            }
        }
    }

    fun postComment(text: String) {
        val state = postDetail.value ?: return
        val clean = text.trim()
        if (clean.isBlank() || state.postingComment) return
        postDetail.value = state.copy(postingComment = true, error = null, actionMessage = null)
        viewModelScope.launch {
            val res = instagram.postComment(state.post.actionTarget(), clean)
            if (res.ok) {
                val updated = state.post.copy(commentCount = state.post.commentCount + 1)
                val localComment = InstagramComment(
                    id = "local-${Instant.now().toEpochMilli()}",
                    username = "You",
                    avatarUrl = null,
                    text = clean,
                    likeCount = 0,
                    createdAt = Instant.now().epochSecond
                )
                updateDetailPost(updated) {
                    it.copy(
                        comments = listOf(localComment) + it.comments,
                        commentsTotal = it.commentsTotal + 1,
                        postingComment = false,
                        actionMessage = "Comment posted"
                    )
                }
            } else {
                postDetail.value = postDetail.value?.copy(
                    postingComment = false,
                    error = res.reason ?: "Could not post the comment"
                )
            }
        }
    }

    private fun updateDetailPost(post: HandleFeedPost, change: (HandlePostDetailState) -> HandlePostDetailState) {
        feedPosts.value = feedPosts.value.map { if (it.postId == post.postId) post else it }
        postDetail.value = postDetail.value?.let { change(it.copy(post = post)) }
    }

    fun clearMessage() {
        message.value = null
        error.value = null
    }

    private fun normalizeUsername(raw: String): String =
        (InstagramCrypto.parseUsername(raw) ?: raw)
            .trim()
            .removePrefix("@")
            .substringBefore("?")
            .trim('/')
}

private fun prettyJson(raw: String): String = runCatching {
    val trimmed = raw.trim()
    when {
        trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
        trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
        else -> trimmed
    }
}.getOrElse { raw }

private fun buildFeedJson(
    profile: InstagramUserProfileDetails,
    posts: List<InstagramUserFeedItem>
): String {
    val username = profile.username.ifBlank { "instagram" }
    val homeUrl = "https://www.instagram.com/$username/"
    val obj = JSONObject()
        .put("version", "https://jsonfeed.org/version/1.1")
        .put("title", "${profile.fullName.ifBlank { username }} (@$username)")
        .put("home_page_url", homeUrl)
        .put("feed_url", homeUrl)
        .put("favicon", rewriteInstagramCdnHost(profile.profilePicUrl))
        .put("language", "en")
        .put(
            "description",
            "${formatFeedCount(profile.followerCount)} Followers" +
                profile.biography.trim().takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        )

    val items = JSONArray()
    posts.forEach { post ->
        val url = if (post.code.isBlank()) homeUrl else "https://www.instagram.com/p/${post.code}/"
        val content = post.caption?.takeIf { it.isNotBlank() }
            ?: when (post.mediaType) {
                2 -> "Instagram video by @$username"
                8 -> "Instagram carousel by @$username"
                else -> "Instagram post by @$username"
            }
        val item = JSONObject()
            .put("id", post.mediaId.ifBlank { post.id.ifBlank { post.code } })
            .put("url", url)
            .put("title", if (post.code.isBlank()) "Post" else "Post ${post.code}")
            .put("content_text", content)
            .put("content_html", "<div><img src=\"${post.displayUrl ?: post.videoUrl.orEmpty()}\" /></div>")
            .put("image", post.displayUrl ?: post.videoUrl.orEmpty())
            .put(
                "date_published",
                post.takenAt.takeIf { it > 0L }
                    ?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toString() }
                    ?: Instant.now().atOffset(ZoneOffset.UTC).toString()
            )
            .put("authors", JSONArray().put(JSONObject().put("name", username)))

        val mediaUrl = post.displayUrl ?: post.videoUrl
        if (!mediaUrl.isNullOrBlank()) {
            item.put("attachments", JSONArray().put(JSONObject().put("url", mediaUrl)))
        }
        items.put(item)
    }
    obj.put("items", items)
    return obj.toString(2)
}

private fun rewriteInstagramCdnHost(url: String?): String =
    url.orEmpty().replace(
        "https://instagram.frpr5-1.fna.fbcdn.net",
        "https://scontent-mia5-1.cdninstagram.com"
    )

private fun formatFeedCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1f", value / 1_000_000.0).removeSuffix(".0") + "M"
    value >= 1_000 -> String.format(Locale.US, "%.1f", value / 1_000.0).removeSuffix(".0") + "K"
    else -> value.toString()
}

private fun HandleFeedPost.actionTarget(): String =
    postId.ifBlank { permalink ?: code?.let { "https://www.instagram.com/p/$it/" } ?: "" }

private fun HandleFeedPost.commentLink(): String? =
    permalink ?: code?.let { "https://www.instagram.com/p/$it/" }

private fun InstagramUserFeedItem.toSaveRequest() = SaveWatchedPostRequest(
    postId = mediaId.ifBlank { id },
    code = code,
    caption = caption,
    mediaUrl = displayUrl ?: videoUrl,
    permalink = if (code.isBlank()) null else "https://www.instagram.com/p/$code/",
    mediaType = mediaType,
    likeCount = likeCount,
    commentCount = commentCount,
    takenAt = takenAt.takeIf { it > 0L }
        ?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toString() }
)

private fun HandleFeedPost.toSaveRequest() = SaveWatchedPostRequest(
    postId = postId,
    code = code,
    caption = caption,
    mediaUrl = mediaUrl,
    permalink = permalink,
    mediaType = mediaType,
    likeCount = likeCount,
    commentCount = commentCount,
    takenAt = takenAt.takeIf { it > 0L }
        ?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toString() }
)

private fun InstagramUserFeedItem.toFeedPost() = HandleFeedPost(
    postId = mediaId.ifBlank { id },
    code = code.ifBlank { null },
    caption = caption,
    mediaUrl = displayUrl ?: videoUrl,
    mediaType = mediaType,
    likeCount = likeCount,
    commentCount = commentCount,
    takenAt = takenAt,
    permalink = if (code.isBlank()) null else "https://www.instagram.com/p/$code/"
)
