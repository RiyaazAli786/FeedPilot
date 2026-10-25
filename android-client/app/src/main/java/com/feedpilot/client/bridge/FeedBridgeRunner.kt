package com.feedpilot.client.bridge

import android.util.Log
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.BridgeHighlightDto
import com.feedpilot.client.data.remote.dto.BridgeHeartbeatAccountDto
import com.feedpilot.client.data.remote.dto.BridgeHeartbeatRequest
import com.feedpilot.client.data.remote.dto.BridgeJobDto
import com.feedpilot.client.data.remote.dto.BridgeStoryItemDto
import com.feedpilot.client.data.remote.dto.CompleteBridgeJobRequest
import com.feedpilot.client.data.remote.dto.FailBridgeJobRequest
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.TargetRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class FeedBridgeRunner @Inject constructor(
    private val api: ApiService,
    private val accounts: AccountDao,
    private val instagram: InstagramRepository,
    private val targets: TargetRepository,
    private val identity: DeviceIdentity,
    private val settings: SettingsRepository,
    private val notifications: BridgeJobNotifier,
    private val authRepository: dagger.Lazy<AuthRepository>
) {
    private val executionLock = Mutex()
    private val heartbeatLock = Mutex()
    @Volatile private var lastHeartbeatTimeMs: Long = 0L

    suspend fun isBridgeEnabled(): Boolean = settings.settings.first().bridgeEnabled

    suspend fun heartbeat(): Boolean = heartbeatLock.withLock {
        val enabled = isBridgeEnabled()
        if (!authRepository.get().isLoggedIn.value) {
            runCatching {
                authRepository.get().ensureDeviceSession(com.feedpilot.client.BuildConfig.VERSION_NAME)
            }
        }

        val allAccounts = accounts.getAll()
        val localAccounts = allAccounts.filter {
            it.sessionCookies.isNotBlank() || (it.lastLogin?.contains("sessionid") == true)
        }
        val active = localAccounts.firstOrNull { it.isLoggedIn } ?: localAccounts.firstOrNull()
        val body = BridgeHeartbeatRequest(
            deviceId = identity.stableAppInstallationId,
            installationId = identity.accountBindingInstallationId,
            appInstanceId = identity.appInstanceId,
            deviceModel = identity.deviceLabel,
            androidVersion = identity.androidVersion,
            bridgeEnabled = enabled && localAccounts.isNotEmpty(),
            activeAccountUsername = active?.username,
            loggedInAccounts = localAccounts.map { it.username },
            accounts = localAccounts.map {
                val hasCookies = it.sessionCookies.isNotBlank() || (it.lastLogin?.contains("sessionid") == true)
                val isExpired = it.status.equals("SessionExpired", ignoreCase = true) ||
                    it.status.equals("Suspended", ignoreCase = true)
                val status = if (hasCookies && !isExpired) "Active" else it.status
                BridgeHeartbeatAccountDto(
                    accountId = it.id,
                    username = it.username,
                    status = status,
                    isLoggedIn = hasCookies && !isExpired
                )
            }
        )
        runCatching { api.sendBridgeHeartbeat(body).accepted }
            .onSuccess { accepted ->
                if (accepted) {
                    lastHeartbeatTimeMs = System.currentTimeMillis()
                }
                if (accepted && isBridgeEnabled()) {
                    if (localAccounts.isNotEmpty()) BridgeStatusStore.online()
                    else BridgeStatusStore.error("Add an Instagram account with a saved session to enable bridge actions.")
                }
            }
            .onFailure { Log.w(TAG, "Bridge heartbeat failed", it) }
            .getOrDefault(false)
    }

    suspend fun runOneJob(): Boolean = executionLock.withLock {
        if (!isBridgeEnabled()) return@withLock false

        if (!authRepository.get().isLoggedIn.value) {
            runCatching {
                authRepository.get().ensureDeviceSession(com.feedpilot.client.BuildConfig.VERSION_NAME)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastHeartbeatTimeMs >= HEARTBEAT_INTERVAL_MS || lastHeartbeatTimeMs == 0L) {
            heartbeat()
        }

        if (!isBridgeEnabled()) return@withLock false
        val job = runCatching {
            val response = api.pollBridgeJob(
                deviceId = identity.stableAppInstallationId,
                installationId = identity.accountBindingInstallationId
            )
            if (response.isSuccessful) response.body() else {
                error("Bridge poll failed HTTP ${response.code()}")
            }
        }.onFailure { Log.w(TAG, "Bridge poll failed", it) }.getOrNull() ?: return@withLock false

        if (!isBridgeEnabled()) return@withLock false
        BridgeStatusStore.resolving(job.targetUsername ?: job.targetUserId, job.id, job.accountUsername)
        notifications.show(BridgeStatusStore.status.value, newRequest = true)
        try {
            execute(job)
        } finally {
            if (isBridgeEnabled()) notifications.show(BridgeStatusStore.status.value)
            else notifications.cancel()
        }
        true
    }

    private suspend fun execute(job: BridgeJobDto) {
        BridgeStatusStore.resolving(job.targetUsername ?: job.targetUserId, job.id, job.accountUsername)
        val account = resolveAccount(job)
        if (account == null) {
            fail(job, "NO_LOCAL_SESSION", "Assigned Instagram account is not available on this device.")
            return
        }
        BridgeStatusStore.resolving(job.targetUsername ?: job.targetUserId, job.id, account.username)
        if (!isBridgeEnabled()) return
        notifications.show(BridgeStatusStore.status.value)

        val target = job.targetUsername?.takeIf { it.isNotBlank() } ?: job.targetUserId
        if (target.isNullOrBlank()) {
            fail(job, "INVALID_TARGET", "Bridge job did not include a username or user id.")
            return
        }

        var returningResult = false
        try {
            // GraphQL profile feeds require a handle, including jobs submitted with only a user ID.
            val feedTarget = job.targetUsername?.takeIf { it.isNotBlank() }
                ?: instagram.getUserProfileDetails(target, account.sessionCookies)?.username
                    ?.takeIf { it.isNotBlank() && it.any { char -> !char.isDigit() } }
                ?: error("Instagram username could not be resolved for user ID $target.")
            val feed = when (val result = targets.fetchUserFeed(feedTarget, job.maxId, account.sessionCookies)) {
                is Resource.Success -> result.data
                is Resource.Error -> {
                    fail(job, classify(result.cause ?: IllegalStateException(result.message)), result.message)
                    return
                }
                Resource.Loading -> {
                    fail(job, "IG_EMPTY_FEED", "Instagram returned no feed data for $feedTarget.")
                    return
                }
            }
            if (feed.items.isEmpty()) {
                val profileLabel = feed.profile?.username?.let { "@$it" } ?: feedTarget
                fail(job, "IG_EMPTY_FEED", "Instagram feed API returned zero posts for $profileLabel.")
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

            val targetUsername = job.targetUsername?.takeIf { it.isNotBlank() }
                ?: target.takeIf { it.any { char -> !char.isDigit() } }
            if (!targetUsername.isNullOrBlank()) {
                val profile = feed.profile
                if (profile != null && !profile.username.isNullOrBlank() &&
                    !profile.username.equals(targetUsername, ignoreCase = true)
                ) {
                    Log.e(TAG, "Bridge feed profile mismatch: target is @$targetUsername but feed returned @${profile.username}")
                    fail(job, "IG_PROFILE_MISMATCH", "Device resolved profile @${profile.username} instead of target @$targetUsername.")
                    return
                }
            }
            val data = feed.toBridgeResultData(job.limit, stories, highlights)
            if (!isBridgeEnabled()) return
            val postCount = feed.items.size
            val followerCount = feed.profile?.followerCount
            val followingCount = feed.profile?.followingCount
            BridgeStatusStore.finished(postCount)
            BridgeStatusStore.sendingResult()
            returningResult = true
            val result = api.completeBridgeJob(job.id, CompleteBridgeJobRequest(data))
            if (result.accepted && result.status.equals("Completed", ignoreCase = true)) {
                BridgeStatusStore.resultReturned(
                    postCount = postCount,
                    followerCount = followerCount,
                    followingCount = followingCount
                )
            } else {
                BridgeStatusStore.resultFailed("Result was not accepted: ${result.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bridge job ${job.id} failed", e)
            fail(job, classify(e), e.message ?: "Instagram feed execution failed.")
            if (returningResult) BridgeStatusStore.resultFailed(
                "Could not confirm result delivery: ${e.message ?: "network error"}")
        }
    }

    private suspend fun resolveAccount(job: BridgeJobDto): AccountEntity? {
        val all = accounts.getAll()
        val match = job.assignedAccountId?.let { id -> all.firstOrNull { it.id == id } }
            ?: job.accountUsername?.let { username -> all.firstOrNull { it.username.equals(username, ignoreCase = true) } }
            ?: all.firstOrNull { it.isLoggedIn }
            ?: all.firstOrNull()

        return match?.let {
            val effectiveCookies = if (it.sessionCookies.isNotBlank()) {
                it.sessionCookies
            } else if (it.lastLogin?.contains("sessionid") == true) {
                it.lastLogin
            } else ""
            if (effectiveCookies.isNotBlank()) it.copy(sessionCookies = effectiveCookies) else null
        }
    }

    private suspend fun fail(job: BridgeJobDto, code: String, message: String) {
        val report = runCatching {
            api.failBridgeJob(job.id, FailBridgeJobRequest(code = code, message = message))
        }.onFailure {
            Log.w(TAG, "Could not report bridge job failure", it)
        }
        BridgeStatusStore.jobFailed(if (report.getOrNull()?.accepted == true)
            "$message Failure reported to FeedPilot; another available device can be tried."
        else "$message Could not confirm failure delivery to FeedPilot.")
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
        const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}
