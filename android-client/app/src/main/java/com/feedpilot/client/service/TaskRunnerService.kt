package com.feedpilot.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.feedpilot.client.MainActivity
import com.feedpilot.client.R
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.TaskEntity
import com.feedpilot.client.data.repository.AppSettings
import com.feedpilot.client.data.repository.ClaimOutcome
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.TaskRepository
import com.feedpilot.client.data.repository.WalletRepository
import com.feedpilot.client.task.EngagementTaskType
import com.feedpilot.client.task.TaskExecutor
import com.feedpilot.client.task.TaskOutcome
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountLogDao
import com.feedpilot.client.data.local.AccountLogEntity
import com.feedpilot.client.data.remote.InstagramSuggestedUser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.random.Random

/**
 * Foreground service that drains the pending-order queue — independently, per account.
 *
 * Each account started (see `TasksViewModel`) gets its own coroutine [Job] in [runJobs], each
 * running its own claim → perform → report loop against [TaskExecutor]. Accounts genuinely do
 * not interfere with each other: starting a second account never touches the first one's job,
 * and stopping one leaves every other running account alone. Before this, the whole service ran
 * a single shared loop for a single fixed account list — starting a *different* account while one
 * was already running looked, from that loop's point of view, exactly like a fresh start request,
 * so it replaced (and from the UI's "toggle" logic, sometimes outright stopped) the running one.
 *
 * The service's own lifecycle (foreground notification) follows whether [runJobs] is empty, not
 * any single account's state.
 */
@AndroidEntryPoint
class TaskRunnerService : Service() {

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var walletRepository: WalletRepository
    @Inject lateinit var executor: TaskExecutor
    @Inject lateinit var runnerState: TaskRunnerState
    @Inject lateinit var accountLogDao: AccountLogDao
    @Inject lateinit var accountDao: AccountDao
    @Inject lateinit var instagramRepository: InstagramRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appGuardRepository: com.feedpilot.client.data.repository.AppGuardRepository
    @Inject lateinit var deviceIdentity: DeviceIdentity

