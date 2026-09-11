package com.feedpilot.client.bridge

import android.util.Log
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.BridgeFeedItemDto
import com.feedpilot.client.data.remote.dto.BridgeHighlightDto
import com.feedpilot.client.data.remote.dto.BridgeFeedProfileDto
import com.feedpilot.client.data.remote.dto.BridgeFeedResultDataDto
import com.feedpilot.client.data.remote.dto.BridgeHeartbeatAccountDto
import com.feedpilot.client.data.remote.dto.BridgeHeartbeatRequest
import com.feedpilot.client.data.remote.dto.BridgeJobDto
import com.feedpilot.client.data.remote.dto.BridgeStoryItemDto
import com.feedpilot.client.data.remote.dto.CompleteBridgeJobRequest
import com.feedpilot.client.data.remote.dto.FailBridgeJobRequest
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.TargetRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedBridgeRunner @Inject constructor(
    private val api: ApiService,
    private val accounts: AccountDao,
    private val instagram: InstagramRepository,
    private val targets: TargetRepository,
    private val identity: DeviceIdentity
) {
    suspend fun heartbeat(): Boolean {
        val localAccounts = accounts.getAll().filter { it.sessionCookies.isNotBlank() }
        val active = localAccounts.firstOrNull()
        val body = BridgeHeartbeatRequest(
            deviceId = identity.stableAppInstallationId,
            installationId = identity.accountBindingInstallationId,
            appInstanceId = identity.appInstanceId,
            deviceModel = identity.deviceLabel,
            androidVersion = identity.androidVersion,
            activeAccountUsername = active?.username,
            loggedInAccounts = localAccounts.map { it.username },
            accounts = localAccounts.map {
                BridgeHeartbeatAccountDto(
                    accountId = it.id,
                    username = it.username,
                    status = if (it.isLoggedIn || it.status.equals("Active", ignoreCase = true)) "Active" else it.status,
                    isLoggedIn = it.sessionCookies.isNotBlank()
                )
            }
        )
        return runCatching { api.sendBridgeHeartbeat(body).accepted }
            .onSuccess { accepted ->
                if (accepted) BridgeStatusStore.online()
            }
            .onFailure { Log.w(TAG, "Bridge heartbeat failed", it) }
            .getOrDefault(false)
    }

    suspend fun runOneJob(): Boolean {
        heartbeat()
        val job = runCatching {
            val response = api.pollBridgeJob(
                deviceId = identity.stableAppInstallationId,
                installationId = identity.accountBindingInstallationId
            )
            if (response.isSuccessful) response.body() else {
                Log.w(TAG, "Bridge poll failed HTTP ${response.code()}: ${response.errorBody()?.string().orEmpty().take(300)}")
                null
            }
        }.onFailure { Log.w(TAG, "Bridge poll failed", it) }.getOrNull() ?: return false

        execute(job)
        return true
    }

    private suspend fun execute(job: BridgeJobDto) {
        BridgeStatusStore.resolving(job.targetUsername ?: job.targetUserId)
        val account = resolveAccount(job)
        if (account == null) {
            fail(job, "NO_LOCAL_SESSION", "Assigned Instagram account is not available on this device.")
            BridgeStatusStore.online()
            return
        }

        val target = job.targetUsername ?: job.targetUserId
        if (target.isNullOrBlank()) {
            fail(job, "INVALID_TARGET", "Bridge job did not include a username or user id.")
            BridgeStatusStore.online()
            return
        }

        try {
            val feedTarget = job.targetUsername?.takeIf { it.isNotBlank() } ?: job.targetUserId ?: target
            val feed = when (val result = targets.fetchUserFeed(feedTarget, job.maxId, account.sessionCookies)) {
                is Resource.Success -> result.data
                is Resource.Error -> {
                    fail(job, classify(result.cause ?: IllegalStateException(result.message)), result.message)
                    BridgeStatusStore.online()
                    return
                }
                Resource.Loading -> {
                    fail(job, "IG_EMPTY_FEED", "Instagram returned no feed data for $feedTarget.")
                    BridgeStatusStore.online()
                    return
                }
            }
            if (feed.items.isEmpty()) {
                val profileLabel = feed.profile?.username?.let { "@$it" } ?: feedTarget
                fail(job, "IG_EMPTY_FEED", "Instagram feed API returned zero posts for $profileLabel.")
                BridgeStatusStore.online()
                return
            }
            val profile = feed.profile
            val feedUserId = job.targetUserId ?: profile?.id ?: feedTarget

            val canFetchStorySurfaces = feedUserId.all(Char::isDigit)
            val stories = if (job.includeStories && canFetchStorySurfaces) {
                instagram.getUserStoryItems(feedUserId, account.sessionCookies).map {
                    BridgeStoryItemDto(
                        id = it.id,
                        code = it.code,
                        mediaType = it.mediaType,
                        displayUrl = it.displayUrl,
                        videoUrl = it.videoUrl,
                        takenAt = it.takenAt,
                        expiringAt = it.expiringAt,
                        mediaId = it.mediaId
                    )
                }
            } else {
                emptyList()
            }

            val highlights = if (job.includeStories && canFetchStorySurfaces) {
                instagram.getUserHighlights(
                    userId = feedUserId,
                    includeDetails = true,
                    detailLimit = 10,
                    customCookies = account.sessionCookies
                ).map { highlight ->
                    BridgeHighlightDto(
                        id = highlight.id,
                        title = highlight.title,
                        coverUrl = highlight.coverUrl,
                        items = highlight.items.map {
                            BridgeStoryItemDto(
                                id = it.id,
                                code = it.code,
                                mediaType = it.mediaType,
                                displayUrl = it.displayUrl,
                                videoUrl = it.videoUrl,
                                takenAt = it.takenAt,
                                expiringAt = it.expiringAt,
                                mediaId = it.mediaId
                            )
                        }
                    )
                }
            } else {
                emptyList()
            }

            val data = BridgeFeedResultDataDto(
                profile = profile?.let {
                    BridgeFeedProfileDto(
                        id = it.id,
                        username = it.username,
                        fullName = it.fullName,
                        biography = it.biography,
                        profilePicUrl = it.profilePicUrl,
                        followerCount = it.followerCount,
                        followingCount = it.followingCount,
                        mediaCount = it.mediaCount,
                        isPrivate = it.isPrivate,
                        isVerified = it.isVerified
                    )
                },
                items = feed.items.take(job.limit).map {
                    BridgeFeedItemDto(
                        id = it.id,
                        code = it.code,
                        caption = it.caption,
                        mediaType = it.mediaType,
                        displayUrl = it.displayUrl,
                        videoUrl = it.videoUrl,
                        likeCount = it.likeCount,
                        commentCount = it.commentCount,
                        repostCount = it.repostCount,
                        takenAt = it.takenAt,
                        mediaId = it.mediaId
                    )
                },
                maxId = feed.maxId,
                hasMore = feed.hasMore,
                stories = stories,
                highlights = highlights
            )
            api.completeBridgeJob(job.id, CompleteBridgeJobRequest(data))
            BridgeStatusStore.online("Completed bridge feed request")
        } catch (e: Exception) {
            Log.w(TAG, "Bridge job ${job.id} failed", e)
            fail(job, classify(e), e.message ?: "Instagram feed execution failed.")
            BridgeStatusStore.online()
        }
    }

    private suspend fun resolveAccount(job: BridgeJobDto): AccountEntity? {
        job.assignedAccountId?.let { id ->
            accounts.getById(id)?.takeIf { it.sessionCookies.isNotBlank() }?.let { return it }
        }
        job.accountUsername?.let { username ->
            accounts.getAll().firstOrNull {
                it.sessionCookies.isNotBlank() && it.username.equals(username, ignoreCase = true)
            }?.let { return it }
        }
        return accounts.getAll().firstOrNull { it.sessionCookies.isNotBlank() }
    }

    private suspend fun fail(job: BridgeJobDto, code: String, message: String) {
        runCatching {
            api.failBridgeJob(job.id, FailBridgeJobRequest(code = code, message = message))
        }.onFailure {
            Log.w(TAG, "Could not report bridge job failure", it)
        }
    }

    private fun classify(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "login_required" in message || ("session" in message && "expired" in message) -> "IG_SESSION_EXPIRED"
            "checkpoint" in message || "challenge" in message -> "IG_CHECKPOINT_REQUIRED"
            "captcha" in message -> "IG_CAPTCHA_REQUIRED"
            "rate" in message || "429" in message -> "IG_RATE_LIMITED"
            "not found" in message || "404" in message -> "IG_TARGET_NOT_FOUND"
            "private" in message -> "IG_PRIVATE_PROFILE"
            else -> "IG_UNKNOWN_ERROR"
        }
    }

    private companion object {
        const val TAG = "FeedBridgeRunner"
    }
}
