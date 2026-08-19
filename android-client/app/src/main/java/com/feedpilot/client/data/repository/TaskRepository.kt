package com.feedpilot.client.data.repository

import android.util.Log
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.common.extractInstagramHandle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val api: ApiService,
    private val taskDao: TaskDao,
    private val accountDao: AccountDao,
    private val walletRepository: WalletRepository,
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
     * Dashboard-configured per-action coin reward ("Action Coin Pricing & Referral Schema"),
     * Normal vs Upgraded (24h bonus window) account — mirrors the backend's
     * RunnerSettingsStore.GetActionCoinReward so the same setting actually governs what a
     * device visibly earns, not just what the server would have paid it.
     */
    /** Public entry point for callers outside this repository (e.g. the manual action-log retry
     *  flow in TasksViewModel) that need the same dashboard-configured reward this repository
     *  uses internally, without duplicating the hardcoded fallback this replaced. */
    suspend fun rewardCoinsForTaskType(taskType: String?, isUpgraded: Boolean): Long =
        rewardCoinsFor(taskType, isUpgraded, settingsRepository.current())

    suspend fun submitManualActionResult(
        accountId: String,
        taskType: String,
        target: String,
        message: String? = null
    ): Resource<Int> = try {
        val response = api.submitManualActionResult(
            ManualActionResultRequest(accountId, taskType, target, message)
        )
        walletRepository.reconcileCoins(response.coinsAwarded.toLong())
        if (response.coinsAwarded != 0) accountDao.incrementCoinsEarned(accountId, response.coinsAwarded.toLong())
        Resource.Success(response.coinsAwarded)
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("Failed to submit retry result"), t)
    }

    private fun rewardCoinsFor(taskType: String?, isUpgraded: Boolean, settings: AppSettings): Long {
        val (normal, upgraded) = when {
            taskType.equals(TASK_TYPE_FOLLOW, ignoreCase = true) -> settings.followCoinsNormal to settings.followCoinsUpgraded
            taskType.equals(TASK_TYPE_LIKE, ignoreCase = true) -> settings.likeCoinsNormal to settings.likeCoinsUpgraded
            taskType.equals(TASK_TYPE_COMMENT, ignoreCase = true) -> settings.commentCoinsNormal to settings.commentCoinsUpgraded
            taskType.equals(TASK_TYPE_REPOST, ignoreCase = true) -> settings.repostCoinsNormal to settings.repostCoinsUpgraded
            taskType.equals(TASK_TYPE_SAVE, ignoreCase = true) -> settings.savePostCoinsNormal to settings.savePostCoinsUpgraded
            taskType.equals(TASK_TYPE_STORY, ignoreCase = true) -> settings.storyViewCoinsNormal to settings.storyViewCoinsUpgraded
            else -> 1 to 2
        }
        return (if (isUpgraded) upgraded else normal).toLong()
    }

    /**
     * The credit already applied in [submitResult] used [locallyCredited], computed from this
     * device's own RunnerSettings cache (synced at most every [SettingsRepository]-defined
     * interval, so it can briefly lag a dashboard price change). [backendAwarded] is what
     * RunnerSettingsStore actually priced this same completion at, read live from the DB on the
     * settlement request that just landed — reconcile any gap now instead of leaving a stale
     * local price as a silent, permanent over/under-credit in the wallet and account tally.
     *
     * The wallet side goes through [WalletRepository.creditConfirmedEarning] rather than a plain
     * clear-then-reconcile — clearing [taskId]'s pending row and crediting the wallet base total
     * used to be two separate calls, which left a window where the reward was in neither bucket.
     * Usually too brief to notice, but wide enough to show up as a visible drop right after a
     * credit when several accounts are settling tasks concurrently and contending for the same
     * wallet mutation lock.
     */
    private suspend fun reconcileReward(taskId: String, accountId: String, locallyCredited: Long, backendAwarded: Long) {
        walletRepository.creditConfirmedEarning(taskId, backendAwarded)
        val delta = backendAwarded - locallyCredited
        if (delta != 0L) accountDao.incrementCoinsEarned(accountId, delta)
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
        var creditAttempted = false
        return try {
            val isBackendOrderTask = taskId.startsWith(BACKEND_TASK_PREFIX)
            val isSmmOrderTask = taskId.startsWith(SMM_ORDER_PREFIX)
            val isLegacyQueueTask = !isBackendOrderTask && !isSmmOrderTask

            val account = runCatching { accountDao.getById(accountId) }.getOrNull()
            val isUpgraded = com.feedpilot.client.common.isUpgradedWithin24h(account?.upgradedAt)
            // Cheap read-only lookup just for the task type, kept separate from the later
            // `taskDao.getById` below (used for settleOrder) so this doesn't shift that call's
            // timing relative to the STATUS_COMPLETED write it currently happens after.
            val taskTypeForReward = runCatching { taskDao.getById(taskId) }.getOrNull()?.taskType
            val rewardCoins = rewardCoinsFor(taskTypeForReward, isUpgraded, settingsRepository.current())
            // Starts as the locally-estimated reward; bumped to the backend-confirmed figure
            // below once a reconciliation actually lands, so the runner's success log reflects
            // what was really credited rather than a possibly-stale local guess.
            var finalReward = rewardCoins

            val taskObj = runCatching { taskDao.getById(taskId) }.getOrNull()
            if (success) {
                taskDao.updateStatus(taskId, STATUS_COMPLETED)
                creditAttempted = true
                accountDao.incrementCoinsEarned(accountId, rewardCoins)
                walletRepository.addPendingEarning(taskId, taskObj?.orderId, accountId, rewardCoins)
            } else {
                taskDao.updateStatus(taskId, STATUS_FAILED)
            }

            if (isLegacyQueueTask) {
                runCatching { api.submitTaskResult(TaskResultRequest(taskId, accountId, success, message)) }
                    .getOrNull()
                    ?.let { response ->
                        if (success) {
                            reconcileReward(taskId, accountId, rewardCoins, response.coinsAwarded.toLong())
                            finalReward = response.coinsAwarded.toLong()
                        }
                    }
            }

            if (isBackendOrderTask) {
                if (taskObj != null) {
                    val settlementError = message.takeUnless { success }
                    val reporterAccountId = when {
                        success -> accountId
                        isPrivateAccount(settlementError) -> accountId
                        else -> null
                    }
                    val backendAwarded = settleOrder(taskObj, reporterAccountId, settlementError)
                    if (success && backendAwarded != null) {
                        reconcileReward(taskId, accountId, rewardCoins, backendAwarded.toLong())
                        finalReward = backendAwarded.toLong()
                    }
                }
            }

            if (success) {
                runCatching { walletRepository.refresh() }
            }

            Resource.Success(if (success) finalReward.toInt() else 0)
        } catch (t: Throwable) {
            taskDao.updateStatus(taskId, if (success) STATUS_COMPLETED else STATUS_FAILED)
            if (success && !creditAttempted) {
                val account = runCatching { accountDao.getById(accountId) }.getOrNull()
                val isUpgraded = com.feedpilot.client.common.isUpgradedWithin24h(account?.upgradedAt)
                val taskTypeForReward = runCatching { taskDao.getById(taskId) }.getOrNull()?.taskType
                val settings = runCatching { settingsRepository.current() }.getOrNull() ?: AppSettings()
                val rewardCoins = rewardCoinsFor(taskTypeForReward, isUpgraded, settings)
                walletRepository.addCoins(rewardCoins)
                accountDao.incrementCoinsEarned(accountId, rewardCoins)
            }
            Resource.Error(t.message ?: "Failed to submit result", t)
        }
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
    private suspend fun settleOrder(task: TaskEntity, accountId: String?, errorMessage: String? = null): Int? {
        val orderId = task.orderId
        val doneLocally = runCatching { taskDao.completedCountForOrder(orderId) }.getOrNull() ?: return null

        val log = runCatching { orderHistoryRepository.findBySmmOrderId(orderId) }.getOrNull()
        // Work already credited before this device claimed the order still counts.
        val baseline = log?.completedCount ?: 0
        val quantity = log?.quantity ?: Int.MAX_VALUE
        val total = (baseline + doneLocally).coerceAtMost(quantity)
        val targetNotFound = isTargetNotFound(errorMessage)
        val privateAccount = isPrivateAccount(errorMessage)
        val terminalTargetFailure = targetNotFound || privateAccount
        val observedCount = if (!targetNotFound) observedTargetCount(task, accountId) else null

        val stillOwed = quantity > total
        val pendingLocally = runCatching { taskDao.pendingCountForOrder(orderId) }.getOrDefault(0)
        // Nothing changed and nothing local is exhausted yet — this device may still add to the
        // total shortly, so there is nothing worth telling the backend right now.
        if (!terminalTargetFailure && accountId == null && pendingLocally > 0) return null

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
            observedCount = observedCount
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
                runCatching { taskDao.markOrderTasksReported(orderId) }
                if (result.data.status.equals("Pending", ignoreCase = true) ||
                    result.data.status.equals("Completed", ignoreCase = true) ||
                    result.data.status.equals("Failed", ignoreCase = true) ||
                    result.data.status.equals("NotFound", ignoreCase = true)
                ) {
                    runCatching { taskDao.deleteUnprocessedForOrder(orderId) }
                }
                result.data.workerCoinsAwarded
            }
            is Resource.Error ->
                // The report didn't land, so the backend's total (and therefore `baseline` on the
                // next call) is unchanged. Deliberately NOT writing `total` into order_history
                // here: that field doubles as next call's baseline, and since these rows stay
                // 'Completed' (not flipped to Reported) they will be counted again next time —
                // writing `total` now would double-count them a second time on top of that. The
                // History card just shows the last confirmed value until a report succeeds. Also
                // returning null (not 0) — a dropped network call must not be reconciled as if
                // the backend had confirmed a zero-coin award.
                null
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
        const val STATUS_FAILED = "Failed"

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