    /** See DeviceIdentity.cloneScopedNotificationId — keeps two same-package clones from
     *  silently overwriting each other's foreground-service notification. */
    private val notificationId by lazy { deviceIdentity.cloneScopedNotificationId(NOTIFICATION_ID_BASE) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One independent job per running account. Mutated from both the main thread (start/stop
     *  requests) and each job's own completion (on the IO dispatcher), hence the concurrent map. */
    private val runJobs = ConcurrentHashMap<String, Job>()
    private val completedTargetsPerAccount = ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>>()
    private val completedOrdersPerAccount = ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>>()

    /** Message to report for an account whose stop was explicitly requested, keyed by accountId. */
    private val stopMessages = ConcurrentHashMap<String, String>()
    private var wakeLock: PowerManager.WakeLock? = null
    // Keeps the Wi-Fi radio fully active during long screen-lock periods so the network never
    // drops mid-run. Without this Android powers down the Wi-Fi chip after the screen locks
    // for a while, which makes isNetworkAvailable() fail and parks every account loop inside
    // waitForNetwork() until the user unlocks the screen.
    private var wifiLock: WifiManager.WifiLock? = null
    private var serviceShutdownMessage: String? = null
    private var stoppingFromTaskRemoved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (appGuardRepository.guardState.value.isDestructive) {
            Log.w(TAG, "AppGuard active (destructive=true). Rejecting TaskRunnerService start.")
            clearPersistedRuns()
            requestStopAll("App stopped by server guard")
            stopRunnerService()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                val ids = intent.getStringArrayListExtra(EXTRA_ACCOUNT_IDS)
                if (ids.isNullOrEmpty()) {
                    clearPersistedRuns()
                    requestStopAll("Stopped")
                } else {
                    ids.forEach { requestStopAccount(it, "Stopped") }
                }
                if (runJobs.isEmpty()) stopRunnerService()
                return START_NOT_STICKY
            }
            else -> {
                val accountIds = intent?.getStringArrayListExtra(EXTRA_ACCOUNT_IDS).orEmpty()
                val modesBundle = intent?.getBundleExtra(EXTRA_ACCOUNT_MODES)
                val accountModes: Map<String, Set<EngagementTaskType>> = modesBundle?.let { bundle ->
                    bundle.keySet().associateWith { accId ->
                        bundle.getStringArrayList(accId)
                            ?.mapNotNull { EngagementTaskType.from(it) }
                            ?.toSet()
                            ?: executor.supportedTypes
                    }
                } ?: emptyMap()

                val allowed = intent?.getStringArrayListExtra(EXTRA_TASK_TYPES)
                    ?.mapNotNull { EngagementTaskType.from(it) }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: executor.supportedTypes

                if (accountIds.isEmpty()) {
                    val restored = restorePersistedRuns()
                    if (restored.isNotEmpty()) {
                        startForegroundCompat(buildNotification("Resuming…"))
                        acquireRunnerWakeLock()
                        restored.forEach { (accountId, restoredTypes) ->
                            if (runJobs[accountId]?.isActive != true) {
                                runJobs[accountId] = launchAccountRun(accountId, restoredTypes)
                            }
                        }
                        updateNotification()
                        return START_STICKY
                    }
                    // This request was dispatched via startForegroundService(), which obligates
                    // the service to call startForeground() shortly after — regardless of whether
                    // there was anything to actually run. Skipping straight to stopSelf() here
                    // broke that contract and crashed with ForegroundServiceDidNotStartInTimeException
                    // on Android 12+ whenever Start was pressed with no account selected.
                    if (runJobs.isEmpty()) {
                        startForegroundCompat(buildNotification("Nothing to run — no account selected"))
                        stopRunnerService()
                    }
                    return START_NOT_STICKY
                }

                // A generic placeholder here, not notificationText() — computing the real count
                // before this request's own accounts are added to runJobs is exactly what showed
                // "1 account running" forever after starting a 2nd or 3rd account one at a time:
                // each start request's notification locked in whatever runJobs.size was *before*
                // its own additions, and nothing ever corrected it afterwards.
                startForegroundCompat(buildNotification("Starting…"))
                acquireRunnerWakeLock()

                for (accountId in accountIds) {
                    // Already has its own loop running — leave it alone. This is exactly the
                    // case that used to stop it: a re-start request for an account already
                    // active must be a no-op, not a restart.
                    if (runJobs[accountId]?.isActive == true) continue
                    val allowedForThis = accountModes[accountId] ?: allowed
                    persistRun(accountId, allowedForThis)
                    runJobs[accountId] = launchAccountRun(accountId, allowedForThis)
                }

                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val ids = runJobs.keys.toList()
        scope.cancel()
        ids.forEach { runnerState.stoppedAccount(it, serviceShutdownMessage) }
        runJobs.clear()
        releaseRunnerWakeLock()
        runCatching {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val message = "Stopped because the app was cleared from recent apps"
        Log.i(TAG, "App task removed from recents; stopping all active account runners")
        serviceShutdownMessage = message
        stoppingFromTaskRemoved = true
        clearPersistedRuns()
        requestStopAll(message)
        scope.launch {
            val released = runCatching { taskRepository.releaseActiveClaimsHeldLocally() }
                .onFailure { Log.w(TAG, "Could not release claimed orders after recents clear", it) }
                .getOrDefault(0)
            Log.i(TAG, "Released $released claimed backend order(s) after recents clear")
            stoppingFromTaskRemoved = false
            stopRunnerService()
        }
        super.onTaskRemoved(rootIntent)
    }

    // ---------- Run loop (one independent instance per account) ----------

    private fun launchAccountRun(
        accountId: String,
        allowedTypes: Set<EngagementTaskType>
    ): Job = scope.launch {
        runnerState.startingAccount(accountId)
        var endMessage: String? = null
        // Consecutive passes with no real progress (empty batch, or a claimed batch nothing was
        // eligible for) — grows the idle-wait backoff via [idleWait] and resets to 0 the moment
        // this account actually executes something.
        var idleStreak = 0

        // Carries an in-progress Random-mode streak's type and remaining count across claims —
        // see [buildStreakOrderedBatch] for why this can't just be rebuilt fresh each claim.
        val streakCursor = StreakCursor()

        // Pre-load historical interacted targets from local database so re-started runner sessions
        // or app upgrades never duplicate actions against previously attempted targets.
        runCatching {
            val historical = accountLogDao.getInteractedTargetsForAccount(accountId)
            if (historical.isNotEmpty()) {
                completedTargetsPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }.addAll(historical)
            }
        }

        // Picks up whatever the dashboard currently has configured before this account's very
        // first claim, rather than waiting up to a minute for the loop's own throttled sync below.
        runCatching { settingsRepository.syncRunnerSettingsFromBackend(force = true) }

        try {
            while (isActive) {
                if (appGuardRepository.guardState.value.isDestructive) {
                    Log.w(TAG, "[$accountId] AppGuard destructive flag active — stopping account run")
                    break
                }
                // Belt-and-suspenders: meetsOrderStartRequirements() already blocked this account
                // at Start time if it had no saved session, but the account can be removed/logged
                // out from another screen mid-run — catch that promptly here instead of only
                // finding out reactively once a claimed task's own Instagram call fails.
                val currentAccount = runCatching { accountDao.getById(accountId) }.getOrNull()
                if (currentAccount == null || currentAccount.sessionCookies.isBlank()) {
                    Log.w(TAG, "[$accountId] No saved session anymore — stopping account run")
                    endMessage = "Stopped: no saved Instagram session for this account"
                    break
                }
                if (!isNetworkAvailable()) {
                    runnerState.noteAccount(accountId, "Network unavailable - retrying automatically")
                    updateNotification()
                    waitForNetwork()
                    continue
                }
                // Throttled internally — safe to call every pass without hammering the backend.
                runCatching { settingsRepository.syncRunnerSettingsFromBackend() }
                val settings = settingsRepository.current()

                // Check daily limits per activity for this account
                val startOfTodayMs = getStartOfTodayMs()
                val activeAllowedTypes = allowedTypes.filter { type ->
                    val limit = when (type) {
                        EngagementTaskType.FOLLOW -> settings.maxFollowsPerDay
                        EngagementTaskType.LIKE -> settings.maxLikesPerDay
                        EngagementTaskType.COMMENT -> settings.maxCommentsPerDay
                        EngagementTaskType.REPOST -> settings.maxRepostsPerDay
                        EngagementTaskType.SAVE_POST -> settings.maxSavePostsPerDay
                        EngagementTaskType.STORY_VIEW -> settings.maxStoryViewsPerDay
                    }
                    if (limit > 0) {
                        val count = accountLogDao.getDailyCountForAccountAction(accountId, type.wireName, startOfTodayMs)
                        count < limit
                    } else true
                }.toSet()

                if (activeAllowedTypes.isEmpty() && allowedTypes.isNotEmpty()) {
                    val cooldownMins = settings.dailyLimitCooldownMinutes.coerceAtLeast(1)
                    Log.w(TAG, "[$accountId] Daily action limits reached for all activities ($allowedTypes). Cooldown for ${cooldownMins}m...")
                    runnerState.noteAccount(accountId, PROCESSING_MESSAGE)
                    updateNotification()
                    delay(cooldownMins * 60_000L)
                    continue
                }

                val effectiveAllowedTypes = activeAllowedTypes.ifEmpty { allowedTypes }

                val claimStartedAt = System.currentTimeMillis()
                val claimResult = runCatching {
                    taskRepository.claimPending(
                        accountId = accountId,
                        allowedTypes = effectiveAllowedTypes.map { it.wireName }.toSet(),
                        limit = settings.claimBatchSize
                    )
                }
                    .onFailure { Log.w(TAG, "[$accountId] Could not claim orders", it) }
                    .getOrDefault(TaskRepository.ClaimResult(emptyList(), ClaimOutcome.NetworkError(null)))
                val claimed = claimResult.tasks
                // Random mode covers more than one order type. Grouping the claimed batch into
                // same-type streaks (instead of leaving claim order, or the old plain shuffle)
                // is what lets the loop below do "N follows, cooldown, M likes, cooldown, ..." —
                // a person switching between liking and following works in bursts like this, not
                // one-at-a-time interleaved. A single-type mode (plain Follow or plain Like) has
                // nothing to group, so it's left in claim order there with no streak boundaries.
                val (batch, streakBoundaryTaskIds) = if (effectiveAllowedTypes.size > 1) {
                    val perTypeStreakCounts = mapOf(
                        "Follow"     to settings.followStreakCount,
                        "Like"       to settings.likeStreakCount,
                        "Comment"    to settings.commentStreakCount,
                        "Repost"     to settings.repostStreakCount,
                        "SavePost"   to settings.savePostStreakCount,
                        "StoryView"  to settings.storyViewStreakCount
                    )
                    buildStreakOrderedBatch(claimed, streakCursor, perTypeStreakCounts)
                } else {
                    claimed to emptySet()
                }
                Log.w(TAG, "[$accountId] Claimed ${batch.size} task(s) in ${System.currentTimeMillis() - claimStartedAt}ms")

                if (batch.isEmpty()) {
                    if (
                        claimResult.outcome is ClaimOutcome.Success &&
                        EngagementTaskType.FOLLOW in effectiveAllowedTypes
                    ) {
                        val suggestedResult = processSuggestedFollow(accountId, currentAccount.sessionCookies)
                        if (suggestedResult.ran) {
                            idleStreak = 0
                            delay(actionDelay(settings))
                            if (suggestedResult.sessionDead) {
                                endMessage = "Stopped: Instagram session needs attention - log in via globe icon"
                                break
                            }
                            continue
                        }
                    }
                    val baseMessage = if (activeAllowedTypes.size < allowedTypes.size) {
                        val maxedTypes = (allowedTypes - activeAllowedTypes).joinToString("/") { it.wireName }
                        Log.w(TAG, "[$accountId] $maxedTypes limit reached for today; checking for other orders")
                        PROCESSING_MESSAGE
                    } else {
                        PROCESSING_MESSAGE
                    }
                    idleStreak = idleWait(accountId, idleStreak, claimResult.outcome, baseMessage, settings.fetchDelayMs)
                    continue
                }

                var executed = 0
                // Tracks, per backend order touched this batch, whether this account was ever
                // eligible for one of its tasks. An order that ends up in touchedOrderIds but
                // never in attemptedOrderIds is one this account cannot make any progress on at
                // all (already completed that target, or its mode doesn't cover the order type)
                // and this account must not keep holding its claim.
                val touchedOrderIds = mutableSetOf<String>()
                val attemptedOrderIds = mutableSetOf<String>()
                val terminalOrderIds = mutableSetOf<String>()

                var sessionDead = false
                for (task in batch) {
                    if (!isActive) break
                    if (task.id.startsWith(BACKEND_TASK_PREFIX)) touchedOrderIds += task.orderId
                    if (task.orderId in terminalOrderIds) {
                        Log.w(TAG, "[$accountId] Task ${task.id}: order ${task.orderId} was already marked terminal, skipping")
                        taskRepository.markStatus(task.id, STATUS_SKIPPED)
                        continue
                    }

                    // A single task's unexpected exception (a DB hiccup, a network layer bug)
                    // must not take down this account's whole run — that is what made the
                    // runner look like it silently died after one order instead of moving on.
                    val taskStartedAt = System.currentTimeMillis()
                    val result = try {
                        if (!isEligible(task, accountId, effectiveAllowedTypes)) {
                            Log.w(TAG, "[$accountId] Task ${task.id} (${task.taskType} -> ${task.targetId}): not eligible, skipping")
                            taskRepository.markStatus(task.id, STATUS_SKIPPED)
                            runnerState.recordSkip(
                                "No account set to ${EngagementTaskType.from(task.taskType)?.label ?: task.taskType}"
                            )
                            ProcessResult(ran = false)
                        } else {
                            if (task.id.startsWith(BACKEND_TASK_PREFIX)) attemptedOrderIds += task.orderId
                            Log.w(TAG, "[$accountId] Task ${task.id} (${task.taskType} -> ${task.targetId}): running")
                            process(task, accountId, effectiveAllowedTypes)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        Log.w(TAG, "[$accountId] Task ${task.id} crashed the processing step, skipping it", t)
                        runCatching { taskRepository.markStatus(task.id, STATUS_SKIPPED) }
                        ProcessResult(ran = false)
                    }
                    Log.w(TAG, "[$accountId] Task ${task.id}: ran=${result.ran}, took ${System.currentTimeMillis() - taskStartedAt}ms")

                    if (result.ran) {
                        executed++
                        // Random mode: buildStreakOrderedBatch already picked this streak's random
                        // length and marked the last task of each chunk — cooling down exactly on
                        // those boundaries (instead of a separately-counted constant) is what keeps
                        // the "N actions, then switch type" grouping and the cooldown trigger from
                        // ever disagreeing with each other, even though each streak's N is random.
                        if (task.id in streakBoundaryTaskIds) {
                            Log.w(TAG, "[$accountId] Streak done — cooling down ${settings.cooldownSeconds}s")
                            runnerState.noteAccount(accountId, "Cooling down ${settings.cooldownSeconds}s…")
                            delay(settings.cooldownSeconds * 1_000L)
                        } else {
                            delay(actionDelay(settings))
                        }
                    }
                    result.terminalOrderId?.let { terminalOrderIds += it }
                    if (result.sessionDead) {
                        Log.w(TAG, "[$accountId] Session looks logged out — stopping this batch early instead of repeating the same failure")
                        runnerState.noteAccount(accountId, "Session logged out — re-add this account")
                        sessionDead = true
                        break
                    }
                }

                val unfulfillable = touchedOrderIds - attemptedOrderIds
                if (unfulfillable.isNotEmpty()) {
                    Log.w(TAG, "[$accountId] Releasing ${unfulfillable.size} order(s) it cannot act on: $unfulfillable")
                    unfulfillable.forEach { orderId ->
                        runCatching { taskRepository.releaseUnfulfillableOrder(orderId, accountId) }
                    }
                }

                if (sessionDead) {
                    // Used to back off SESSION_DEAD_BACKOFF_MS and loop back to claim again —
                    // which just repeated the exact same failure forever against a session that
                    // needs the operator's attention, showing up as the runner starting and then
                    // getting stuck cycling instead of actually stopping. The status update above
                    // (CHALLENGE_REQUIRED / SESSION_LOGGED_OUT) is what makes a restart correctly
                    // get blocked at Start time until the operator resolves it.
                    endMessage = "Stopped: Instagram session needs attention — log in via globe icon"
                    break
                } else if (claimed.isNotEmpty() && executed == 0) {
                    // Tasks were claimed locally/remote but all were ineligible for this account.
                    val baseMessage = if (activeAllowedTypes.size < allowedTypes.size) {
                        val maxedTypes = (allowedTypes - activeAllowedTypes).joinToString("/") { it.wireName }
                        Log.w(TAG, "[$accountId] $maxedTypes limit reached for today; checking next orders")
                        PROCESSING_MESSAGE
                    } else {
                        "Checking next orders…"
                    }
                    idleStreak = idleWait(accountId, idleStreak, claimResult.outcome, baseMessage, settings.fetchDelayMs)
                } else if (claimed.isEmpty()) {
                    idleStreak = idleWait(accountId, idleStreak, claimResult.outcome, "Waiting for orders…", settings.fetchDelayMs)
                } else {
                    idleStreak = 0
                }

                // Reward totals move server-side as results land.
                runCatching { walletRepository.refresh() }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Nothing inside the loop body should be able to reach here anymore (each task and
            // each claim is caught individually above) — this is only a last-resort net so a
            // truly unexpected crash tells the user why this account's runner stopped instead of
            // it just going quiet.
            Log.e(TAG, "[$accountId] Runner loop crashed", t)
            endMessage = "Stopped: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            finishAccount(accountId, endMessage)
        }
    }

    /** Random gap between two individual actions, configurable via Settings. */
    private fun actionDelay(settings: AppSettings): Long {
        val lo = settings.actionDelayMinMs
        val hi = settings.actionDelayMaxMs
        return if (lo >= hi) lo else Random.nextLong(lo, hi + 1)
    }

    /** Carries a Random-mode streak's type and remaining count across separate claim calls. */
    private class StreakCursor {
        var type: String? = null
        var remaining: Int = 0
    }

    /**
     * Reorders a claimed batch into same-type chunks, each [perTypeStreakCounts] actions long for
     * that chunk's type — e.g. Follow=5/Like=3 against a Follow+Like batch produces 5 Follows, 3
     * Likes, 5 Follows, ... so the streak/cooldown logic in [launchAccountRun] lands on a real
     * type switch instead of cooling down mid-way through a randomly interleaved mix.
     *
     * [cursor] carries an in-progress streak's type and remaining count over from the previous
     * claim. A claim only ever returns up to `claimBatchSize` tasks, so a streak whose target
     * exceeds how many of that type happen to be in *this* claim used to just get capped at
     * whatever this batch had — silently delivering a shorter streak (or an immediate, unintended
     * switch) than configured. Picking up where the last claim's streak left off, instead of
     * drawing a fresh target every call, is what makes the configured count actually hold across
     * claim boundaries.
     *
     * Returns the reordered list paired with the set of task ids marking the *end* of each
     * chunk — [launchAccountRun] cools down exactly on those ids rather than recounting a
     * separate streak length, which is what keeps the chunk boundaries built here and the
     * cooldown trigger from ever disagreeing. A task id only lands in that set when the streak's
     * target was actually reached — a type simply running out mid-streak in this claim is not a
     * boundary, so no cooldown fires and [cursor] carries the remaining count into the next claim
     * instead.
     *
     * A batch containing only one type (even though [allowedTypes] allows more, e.g. the server
     * happened to hand back all Follows this pass) still gets chunked into that type's own streak
     * length — the account pauses for the cooldown at the end of each one rather than running
     * that type non-stop, satisfying "only one order type available right now" the same as the
     * mixed case.
     */
    private fun buildStreakOrderedBatch(
        tasks: List<TaskEntity>,
        cursor: StreakCursor,
        perTypeStreakCounts: Map<String, Int> = emptyMap()
    ): Pair<List<TaskEntity>, Set<String>> {
        if (tasks.isEmpty()) return tasks to emptySet()

        val queues = tasks.groupByTo(LinkedHashMap()) { it.taskType }.mapValues { it.value.toMutableList() }
        val ordered = ArrayList<TaskEntity>(tasks.size)
        val streakBoundaryTaskIds = mutableSetOf<String>()

        var type = cursor.type
        var remaining = cursor.remaining

        while (true) {
            // Skips straight to consuming below, with `remaining` untouched, exactly when a
            // streak carried over from the previous claim is still in progress *and* this claim
            // actually has more of that type — the resume case the whole cursor exists for.
            if (type == null || remaining <= 0 || queues[type].isNullOrEmpty()) {
                val candidates = queues.keys.filter { queues.getValue(it).isNotEmpty() }
                if (candidates.isEmpty()) break
                // Excludes whatever `type` still holds — the type that just hit its target, or
                // ran dry before it could — so a switch away from it is preferred over an
                // immediate repeat when another type still has work.
                type = candidates.firstOrNull { it != type } ?: candidates.first()
                // The caller always supplies a count for every real task type; this default only
                // guards a theoretical call with an incomplete map.
                remaining = perTypeStreakCounts[type] ?: DEFAULT_STREAK_LENGTH
            }
            val queue = queues.getValue(type!!)
            val task = queue.removeAt(0)
            ordered.add(task)
            remaining--
            if (remaining <= 0) {
                streakBoundaryTaskIds += task.id
                // `type` deliberately stays set to this just-finished type (not nulled) so the
                // next pick above excludes it via `it != type` instead of allowing an immediate
                // repeat; `remaining <= 0` alone is what triggers that next pick to run.
            }
        }
        cursor.type = type
        cursor.remaining = remaining
        return ordered to streakBoundaryTaskIds
    }

    /**
     * Whether [accountId] can perform [task]: its selected mode covers the order type, and it
     * has not already completed this target (Instagram will not let it repeat the action).
     */
    private suspend fun isEligible(
        task: TaskEntity,
        accountId: String,
        allowedTypes: Set<EngagementTaskType>
    ): Boolean {
        val type = EngagementTaskType.from(task.taskType) ?: return false
        if (type !in allowedTypes) return false
        val key = canonicalTargetKey(task.taskType, task.targetId)
        if (completedTargetsPerAccount[accountId]?.contains(key) == true) return false
        if (task.orderId.isNotBlank() && completedOrdersPerAccount[accountId]?.contains(task.orderId) == true) return false
        if (accountLogDao.hasAccountCompletedTarget(accountId, key)) {
            completedTargetsPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }.add(key)
            return false
        }
        return true
    }

    /**
     * Normalizes a task's raw target into a stable dedup key. Two different orders for the same
     * real profile/post can carry different raw URLs (share-link tracking params like `?igsh=`,
     * trailing slashes, casing), which would otherwise defeat an exact-string match and let the
     * same account be dispatched against the same real-world target more than once.
     */
    private fun canonicalTargetKey(taskType: String, targetId: String): String {
        return when (EngagementTaskType.from(taskType)) {
            EngagementTaskType.FOLLOW ->
                // Usernames are case-insensitive on Instagram, so different sources referencing
                // the same account must fold to one key regardless of casing.
                InstagramCrypto.parseUsername(targetId)?.lowercase() ?: targetId.trim().lowercase()
            EngagementTaskType.LIKE, EngagementTaskType.COMMENT, EngagementTaskType.REPOST, EngagementTaskType.SAVE_POST, EngagementTaskType.STORY_VIEW ->
                // Media shortcodes ARE case-sensitive (base64-style alphabet) — lowercasing this
                // used to corrupt the stored value into pointing at a different/nonexistent post,
                // which is exactly what made the Action Log's "View" button 404 ("Sorry, this page
                // isn't available") for Like/Comment/Repost/Save/StoryView entries. The actual
                // engagement mutation was never affected (it resolves from task.targetId directly,
                // not this key) — only this dedup/display value was wrong.
                InstagramCrypto.getCodeFromUrl(targetId)
            null -> targetId.trim().lowercase()
        }
    }

    private suspend fun processSuggestedFollow(accountId: String, sessionCookies: String): ProcessResult {
        runnerState.noteAccount(accountId, "Finding suggested users...")
        updateNotification()

        val completed = completedTargetsPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }
        val suggestions = instagramRepository.fetchSuggestedUsers(
            customCookies = sessionCookies,
            maxNumberToDisplay = SUGGESTED_USERS_BATCH_SIZE
        )
            .shuffled(Random)
        var candidate: InstagramSuggestedUser? = null
        var candidateKey = ""
        for (suggested in suggestions) {
            val key = canonicalTargetKey(EngagementTaskType.FOLLOW.wireName, suggested.username)
            if (key in completed || accountLogDao.hasAccountCompletedTarget(accountId, key)) continue
            candidate = suggested
            candidateKey = key
            break
        }
        val selected = candidate ?: return ProcessResult(ran = false)

        val targetKey = candidateKey
        runnerState.working(accountId, "Follow")
        Log.w(TAG, "[$accountId] No order available; following suggested user @${selected.username} (${selected.id})")

        val result = instagramRepository.follow(
            targetUserId = selected.id,
            targetUsername = selected.username,
            customCookies = sessionCookies
        )
        if (!result.updatedCookies.isNullOrBlank()) {
            runCatching { accountDao.updateSessionCookies(accountId, result.updatedCookies) }
                .onFailure { Log.w(TAG, "[$accountId] Could not persist rotated Instagram cookies", it) }
        }

        completed.add(targetKey)
        val message = if (result.ok) {
            "Followed suggested @${selected.username}"
        } else {
            result.reason ?: "Instagram rejected suggested follow"
        }

        runCatching {
            accountLogDao.insert(AccountLogEntity(
                id = if (result.ok) {
                    "success_${accountId}_${EngagementTaskType.FOLLOW.wireName}_$targetKey"
                } else {
                    UUID.randomUUID().toString()
                },
                accountId = accountId,
                action = EngagementTaskType.FOLLOW.wireName,
                target = targetKey,
                success = result.ok,
                message = message,
                timestampMs = System.currentTimeMillis()
            ))
        }

        return if (result.ok) {
            runnerState.recordSuccess(accountId, 0, message)
            updateNotification()
            ProcessResult(ran = true)
        } else {
            runnerState.recordFailure(accountId, message)
            val isChallenge = isChallengeFailure(result.reason)
            val isLoggedOut = !isChallenge &&
                result.reason?.contains("Session logged out", ignoreCase = true) == true
            if (isChallenge) {
                runCatching { accountDao.updateStatus(accountId, "CHALLENGE_REQUIRED") }
                runnerState.noteAccount(accountId, "Challenge Required")
            } else if (isLoggedOut) {
                runCatching { accountDao.updateStatus(accountId, "SESSION_LOGGED_OUT") }
                runnerState.noteAccount(accountId, "Session logged out - log in via globe icon")
            }
            updateNotification()
            ProcessResult(ran = true, sessionDead = isChallenge || isLoggedOut)
        }
    }

    /** Outcome of [process]: whether the task actually ran (success or failure, vs. skipped),
     *  and whether the failure means the whole session is dead rather than just this one task. */
    private data class ProcessResult(
        val ran: Boolean,
        val sessionDead: Boolean = false,
        val terminalOrderId: String? = null
    )

    /**
     * Performs one order and reports the outcome.
     */
    private suspend fun process(
        task: TaskEntity,
        accountId: String,
        allowedTypes: Set<EngagementTaskType>
    ): ProcessResult {
        val baseLabel = EngagementTaskType.from(task.taskType)?.label ?: task.taskType
        val label = baseLabel
        val targetKey = canonicalTargetKey(task.taskType, task.targetId)
        runnerState.working(accountId, label)
        taskRepository.markRunning(task.id)

        return when (val outcome = executor.execute(task, accountId, allowedTypes)) {
            is TaskOutcome.Success -> {
                val awarded = taskRepository.submitResult(task.id, accountId, true, outcome.message)
                if (awarded is Resource.Error && isNetworkFailure(awarded.message)) {
                    runnerState.noteAccount(accountId, "Network unavailable - will retry when connected")
                    updateNotification()
                    waitForNetwork()
                    return ProcessResult(ran = false)
                }
                val coins = (awarded as? Resource.Success)?.data ?: 0
                val successMessage = outcome.message ?: "Success"
                runnerState.recordSuccess(accountId, coins, successMessage)
                updateNotification()
                // Record target and order completion in memory & DB synchronously
                completedTargetsPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }.add(targetKey)
                if (task.orderId.isNotBlank()) {
                    completedOrdersPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }.add(task.orderId)
                }
                runCatching {
                    accountLogDao.insert(AccountLogEntity(
                        // Deterministic — matches AccountRepository's "restored_" backfill id
                        // scheme — so a second success log for a target this account already
                        // has one for (a re-claimed order whose completion report didn't fully
                        // land, a retry, a canonical-key edge case) replaces that row instead of
                        // adding a new one. A random id here let duplicate success rows pile up
                        // for the same real-world action, and getDailyCountForAccountAction counts
                        // raw rows — that inflated the daily action-limit tally past the number of
                        // targets actually acted on, tripping the daily cap far short of the
                        // configured number.
                        id = "success_${accountId}_${task.taskType}_$targetKey",
                        accountId = accountId,
                        action = task.taskType,
                        target = targetKey,
                        success = true,
                        message = successMessage,
                        timestampMs = System.currentTimeMillis()
                    ))
                }
                val terminalOrderId = if (task.id.startsWith(BACKEND_TASK_PREFIX)) task.orderId else null
                ProcessResult(ran = true, terminalOrderId = terminalOrderId)
            }
            is TaskOutcome.Failure -> {
                if (isNetworkFailure(outcome.reason)) {
                    taskRepository.markStatus(task.id, STATUS_PENDING)
                    runnerState.noteAccount(accountId, "Network unavailable - will retry when connected")
                    updateNotification()
                    waitForNetwork()
                    return ProcessResult(ran = false)
                }
                taskRepository.submitResult(task.id, accountId, false, outcome.reason)
                val failureMessage = outcome.reason ?: "Unknown failure"
                runnerState.recordFailure(accountId, failureMessage)
                // Record target and order interaction in memory & DB synchronously
                completedTargetsPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }.add(targetKey)
                if (task.orderId.isNotBlank()) {
                    completedOrdersPerAccount.getOrPut(accountId) { ConcurrentHashMap.newKeySet() }.add(task.orderId)
                }
                // Write failure log entry synchronously
                runCatching {
                    accountLogDao.insert(AccountLogEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId,
                        action = task.taskType,
                        target = targetKey,
                        success = false,
                        message = failureMessage,
                        timestampMs = System.currentTimeMillis()
                    ))
                }
                // A dead session or challenge fails every subsequent task in the batch —
                // continuing through the other ~19 only wastes calls against a session that is
                // not coming back on its own and makes the "no more actions after some
                // processing" symptom look like a hang instead of what it actually is.
                val isChallenge = isChallengeFailure(outcome.reason)
                val isLoggedOut = !isChallenge &&
                    outcome.reason?.contains("Session logged out", ignoreCase = true) == true
                if (isChallenge) {
                    Log.w(TAG, "[$accountId] Account hit Instagram challenge: ${outcome.reason}")
                    runCatching { accountDao.updateStatus(accountId, "CHALLENGE_REQUIRED") }
                    runnerState.noteAccount(accountId, "Challenge Required")
                } else if (isLoggedOut) {
                    // Persisted (not just logged) so the Start-time gate — TasksViewModel's
                    // meetsOrderStartRequirements callers — blocks a restart until the operator
                    // re-adds the account, the same way CHALLENGE_REQUIRED already does.
                    Log.w(TAG, "[$accountId] Account session logged out: ${outcome.reason}")
                    runCatching { accountDao.updateStatus(accountId, "SESSION_LOGGED_OUT") }
                    runnerState.noteAccount(accountId, "Session logged out — log in via globe icon")
                }
                val sessionDead = isChallenge || isLoggedOut
                val terminalOrderId = if (
                    task.id.startsWith(BACKEND_TASK_PREFIX) &&
                    isTerminalTargetFailure(outcome.reason)
                ) task.orderId else null
                ProcessResult(ran = true, sessionDead = sessionDead, terminalOrderId = terminalOrderId)
            }
            is TaskOutcome.Skipped -> {
                // Not reported — the backend keeps the order and hands it out again later.
                taskRepository.markStatus(task.id, STATUS_SKIPPED)
                runnerState.recordSkip(outcome.reason)
                ProcessResult(ran = false)
            }
        }
    }

    private fun isChallengeFailure(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.lowercase()
        return r.contains("checkpoint") || r.contains("challenge")
    }

    /** User- or system-initiated stop for one account. Cancels its job; teardown happens in [finishAccount]. */
    private fun requestStopAccount(accountId: String, message: String?) {
        clearPersistedRun(accountId)
        val job = runJobs[accountId]
        if (job != null && job.isActive) {
            stopMessages[accountId] = message ?: "Stopped"
            job.cancel()
        } else {
            // Nothing actually running for this account — still clear a stale "running" row.
            runnerState.stoppedAccount(accountId, message)
        }
    }

    private fun requestStopAll(message: String?) {
        runJobs.keys.toList().forEach { requestStopAccount(it, message) }
    }

    /** Single teardown path for one account's job: runs once, whether it drained, was cancelled, or errored. */
    private fun finishAccount(accountId: String, message: String?) {
        runJobs.remove(accountId)
        val explicitStop = stopMessages.remove(accountId)
        if (explicitStop != null || message != null) {
            clearPersistedRun(accountId)
        }
        val finalMessage = explicitStop ?: message ?: "Stopped"
        runnerState.stoppedAccount(accountId, finalMessage)

        if (runJobs.isEmpty()) {
            if (!stoppingFromTaskRemoved) stopRunnerService()
        } else {
            acquireRunnerWakeLock()
            updateNotification()
        }
    }

    // ---------- Notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.RUNNER_CHANNEL_ID,
                "FeedPilot Task Runner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while background engagement tasks are running."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopRunnerService() {
        releaseRunnerWakeLock()
        stopForegroundCompat()
        runCatching {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }
        stopSelf()
    }

    private fun acquireRunnerWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:TaskRunner").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { Log.w(TAG, "Could not acquire task runner wake lock", it) }
        }
        // Acquire a high-performance Wi-Fi lock so the radio stays fully alive while the screen
        // is locked. WIFI_MODE_FULL_HIGH_PERF prevents the chip from entering power-save mode,
        // which would drop NET_CAPABILITY_VALIDATED and stall every account in waitForNetwork().
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:TaskRunnerWifi").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.w(TAG, "Could not acquire task runner wifi lock", it) }
            }
        }
    }

    private fun releaseRunnerWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) {
            runCatching { lock.release() }
                .onFailure { Log.w(TAG, "Could not release task runner wake lock", it) }
        }
        val wifi = wifiLock
        wifiLock = null
        if (wifi?.isHeld == true) {
            runCatching { wifi.release() }
                .onFailure { Log.w(TAG, "Could not release task runner wifi lock", it) }
        }
    }

    /** Every running account gets its own independent loop, so the notification just summarizes
     *  how many are active rather than any single one's current step. */
    private fun notificationText(): String {
        val running = runJobs.size
        val coins = runnerState.stats.value.coinsEarned
        val suffix = if (coins > 0) " - +$coins coins" else ""
        return when (running) {
            0 -> "Starting…"
            1 -> "Running 1 account$suffix"
            else -> "Running $running accounts$suffix"
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TaskRunnerService::class.java).apply {
            action = ACTION_STOP
        }
        val stop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.RUNNER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("FeedPilot Task Runner")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification() {
        // Posting is best-effort: on Android 13+ the user may have denied the notification permission.
        runCatching {
            NotificationManagerCompat.from(this).notify(notificationId, buildNotification(notificationText()))
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun waitForNetwork() {
        while (currentCoroutineContext().isActive && !isNetworkAvailable()) {
            delay(NETWORK_RETRY_DELAY_MS)
        }
    }

    /**
     * Waits out one non-productive pass (empty batch, or a claimed batch nothing was eligible
     * for) — picks a message and delay from this account's own [outcome] (the result of the very
     * claim call that just came back empty) so a rate-limited or network-broken claim reads
     * differently from a genuinely empty queue, and grows the delay via [growingBackoff] on
     * repeated non-productive passes instead of retrying at the same fixed cadence forever. A
     * [ClaimOutcome.RateLimited] response trusts the server's own Retry-After instead of
     * compounding it further.
     *
     * [outcome] must come from this account's own claim result, not a value shared across
     * accounts — this repository/service is shared by every signed-in account's independent
     * runner loop on this clone, and reading a cross-account "last outcome" let one account's
     * rate-limit or error show up on a sibling account that had nothing to do with it.
     *
     * Returns the next [idleStreak] value — callers must feed it back in and reset it to 0 the
     * moment a pass actually executes something.
     */
    private suspend fun idleWait(accountId: String, idleStreak: Int, outcome: ClaimOutcome, baseMessage: String, fetchDelayMs: Long): Int {
        // The countdown itself is rendered live by the UI off AccountRunnerStats.waitUntilMs
        // (see TasksScreen's status banner) — these messages deliberately don't bake in a static
        // second count, or the two would drift apart and double up ("...12s (11s)").
        val (message, waitMs) = when (outcome) {
            is ClaimOutcome.RateLimited ->
                "Rate limited by the server" to (outcome.retryAfterSeconds ?: 30L).coerceAtLeast(5L) * 1_000L
            is ClaimOutcome.NetworkError ->
                "Connection issue" to growingBackoff(fetchDelayMs, idleStreak)
            is ClaimOutcome.ServerError ->
                "Server error (${outcome.code})" to growingBackoff(fetchDelayMs, idleStreak)
            is ClaimOutcome.Success ->
                // An empty queue is normal — a new order can arrive at any moment, so poll at a
                // flat fetchDelayMs cadence rather than compounding delays that would slow pickup.
                baseMessage to fetchDelayMs.coerceIn(1_000L, 5_000L)
        }
        runnerState.noteAccountWaiting(accountId, message, waitMs)
        updateNotification()
        delay(waitMs)
        return idleStreak + 1
    }

    /** Doubles [base] per consecutive non-productive pass, capped at 3s — used only for actual
     * errors (network/server) to back off a broken endpoint, not for an empty-but-healthy queue. */
    private fun growingBackoff(base: Long, streak: Int): Long =
        (base * (1L shl streak.coerceIn(0, 3))).coerceAtMost(3_000L)

    companion object {
        private const val TAG = "TaskRunnerService"
        private const val NOTIFICATION_ID_BASE = 4201
        private const val STATUS_PENDING = "Pending"
        private const val STATUS_SKIPPED = "Skipped"
        private const val PROCESSING_MESSAGE = "Processing..."
        private const val NETWORK_RETRY_DELAY_MS = 10_000L
        /** Matches the id prefix TaskRepository stamps on tasks fanned out from a backend order. */
        private const val BACKEND_TASK_PREFIX = "backend_order_"

        private const val BATCH_SIZE = 20
        private const val SUGGESTED_USERS_BATCH_SIZE = 50
        /** Fallback streak length for buildStreakOrderedBatch — see its doc comment. */
        private const val DEFAULT_STREAK_LENGTH = 5

        const val ACTION_START = "com.feedpilot.client.action.START_RUNNER"
        const val ACTION_STOP = "com.feedpilot.client.action.STOP_RUNNER"
        private const val EXTRA_ACCOUNT_IDS = "account_ids"
        private const val EXTRA_TASK_TYPES = "task_types"
        private const val EXTRA_ACCOUNT_MODES = "account_modes"
        private const val EXTRA_SINGLE_TASKING = "single_tasking"
        private const val PREFS_NAME = "task_runner_service"
        private const val PREF_RUNNING = "running_accounts"

        /**
         * Starts (or, for an account already running, leaves untouched) an independent loop per
         * account in [accountIds]. Safe to call while other accounts are already running —
         * see the class doc.
         */
        fun start(
            context: Context,
            accountIds: List<String>,
            accountModes: Map<String, Set<EngagementTaskType>>,
            singleTasking: Boolean
        ) {
            val intent = Intent(context, TaskRunnerService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_ACCOUNT_IDS, ArrayList(accountIds))
                val modesBundle = android.os.Bundle()
                accountModes.forEach { (accId, types) ->
                    modesBundle.putStringArrayList(accId, ArrayList(types.map { it.wireName }))
                }
                putExtra(EXTRA_ACCOUNT_MODES, modesBundle)
                putExtra(EXTRA_SINGLE_TASKING, singleTasking)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { e ->
                Log.e(TAG, "Failed to start TaskRunnerService foreground service", e)
            }
        }

        /** Starts the runner for [accountIds], limited to [taskTypes] (empty = every supported type). */
        fun start(
            context: Context,
            accountIds: List<String>,
            taskTypes: Set<EngagementTaskType>,
            singleTasking: Boolean
        ) {
            val defaultModes = accountIds.associateWith { taskTypes }
            start(context, accountIds, defaultModes, singleTasking)
        }

        /** Stops every running account, or just [accountIds] when given. */
        fun stop(context: Context, accountIds: List<String>? = null) {
            val intent = Intent(context, TaskRunnerService::class.java).setAction(ACTION_STOP)
            if (!accountIds.isNullOrEmpty()) {
                intent.putStringArrayListExtra(EXTRA_ACCOUNT_IDS, ArrayList(accountIds))
            }
            context.startService(intent)
        }
    }

    private fun isTargetNotFound(message: String?): Boolean =
        !message.isNullOrBlank() &&
            (message.contains("user not found", ignoreCase = true) ||
                message.contains("account not found", ignoreCase = true) ||
                message.contains("could not resolve", ignoreCase = true) ||
                message.contains("post not found", ignoreCase = true) ||
                message.contains("media not found", ignoreCase = true) ||
                message.contains("unavailable", ignoreCase = true) ||
                message.contains("404", ignoreCase = true))

    private fun isTerminalTargetFailure(message: String?): Boolean =
        isTargetNotFound(message) ||
            (!message.isNullOrBlank() && message.contains("private account", ignoreCase = true))

    private fun isNetworkFailure(message: String?): Boolean =
        !message.isNullOrBlank() &&
            (message.contains("network error", ignoreCase = true) ||
                message.contains("no internet", ignoreCase = true) ||
                message.contains("internet connection", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("failed to connect", ignoreCase = true) ||
                message.contains("could not reach", ignoreCase = true) ||
                message.contains("server unreachable", ignoreCase = true) ||
                message.contains("unable to resolve host", ignoreCase = true) ||
                message.contains("unknownhost", ignoreCase = true) ||
                message.contains("socket", ignoreCase = true) ||
                message.contains("connection reset", ignoreCase = true))

    private fun persistRun(accountId: String, allowedTypes: Set<EngagementTaskType>) {
        val runs = restorePersistedRuns().toMutableMap()
        runs[accountId] = allowedTypes
        savePersistedRuns(runs)
    }

    private fun clearPersistedRun(accountId: String) {
        val runs = restorePersistedRuns().toMutableMap()
        if (runs.remove(accountId) != null) savePersistedRuns(runs)
    }

    private fun clearPersistedRuns() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_RUNNING)
            .apply()
    }

    private fun restorePersistedRuns(): Map<String, Set<EngagementTaskType>> {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RUNNING, null)
            ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { accountId ->
                val arr = root.optJSONArray(accountId) ?: JSONArray()
                buildSet {
                    for (i in 0 until arr.length()) {
                        EngagementTaskType.from(arr.optString(i))?.let(::add)
                    }
                    if (isEmpty()) addAll(executor.supportedTypes)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun savePersistedRuns(runs: Map<String, Set<EngagementTaskType>>) {
        val root = JSONObject()
        runs.forEach { (accountId, types) ->
            root.put(accountId, JSONArray(types.map { it.wireName }))
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_RUNNING, root.toString())
            .apply()
    }

    private fun getStartOfTodayMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
