package com.feedpilot.client.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountLogDao
import com.feedpilot.client.data.local.AccountLogEntity
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.service.TaskRunnerState
import com.feedpilot.client.service.UpgradeRunnerState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Periodic hourly worker that refreshes the Instagram feed for logged-in accounts
 * and performs randomized organic warmup activities (Like, Save Post, Repost, Follow).
 *
 * Safety & Anti-Detection Guarantees:
 * 1. Strictly disabled unless enabled via settings (settings.feedWarmupEnabled).
 * 2. Never processes if the account is currently running or processing tasks in
 *    TaskRunnerService or UpgradeRunnerService at that moment.
 * 3. Never triggers actions instantaneously at the same moment the feed is loaded:
 *    staggers with human reading/browsing dwell time (12s - 28s) before any action.
 * 4. Staggers execution between multiple accounts to prevent burst traffic from the same IP.
 * 5. 25% of sessions simulate "lurking" (viewing feed without clicking or interacting).
 */
@HiltWorker
class FeedWarmupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountDao: AccountDao,
    private val accountLogDao: AccountLogDao,
    private val instagram: InstagramRepository,
    private val settingsRepository: SettingsRepository,
    private val taskRunnerState: TaskRunnerState,
    private val upgradeRunnerState: UpgradeRunnerState
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val settings = settingsRepository.current()
            if (!settings.feedWarmupEnabled) {
                Log.i(TAG, "Hourly feed warmup is disabled in settings; skipping.")
                return Result.success()
            }

            val accounts = accountDao.getAll().filter { it.isLoggedIn && it.sessionCookies.isNotBlank() }
            if (accounts.isEmpty()) {
                Log.i(TAG, "No verified logged-in accounts found; skipping feed warmup.")
                return Result.success()
            }

            val now = System.currentTimeMillis()

            for (account in accounts) {
                // 1. Guard: Check if the account is actively processing tasks right now
                if (taskRunnerState.isAccountRunning(account.id) || upgradeRunnerState.anyRunning(account.id)) {
                    Log.i(TAG, "[${account.username}] Account is actively running tasks; skipping feed warmup at this moment.")
                    continue
                }

                // 2. Guard: Debounce check (minimum 45 minutes between warmups for the same account)
                val lastWarmup = lastWarmupMsPerAccount[account.id] ?: 0L
                if (now - lastWarmup < MIN_WARMUP_INTERVAL_MS) {
                    Log.i(TAG, "[${account.username}] Warmed up recently (${(now - lastWarmup) / 60_000}m ago); skipping.")
                    continue
                }

                // 3. Stagger delay between accounts so multiple accounts don't burst simultaneously
                delay(Random.nextLong(2_000L, 6_000L))

                // Re-check after stagger in case a runner task claimed this account
                if (taskRunnerState.isAccountRunning(account.id) || upgradeRunnerState.anyRunning(account.id)) {
                    Log.i(TAG, "[${account.username}] Account became active during stagger; skipping feed warmup.")
                    continue
                }

                // 4. Refresh feed
                Log.i(TAG, "[${account.username}] Refreshing home timeline feed...")
                val feedResult = runCatching {
                    instagram.getHomeTimeline(account.sessionCookies)
                }.getOrNull()

                if (feedResult == null || feedResult.items.isEmpty()) {
                    Log.w(TAG, "[${account.username}] Feed refresh returned no items.")
                    continue
                }

                // Persist any rotated cookies issued by Instagram
                feedResult.updatedCookies?.let { freshCookies ->
                    if (freshCookies != account.sessionCookies) {
                        accountDao.updateSessionCookies(account.id, freshCookies)
                    }
                }

                // 5. Human reading dwell time (do NOT process engagement action at that exact moment)
                val dwellMs = Random.nextLong(12_000L, 28_000L)
                Log.i(
                    TAG,
                    "[${account.username}] Feed refreshed (${feedResult.items.size} posts). Browsing for ${dwellMs / 1000}s before deciding action..."
                )
                delay(dwellMs)

                // Re-check if account started tasks during reading
                if (taskRunnerState.isAccountRunning(account.id) || upgradeRunnerState.anyRunning(account.id)) {
                    Log.i(TAG, "[${account.username}] Account became active during browsing; aborting warmup action at this moment.")
                    continue
                }

                // 6. 25% chance to lurk (simulate reading feed without any write action)
                val isLurk = Random.nextInt(100) < 25
                if (isLurk) {
                    Log.i(TAG, "[${account.username}] Lurk session completed (browsed feed without action).")
                    lastWarmupMsPerAccount[account.id] = System.currentTimeMillis()
                    continue
                }

                // 7. Pick random post from feed items
                val candidatePost = feedResult.items.filter { it.code.isNotBlank() }.randomOrNull()
                if (candidatePost == null) {
                    Log.i(TAG, "[${account.username}] No valid post candidate found in feed.")
                    lastWarmupMsPerAccount[account.id] = System.currentTimeMillis()
                    continue
                }

                val actionRoll = Random.nextInt(100)
                val currentCookies = feedResult.updatedCookies ?: account.sessionCookies

                when {
                    // Like post (40% probability)
                    actionRoll < 40 -> {
                        Log.i(TAG, "[${account.username}] Warmup action: Liking post ${candidatePost.code}")
                        val result = instagram.like(candidatePost.code, customCookies = currentCookies)
                        result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                        if (result.ok) {
                            accountLogDao.insert(
                                AccountLogEntity(
                                    id = UUID.randomUUID().toString(),
                                    accountId = account.id,
                                    action = "Like",
                                    target = "https://instagram.com/p/${candidatePost.code}",
                                    success = true,
                                    message = "[Warmup] Organic feed like",
                                    timestampMs = System.currentTimeMillis()
                                )
                            )
                        } else if (result.reason?.contains("feedback_required", ignoreCase = true) == true) {
                            Log.w(TAG, "[${account.username}] Action block on warmup like: ${result.reason}")
                        }
                    }

                    // Save post (30% probability)
                    actionRoll < 70 -> {
                        Log.i(TAG, "[${account.username}] Warmup action: Saving post ${candidatePost.code}")
                        val result = instagram.savePost(candidatePost.code, customCookies = currentCookies)
                        result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                        if (result.ok) {
                            accountLogDao.insert(
                                AccountLogEntity(
                                    id = UUID.randomUUID().toString(),
                                    accountId = account.id,
                                    action = "SavePost",
                                    target = "https://instagram.com/p/${candidatePost.code}",
                                    success = true,
                                    message = "[Warmup] Organic feed save",
                                    timestampMs = System.currentTimeMillis()
                                )
                            )
                        } else if (result.reason?.contains("feedback_required", ignoreCase = true) == true) {
                            Log.w(TAG, "[${account.username}] Action block on warmup save: ${result.reason}")
                        }
                    }

                    // Repost (15% probability)
                    actionRoll < 85 -> {
                        if (candidatePost.canReshare) {
                            Log.i(TAG, "[${account.username}] Warmup action: Reposting ${candidatePost.code}")
                            val result = instagram.repost(candidatePost.code, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                            if (result.ok) {
                                accountLogDao.insert(
                                    AccountLogEntity(
                                        id = UUID.randomUUID().toString(),
                                        accountId = account.id,
                                        action = "Repost",
                                        target = "https://instagram.com/p/${candidatePost.code}",
                                        success = true,
                                        message = "[Warmup] Organic feed repost",
                                        timestampMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        } else {
                            // If cannot reshare, fallback to save post
                            val result = instagram.savePost(candidatePost.code, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                        }
                    }

                    // Follow creator / suggested user (15% probability)
                    else -> {
                        val suggested = runCatching {
                            instagram.fetchSuggestedUsers(customCookies = currentCookies, maxNumberToDisplay = 5)
                        }.getOrNull()?.filter { it.username.isNotBlank() && !it.username.equals(account.username, ignoreCase = true) }

                        val targetUser = suggested?.randomOrNull()
                        if (targetUser != null && !accountLogDao.hasAccountCompletedTarget(account.id, targetUser.username)) {
                            Log.i(TAG, "[${account.username}] Warmup action: Following @${targetUser.username}")
                            val result = instagram.follow(targetUser.id, targetUser.username, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                            if (result.ok) {
                                accountLogDao.insert(
                                    AccountLogEntity(
                                        id = UUID.randomUUID().toString(),
                                        accountId = account.id,
                                        action = "Follow",
                                        target = "@${targetUser.username}",
                                        success = true,
                                        message = "[Warmup] Organic feed follow",
                                        timestampMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        } else {
                            // Fallback to like if no target user found
                            val result = instagram.like(candidatePost.code, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                        }
                    }
                }

                lastWarmupMsPerAccount[account.id] = System.currentTimeMillis()
            }

            Result.success()
        }.getOrElse { error ->
            Log.e(TAG, "Error in FeedWarmupWorker", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FeedWarmupWorker"
        private const val MIN_WARMUP_INTERVAL_MS = 45 * 60_000L // 45 minutes debounce per account
        private val lastWarmupMsPerAccount = ConcurrentHashMap<String, Long>()
    }
}
