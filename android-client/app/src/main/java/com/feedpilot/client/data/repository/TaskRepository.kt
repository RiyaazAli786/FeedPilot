package com.feedpilot.client.data.repository

import android.util.Log
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.LiveCoinSyncManager
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.common.extractInstagramHandle
import com.feedpilot.client.common.isUpgradedWithin24h
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.OrderHistoryEntity
import com.feedpilot.client.data.local.OrderSource
import com.feedpilot.client.data.local.TaskDao
import com.feedpilot.client.data.local.TaskEntity
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.HanumanSmmClient
import com.feedpilot.client.data.remote.SmmAdminOrder
import com.feedpilot.client.data.remote.dto.ManualActionResultRequest
import com.feedpilot.client.data.remote.dto.TaskResultRequest
import com.feedpilot.client.data.toEntity
import com.feedpilot.client.task.EngagementTaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val api: ApiService,
    private val taskDao: TaskDao,
    private val accountDao: AccountDao,
    private val walletRepository: WalletRepository,
    private val liveCoinSyncManager: LiveCoinSyncManager,
    private val hanumanSmmClient: HanumanSmmClient,
    private val orderHistoryRepository: OrderHistoryRepository,
    private val appOrderRepository: AppOrderRepository,
    private val instagramRepository: InstagramRepository,
    private val sessionGate: AccountSessionGate,
    private val deviceIdentity: DeviceIdentity,
    private val settingsRepository: SettingsRepository
) {
    /**
     * Clone-specific claim holder. The physical device id is shared by cloned packages on the
     * same phone, so order locks use appId + deviceId to keep each clone independent.
     */
    private fun deviceId(): String =
        deviceIdentity.stableAppInstallationId

    /**
     * Serializes [settleOrder] per orderId. Orders aren't scoped to one account — any account
     * running on this device can pick up units of the same order — and [TaskDao.completedCountForOrder]/
     * [TaskDao.markOrderTasksReported] are device-wide, not account-scoped. Two accounts finishing
     * a unit of the *same* order around the same moment (the common case once a second account
     * starts: both freshly claimed accounts tend to land on the same newly-available orders) used
     * to each read completedCountForOrder before either had flipped its rows to 'Reported', each
     * compute an absolute `total` from that same stale snapshot, and send it to the backend's
     * monotonic Progress endpoint — whichever report lands second sends a `total` the backend has
     * already met or exceeded, so its own genuinely-new completions score `newlyDone = 0` and get
     * awarded zero coins, yet still get marked 'Reported' and are never retried. A per-order lock
     * held across the whole settle (read → report → mark-reported) closes that window: the second
     * account's settle call now waits until the first's markOrderTasksReported has actually run, so
     * its own completedCountForOrder read starts from an up-to-date baseline instead of a stale one.
     */
    private val orderSettleMutexes = ConcurrentHashMap<String, Mutex>()
    private fun orderSettleMutex(orderId: String): Mutex =
        // computeIfAbsent, not the Kotlin `getOrPut` extension: getOrPut is a plain get-then-put
        // on the Map interface, so it is not itself atomic even backed by a ConcurrentHashMap —
        // two accounts racing on the same brand-new orderId can each observe a null lookup, each
        // mint their own Mutex(), and each lock only their own instance while just one wins the
        // put, leaving both settleOrder calls to run unlocked against each other. That reopened
        // exactly the stale-baseline race this mutex exists to close (see the field doc above).
        // computeIfAbsent is a genuine atomic operation on ConcurrentHashMap: every caller for a
        // given key is guaranteed to observe and lock the very same Mutex instance.
        orderSettleMutexes.computeIfAbsent(orderId) { Mutex() }

    val tasks: Flow<List<TaskEntity>> = taskDao.observeAll()
    val completedCount: Flow<Int> = taskDao.observeCompletedCount()
    val runningCount: Flow<Int> = taskDao.observeRunningCount()

    /**
     * What a single [claimPending] call actually returned, paired with the tasks. Handed straight
     * back to the caller instead of published on a repository-wide flow — this repository is a
     * singleton shared by every signed-in account's independent runner loop on this clone (see
     * [deviceId]), and a shared "last outcome" meant one account's rate-limit/network/server
     * result could be read by a different account's idle-wait, showing that account a message
     * that had nothing to do with its own most recent claim attempt.
     */
    data class ClaimResult(val tasks: List<TaskEntity>, val outcome: ClaimOutcome)
    private val recentlyReleasedOrderIdsPerAccount = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>>()

    private fun isRecentlyReleased(accountId: String?, orderId: String): Boolean {
        if (accountId.isNullOrBlank()) return false
        val map = recentlyReleasedOrderIdsPerAccount[accountId] ?: return false
        val releasedAt = map[orderId] ?: return false
        // 3s TTL: the backend already atomically flips the claim back to Pending on release=true,
        // so a long local exclusion window only delays re-claiming when the pool is small.
        if (System.currentTimeMillis() - releasedAt > 3_000L) {
            map.remove(orderId)
            return false
        }
        return true
    }

    /**
     * Fetches orders queued for processing from the SMM Admin pull API
     * (https://smmorigin.com/adminapi/v2/orders/pull) — separately for the Follow and Like
     * service IDs — as well as the backend server endpoint. Used for a full manual/periodic
     * refresh (pull-to-refresh, [com.feedpilot.client.worker.SyncWorker]); the runner loop's own
     * per-account polling goes through [claimPending] instead, which does not always repeat the
     * bulk pull below — see its doc comment for why.
     */
    suspend fun downloadPending(limit: Int = 20): Resource<List<TaskEntity>> {
        // Every task here is work for a signed-in account to carry out on Instagram. With none
        // signed in, claiming any of it would only strand orders that another device could serve,
        // so nothing is pulled — not from the admin queue, the order pool, or the backend.
        if (!sessionGate.isSignedIn()) return Resource.Success(emptyList())
        val bulk = pullBulkSources(limit)
        val claimedFromBackend = claimFromBackend(null, null, limit).tasks
        return when (bulk) {
            is Resource.Success -> Resource.Success(bulk.data + claimedFromBackend)
            else -> bulk
        }
    }

    /**
     * Pulls from the two *bulk* sources — the SMM admin queue (up to [PULL_BATCH_SIZE] orders
     * per service id) and the legacy backend task queue. Both can hand back far more rows than
     * any one account needs in a pass, so [claimPending] only bothers calling this when the
     * local pool is actually running low — unlike [claimFromBackend], which is cheap and always
     * attempted regardless.
     */
    private suspend fun pullBulkSources(limit: Int): Resource<List<TaskEntity>> = try {
        val tasksList = mutableListOf<TaskEntity>()

        // 1. Pull live orders per service ID so each order's task type is known up front.
        //    These are the admin queue's own service IDs — deliberately NOT the provider row's
        //    followServiceId/likeServiceId, which belong to the user panel we buy from and
        //    number their services differently.
        try {
            val pulled = mutableListOf<Pair<String, SmmAdminOrder>>()
            for ((taskType, serviceId) in listOf(
                TASK_TYPE_FOLLOW to HanumanSmmClient.PULL_FOLLOW_SERVICE_ID,
                TASK_TYPE_LIKE to HanumanSmmClient.PULL_LIKE_SERVICE_ID,
                TASK_TYPE_REPOST to HanumanSmmClient.PULL_REPOST_SERVICE_ID,
                TASK_TYPE_SAVE to HanumanSmmClient.PULL_SAVE_SERVICE_ID,
                TASK_TYPE_COMMENT to HanumanSmmClient.PULL_COMMENT_RANDOM_SERVICE_ID,
                TASK_TYPE_COMMENT to HanumanSmmClient.PULL_COMMENT_CUSTOM_SERVICE_ID,
                TASK_TYPE_STORY to HanumanSmmClient.PULL_STORY_VIEW_SERVICE_ID
            )) {
                if (serviceId.isBlank()) continue
                val orders = hanumanSmmClient.pullOrders(
                    serviceIds = serviceId,
                    limit = PULL_BATCH_SIZE
                )
                orders.forEach { pulled.add(taskType to it) }
            }

            val externalLogs = mutableListOf<OrderHistoryEntity>()

            for ((taskType, adminOrder) in pulled.distinctBy { it.second.id }) {
                if (adminOrder.id.isBlank() || adminOrder.link.isBlank()) continue

                // Mirror each pulled order into history, tagged EXTERNAL so it is
                // distinguishable from orders this app's user paid to place.
                externalLogs.add(
                    OrderHistoryEntity(
                        id = "smm_ext_${adminOrder.id}",
                        smmOrderId = adminOrder.id,
                        providerNickname = "SMM Origin (Admin Queue)",
                        providerUrl = HanumanSmmClient.ADMIN_PULL_URL,
                        targetUsername = extractInstagramHandle(adminOrder.link),
                        orderType = taskType,
                        quantity = adminOrder.quantity,
                        coinsSpent = 0L,
                        status = adminOrder.status.ifBlank { "Pending" },
                        source = OrderSource.EXTERNAL.name
                    )
                )

                val needed = maxOf(1, adminOrder.remains)
                val commentsList = adminOrder.comments?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

                for (i in 0 until minOf(needed, MAX_TASKS_PER_ORDER)) {
                    val commentText = if (commentsList.isNotEmpty()) {
                        commentsList[i % commentsList.size]
                    } else null

                    tasksList.add(
                        TaskEntity(
                            id = "smm_order_${adminOrder.id}_$i",
                            orderId = adminOrder.id,
                            accountId = null,
                            taskType = taskType,
                            targetId = adminOrder.link,
                            status = "Pending",
                            retryCount = 0,
                            rewardCoins = 1,
                            startCount = 0,
                            commentText = commentText,
                            serviceId = adminOrder.serviceId,
                            createdAt = System.currentTimeMillis().toString()
                        )
                    )
                }
            }

            orderHistoryRepository.logExternalOrders(externalLogs)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Legacy backend task queue.
        try {
            val remote = api.getPendingTasks(limit).map { it.toEntity() }
            tasksList.addAll(remote)
        } catch (_: Exception) {}

        if (tasksList.isNotEmpty()) {
            taskDao.insertNew(tasksList)
        }

        Resource.Success(tasksList)
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("Failed to download tasks. The app will retry automatically."), t)
    }

    private data class BackendClaimResult(val outcome: ClaimOutcome, val tasks: List<TaskEntity>)

    /**
     * Claims a small lease window straight from our own backend — already capped at
     * [BACKEND_MAX_CLAIM_BATCH] and per-device rate-limited server-side, so unlike
     * [pullBulkSources] this is cheap enough to attempt on every single [claimPending] pass. That
     * matters: this is the one source whose freshness an operator actually notices — an order
     * placed moments ago needs to show up on the very next poll, not only once the local pool
     * (which can be full of rows this particular account cannot act on: wrong order type for its
     * mode, or targets it already completed) happens to run dry.
     */
    private suspend fun claimFromBackend(accountId: String?, allowedTypes: Set<String>?, limit: Int): BackendClaimResult {
        val excluded = if (!accountId.isNullOrBlank()) {
            recentlyReleasedOrderIdsPerAccount[accountId]?.keys?.toList().orEmpty()
        } else {
            emptyList()
        }
        val account = if (!accountId.isNullOrBlank()) {
            runCatching { accountDao.getById(accountId) }.getOrNull()
        } else null
        val accountHandle = account?.username?.takeIf { it.isNotBlank() } ?: accountId
        val requestedTypes = allowedTypes?.toList()?.filter { it.isNotBlank() }?.distinct()
        val outcome = try {
            if (requestedTypes != null && requestedTypes.size > 1) {
                val perTypeBatchSize = maxOf(1, kotlin.math.ceil(BACKEND_MAX_CLAIM_BATCH.toDouble() / requestedTypes.size).toInt())
                // Each type is claimed via its own request so one popular type (e.g. Follow)
                // can't crowd the others out of a shared batch — but these must run concurrently,
                // not one after another. Awaiting them sequentially multiplied a single claim
                // pass's round-trip latency by the number of allowed types (up to 6x in Random
                // mode), which is what made claiming visibly stall on "Checking next orders…".
                // An order's type is fixed, so results across these calls can never overlap —
                // no cross-request de-dup exclusion is needed the way the sequential version had.
                val perTypeOutcomes = coroutineScope {
                    requestedTypes.map { type ->
                        async {
                            appOrderRepository.claimOrders(
                                deviceId = deviceId(),
                                batchSize = perTypeBatchSize,
                                excludeOrderIds = excluded,
                                accountId = accountId,
                                accountHandle = accountHandle,
                                taskTypes = listOf(type)
                            )
                        }
                    }.map { it.await() }
                }

                val claimed = perTypeOutcomes.filterIsInstance<ClaimOutcome.Success>()
                    .flatMap { it.orders }
                    .distinctBy { it.id }
                    .take(BACKEND_MAX_CLAIM_BATCH)

                if (claimed.isNotEmpty()) {
                    ClaimOutcome.Success(claimed)
                } else {
                    perTypeOutcomes.firstOrNull { it !is ClaimOutcome.Success } ?: ClaimOutcome.Success(emptyList())
                }
            } else {
                appOrderRepository.claimOrders(
                    deviceId = deviceId(),
                    batchSize = BACKEND_MAX_CLAIM_BATCH,
                    excludeOrderIds = excluded,
                    accountId = accountId,
                    accountHandle = accountHandle,
                    taskTypes = requestedTypes
                )
            }
        } catch (t: Throwable) {
            ClaimOutcome.NetworkError(t.message)
        }
        val claimed = (outcome as? ClaimOutcome.Success)?.orders.orEmpty()
        if (claimed.isEmpty()) return BackendClaimResult(outcome, emptyList())

        val tasksList = mutableListOf<TaskEntity>()
        try {
            val externalLogs = mutableListOf<OrderHistoryEntity>()

            for (order in claimed) {
                if (isRecentlyReleased(accountId, order.id)) {
                    Log.w(TAG, "Skipping recently released unfulfillable order ${order.id} for account $accountId")
                    continue
                }
                val taskType = order.orderType

                // Only the outstanding actions are queued — an order part-way through must
                // not be re-run from zero.
                val remaining = if (order.remaining > 0) {
                    order.remaining
                } else {
                    order.quantity - order.completedCount
                }
                if (remaining <= 0) continue

                val commentsList = order.commentText?.split("\n", "\r\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                for (i in 0 until minOf(remaining, MAX_TASKS_PER_ORDER)) {
                    val singleComment = if (commentsList.isNotEmpty()) {
                        commentsList[(order.completedCount + i) % commentsList.size]
                    } else {
                        order.commentText
                    }
                    tasksList.add(
                        TaskEntity(
                            id = "backend_order_${order.id}_${order.completedCount + i}",
                            orderId = order.id,
                            accountId = null,
                            taskType = taskType,
                            targetId = order.targetUrl,
                            status = "Pending",
                            retryCount = 0,
                            rewardCoins = 1,
                            startCount = order.startCount,
                            commentText = singleComment,
                            serviceId = order.providerServiceId,
                            createdAt = System.currentTimeMillis().toString()
                        )
                    )
                }

                externalLogs.add(
                    OrderHistoryEntity(
                        id = "backend_ext_${order.id}",
                        smmOrderId = order.id,
                        providerNickname = "FeedPilot Backend",
                        providerUrl = "api/orders/processing",
                        targetUsername = extractInstagramHandle(order.targetUrl).ifBlank {
                            order.targetUsername?.trim().orEmpty()
                        },
                        orderType = taskType,
                        quantity = order.quantity,
                        completedCount = order.completedCount,
                        coinsSpent = 0L,
                        status = "Processing",
                        source = OrderSource.EXTERNAL.name
                    )
                )
            }

            if (tasksList.isNotEmpty()) taskDao.insertNew(tasksList)
            orderHistoryRepository.logExternalOrders(externalLogs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return BackendClaimResult(outcome, tasksList)
    }

    suspend fun claimPending(accountId: String, allowedTypes: Set<String>? = null, limit: Int = 20): ClaimResult {
        if (!sessionGate.isSignedIn()) return ClaimResult(emptyList(), ClaimOutcome.Success(emptyList()))

        val local = taskDao.pending(limit)
        if (local.size < limit) {
            pullBulkSources(limit)
        }
        val outcome = claimFromBackend(accountId, allowedTypes, limit).outcome

        val claimed = taskDao.claimForAccount(accountId, limit, allowedTypes?.toList())
        return ClaimResult(claimed, outcome)
    }

    suspend fun pendingCount(): Int = taskDao.pendingCount()

    suspend fun releaseUnfulfillableOrder(orderId: String, accountId: String? = null) {
        if (!accountId.isNullOrBlank()) {
            recentlyReleasedOrderIdsPerAccount
                .getOrPut(accountId) { java.util.concurrent.ConcurrentHashMap() }[orderId] = System.currentTimeMillis()
        }
        runCatching { appOrderRepository.releaseOrder(orderId, deviceId()) }
        runCatching { taskDao.deleteUnprocessedForOrder(orderId) }
    }

    /**
     * Releases backend claims still held by local account runners that are being force-stopped
     * by the app task being cleared from Recents. Completed-but-not-reported rows are left alone:
     * those represent work that may already have happened on Instagram and should be settled by
     * the normal progress path instead of silently discarded as a bare release.
     */
    suspend fun releaseActiveClaimsHeldLocally(): Int {
        val orderIds = taskDao.activeBackendOrderIdsHeldLocally().filter { it.isNotBlank() }.distinct()
        var released = 0
        for (orderId in orderIds) {
            if (runCatching { appOrderRepository.releaseOrder(orderId, deviceId()) }.getOrDefault(false)) {
                released++
            }
            runCatching { taskDao.skipAllPendingForOrder(orderId) }
        }
        return released
    }

    /**
     * Reports a manually retried Action Log entry (TasksViewModel.retryFailedActionLog) — there is
     * no backend task/order behind an old log entry, so this reports the action fresh instead of
     * reconciling against one. Unlike the old local-only credit this replaced (a direct
     * `walletRepository.addCoins` call with no backend record at all, permanently unreconciled
     * against the server — see git history), the award now comes back from
     * RunnerSettingsStore.GetActionCoinReward on the backend, the same source of truth every
     * other completion is priced from, and lands through the same confirmed-only credit path.
     * On any failure (network, auth, validation) this credits nothing — a manual retry that can't
     * reach the backend simply isn't paid, rather than paying locally and hoping it reconciles.
     */
    suspend fun submitManualActionResult(
        accountId: String,
        taskType: String,
        target: String,
        message: String? = null,
        idempotencyKey: String? = null
    ): Resource<Int> = try {
        val key = idempotencyKey ?: "manual:${accountId}:${taskType}:${target}".lowercase()
        val response = api.submitManualActionResult(
            ManualActionResultRequest(accountId, taskType, target, message, key)
        )
        walletRepository.applyConfirmedBalance(response.walletBalance)
        response.accountCoinsEarned?.let { accountDao.setCoinsEarned(accountId, it) }
            ?: run {
                if (response.coinsAwarded != 0) accountDao.incrementCoinsEarned(accountId, response.coinsAwarded.toLong())
            }
        Resource.Success(response.coinsAwarded)
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("Failed to submit retry result"), t)
    }

    /**
     * Single-task reconciliation, for the legacy queue only — every legacy report is exactly one
     * task's worth (`POST /api/tasks/result` never batches), so its one pending-earning row and
     * this one [backendAwarded] figure are always a matched pair. Order-backed tasks go through
     * [reconcilePendingBatch] instead: one settle report there can confirm several accumulated
     * units at once, so reconciliation has to cover every pending row it actually paid for, not
     * just whichever single task triggered the call.
     *
     * [locallyCredited] is what [applyLocalReward] already applied, computed from this device's
     * own RunnerSettings cache (synced at most every [SettingsRepository]-defined interval, so it
     * can briefly lag a dashboard price change). [backendAwarded] is what RunnerSettingsStore
     * actually priced this same completion at, read live from the DB — reconcile any gap now
     * instead of leaving a stale local price as a silent, permanent over/under-credit.
     *
     * The wallet side goes through [WalletRepository.creditConfirmedEarning] rather than a plain
     * clear-then-reconcile — clearing [taskId]'s pending row and crediting the wallet base total
     * used to be two separate calls, which left a window where the reward was in neither bucket.
     * Usually too brief to notice, but wide enough to show up as a visible drop right after a
     * credit when several accounts are settling tasks concurrently and contending for the same
     * wallet mutation lock.
     */
    private suspend fun applyConfirmedReward(accountId: String, backendAwarded: Long, walletBalance: Long? = null) {
        walletBalance?.let { walletRepository.applyConfirmedBalance(it) }
        if (backendAwarded != 0L) accountDao.incrementCoinsEarned(accountId, backendAwarded)
    }

    private suspend fun reconcileReward(taskId: String, accountId: String, locallyCredited: Long, backendAwarded: Long) {
        walletRepository.clearPendingEarning(taskId)
    }

    /**
     * Batch reconciliation for order-backed tasks, called from inside [settleOrderLocked] right
     * after one successful report — that single report's [confirmedCoins] can represent several
     * of this account's own units completed since the last report (`doneLocally` there is a
     * running count), so every pending-earning row for this exact (orderId, accountId) pair —
     * not just whichever task happened to trigger the call — is summed, reconciled against the
     * confirmed total by one delta, and cleared together.
     */
    private suspend fun reconcilePendingBatch(
        orderId: String,
        accountId: String,
        confirmedCoins: Long,
        walletBalance: Long? = null,
        accountCoinsEarned: Long? = null
    ) {
        val pending = walletRepository.pendingEarningsForOrderAndAccount(orderId, accountId)
        val locallyCredited = pending.sumOf { it.rewardCoins }
        walletBalance?.let { walletRepository.applyConfirmedBalance(it) }
            ?: run {
                val delta = confirmedCoins - locallyCredited
                if (delta != 0L) walletRepository.reconcileCoins(delta)
            }
        when {
            accountCoinsEarned != null -> accountDao.setCoinsEarned(accountId, accountCoinsEarned)
            else -> {
                val delta = confirmedCoins - locallyCredited
                if (delta != 0L) accountDao.incrementCoinsEarned(accountId, delta)
            }
        }
        walletRepository.clearPendingEarningsForOrderAndAccount(orderId, accountId)
    }

    private suspend fun reconcilePendingTask(
        taskId: String,
        accountId: String,
        confirmedCoins: Long,
        walletBalance: Long? = null,
        accountCoinsEarned: Long? = null
    ) {
        val pending = walletRepository.pendingEarning(taskId)
        val locallyCredited = pending?.rewardCoins ?: 0L
        walletBalance?.let { walletRepository.applyConfirmedBalance(it) }
            ?: run {
                if (confirmedCoins > 0L) {
                    walletRepository.creditConfirmedEarning(taskId, locallyCredited, confirmedCoins)
                }
            }
        when {
            accountCoinsEarned != null -> accountDao.setCoinsEarned(accountId, accountCoinsEarned)
            confirmedCoins > 0L -> {
                val delta = confirmedCoins - locallyCredited
                if (delta != 0L) accountDao.incrementCoinsEarned(accountId, delta)
            }
        }
        if (confirmedCoins > 0L) {
            walletRepository.clearPendingEarning(taskId)
        }
    }

    /**
     * Re-attempts server settlement for every locally-credited reward the backend hasn't
     * confirmed yet (see [WalletRepository.pendingEarnings]) — the actual push-to-server for a
     * reward whose settle call failed the first time (offline, backend briefly down, app killed
     * mid-call). The local credit itself is never at risk either way, since [applyLocalReward]
     * already wrote it durably to Room the instant the action completed; without this, though, a
     * reward that failed to settle once had no other path back to the server, since nothing else
     * ever revisits an already-'Completed'/'Reported' local task row on its own. Meant to be
     * called periodically (see TaskRunnerService's 10s sync loop) — a row that still fails here
     * simply gets tried again on the next pass.
     */
    suspend fun flushPendingEarnings() {
        val pending = runCatching { walletRepository.pendingEarnings() }.getOrNull().orEmpty()
        if (pending.isEmpty()) return

        val (orderBacked, nonOrderBacked) = pending.partition { it.taskId.startsWith(BACKEND_TASK_PREFIX) }
        val (smmBacked, legacy) = nonOrderBacked.partition { it.taskId.startsWith(SMM_ORDER_PREFIX) }

        // One retry per distinct (orderId, accountId) pair is enough — a successful report
        // reconciles every pending row for that pair at once (see reconcilePendingBatch), so
        // retrying per-row here would just resend the same already-resolved report redundantly.
        orderBacked.forEach { p ->
            val task = runCatching { taskDao.getById(p.taskId) }.getOrNull() ?: return@forEach
            runCatching { settleOrder(task, p.accountId, null) }
        }

        smmBacked.forEach { p ->
            val task = runCatching { taskDao.getById(p.taskId) }.getOrNull() ?: return@forEach
            val response = submitManualTaskResultWithRetry(task, p.accountId, null) ?: return@forEach
            reconcilePendingTask(
                taskId = p.taskId,
                accountId = p.accountId,
                confirmedCoins = response.coinsAwarded.toLong(),
                walletBalance = response.walletBalance,
                accountCoinsEarned = response.accountCoinsEarned
            )
            if (response.coinsAwarded > 0) runCatching { taskDao.markTaskReported(p.taskId) }
        }

        legacy.forEach { p ->
            val response = runCatching {
                api.submitTaskResult(TaskResultRequest(p.taskId, p.accountId, true, null))
            }.getOrNull() ?: return@forEach
            reconcileReward(p.taskId, p.accountId, p.rewardCoins, response.coinsAwarded.toLong())
        }
    }

    /**
     * Applies this completion's dashboard-priced reward to the account card and wallet
     * immediately, from this device's own cached RunnerSettings — before any network call, so
     * the UI reflects it the instant Instagram confirms the action instead of waiting on (or
     * racing over) a shared server-side settle call. Persisted via [WalletRepository.addPendingEarning]
     * so the credit survives process death; [reconcileReward] corrects any drift against the
     * backend's own live-priced amount once the settle call actually confirms it. Returns 0 (and
     * applies nothing) for a task type this repository doesn't know how to price, or a task with
     * no order context to persist the pending record against.
     */
    private suspend fun applyLocalReward(task: TaskEntity, accountId: String): Long {
        if (task.status == STATUS_COMPLETED || task.status == STATUS_REPORTED) return 0L
        if (walletRepository.pendingEarning(task.id) != null) return 0L
        val engagementType = EngagementTaskType.from(task.taskType) ?: return 0L
        val account = runCatching { accountDao.getById(accountId) }.getOrNull()
        val isUpgraded = isUpgradedWithin24h(account?.upgradedAt)
        val reward = settingsRepository.current().actionCoinReward(engagementType, isUpgraded).toLong()
        if (reward <= 0) return 0L

        walletRepository.addPendingEarning(task.id, task.orderId.ifBlank { null }, accountId, reward)
        walletRepository.addCoins(reward)
        accountDao.incrementCoinsEarned(accountId, reward)
        return reward
    }

    /** Marks an order as in-flight locally so a second runner pass doesn't pick it up. */
    suspend fun markRunning(taskId: String) = taskDao.updateStatus(taskId, STATUS_RUNNING)

    suspend fun markStatus(taskId: String, status: String) = taskDao.updateStatus(taskId, status)

    /**
     * Submits the outcome of a task execution and awards coins.
     */
    suspend fun submitResult(
        taskId: String,
        accountId: String,
        success: Boolean,
        message: String? = null
    ): Resource<Int> {
        // Tracks whether the legacy-queue credit below was already attempted, so a throw from
        // walletRepository.addCoins (Room write + disk backup, either of which can fail) doesn't
        // cause the catch block's fallback credit to double-apply accountDao.incrementCoinsEarned
        // on top of an increment that already landed — this was silently inflating the local
        // coinsEarned cache above the true server value until the next refreshFromServer() call
        // yanked it back down, which is what showed up as the badge visibly dropping on screen.
        return try {
            val isBackendOrderTask = taskId.startsWith(BACKEND_TASK_PREFIX)
            val isSmmOrderTask = taskId.startsWith(SMM_ORDER_PREFIX)
            val isLegacyQueueTask = !isBackendOrderTask && !isSmmOrderTask

            // Set to the local dashboard-priced credit as soon as it's applied below, then
            // overwritten with the backend-confirmed amount once the settle call actually lands
            // — see applyLocalReward/reconcileReward. Stays at the local figure (not 0) if the
            // settle call never resolves, since that is what the wallet/account card actually show.
            var finalReward = 0L

            val taskObj = runCatching { taskDao.getById(taskId) }.getOrNull()
            if (success && taskObj?.status in setOf(STATUS_COMPLETED, STATUS_REPORTED)) {
                return Resource.Success(0)
            }
            if (success) {
                taskDao.updateStatus(taskId, STATUS_COMPLETED)
            } else {
                taskDao.updateStatus(taskId, STATUS_FAILED)
            }

            // Credit the dashboard-priced reward locally right away — see applyLocalReward — so
            // the account card and wallet move the instant Instagram confirms the action, not
            // whenever the settle call below eventually lands. finalReward starts here instead of
            // at 0 so a settle call that never resolves (offline, backend down) still reports what
            // the user actually sees rather than silently 0.
            if (isLegacyQueueTask) {
                // taskDao.updateStatus above already marked this row STATUS_COMPLETED — a legacy
                // task has no "completed but not yet reported" state the way an order-backed task
                // does (settleOrder leaves those un-flipped for a later retry, see its doc comment),
                // so once this method returns, nobody will ever call submitTaskResult for this
                // taskId again. A single failed attempt against a cold-starting host (the free-tier
                // backend can take real time to wake up — see WalletRepository's refresh comments)
                // used to mean the Instagram action succeeded but the reward was silently and
                // permanently lost, both locally and server-side, since the report never landed.
                // Retry a few times with backoff before giving up, rather than only trying once.
                submitLegacyTaskResultWithRetry(taskId, accountId, success, message)
                    ?.let { response ->
                        if (success) {
                            applyConfirmedReward(accountId, response.coinsAwarded.toLong(), response.walletBalance)
                            finalReward = response.coinsAwarded.toLong()
                        }
                    }
            }

            if (isBackendOrderTask) {
                if (taskObj != null) {
                    val localReward = if (success) applyLocalReward(taskObj, accountId) else 0L
                    if (localReward > 0L) finalReward = localReward
                    val settlementError = message.takeUnless { success }
                    val reporterAccountId = when {
                        success -> accountId
                        isPrivateAccount(settlementError) -> accountId
                        else -> null
                    }
                    // Reconciliation (wallet/account delta + clearing the matching pending-earning
                    // rows) happens inside settleOrderLocked itself via reconcilePendingBatch, not
                    // here — this report's backendAwarded can cover several of this account's
                    // pending units at once, not just this one taskId.
                    val backendAwarded = settleOrder(taskObj, reporterAccountId, settlementError)
                    if (success && backendAwarded != null) {
                        finalReward = backendAwarded.toLong()
                    }
                }
            }

            if (isSmmOrderTask) {
                if (taskObj != null && success) {
                    val localReward = applyLocalReward(taskObj, accountId)
                    if (localReward > 0L) finalReward = localReward
                    val response = submitManualTaskResultWithRetry(taskObj, accountId, message)
                    if (response != null) {
                        reconcilePendingTask(
                            taskId = taskId,
                            accountId = accountId,
                            confirmedCoins = response.coinsAwarded.toLong(),
                            walletBalance = response.walletBalance,
                            accountCoinsEarned = response.accountCoinsEarned
                        )
                        if (response.coinsAwarded > 0) {
                            taskDao.markTaskReported(taskId)
                            finalReward = response.coinsAwarded.toLong()
                        }
                    }
                }
            }

            if (success) {
                // The backend already pushes the authoritative wallet total over the live socket
                // right after crediting (WalletService.CreditAsync -> CoinSyncHub -> WalletRepository
                // .applyLiveUpdate), so polling refresh() here too, unconditionally, on every single
                // completed action from every concurrently-running account, only produced a storm of
                // overlapping GET /wallet requests with nothing to gain — and whose out-of-order
                // responses were the actual source of the intermittent coin-drop bug. Only fall back
                // to an HTTP refresh when the push channel is down, so the balance still catches up
                // for a disconnected/offline session.
                if (!liveCoinSyncManager.isConnected.value) {
                    runCatching { walletRepository.refresh() }
                }
            }

            Resource.Success(if (success) finalReward.toInt() else 0)
        } catch (t: Throwable) {
            taskDao.updateStatus(taskId, if (success) STATUS_COMPLETED else STATUS_FAILED)
            Resource.Error(t.message ?: "Failed to submit result", t)
        }
    }

    /**
     * Reports a legacy-queue task's outcome, retrying a couple of times on failure before giving
     * up — see the call site in [submitResult] for why a single attempt isn't enough here. Only
     * retries on an actual failure to reach/parse a response; a request that lands but the server
     * rejects (4xx business error) is still just one attempt, since retrying that would only repeat
     * the same rejection.
     */
    private suspend fun submitLegacyTaskResultWithRetry(
        taskId: String,
        accountId: String,
        success: Boolean,
        message: String?
    ): com.feedpilot.client.data.remote.dto.TaskResultResponse? {
        val request = TaskResultRequest(taskId, accountId, success, message)
        repeat(LEGACY_RESULT_RETRY_ATTEMPTS) { attempt ->
            val result = runCatching { api.submitTaskResult(request) }
            result.getOrNull()?.let { return it }
            if (attempt < LEGACY_RESULT_RETRY_ATTEMPTS - 1) {
                delay(LEGACY_RESULT_RETRY_DELAY_MS * (attempt + 1))
            } else {
                Log.w(
                    TAG,
                    "Giving up reporting legacy task $taskId after $LEGACY_RESULT_RETRY_ATTEMPTS attempts",
                    result.exceptionOrNull()
                )
            }
        }
        return null
    }

    /**
     * Releases [orderId]'s claim if this device has nothing left pending for it locally —
     * called after every single task against a backend order settles, success **or failure**.
     *
     * It used to run only on success (`if (success) reportBackendOrderProgress(...)`), which
     * quietly stuck the claim as Processing forever the moment anything failed: a batch of local
     * rows fanned out from one order all get consumed one way or another (Completed, Failed, or
     * Skipped) as the runner works through them, but only a Completed one ever told the backend
     * "I'm done, hand this to someone else if it still needs work." A run of failures — a
     * session going stale mid-run being the most common one — left the order's claim orphaned:
     * not progressing, not released, not even eligible for the stale-claim reclaim window because
     * this device still nominally "held" it. That order then vanished from the claimable queue
     * for everyone, which is what a device saw as getting stuck waiting after several actions
     * instead of continuing — nothing was hung, the *order* just had nowhere to go.
     *
     * [accountId] is passed only when this call is reporting a genuinely new success — it credits
     * that account's own CoinsEarned tally. A settle-on-failure call passes null: nothing new was
     * completed, so nothing new should be credited, only the release needs to happen.
     *
     * Returns the backend's authoritative `workerCoinsAwarded` for this report, or `null` if
     * nothing was actually sent/confirmed (skipped, or the request itself failed) — callers must
     * treat `null` as "unknown," not as a confirmed zero-coin award, or a dropped network call
     * would be misread as the backend clawing back a reward that was never actually revoked.
     */
    private suspend fun submitManualTaskResultWithRetry(
        task: TaskEntity,
        accountId: String,
        message: String?
    ): com.feedpilot.client.data.remote.dto.TaskResultResponse? {
        val request = ManualActionResultRequest(
            accountId = accountId,
            taskType = task.taskType,
            target = task.targetId,
            message = message,
            idempotencyKey = task.id
        )
        repeat(LEGACY_RESULT_RETRY_ATTEMPTS) { attempt ->
            val result = runCatching { api.submitManualActionResult(request) }
            result.getOrNull()?.let { return it }
            if (attempt < LEGACY_RESULT_RETRY_ATTEMPTS - 1) {
                delay(LEGACY_RESULT_RETRY_DELAY_MS * (attempt + 1))
            } else {
                Log.w(
                    TAG,
                    "Giving up reporting manual/SMM task ${task.id} after $LEGACY_RESULT_RETRY_ATTEMPTS attempts",
                    result.exceptionOrNull()
                )
            }
        }
        return null
    }

    private suspend fun settleOrder(task: TaskEntity, accountId: String?, errorMessage: String? = null): Int? =
        orderSettleMutex(task.orderId).withLock { settleOrderLocked(task, accountId, errorMessage) }

    private suspend fun settleOrderLocked(task: TaskEntity, accountId: String?, errorMessage: String? = null): Int? {
        val orderId = task.orderId
        // Account-scoped whenever this report will actually credit someone (accountId != null):
        // orders are shared across every account running on this device, so counting device-wide
        // here let whichever account's settle call won the per-order lock claim (and get credited
        // for) a sibling account's own just-finished, not-yet-reported completions too — see
        // TaskDao.completedCountForOrderAndAccount. The release-only path (accountId == null,
        // nothing being credited) still counts device-wide, matching what it flushes.
        val doneLocally = runCatching {
            if (accountId != null) taskDao.completedCountForOrderAndAccount(orderId, accountId)
            else taskDao.completedCountForOrder(orderId)
        }.getOrNull() ?: return null

        val log = runCatching { orderHistoryRepository.findBySmmOrderId(orderId) }.getOrNull()
        // Work already credited before this device claimed the order still counts.
        val baseline = log?.completedCount ?: 0
        val quantity = log?.quantity ?: Int.MAX_VALUE
        val reportedUnits = if (accountId != null) 1 else doneLocally
        val total = (baseline + reportedUnits).coerceAtMost(quantity)
        val targetNotFound = isTargetNotFound(errorMessage)
        val privateAccount = isPrivateAccount(errorMessage)
        val terminalTargetFailure = targetNotFound || privateAccount
        val observedCount = if (!targetNotFound) observedTargetCount(task, accountId) else null

        val stillOwed = quantity > total
        val pendingLocally = runCatching { taskDao.pendingCountForOrder(orderId) }.getOrDefault(0)
        // Nothing changed and nothing local is exhausted yet — this device may still add to the
        // total shortly, so there is nothing worth telling the backend right now.
        if (!terminalTargetFailure && accountId == null && pendingLocally > 0) return null

        // Temporary diagnostic for the zero-coin-reward reports under investigation — shows
        // exactly what baseline/doneLocally/total this device computed and sent, to compare
        // against the backend's own before/reportedCompleted/newlyDone log for the same call.
        // Remove once the root cause is confirmed and fixed.
        Log.d(TAG, "settleOrder diag: orderId=$orderId baseline=$baseline doneLocally=$doneLocally total=$total quantity=$quantity accountId=$accountId")

        val result = appOrderRepository.reportProgress(
            orderId = orderId,
            deviceId = deviceId(),
            completed = total,
            // One Instagram account can only fulfil this target once. After each landed report,
            // free the remaining quantity so another device/account can continue instead of this
            // runner re-claiming and then skipping the same order in the next wait loop.
            release = true,
            errorMessage = errorMessage?.takeIf { it.isNotBlank() },
            accountId = accountId,
            failureCode = when {
                targetNotFound -> FAILURE_TARGET_NOT_FOUND
                privateAccount -> FAILURE_PRIVATE_ACCOUNT
                else -> null
            },
            observedCount = observedCount,
            clientTaskId = task.id.takeIf { accountId != null }
        )

        if (terminalTargetFailure) {
            runCatching { taskDao.skipAllPendingForOrder(orderId) }
        }

        // Mirror the backend's answer into local history straight away. order_history is
        // exposed as a Flow, so the History screen redraws the moment this lands — no
        // manual sync needed to see progress move or an order settle as Completed.
        return when (result) {
            is Resource.Success -> {
                orderHistoryRepository.syncFromBackend(listOf(result.data))
                // The report just landed, so `total` (baseline + doneLocally) is now exactly
                // what the backend has on record. Flip the rows that made up doneLocally so the
                // *next* settleOrder call starts its local count from zero instead of re-adding
                // them on top of the now-updated baseline — without this, baseline and doneLocally
                // both grew from the same completions and progress compounded (1, 3, 6, 10, ...
                // instead of 1, 2, 3, 4) every time a device reported more than once per order.
                Log.d(TAG, "settleOrder diag: orderId=$orderId result=SUCCESS workerCoinsAwarded=${result.data.workerCoinsAwarded} status=${result.data.status}")
                runCatching {
                    if (accountId != null) {
                        if (result.data.workerCoinsAwarded > 0) {
                            taskDao.markTaskReported(task.id)
                        }
                        // This one report can confirm several of this account's own units at
                        // once (doneLocally above is a running count, not one task's worth), so
                        // every pending-earning row it covers must be reconciled and cleared here
                        // — not just whichever single task happened to trigger this settle call.
                        // Otherwise a sibling task's pending row that got folded into this same
                        // batch is left orphaned: still "outstanding" forever, and a later flush
                        // retry for it would re-settle an already-fully-confirmed order and read
                        // back newlyDone=0, wrongly clawing back a reward already paid.
                        if (result.data.workerCoinsAwarded > 0) {
                            reconcilePendingTask(
                                taskId = task.id,
                                accountId = accountId,
                                confirmedCoins = result.data.workerCoinsAwarded.toLong(),
                                walletBalance = result.data.workerWalletBalance,
                                accountCoinsEarned = result.data.workerAccountCoinsEarned
                            )
                        } else {
                            Log.w(TAG, "settleOrder diag: orderId=$orderId taskId=${task.id} confirmed zero coins; keeping pending earning for retry")
                        }
                    } else {
                        taskDao.markOrderTasksReported(orderId)
                    }
                }
                if (result.data.status.equals("Pending", ignoreCase = true) ||
                    result.data.status.equals("Completed", ignoreCase = true) ||
                    result.data.status.equals("Failed", ignoreCase = true) ||
                    result.data.status.equals("NotFound", ignoreCase = true)
                ) {
                    runCatching { taskDao.deleteUnprocessedForOrder(orderId) }
                }
                result.data.workerCoinsAwarded
            }
            is Resource.Error -> {
                Log.d(TAG, "settleOrder diag: orderId=$orderId result=ERROR message=${result.message}")
                // The report didn't land, so the backend's total (and therefore `baseline` on the
                // next call) is unchanged. Deliberately NOT writing `total` into order_history
                // here: that field doubles as next call's baseline, and since these rows stay
                // 'Completed' (not flipped to Reported) they will be counted again next time —
                // writing `total` now would double-count them a second time on top of that. The
                // History card just shows the last confirmed value until a report succeeds. Also
                // returning null (not 0) — a dropped network call must not be reconciled as if
                // the backend had confirmed a zero-coin award.
                null
            }
            Resource.Loading -> null
        }
    }

    /**
     * Fetches the target's current public count so the backend can use it as a progress baseline
     * (see OrderProcessingController.Progress / ObservedCompleted): followers for Follow orders,
     * likes from media info for Like/post/reel orders.
     *
     * Deliberately does NOT require `task.startCount > 0` first — that field mirrors the
     * order's own StartCount as it stood when this device claimed it, which for an
     * externally-sourced (panel) order is 0 until *something* reports an observed count for
     * it. Gating this fetch on startCount already being set meant it could never run a first
     * time, so external orders' StartCount (and therefore the start_count pushed back to the
     * SMM panel) stayed permanently 0. The backend seeds the baseline from whatever this
     * returns the first time; every call after that measures real growth against it.
     */
    private suspend fun observedTargetCount(task: TaskEntity, accountId: String?): Int? {
        if (accountId.isNullOrBlank()) return null
        val username = extractInstagramHandle(task.targetId).ifBlank { task.targetId.removePrefix("@") }
        val account = accountDao.getById(accountId) ?: return null
        val cookies = account.sessionCookies.ifBlank { account.lastLogin ?: "" }
        if (!cookies.contains("sessionid")) return null

        return when {
            task.taskType.equals(TASK_TYPE_FOLLOW, ignoreCase = true) ->
                instagramRepository.getUserProfileDetails(username, customCookies = cookies)
                    ?.followerCount
                    ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                    ?.toInt()

            task.taskType.equals(TASK_TYPE_LIKE, ignoreCase = true) ->
                instagramRepository.getPostDetails(task.targetId, customCookies = cookies)
                    ?.likeCount
                    ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                    ?.toInt()

            else -> null
        }
    }

    /**
     * Continuously fetches cancelled tasks from SMM Admin API (`POST /cancel/pull`),
     * sets their status to "partial" and updates their remaining count via `POST /orders/update`.
     */
    suspend fun syncCancelledTasks(): Int = withContext(Dispatchers.IO) {
        try {
            val cancelledList = hanumanSmmClient.pullCancelTasks(limit = 100)
            val listToProcess = if (cancelledList.isNotEmpty()) {
                cancelledList
            } else {
                hanumanSmmClient.fetchCancelledOrders()
            }

            if (listToProcess.isEmpty()) return@withContext 0

            val updates = listToProcess.map { item ->
                com.feedpilot.client.data.remote.SmmOrderUpdatePayload(
                    id = item.id,
                    status = "partial",
                    remains = item.remains
                )
            }

            val success = hanumanSmmClient.updateOrders(updates)
            if (success) {
                Log.i(TAG, "syncCancelledTasks: Updated ${updates.size} cancelled tasks to partial status on SMM Admin API")
                updates.size
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncCancelledTasks failed", e)
            0
        }
    }

    private companion object {
        const val TAG = "TaskRepository"
        const val BACKEND_TASK_PREFIX = "backend_order_"
        const val SMM_ORDER_PREFIX = "smm_order_"
        const val STATUS_RUNNING = "Running"
        const val STATUS_COMPLETED = "Completed"
        const val STATUS_REPORTED = "Reported"
        const val STATUS_FAILED = "Failed"
        const val LEGACY_RESULT_RETRY_ATTEMPTS = 3
        const val LEGACY_RESULT_RETRY_DELAY_MS = 1_500L

        const val TASK_TYPE_FOLLOW = "Follow"
        const val TASK_TYPE_LIKE = "Like"
        const val TASK_TYPE_COMMENT = "Comment"
        const val TASK_TYPE_REPOST = "Repost"
        const val TASK_TYPE_SAVE = "SavePost"
        const val TASK_TYPE_STORY = "StoryView"
        const val FAILURE_TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val FAILURE_PRIVATE_ACCOUNT = "TARGET_PRIVATE"

        /** Orders requested per service ID on each pull, matching the panel's documented cap. */
        const val PULL_BATCH_SIZE = 100
        /** Order lease window claimed per pass from backend. */
        const val BACKEND_MAX_CLAIM_BATCH = 10

        /** Upper bound on the per-account tasks fanned out from a single order. */
        const val MAX_TASKS_PER_ORDER = 500
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

    private fun isPrivateAccount(message: String?): Boolean =
        !message.isNullOrBlank() &&
            message.contains("private account", ignoreCase = true)
}

