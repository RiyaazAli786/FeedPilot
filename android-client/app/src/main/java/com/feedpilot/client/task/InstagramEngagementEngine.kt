package com.feedpilot.client.task

import android.util.Log
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.ResolveCacheRequest
import com.feedpilot.client.data.repository.InstagramRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Concrete [EngagementEngine] implementation that routes engagement tasks
 * to real Instagram Web APIs using [InstagramRepository].
 *
 * There is deliberately no simulated fallback here. This engine previously answered a failed
 * like or follow — and an account with no session at all — by delegating to
 * [SimulatedEngagementEngine], which always succeeds. Every such attempt was then reported to
 * the backend as completed work and paid a coin, so the runner could never fail. An action
 * that did not happen is now a [EngagementResult.Failure] carrying the reason.
 */
@Singleton
class InstagramEngagementEngine @Inject constructor(
    private val accountDao: AccountDao,
    private val instagramRepository: InstagramRepository,
    private val apiService: ApiService
) : EngagementEngine {

    override suspend fun like(targetId: String, accountId: String): EngagementResult {
        applyHumanJitter()
        val account = accountDao.getById(accountId)
        val cookies = sessionCookiesFor(account?.sessionCookies, account?.lastLogin)
            ?: return noSession(accountId, "like")

        Log.i(TAG, "Executing real Instagram web like for media $targetId using account ${account?.username}")
        val result = instagramRepository.like(mediaCodeOrId = targetId, customCookies = cookies)
        persistRotatedCookies(accountId, result.updatedCookies)

        return if (result.ok) {
            EngagementResult.Success
        } else {
            Log.w(TAG, "Real Instagram web like failed for $targetId: ${result.reason}")
            EngagementResult.Failure(result.reason ?: "Instagram rejected the like")
        }
    }

    override suspend fun follow(targetId: String, accountId: String): EngagementResult {
        applyHumanJitter()
        val account = accountDao.getById(accountId)
        val cookies = sessionCookiesFor(account?.sessionCookies, account?.lastLogin)
            ?: return noSession(accountId, "follow")

        val cleanUsername = com.feedpilot.client.common.InstagramCrypto.parseUsername(targetId)
            ?: com.feedpilot.client.common.extractInstagramHandle(targetId).removePrefix("@")

        val normalizedUsername = cleanUsername.lowercase().trim()
        var numericUserId: String? = null
        var fromCache = false

        // Try getting resolved user ID from backend cache
        try {
            val cacheResponse = apiService.getInstagramResolveCache(normalizedUsername)
            if (cacheResponse.isSuccessful) {
                val cachedUserId = cacheResponse.body()?.userId?.trim()
                if (isNumericUserId(cachedUserId)) {
                    numericUserId = cachedUserId
                    fromCache = true
                    Log.i(TAG, "Resolved user ID from backend cache: $normalizedUsername -> $numericUserId")
                } else if (!cachedUserId.isNullOrBlank()) {
                    Log.w(
                        TAG,
                        "Ignoring non-numeric backend cache entry for @$normalizedUsername: $cachedUserId; will re-resolve"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get user ID from backend cache for $normalizedUsername", e)
        }

        // If not found in cache, or the cached value was a bad username/string, resolve from Instagram.
        if (numericUserId.isNullOrBlank()) {
            numericUserId = instagramRepository.resolveUserId(cleanUsername, customCookies = cookies)
                ?: instagramRepository.resolveUserId(targetId, customCookies = cookies)

            if (!isNumericUserId(numericUserId)) {
                Log.w(TAG, "Could not resolve user id for @$cleanUsername (targetId: $targetId)")
                return EngagementResult.Failure("Could not resolve @$cleanUsername on Instagram")
            }
            val resolvedUserId = numericUserId
                ?: return EngagementResult.Failure("Could not resolve @$cleanUsername on Instagram")

            // Save/repair the resolved numeric user ID in the backend cache.
            try {
                apiService.saveInstagramResolveCache(ResolveCacheRequest(normalizedUsername, resolvedUserId))
                Log.i(TAG, "Saved resolved user ID to backend cache: $normalizedUsername -> $resolvedUserId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save resolved user ID to backend cache for $normalizedUsername", e)
            }
        }

        var targetNumericUserId = numericUserId
            ?: return EngagementResult.Failure("Could not resolve @$cleanUsername on Instagram")

        Log.i(TAG, "Executing real Instagram web follow for user $targetNumericUserId (@$cleanUsername) using account ${account?.username}")
        var result = instagramRepository.follow(
            targetUserId = targetNumericUserId,
            targetUsername = cleanUsername,
            customCookies = cookies
        )
        persistRotatedCookies(accountId, result.updatedCookies)

        // A cached id can go stale — the target account was deleted or renamed after it was
        // resolved — and Instagram reports that as "user not found" rather than rejecting the
        // follow itself. The backend cache has no expiry/invalidation, so without this every
        // device would keep being served the same dead id forever. Re-resolve fresh and retry
        // once instead of just failing.
        if (!result.ok && fromCache && result.reason?.contains("account not found", ignoreCase = true) == true) {
            Log.w(TAG, "Cached user id $targetNumericUserId for @$cleanUsername looks stale (${result.reason}); re-resolving")
            val freshId = instagramRepository.resolveUserId(cleanUsername, customCookies = cookies)
                ?: instagramRepository.resolveUserId(targetId, customCookies = cookies)

            if (freshId != null && isNumericUserId(freshId) && freshId != targetNumericUserId) {
                try {
                    apiService.saveInstagramResolveCache(ResolveCacheRequest(normalizedUsername, freshId))
                    Log.i(TAG, "Refreshed stale backend cache entry: $normalizedUsername -> $freshId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh stale backend cache entry for $normalizedUsername", e)
                }
                targetNumericUserId = freshId
                result = instagramRepository.follow(
                    targetUserId = freshId,
                    targetUsername = cleanUsername,
                    customCookies = cookies
                )
                persistRotatedCookies(accountId, result.updatedCookies)
            }
        }

        return if (result.ok) {
            EngagementResult.Success
        } else {
            Log.w(TAG, "Real Instagram web follow failed for @$cleanUsername: ${result.reason}")
            EngagementResult.Failure(result.reason ?: "Instagram rejected the follow")
        }
    }

    override suspend fun comment(targetId: String, commentText: String, accountId: String): EngagementResult {
        applyHumanJitter()
        val account = accountDao.getById(accountId)
        val cookies = sessionCookiesFor(account?.sessionCookies, account?.lastLogin)
            ?: return noSession(accountId, "comment")

        Log.i(TAG, "Executing real Instagram web comment for $targetId using account ${account?.username}")
        val result = instagramRepository.postComment(mediaCodeOrId = targetId, text = commentText, customCookies = cookies)
        persistRotatedCookies(accountId, result.updatedCookies)

        return if (result.ok) {
            EngagementResult.Success
        } else {
            Log.w(TAG, "Real Instagram web comment failed for $targetId: ${result.reason}")
            EngagementResult.Failure(result.reason ?: "Instagram rejected the comment")
        }
    }

    override suspend fun repost(targetId: String, accountId: String): EngagementResult {
        applyHumanJitter()
        val account = accountDao.getById(accountId)
        val cookies = sessionCookiesFor(account?.sessionCookies, account?.lastLogin)
            ?: return noSession(accountId, "repost")

        Log.i(TAG, "Executing Instagram web repost for $targetId using account ${account?.username}")
        val result = instagramRepository.repost(mediaCodeOrId = targetId, customCookies = cookies)
        persistRotatedCookies(accountId, result.updatedCookies)

        return if (result.ok) {
            EngagementResult.Success
        } else {
            Log.w(TAG, "Instagram web repost failed for $targetId: ${result.reason}")
            EngagementResult.Failure(result.reason ?: "Instagram rejected the repost")
        }
    }

    override suspend fun savePost(targetId: String, accountId: String): EngagementResult {
        if (targetId.isBlank()) return EngagementResult.Failure("Order has no target post")
        applyHumanJitter()

        val account = accountDao.getById(accountId)
        val cookies = sessionCookiesFor(account?.sessionCookies, account?.lastLogin)
            ?: return noSession(accountId, "save")

        Log.i(TAG, "Executing Instagram web save for $targetId using account ${account?.username}")
        val result = instagramRepository.savePost(mediaCodeOrId = targetId, customCookies = cookies)
        persistRotatedCookies(accountId, result.updatedCookies)

        return if (result.ok) {
            EngagementResult.Success
        } else {
            Log.w(TAG, "Instagram web save failed for $targetId: ${result.reason}")
            EngagementResult.Failure(result.reason ?: "Instagram rejected the save")
        }
    }

    override suspend fun storyView(targetId: String, accountId: String): EngagementResult {
        if (targetId.isBlank()) return EngagementResult.Failure("Order has no target story")
        applyHumanJitter()

        val account = accountDao.getById(accountId)
        val cookies = sessionCookiesFor(account?.sessionCookies, account?.lastLogin)
            ?: return noSession(accountId, "storyView")

        Log.i(TAG, "Executing Instagram story view for $targetId using account ${account?.username}")
        val result = instagramRepository.storyView(targetIdOrUrl = targetId, customCookies = cookies)
        persistRotatedCookies(accountId, result.updatedCookies)

        return if (result.ok) {
            EngagementResult.Success
        } else {
            Log.w(TAG, "Instagram story view failed for $targetId: ${result.reason}")
            EngagementResult.Failure(result.reason ?: "Instagram rejected story view")
        }
    }

    /** A usable session is one that actually carries a `sessionid` cookie. */
    private fun sessionCookiesFor(sessionCookies: String?, lastLogin: String?): String? {
        val cookies = sessionCookies?.ifBlank { null } ?: lastLogin
        return cookies?.takeIf { it.isNotBlank() && it.contains("sessionid") }
    }

    private fun noSession(accountId: String, action: String): EngagementResult {
        Log.w(TAG, "Account $accountId has no live Instagram session cookies; cannot $action")
        return EngagementResult.Failure("No Instagram session for this account — sign in again")
    }

    /**
     * Saves a cookie Instagram rotated on this action's response, so the next call — and the
     * next app launch — uses it instead of the value the session started with. Without this a
     * perfectly live session slowly drifted out of date purely because nothing ever picked up
     * what a real browser's cookie jar absorbs automatically, eventually getting rejected as
     * "logged out" after a run of otherwise-successful actions.
     */
    private suspend fun persistRotatedCookies(accountId: String, updatedCookies: String?) {
        if (updatedCookies.isNullOrBlank()) return
        runCatching { accountDao.updateSessionCookies(accountId, updatedCookies) }
            .onFailure { Log.w(TAG, "Could not persist rotated session cookies for $accountId", it) }
    }

    private suspend fun applyHumanJitter() {
        val delayMillis = Random.nextLong(1500, 3500)
        delay(delayMillis)
    }

    private fun isNumericUserId(value: String?): Boolean =
        !value.isNullOrBlank() && value.all { it.isDigit() }

    private companion object {
        const val TAG = "InstagramEngagementEngine"
    }
}

