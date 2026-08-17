package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.WatchedHandleDao
import com.feedpilot.client.data.local.WatchedHandleEntity
import com.feedpilot.client.data.local.WatchedPostEntity
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.CreateWatchedHandleRequest
import com.feedpilot.client.data.remote.dto.SaveWatchedFeedRequest
import com.feedpilot.client.data.remote.dto.SaveWatchedPostRequest
import com.feedpilot.client.data.remote.dto.UpdateWatchedHandleRequest
import com.feedpilot.client.data.remote.dto.WatchedHandleDto
import com.feedpilot.client.data.remote.dto.WatchedPostDto
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchedHandleRepository @Inject constructor(
    private val api: ApiService,
    private val dao: WatchedHandleDao
) {
    fun observeHandles(): Flow<List<WatchedHandleEntity>> = dao.observeHandles()

    fun observePosts(handleId: String): Flow<List<WatchedPostEntity>> = dao.observePosts(handleId)

    suspend fun refreshHandles(): List<WatchedHandleEntity> {
        val remote = api.getWatchedHandles()
        val entities = remote.map { it.toEntity() }
        dao.upsertHandles(entities)
        return entities
    }

    suspend fun addHandle(username: String, pollIntervalMinutes: Int = 60): WatchedHandleEntity {
        val handle = api.createWatchedHandle(
            CreateWatchedHandleRequest(
                username = username,
                pollIntervalMinutes = pollIntervalMinutes,
                watchEnabled = true
            )
        )
        val entity = handle.toEntity()
        dao.upsertHandles(listOf(entity))
        return entity
    }

    suspend fun updateHandle(
        id: String,
        pollIntervalMinutes: Int? = null,
        watchEnabled: Boolean? = null
    ): WatchedHandleEntity {
        val handle = api.updateWatchedHandle(
            id,
            UpdateWatchedHandleRequest(
                pollIntervalMinutes = pollIntervalMinutes,
                watchEnabled = watchEnabled
            )
        )
        val entity = handle.toEntity()
        dao.upsertHandles(listOf(entity))
        return entity
    }

    suspend fun deleteHandle(id: String) {
        api.deleteWatchedHandle(id)
        dao.deletePostsForHandle(id)
        dao.deleteHandle(id)
    }

    suspend fun refreshPosts(handleId: String, limit: Int = 50): List<WatchedPostEntity> {
        val remote = api.getWatchedPosts(handleId, limit)
        val entities = remote.map { it.toEntity() }
        dao.upsertPosts(entities)
        return entities
    }

    suspend fun saveFetchedFeed(
        handleId: String,
        posts: List<SaveWatchedPostRequest>,
        profilePictureUrl: String? = null,
        fullName: String? = null,
        isPrivate: Boolean? = null,
        followerCount: Long? = null,
        followingCount: Long? = null,
        mediaCount: Long? = null
    ): List<WatchedPostEntity> {
        val remote = api.saveWatchedFeed(
            handleId,
            SaveWatchedFeedRequest(
                posts = posts,
                profilePictureUrl = profilePictureUrl,
                fullName = fullName,
                isPrivate = isPrivate,
                followerCount = followerCount,
                followingCount = followingCount,
                mediaCount = mediaCount
            )
        )
        val entities = remote.map { it.toEntity() }
        dao.upsertPosts(entities)
        refreshHandles()
        return entities
    }
}

private fun WatchedHandleDto.toEntity() = WatchedHandleEntity(
    id = id,
    username = username,
    profilePictureUrl = profilePictureUrl,
    fullName = fullName,
    isPrivate = isPrivate,
    followerCount = followerCount,
    followingCount = followingCount,
    mediaCount = mediaCount,
    watchEnabled = watchEnabled,
    pollIntervalMinutes = pollIntervalMinutes,
    lastFetchedAt = lastFetchedAt,
    createdAt = createdAt,
    savedPostCount = savedPostCount
)

private fun WatchedPostDto.toEntity() = WatchedPostEntity(
    id = id,
    watchedHandleId = watchedHandleId,
    postId = postId,
    code = code,
    caption = caption,
    mediaUrl = mediaUrl,
    permalink = permalink,
    mediaType = mediaType,
    likeCount = likeCount,
    commentCount = commentCount,
    takenAt = takenAt,
    fetchedAt = fetchedAt
)
