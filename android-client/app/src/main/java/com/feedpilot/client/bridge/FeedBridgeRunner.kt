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
    private val roundRobinCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private fun hasSession(a: AccountEntity) =
        a.sessionCookies.isNotBlank() || a.lastLogin?.contains("sessionid") == true
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
            appVersion = com.feedpilot.client.BuildConfig.VERSION_NAME,
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
            .onFailure {
                Log.w(TAG, "Bridge heartbeat failed", it)
                if (it is java.io.IOException) {
                    BridgeStatusStore.offline("Cannot reach bridge server")
                }
            }
            .getOrDefault(false)
    }

    fun onNetworkReconnected() {
        lastHeartbeatTimeMs = 0L
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
        }.onFailure {
            Log.w(TAG, "Bridge poll failed", it)
            if (it is java.io.IOException) {
                BridgeStatusStore.offline("Cannot reach bridge server")
            }
        }.getOrNull() ?: return@withLock false

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
        val candidateAccounts = getCandidateAccounts(job)
        if (candidateAccounts.isEmpty()) {
            fail(job, "NO_LOCAL_SESSION", "No Instagram account with a valid session is available on this device.")
            return
        }

        val target = job.targetUsername?.takeIf { it.isNotBlank() } ?: job.targetUserId
        if (target.isNullOrBlank()) {
            fail(job, "INVALID_TARGET", "Bridge job did not include a username or user id.")
            return
        }

        var lastErrorCode = "IG_UNKNOWN_ERROR"
        var lastErrorMessage = "Instagram feed execution failed."
        var jobCompleted = false

        for ((index, account) in candidateAccounts.withIndex()) {
            if (!isBridgeEnabled()) return
            val attemptNum = index + 1
            val totalAccounts = candidateAccounts.size
            Log.i(TAG, "Bridge job ${job.id}: attempting with account @${account.username} ($attemptNum/$totalAccounts)")
            BridgeStatusStore.resolving(job.targetUsername ?: job.targetUserId, job.id, account.username)
            notifications.show(BridgeStatusStore.status.value)

            var returningResult = false
            try {
                // GraphQL profile feeds require a handle, including jobs submitted with only a user ID.
                val feedTarget = job.targetUsername?.takeIf { it.isNotBlank() }
                    ?: runCatching {
                        instagram.getUserProfileDetails(target, account.sessionCookies)?.username
                            ?.takeIf { it.isNotBlank() && it.any { char -> !char.isDigit() } }
                    }.getOrNull()

                if (feedTarget.isNullOrBlank()) {
                    val msg = "Instagram username could not be resolved for user ID $target using @${account.username}."
                    Log.w(TAG, msg)
                    lastErrorCode = "IG_TARGET_NOT_FOUND"
                    lastErrorMessage = msg
                    if (attemptNum < totalAccounts) {
                        Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                        continue
                    }
                    break
                }

                val feed = when (val result = targets.fetchUserFeed(feedTarget, job.maxId, account.sessionCookies)) {
                    is Resource.Success -> result.data
                    is Resource.Error -> {
                        val code = classify(result.cause ?: IllegalStateException(result.message))
                        lastErrorCode = code
                        lastErrorMessage = result.message
                        Log.w(TAG, "Bridge job ${job.id} account @${account.username} failed ($code): ${result.message}")
                        handleAccountFailure(account, code)
                        if (attemptNum < totalAccounts) {
                            Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                            continue
                        }
                        break
                    }
                    Resource.Loading -> {
                        lastErrorCode = "IG_EMPTY_FEED"
                        lastErrorMessage = "Instagram returned no feed data for $feedTarget."
                        if (attemptNum < totalAccounts) {
                            Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                            continue
                        }
                        break
                    }
                }

                if (feed.items.isEmpty()) {
                    val profileLabel = feed.profile?.username?.let { "@$it" } ?: feedTarget
                    lastErrorCode = "IG_EMPTY_FEED"
                    lastErrorMessage = "Instagram feed API returned zero posts for $profileLabel."
                    Log.w(TAG, "Bridge job ${job.id} account @${account.username} returned zero posts.")
                    if (attemptNum < totalAccounts) {
                        Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                        continue
                    }
                    break
                }

                val profile = feed.profile
                val feedUserId = job.targetUserId ?: profile?.id ?: feedTarget

                val canFetchStorySurfaces = feedUserId.all(Char::isDigit)
                val stories = if (job.includeStories && canFetchStorySurfaces) {
                    runCatching {
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
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val highlights = if (job.includeStories && canFetchStorySurfaces) {
                    runCatching {
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
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val targetUsername = job.targetUsername?.takeIf { it.isNotBlank() }
                    ?: target.takeIf { it.any { char -> !char.isDigit() } }
                if (!targetUsername.isNullOrBlank()) {
                    if (profile != null && !profile.username.isNullOrBlank() &&
                        !profile.username.equals(targetUsername, ignoreCase = true)
                    ) {
                        Log.e(TAG, "Bridge feed profile mismatch: target is @$targetUsername but feed returned @${profile.username}")
                        lastErrorCode = "IG_PROFILE_MISMATCH"
                        lastErrorMessage = "Device resolved profile @${profile.username} instead of target @$targetUsername."
                        if (attemptNum < totalAccounts) {
                            Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                            continue
                        }
                        break
                    }
                }

                var resolvedFeed = feed
                if (resolvedFeed.profile == null || ((resolvedFeed.profile?.followerCount ?: 0L) == 0L && (resolvedFeed.profile?.followingCount ?: 0L) == 0L)) {
                    val p = runCatching { instagram.getUserProfileDetails(feedTarget, account.sessionCookies) }.getOrNull()
                    if (p != null && (p.followerCount > 0 || p.followingCount > 0 || p.mediaCount > 0)) {
                        val merged = resolvedFeed.profile?.copy(
                            followerCount = p.followerCount,
                            followingCount = p.followingCount,
                            mediaCount = if (p.mediaCount > 0) p.mediaCount else resolvedFeed.profile?.mediaCount ?: 0,
                            fullName = p.fullName.ifBlank { resolvedFeed.profile?.fullName.orEmpty() },
                            biography = p.biography.ifBlank { resolvedFeed.profile?.biography.orEmpty() },
                            profilePicUrl = p.profilePicUrl.ifBlank { resolvedFeed.profile?.profilePicUrl.orEmpty() }
                        ) ?: p
                        resolvedFeed = resolvedFeed.copy(profile = merged)
                    }
                }

                val data = resolvedFeed.toBridgeResultData(job.limit, stories, highlights)
                if (!isBridgeEnabled()) return
                val postCount = resolvedFeed.items.size
                val followerCount = resolvedFeed.profile?.followerCount
                val followingCount = resolvedFeed.profile?.followingCount
                BridgeStatusStore.finished(postCount)
                BridgeStatusStore.sendingResult()
                returningResult = true

                val result = api.completeBridgeJob(job.id, CompleteBridgeJobRequest(data, resolvedAccountUsername = account.username))
                if (result.accepted && result.status.equals("Completed", ignoreCase = true)) {
                    BridgeStatusStore.resultReturned(
                        postCount = postCount,
                        followerCount = followerCount,
                        followingCount = followingCount
                    )
                    jobCompleted = true
                    Log.i(TAG, "Bridge job ${job.id} completed successfully using account @${account.username}")
                    break
                } else {
                    BridgeStatusStore.resultFailed("Result was not accepted: ${result.status}")
                    lastErrorCode = "JOB_NOT_ACCEPTED"
                    lastErrorMessage = "Server did not accept result: ${result.status}"
                    if (attemptNum < totalAccounts) {
                        Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                        continue
                    }
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bridge job ${job.id} failed on account @${account.username}", e)
                val code = classify(e)
                lastErrorCode = code
                lastErrorMessage = e.message ?: "Instagram feed execution failed."
                if (returningResult) {
                    val statusMsg = if (e is retrofit2.HttpException && e.code() == 404) {
                        "Job timed out or expired on server before delivery"
                    } else {
                        "Could not confirm result delivery: ${e.message ?: "network error"}"
                    }
                    BridgeStatusStore.resultFailed(statusMsg)
                    break
                } else {
                    handleAccountFailure(account, code)
                }
                if (attemptNum < totalAccounts) {
                    Log.i(TAG, "Bridge job ${job.id}: retrying with next account on this device...")
                    continue
                }
                break
            }
        }

        if (!jobCompleted) {
            Log.w(TAG, "Bridge job ${job.id}: all candidate accounts on this device failed ($lastErrorCode). Reporting failure to server.")
            fail(job, lastErrorCode, lastErrorMessage)
        }
    }

    private suspend fun handleAccountFailure(account: AccountEntity, code: String) {
        if (code == "IG_SESSION_EXPIRED") {
            runCatching {
                accounts.updateStatus(account.id, "SessionExpired")
                accounts.updateLoginStatus(account.id, false)
            }.onFailure { Log.w(TAG, "Could not update account status to SessionExpired", it) }
        } else if (code == "IG_CHECKPOINT_REQUIRED") {
            runCatching {
                accounts.updateStatus(account.id, "Checkpoint")
                accounts.updateLoginStatus(account.id, false)
            }.onFailure { Log.w(TAG, "Could not update account status to Checkpoint", it) }
        }
    }

    /**
     * Retrieves all usable accounts on this device ordered for execution.
     * Starts with the server assigned account or round-robin rotation, followed by
     * any other usable accounts on this device so failures can failover locally.
     */
    internal suspend fun getCandidateAccounts(job: BridgeJobDto): List<AccountEntity> {
        val all = accounts.getAll()
        val usable = all
            .filter { hasSession(it) && !it.status.equals("SessionExpired", true) && !it.status.equals("Suspended", true) }
            .sortedBy { it.id }

        val ordered = mutableListOf<AccountEntity>()
        if (usable.isNotEmpty()) {
            val assigned = job.assignedAccountId?.let { id -> usable.firstOrNull { it.id == id } }
                ?: job.accountUsername?.let { username -> usable.firstOrNull { it.username.equals(username, ignoreCase = true) } }
            val startIndex = if (assigned != null) {
                usable.indexOf(assigned).coerceAtLeast(0)
            } else {
                Math.floorMod(roundRobinCounter.getAndIncrement(), usable.size)
            }
            for (i in usable.indices) {
                ordered.add(usable[(startIndex + i) % usable.size])
            }
        } else {
            val fallback = job.assignedAccountId?.let { id -> all.firstOrNull { it.id == id } }
                ?: job.accountUsername?.let { username -> all.firstOrNull { it.username.equals(username, ignoreCase = true) } }
                ?: all.firstOrNull { it.isLoggedIn }
                ?: all.firstOrNull()
            if (fallback != null) ordered.add(fallback)
        }

        return ordered.mapNotNull {
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
