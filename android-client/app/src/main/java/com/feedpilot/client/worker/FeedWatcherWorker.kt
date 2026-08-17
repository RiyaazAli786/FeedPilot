package com.feedpilot.client.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.feedpilot.client.data.local.WatchedHandleEntity
import com.feedpilot.client.data.remote.dto.SaveWatchedPostRequest
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.WatchedHandleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@HiltWorker
class FeedWatcherWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watchedHandles: WatchedHandleRepository,
    private val instagram: InstagramRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val handles = watchedHandles.refreshHandles()
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            handles
                .asSequence()
                .filter { it.watchEnabled }
                .filter { it.isDue(now) }
                .take(MAX_HANDLES_PER_RUN)
                .forEach { handle ->
                    val profile = instagram.getUserProfileDetails(handle.username) ?: return@forEach
                    val feed = instagram.getUserFeed(profile.id) ?: return@forEach
                    val posts = feed.items.map { item ->
                        SaveWatchedPostRequest(
                            postId = item.mediaId.ifBlank { item.id },
                            code = item.code,
                            caption = item.caption,
                            mediaUrl = item.displayUrl ?: item.videoUrl,
                            permalink = if (item.code.isBlank()) null else "https://www.instagram.com/p/${item.code}/",
                            mediaType = item.mediaType,
                            likeCount = item.likeCount,
                            commentCount = item.commentCount,
                            takenAt = item.takenAt.takeIf { it > 0L }
                                ?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toString() }
                        )
                    }
                    watchedHandles.saveFetchedFeed(
                        handleId = handle.id,
                        posts = posts,
                        profilePictureUrl = profile.profilePicUrl,
                        fullName = profile.fullName,
                        isPrivate = profile.isPrivate,
                        followerCount = profile.followerCount,
                        followingCount = profile.followingCount,
                        mediaCount = profile.mediaCount
                    )
                }
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private fun WatchedHandleEntity.isDue(now: OffsetDateTime): Boolean {
        val last = lastFetchedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?: return true
        return last.plusMinutes(pollIntervalMinutes.toLong()) <= now
    }

    private companion object {
        const val MAX_HANDLES_PER_RUN = 10
    }
}
