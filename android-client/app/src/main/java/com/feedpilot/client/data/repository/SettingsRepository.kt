package com.feedpilot.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.feedpilot.client.common.Constants
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.RunnerSettingsDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = Constants.SETTINGS_STORE)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Supported UI languages with a flag emoji for the picker. */
enum class AppLanguage(val displayName: String, val flag: String) {
    ENGLISH("English", "🇬🇧"),
    INDIAN("Indian", "🇮🇳"),
    PERSIAN("Persian", "🇮🇷"),
    ARABIC("Arabic", "🇦🇪")
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.LIGHT,
    val notificationsEnabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val backgroundSync: Boolean = true,
    val smmApiUrl: String = "https://hanumansmm.in/api/v2",
    val smmApiKey: String = "",
    val selectedProvider: String = "Hanuman SMM Panel",
    /** Random gap between two individual actions, so a run doesn't look like a fixed-interval bot. */
    val actionDelayMinMs: Long = DEFAULT_ACTION_DELAY_MIN_MS,
    val actionDelayMaxMs: Long = DEFAULT_ACTION_DELAY_MAX_MS,
    /** How long an account's loop waits before re-polling for orders when there is nothing to do. */
    val fetchDelayMs: Long = DEFAULT_FETCH_DELAY_MS,
    /** Random-mode only: pause after each streak, in seconds. */
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
    /** Automatically fetch cancelled tasks from SMM Admin API and update status to partial with remains count. */
    val autoPartialCancelledTasks: Boolean = true,
    val coinsPerInr: Int = 5,
    val minWithdrawalInr: Int = 100,
    val claimBatchSize: Int = 10,
    val termsAccepted: Boolean = false,
    /** Dashboard-configurable Settings-screen links, synced from RunnerSettings. Null until the
     *  first successful sync, or if the admin has left the field blank. */
    val supportContactUrl: String? = null,
    val telegramChannelUrl: String? = null,
    /** Dashboard-controlled withdraw payment method visibility and per-method coin rates. */
    val upiEnabled: Boolean = true,
    val bankEnabled: Boolean = true,
    val usdtBep20Enabled: Boolean = false,
    val coinsPerUsdt: Int = 400,
    val minWithdrawalUsdt: Double = 5.0,
    /** Dashboard-configurable per-action coin rewards ("Action Coin Pricing & Referral Schema"),
     *  Normal vs Upgraded (24h bonus window) account. Defaults mirror RunnerSettings' own. */
    val followCoinsNormal: Int = 1,
    val followCoinsUpgraded: Int = 2,
    val likeCoinsNormal: Int = 1,
    val likeCoinsUpgraded: Int = 2,
    val commentCoinsNormal: Int = 2,
    val commentCoinsUpgraded: Int = 4,
    val repostCoinsNormal: Int = 1,
    val repostCoinsUpgraded: Int = 2,
    val savePostCoinsNormal: Int = 1,
    val savePostCoinsUpgraded: Int = 2,
    val storyViewCoinsNormal: Int = 1,
    val storyViewCoinsUpgraded: Int = 2,
    val pricePerFollow: Int = 8,
    val pricePerLike: Int = 3,
    val pricePerComment: Int = 10,
    val pricePerRepost: Int = 12,
    val pricePerSavePost: Int = 6,
    val pricePerStoryView: Int = 4,
    /** Per-activity streak counts for Random mode — how many consecutive same-type actions
     *  run before the runner switches action type and enters a cooldown. */
    val followStreakCount: Int = 5,
    val likeStreakCount: Int = 5,
    val commentStreakCount: Int = 3,
    val repostStreakCount: Int = 3,
    val savePostStreakCount: Int = 5,
    val storyViewStreakCount: Int = 5,
    val maxFollowsPerDay: Int = 200,
    val maxLikesPerDay: Int = 200,
    val maxCommentsPerDay: Int = 50,
    val maxRepostsPerDay: Int = 50,
    val maxSavePostsPerDay: Int = 50,
    val maxStoryViewsPerDay: Int = 500,
    val dailyLimitCooldownMinutes: Int = 60
) {
    companion object {
        const val DEFAULT_ACTION_DELAY_MIN_MS = 1_500L
        const val DEFAULT_ACTION_DELAY_MAX_MS = 4_000L
        const val MIN_ACTION_DELAY_MS = 500L
        const val MAX_ACTION_DELAY_MS = 120_000L

        const val DEFAULT_FETCH_DELAY_MS = 15_000L
        const val MIN_FETCH_DELAY_MS = 3_000L
        const val MAX_FETCH_DELAY_MS = 60_000L

        const val DEFAULT_COOLDOWN_SECONDS = 30
        const val MIN_COOLDOWN_SECONDS = 1
        const val MAX_COOLDOWN_SECONDS = 3_600
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        val SMM_API_URL = stringPreferencesKey("smm_api_url")
        val SMM_API_KEY = stringPreferencesKey("smm_api_key")
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val ACTION_DELAY_MIN_MS = longPreferencesKey("action_delay_min_ms")
        val ACTION_DELAY_MAX_MS = longPreferencesKey("action_delay_max_ms")
        val FETCH_DELAY_MS = longPreferencesKey("fetch_delay_ms")
        val COOLDOWN_SECONDS = intPreferencesKey("cooldown_seconds")
        val AUTO_PARTIAL_CANCELLED_TASKS = booleanPreferencesKey("auto_partial_cancelled_tasks")
        val COINS_PER_INR = intPreferencesKey("coins_per_inr")
        val MIN_WITHDRAWAL_INR = intPreferencesKey("min_withdrawal_inr")
        val CLAIM_BATCH_SIZE = intPreferencesKey("claim_batch_size")
        val TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")
        val SUPPORT_CONTACT_URL = stringPreferencesKey("support_contact_url")
        val TELEGRAM_CHANNEL_URL = stringPreferencesKey("telegram_channel_url")
        val UPI_ENABLED = booleanPreferencesKey("upi_enabled")
        val BANK_ENABLED = booleanPreferencesKey("bank_enabled")
        val USDT_BEP20_ENABLED = booleanPreferencesKey("usdt_bep20_enabled")
        val COINS_PER_USDT = intPreferencesKey("coins_per_usdt")
        val MIN_WITHDRAWAL_USDT = doublePreferencesKey("min_withdrawal_usdt")
        val FOLLOW_COINS_NORMAL = intPreferencesKey("follow_coins_normal")
        val FOLLOW_COINS_UPGRADED = intPreferencesKey("follow_coins_upgraded")
        val LIKE_COINS_NORMAL = intPreferencesKey("like_coins_normal")
        val LIKE_COINS_UPGRADED = intPreferencesKey("like_coins_upgraded")
        val COMMENT_COINS_NORMAL = intPreferencesKey("comment_coins_normal")
        val COMMENT_COINS_UPGRADED = intPreferencesKey("comment_coins_upgraded")
        val REPOST_COINS_NORMAL = intPreferencesKey("repost_coins_normal")
        val REPOST_COINS_UPGRADED = intPreferencesKey("repost_coins_upgraded")
        val SAVEPOST_COINS_NORMAL = intPreferencesKey("savepost_coins_normal")
        val SAVEPOST_COINS_UPGRADED = intPreferencesKey("savepost_coins_upgraded")
        val STORYVIEW_COINS_NORMAL = intPreferencesKey("storyview_coins_normal")
        val STORYVIEW_COINS_UPGRADED = intPreferencesKey("storyview_coins_upgraded")
        val PRICE_PER_FOLLOW = intPreferencesKey("price_per_follow")
        val PRICE_PER_LIKE = intPreferencesKey("price_per_like")
        val PRICE_PER_COMMENT = intPreferencesKey("price_per_comment")
        val PRICE_PER_REPOST = intPreferencesKey("price_per_repost")
        val PRICE_PER_SAVEPOST = intPreferencesKey("price_per_savepost")
        val PRICE_PER_STORYVIEW = intPreferencesKey("price_per_storyview")
        val FOLLOW_STREAK_COUNT = intPreferencesKey("follow_streak_count")
        val LIKE_STREAK_COUNT = intPreferencesKey("like_streak_count")
        val COMMENT_STREAK_COUNT = intPreferencesKey("comment_streak_count")
        val REPOST_STREAK_COUNT = intPreferencesKey("repost_streak_count")
        val SAVEPOST_STREAK_COUNT = intPreferencesKey("savepost_streak_count")
        val STORYVIEW_STREAK_COUNT = intPreferencesKey("storyview_streak_count")
        val MAX_FOLLOWS_PER_DAY = intPreferencesKey("max_follows_per_day")
        val MAX_LIKES_PER_DAY = intPreferencesKey("max_likes_per_day")
        val MAX_COMMENTS_PER_DAY = intPreferencesKey("max_comments_per_day")
        val MAX_REPOSTS_PER_DAY = intPreferencesKey("max_reposts_per_day")
        val MAX_SAVEPOSTS_PER_DAY = intPreferencesKey("max_saveposts_per_day")
        val MAX_STORYVIEWS_PER_DAY = intPreferencesKey("max_storyviews_per_day")
        val DAILY_LIMIT_COOLDOWN_MINUTES = intPreferencesKey("daily_limit_cooldown_minutes")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = ThemeMode.valueOf(p[Keys.THEME] ?: ThemeMode.LIGHT.name),
            notificationsEnabled = p[Keys.NOTIFICATIONS] ?: true,
            autoUpdate = p[Keys.AUTO_UPDATE] ?: true,
            backgroundSync = p[Keys.BACKGROUND_SYNC] ?: true,
            smmApiUrl = p[Keys.SMM_API_URL] ?: "https://hanumansmm.in/api/v2",
            smmApiKey = p[Keys.SMM_API_KEY] ?: "",
            selectedProvider = p[Keys.SELECTED_PROVIDER] ?: "Hanuman SMM Panel",
            actionDelayMinMs = p[Keys.ACTION_DELAY_MIN_MS] ?: AppSettings.DEFAULT_ACTION_DELAY_MIN_MS,
            actionDelayMaxMs = p[Keys.ACTION_DELAY_MAX_MS] ?: AppSettings.DEFAULT_ACTION_DELAY_MAX_MS,
            fetchDelayMs = p[Keys.FETCH_DELAY_MS] ?: AppSettings.DEFAULT_FETCH_DELAY_MS,
            cooldownSeconds = p[Keys.COOLDOWN_SECONDS] ?: AppSettings.DEFAULT_COOLDOWN_SECONDS,
            autoPartialCancelledTasks = p[Keys.AUTO_PARTIAL_CANCELLED_TASKS] ?: true,
            coinsPerInr = p[Keys.COINS_PER_INR] ?: 5,
            minWithdrawalInr = p[Keys.MIN_WITHDRAWAL_INR] ?: 100,
            claimBatchSize = p[Keys.CLAIM_BATCH_SIZE] ?: 10,
            termsAccepted = p[Keys.TERMS_ACCEPTED] ?: false,
            supportContactUrl = p[Keys.SUPPORT_CONTACT_URL],
            telegramChannelUrl = p[Keys.TELEGRAM_CHANNEL_URL],
            upiEnabled = p[Keys.UPI_ENABLED] ?: true,
            bankEnabled = p[Keys.BANK_ENABLED] ?: true,
            usdtBep20Enabled = p[Keys.USDT_BEP20_ENABLED] ?: false,
            coinsPerUsdt = p[Keys.COINS_PER_USDT] ?: 400,
            minWithdrawalUsdt = p[Keys.MIN_WITHDRAWAL_USDT] ?: 5.0,
            followCoinsNormal = p[Keys.FOLLOW_COINS_NORMAL] ?: 1,
            followCoinsUpgraded = p[Keys.FOLLOW_COINS_UPGRADED] ?: 2,
            likeCoinsNormal = p[Keys.LIKE_COINS_NORMAL] ?: 1,
            likeCoinsUpgraded = p[Keys.LIKE_COINS_UPGRADED] ?: 2,
            commentCoinsNormal = p[Keys.COMMENT_COINS_NORMAL] ?: 2,
            commentCoinsUpgraded = p[Keys.COMMENT_COINS_UPGRADED] ?: 4,
            repostCoinsNormal = p[Keys.REPOST_COINS_NORMAL] ?: 1,
            repostCoinsUpgraded = p[Keys.REPOST_COINS_UPGRADED] ?: 2,
            savePostCoinsNormal = p[Keys.SAVEPOST_COINS_NORMAL] ?: 1,
            savePostCoinsUpgraded = p[Keys.SAVEPOST_COINS_UPGRADED] ?: 2,
            storyViewCoinsNormal = p[Keys.STORYVIEW_COINS_NORMAL] ?: 1,
            storyViewCoinsUpgraded = p[Keys.STORYVIEW_COINS_UPGRADED] ?: 2,
            pricePerFollow = p[Keys.PRICE_PER_FOLLOW] ?: 8,
            pricePerLike = p[Keys.PRICE_PER_LIKE] ?: 3,
            pricePerComment = p[Keys.PRICE_PER_COMMENT] ?: 10,
            pricePerRepost = p[Keys.PRICE_PER_REPOST] ?: 12,
            pricePerSavePost = p[Keys.PRICE_PER_SAVEPOST] ?: 6,
            pricePerStoryView = p[Keys.PRICE_PER_STORYVIEW] ?: 4,
            followStreakCount = p[Keys.FOLLOW_STREAK_COUNT] ?: 5,
            likeStreakCount = p[Keys.LIKE_STREAK_COUNT] ?: 5,
            commentStreakCount = p[Keys.COMMENT_STREAK_COUNT] ?: 3,
            repostStreakCount = p[Keys.REPOST_STREAK_COUNT] ?: 3,
            savePostStreakCount = p[Keys.SAVEPOST_STREAK_COUNT] ?: 5,
            storyViewStreakCount = p[Keys.STORYVIEW_STREAK_COUNT] ?: 5,
            maxFollowsPerDay = p[Keys.MAX_FOLLOWS_PER_DAY] ?: 200,
            maxLikesPerDay = p[Keys.MAX_LIKES_PER_DAY] ?: 200,
            maxCommentsPerDay = p[Keys.MAX_COMMENTS_PER_DAY] ?: 50,
            maxRepostsPerDay = p[Keys.MAX_REPOSTS_PER_DAY] ?: 50,
            maxSavePostsPerDay = p[Keys.MAX_SAVEPOSTS_PER_DAY] ?: 50,
            maxStoryViewsPerDay = p[Keys.MAX_STORYVIEWS_PER_DAY] ?: 500,
            dailyLimitCooldownMinutes = p[Keys.DAILY_LIMIT_COOLDOWN_MINUTES] ?: 60
        )
    }

    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setNotifications(enabled: Boolean) = edit { it[Keys.NOTIFICATIONS] = enabled }
    suspend fun setAutoUpdate(enabled: Boolean) = edit { it[Keys.AUTO_UPDATE] = enabled }
    suspend fun setBackgroundSync(enabled: Boolean) = edit { it[Keys.BACKGROUND_SYNC] = enabled }
    suspend fun setSmmApiUrl(url: String) = edit { it[Keys.SMM_API_URL] = url }
    suspend fun setSmmApiKey(key: String) = edit { it[Keys.SMM_API_KEY] = key }
    suspend fun setSelectedProvider(provider: String) = edit { it[Keys.SELECTED_PROVIDER] = provider }
    suspend fun setAutoPartialCancelledTasks(enabled: Boolean) = edit { it[Keys.AUTO_PARTIAL_CANCELLED_TASKS] = enabled }
    suspend fun setTermsAccepted(accepted: Boolean) = edit { it[Keys.TERMS_ACCEPTED] = accepted }
    suspend fun setActionDelayRange(minMs: Long, maxMs: Long) = edit {
        val lo = minMs.coerceIn(AppSettings.MIN_ACTION_DELAY_MS, AppSettings.MAX_ACTION_DELAY_MS)
        val hi = maxMs.coerceIn(AppSettings.MIN_ACTION_DELAY_MS, AppSettings.MAX_ACTION_DELAY_MS)
        it[Keys.ACTION_DELAY_MIN_MS] = minOf(lo, hi)
        it[Keys.ACTION_DELAY_MAX_MS] = maxOf(lo, hi)
    }
    suspend fun setFetchDelay(ms: Long) = edit {
        it[Keys.FETCH_DELAY_MS] = ms.coerceIn(AppSettings.MIN_FETCH_DELAY_MS, AppSettings.MAX_FETCH_DELAY_MS)
    }
    suspend fun setCooldownSeconds(seconds: Int) = edit {
        it[Keys.COOLDOWN_SECONDS] = seconds.coerceIn(AppSettings.MIN_COOLDOWN_SECONDS, AppSettings.MAX_COOLDOWN_SECONDS)
    }
    suspend fun setRandomStreakCounts(
        follow: Int,
        like: Int,
        comment: Int,
        repost: Int,
        savePost: Int,
        storyView: Int
    ) = edit {
        it[Keys.FOLLOW_STREAK_COUNT] = follow.coerceAtLeast(1)
        it[Keys.LIKE_STREAK_COUNT] = like.coerceAtLeast(1)
        it[Keys.COMMENT_STREAK_COUNT] = comment.coerceAtLeast(1)
        it[Keys.REPOST_STREAK_COUNT] = repost.coerceAtLeast(1)
        it[Keys.SAVEPOST_STREAK_COUNT] = savePost.coerceAtLeast(1)
        it[Keys.STORYVIEW_STREAK_COUNT] = storyView.coerceAtLeast(1)
    }

    /** One-shot read for callers (the runner loop) that need current values, not a stream. */
    suspend fun current(): AppSettings = settings.first()

    private val runnerSettingsSyncMutex = Mutex()
    @Volatile private var runnerSettingsLastSyncedAtMs = 0L

    suspend fun saveRunnerSettingsDto(dto: RunnerSettingsDto) {
        edit {
            // FeedPilot keeps random activity timing and switching controls local to the app.
            // The backend sync below intentionally updates only shared commercial/support schema.
            it[Keys.AUTO_PARTIAL_CANCELLED_TASKS] = dto.autoPartialCancelledTasks
            it[Keys.COINS_PER_INR] = dto.coinsPerInr.coerceIn(1, 1000)
            it[Keys.MIN_WITHDRAWAL_INR] = dto.minWithdrawalInr.coerceIn(1, 10000)
            it[Keys.CLAIM_BATCH_SIZE] = dto.claimBatchSize.coerceIn(1, 50)
            if (dto.supportContactUrl.isNullOrBlank()) it.remove(Keys.SUPPORT_CONTACT_URL)
            else it[Keys.SUPPORT_CONTACT_URL] = dto.supportContactUrl
            if (dto.telegramChannelUrl.isNullOrBlank()) it.remove(Keys.TELEGRAM_CHANNEL_URL)
            else it[Keys.TELEGRAM_CHANNEL_URL] = dto.telegramChannelUrl
            it[Keys.UPI_ENABLED] = dto.upiEnabled
            it[Keys.BANK_ENABLED] = dto.bankEnabled
            it[Keys.USDT_BEP20_ENABLED] = dto.usdtBep20Enabled
            it[Keys.COINS_PER_USDT] = dto.coinsPerUsdt.coerceIn(1, 100_000)
            it[Keys.MIN_WITHDRAWAL_USDT] = dto.minWithdrawalUsdt.coerceIn(0.0, 100_000.0)
            it[Keys.FOLLOW_COINS_NORMAL] = dto.followCoinsNormal.coerceIn(0, 100_000)
            it[Keys.FOLLOW_COINS_UPGRADED] = dto.followCoinsUpgraded.coerceIn(0, 100_000)
            it[Keys.LIKE_COINS_NORMAL] = dto.likeCoinsNormal.coerceIn(0, 100_000)
            it[Keys.LIKE_COINS_UPGRADED] = dto.likeCoinsUpgraded.coerceIn(0, 100_000)
            it[Keys.COMMENT_COINS_NORMAL] = dto.commentCoinsNormal.coerceIn(0, 100_000)
            it[Keys.COMMENT_COINS_UPGRADED] = dto.commentCoinsUpgraded.coerceIn(0, 100_000)
            it[Keys.REPOST_COINS_NORMAL] = dto.repostCoinsNormal.coerceIn(0, 100_000)
            it[Keys.REPOST_COINS_UPGRADED] = dto.repostCoinsUpgraded.coerceIn(0, 100_000)
            it[Keys.SAVEPOST_COINS_NORMAL] = dto.savePostCoinsNormal.coerceIn(0, 100_000)
            it[Keys.SAVEPOST_COINS_UPGRADED] = dto.savePostCoinsUpgraded.coerceIn(0, 100_000)
            it[Keys.STORYVIEW_COINS_NORMAL] = dto.storyViewCoinsNormal.coerceIn(0, 100_000)
            it[Keys.STORYVIEW_COINS_UPGRADED] = dto.storyViewCoinsUpgraded.coerceIn(0, 100_000)
            it[Keys.PRICE_PER_FOLLOW] = dto.pricePerFollow.coerceIn(0, 100_000)
            it[Keys.PRICE_PER_LIKE] = dto.pricePerLike.coerceIn(0, 100_000)
            it[Keys.PRICE_PER_COMMENT] = dto.pricePerComment.coerceIn(0, 100_000)
            it[Keys.PRICE_PER_REPOST] = dto.pricePerRepost.coerceIn(0, 100_000)
            it[Keys.PRICE_PER_SAVEPOST] = dto.pricePerSavePost.coerceIn(0, 100_000)
            it[Keys.PRICE_PER_STORYVIEW] = dto.pricePerStoryView.coerceIn(0, 100_000)
        }
        runnerSettingsLastSyncedAtMs = System.currentTimeMillis()
    }

    suspend fun syncRunnerSettingsFromBackend(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - runnerSettingsLastSyncedAtMs < RUNNER_SETTINGS_SYNC_INTERVAL_MS) return

        runnerSettingsSyncMutex.withLock {
            val stillDue = force ||
                System.currentTimeMillis() - runnerSettingsLastSyncedAtMs >= RUNNER_SETTINGS_SYNC_INTERVAL_MS
            if (!stillDue) return@withLock

            val dto = runCatching { api.getRunnerSettings() }.getOrNull() ?: return@withLock
            saveRunnerSettingsDto(dto)
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        const val RUNNER_SETTINGS_SYNC_INTERVAL_MS = 15_000L
    }
}
