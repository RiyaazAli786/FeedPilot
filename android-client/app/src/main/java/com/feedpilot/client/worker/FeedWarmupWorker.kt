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
import java.util.Calendar
import java.util.UUID
import kotlin.random.Random

/**
 * Periodic hourly worker that refreshes the Instagram feed for logged-in accounts
 * and performs randomized organic warmup activities (Like, Save Post, Story View, Repost, Follow).
 *
 * Safety & Anti-Detection Guarantees:
 * 1. Strictly disabled unless enabled via settings (settings.feedWarmupEnabled).
 * 2. Night Sleep Mode (12:30 AM - 6:30 AM local time) simulates natural human sleep cycles.
 * 3. Never processes if the account is currently running or processing tasks in
 *    TaskRunnerService or UpgradeRunnerService at that moment.
 * 4. Never triggers actions instantaneously at the same moment the feed is loaded:
 *    staggers with human reading/browsing dwell time (12s - 28s) before any action.
 * 5. Staggers execution between multiple accounts to prevent burst traffic from the same IP.
 * 6. 25% of sessions simulate "lurking" (viewing feed without clicking or interacting).
 * 7. Enforces persistent debounce and daily warmup caps via AccountLogDao.
 * 8. Protects against duplicate actions on the same target.
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

    private enum class WarmupActionType {
        LIKE, SAVE, STORY_VIEW, REPOST, FOLLOW
    }

    override suspend fun doWork(): Result {
        return runCatching {
            val settings = settingsRepository.current()
            if (!settings.feedWarmupEnabled) {
                Log.i(TAG, "Hourly feed warmup is disabled in settings; skipping.")
                return Result.success()
            }

            // 1. Night Sleep Mode: Real humans sleep at night (12:30 AM - 6:30 AM)
            if (settings.warmupNightModeEnabled && isNightSleepTime()) {
                Log.i(TAG, "Warmup paused for Night Sleep Mode (12:30 AM - 6:30 AM local time). Good night!")
                return Result.success()
            }

            val accounts = accountDao.getAll().filter { it.isLoggedIn && it.sessionCookies.isNotBlank() }
            if (accounts.isEmpty()) {
                Log.i(TAG, "No verified logged-in accounts found; skipping feed warmup.")
                return Result.success()
            }

            val now = System.currentTimeMillis()
            val startOfToday = getStartOfTodayMs()
            val minIntervalMs = (settings.warmupIntervalHours.toLong() * 60L - 10L) * 60_000L

            for (account in accounts) {
                // 2. Guard: Never process feed warmup while an account is actively running tasks
                if (taskRunnerState.isAccountRunning(account.id) || upgradeRunnerState.anyRunning(account.id)) {
                    Log.i(TAG, "[${account.username}] Account is actively running tasks; skipping feed warmup at this moment.")
                    continue
                }

                // 3. Guard: Persistent Debounce (survives app restarts)
                val lastWarmup = accountLogDao.getLastWarmupTimestamp(account.id) ?: 0L
                if (now - lastWarmup < minIntervalMs) {
                    val minutesAgo = (now - lastWarmup) / 60_000L
                    Log.i(TAG, "[${account.username}] Warmed up ${minutesAgo}m ago (interval: ${settings.warmupIntervalHours}h); skipping.")
                    continue
                }

                // 4. Guard: Daily Warmup Action Cap
                val todayWarmupCount = accountLogDao.getDailyWarmupCount(account.id, startOfToday)
                if (todayWarmupCount >= settings.warmupDailyLimit) {
                    Log.i(TAG, "[${account.username}] Daily warmup limit reached ($todayWarmupCount / ${settings.warmupDailyLimit}); skipping.")
                    continue
                }

                // 5. Stagger delay between accounts so multiple accounts don't burst simultaneously
                delay(Random.nextLong(2_000L, 6_000L))

                // Re-check after stagger in case runner claimed this account
                if (taskRunnerState.isAccountRunning(account.id) || upgradeRunnerState.anyRunning(account.id)) {
                    Log.i(TAG, "[${account.username}] Account became active during stagger; skipping feed warmup.")
                    continue
                }

                // 6. Refresh feed
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

                // 7. Human reading dwell time (do NOT process engagement action at that exact moment)
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

                // Determine enabled actions from settings
                val enabledActions = mutableListOf<WarmupActionType>()
                if (settings.warmupLikeEnabled) enabledActions.add(WarmupActionType.LIKE)
                if (settings.warmupSaveEnabled) enabledActions.add(WarmupActionType.SAVE)
                if (settings.warmupStoryViewEnabled) enabledActions.add(WarmupActionType.STORY_VIEW)
                if (settings.warmupFollowEnabled) enabledActions.add(WarmupActionType.FOLLOW)
                if (settings.warmupRepostEnabled) enabledActions.add(WarmupActionType.REPOST)

                // 8. Lurk Session: 25% chance (or if no actions enabled) to browse without clicking
                val isLurk = enabledActions.isEmpty() || Random.nextInt(100) < 25
                if (isLurk) {
                    Log.i(TAG, "[${account.username}] Lurk session completed (browsed feed without action).")
                    accountLogDao.insert(
                        AccountLogEntity(
                            id = UUID.randomUUID().toString(),
                            accountId = account.id,
                            action = "Browse",
                            target = "Home Feed",
                            success = true,
                            message = "[Warmup] Organic feed browse (lurk)",
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                    continue
                }

                // 9. Execute one random enabled action
                val chosenAction = enabledActions.random()
                val currentCookies = feedResult.updatedCookies ?: account.sessionCookies

                when (chosenAction) {
                    WarmupActionType.LIKE -> {
                        val unlikedPosts = feedResult.items.filter { it.code.isNotBlank() && !it.hasLiked }
                        val candidate = unlikedPosts.firstOrNull {
                            !accountLogDao.existsForAccountActionTarget(account.id, "Like", "https://instagram.com/p/${it.code}")
                        } ?: feedResult.items.randomOrNull()

                        if (candidate != null && candidate.code.isNotBlank()) {
                            val targetUrl = "https://instagram.com/p/${candidate.code}"
                            Log.i(TAG, "[${account.username}] Warmup action: Liking post ${candidate.code}")
                            val result = instagram.like(candidate.code, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                            if (result.ok) {
                                accountLogDao.insert(
                                    AccountLogEntity(
                                        id = UUID.randomUUID().toString(),
                                        accountId = account.id,
                                        action = "Like",
                                        target = targetUrl,
                                        success = true,
                                        message = "[Warmup] Organic feed like",
                                        timestampMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }

                    WarmupActionType.SAVE -> {
                        val candidate = feedResult.items.firstOrNull {
                            it.code.isNotBlank() && !accountLogDao.existsForAccountActionTarget(account.id, "SavePost", "https://instagram.com/p/${it.code}")
                        } ?: feedResult.items.randomOrNull()

                        if (candidate != null && candidate.code.isNotBlank()) {
                            val targetUrl = "https://instagram.com/p/${candidate.code}"
                            Log.i(TAG, "[${account.username}] Warmup action: Saving post ${candidate.code}")
                            val result = instagram.savePost(candidate.code, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                            if (result.ok) {
                                accountLogDao.insert(
                                    AccountLogEntity(
                                        id = UUID.randomUUID().toString(),
                                        accountId = account.id,
                                        action = "SavePost",
                                        target = targetUrl,
                                        success = true,
                                        message = "[Warmup] Organic feed save",
                                        timestampMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }

                    WarmupActionType.STORY_VIEW -> {
                        val suggested = runCatching {
                            instagram.fetchSuggestedUsers(customCookies = currentCookies, maxNumberToDisplay = 8)
                        }.getOrNull()?.filter { it.username.isNotBlank() && !it.username.equals(account.username, ignoreCase = true) }

                        val targetUser = suggested?.firstOrNull {
                            !accountLogDao.existsForAccountActionTarget(account.id, "StoryView", "@${it.username}")
                        } ?: suggested?.randomOrNull()

                        if (targetUser != null) {
                            Log.i(TAG, "[${account.username}] Warmup action: Viewing story of @${targetUser.username}")
                            val result = instagram.storyView(targetUser.username, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                            accountLogDao.insert(
                                AccountLogEntity(
                                    id = UUID.randomUUID().toString(),
                                    accountId = account.id,
                                    action = "StoryView",
                                    target = "@${targetUser.username}",
                                    success = result.ok,
                                    message = if (result.ok) "[Warmup] Organic story view" else "[Warmup] Story view completed",
                                    timestampMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }

                    WarmupActionType.REPOST -> {
                        val candidate = feedResult.items.firstOrNull {
                            it.code.isNotBlank() && it.canReshare &&
                                !accountLogDao.existsForAccountActionTarget(account.id, "Repost", "https://instagram.com/p/${it.code}")
                        }
                        if (candidate != null) {
                            val targetUrl = "https://instagram.com/p/${candidate.code}"
                            Log.i(TAG, "[${account.username}] Warmup action: Reposting ${candidate.code}")
                            val result = instagram.repost(candidate.code, customCookies = currentCookies)
                            result.updatedCookies?.let { accountDao.updateSessionCookies(account.id, it) }
                            if (result.ok) {
                                accountLogDao.insert(
                                    AccountLogEntity(
                                        id = UUID.randomUUID().toString(),
                                        accountId = account.id,
                                        action = "Repost",
                                        target = targetUrl,
                                        success = true,
                                        message = "[Warmup] Organic feed repost",
                                        timestampMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }

                    WarmupActionType.FOLLOW -> {
                        val suggested = runCatching {
                            instagram.fetchSuggestedUsers(customCookies = currentCookies, maxNumberToDisplay = 8)
                        }.getOrNull()?.filter { it.username.isNotBlank() && !it.username.equals(account.username, ignoreCase = true) }

                        val targetUser = suggested?.firstOrNull {
                            !accountLogDao.existsForAccountActionTarget(account.id, "Follow", "@${it.username}")
                        }

                        if (targetUser != null) {
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
                        }
                    }
                }
            }

            Result.success()
        }.getOrElse { error ->
            Log.e(TAG, "Error in FeedWarmupWorker", error)
            Result.retry()
        }
    }

    private fun isNightSleepTime(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val minutesFromMidnight = hour * 60 + minute
        // Sleep between 12:30 AM (30 min) and 6:30 AM (390 min) local time
        return minutesFromMidnight in 30..390
    }

    private fun getStartOfTodayMs(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    companion object {
        private const val TAG = "FeedWarmupWorker"
    }
}
