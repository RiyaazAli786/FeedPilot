package com.feedpilot.client.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.feedpilot.client.R
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.data.local.*
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.WithdrawRequest
import com.feedpilot.client.data.toEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val walletDao: WalletDao,
    private val withdrawalDao: WithdrawalDao,
    private val authRepository: AuthRepository,
    private val pendingEarningDao: PendingEarningDao,
    private val deviceIdentity: DeviceIdentity
) {
    // refreshFromServer() is called concurrently by every signed-in account's runner loop plus
    // the header's own poll (see BalanceViewModel) — all on the one shared wallet. Without this,
    // two overlapping refreshes could both read the transactions table before either had upserted
    // a new TransferIn row and both fire a notification for the same incoming transfer.
    private val transferNotifyMutex = Mutex()
    // Guards every read-decide-write against the wallet row's totalCoins/lastServerCoins. Without
    // this, refreshFromServer's decision (built from a `current` read before its api.getWallet()
    // network round-trip — which can take real time, especially against a cold-starting free-tier
    // host) could finish and overwrite the row with a stale snapshot *after* an addCoins/
    // reconcileCoins credit had already landed on it mid-flight, silently reverting that credit —
    // seen as the balance visibly increasing and then auto-decreasing moments later.
    private val walletMutationMutex = Mutex()
    // Only restore from the on-disk backup when there is no wallet row at all. Restoring
    // whenever the balance reads zero resurrected a stale figure every time the balance was
    // legitimately spent down to nothing.
    val wallet: Flow<WalletEntity?> = flow {
        restoreWalletFromBackup()
        emitAll(walletDao.observe())
    }

    /**
     * Alias kept for spend flows. Wallet values are server-confirmed only because transfers,
     * withdrawals, and orders can debit only coins the backend has already accepted.
     */
    val spendableWallet: Flow<WalletEntity?> = wallet

    /**
     * Persists a durable record of one task's optimistic, dashboard-priced local credit — the
     * row survives process death even if the app is killed before [creditConfirmedEarning] ever
     * runs for it, so a pending reward is never silently lost, only left to reconcile on the next
     * app start / [SyncWorker][com.feedpilot.client.worker.SyncWorker] pass. Callers apply the
     * actual optimistic credit to the base total themselves (see TaskRepository.submitResult) —
     * this only records that a not-yet-confirmed credit of [rewardCoins] is outstanding for
     * [taskId], so [creditConfirmedEarning] knows how much of the base total to unwind if the
     * backend's confirmed amount ends up different.
     */
    suspend fun addPendingEarning(taskId: String, orderId: String?, accountId: String, rewardCoins: Long) {
        if (rewardCoins <= 0) return
        pendingEarningDao.upsert(
            PendingEarningEntity(
                id = taskId,
                taskId = taskId,
                orderId = orderId,
                accountId = accountId,
                rewardCoins = rewardCoins
            )
        )
    }

    suspend fun clearPendingEarning(taskId: String) {
        pendingEarningDao.deleteById(taskId)
    }

    /** Every locally-credited reward the backend hasn't confirmed yet — e.g. its settle call
     *  failed or the app died before one could even be attempted. See TaskRepository.flushPendingEarnings,
     *  which re-attempts these so a local-only credit doesn't stay unpushed to the server forever. */
    suspend fun pendingEarnings(): List<PendingEarningEntity> = pendingEarningDao.getAll()

    suspend fun pendingEarning(taskId: String): PendingEarningEntity? = pendingEarningDao.getByTaskId(taskId)

    suspend fun pendingEarningsForOrderAndAccount(orderId: String, accountId: String): List<PendingEarningEntity> =
        pendingEarningDao.getByOrderAndAccount(orderId, accountId)

    suspend fun clearPendingEarningsForOrderAndAccount(orderId: String, accountId: String) {
        pendingEarningDao.deleteByOrderAndAccount(orderId, accountId)
    }

    /**
     * Reconciles one task's already-applied optimistic credit ([locallyCredited], added straight
     * to the base total at completion time — see TaskRepository.submitResult) against the
     * backend's own live-priced [confirmedCoins] for the same completion. Applies only the
     * *difference* between the two, not [confirmedCoins] outright — re-adding the full confirmed
     * amount on top of a credit that was already applied would double-count every action's reward
     * the moment optimistic local crediting is in play. The two normally match (same dashboard
     * price on both ends), so this is usually a no-op delta of 0; it only moves coins when a
     * dashboard price changed inside this device's RunnerSettings cache window, or the backend
     * legitimately priced the completion at less than expected (e.g. a shared order another
     * account had just exhausted).
     */
    suspend fun creditConfirmedEarning(taskId: String, locallyCredited: Long, confirmedCoins: Long) {
        val delta = confirmedCoins - locallyCredited
        if (delta != 0L) reconcileCoins(delta)
        clearPendingEarning(taskId)
    }

    /**
     * Applies a WalletUpdated push from CoinSyncHub under the same [walletMutationMutex] every
     * other wallet mutation uses. This used to be done by LiveCoinSyncManager writing straight
     * to WalletDao, unsynchronized against addCoins/reconcileCoins/refreshFromServer — with
     * several accounts earning concurrently (many pushes in quick succession, each racing local
     * mutations) that let a push and a local mutation interleave into a total lower than either
     * one alone intended, which is what showed up as a visible coin drop right after a credit.
     *
     * Same stale-snapshot guard as [refreshFromServer] (compare against [WalletEntity.lastServerCoins],
     * not the possibly-optimistic [WalletEntity.totalCoins]) — this push travels over a separate
     * channel (WebSocket) from the HTTP wallet refresh, so either one can resolve after the other
     * despite reflecting an older server state, and an older one must not stomp a fresher total.
     *
     * [WalletEntity.lastServerCoins] is kept as a monotonic high-water mark (never regressed by an
     * older response) rather than last-write-wins — see [refreshFromServer] for why: a response
     * that looks "lower than what we've already confirmed" here is far more likely to be a stale,
     * out-of-order one than a genuine decrease, and genuine decreases still reach the wallet via
     * the many `refresh(forceServer = true)` call sites (wallet/orders/withdraw/transfer/etc.
     * screen opens), which bypass this comparison entirely.
     */
    suspend fun applyLiveUpdate(
        coins: Long,
        lifetimeCoins: Long,
        pendingCoins: Long,
        withdrawnCoins: Long,
        updatedAt: String
    ) {
        val newTotal = walletMutationMutex.withLock {
            val current = walletDao.get()
            if (current != null && isStaleSnapshot(current.updatedAt, updatedAt)) {
                return@withLock current
            }
            val updated = (current ?: WalletEntity(
                id = "user_wallet",
                totalCoins = coins,
                lifetimeCoins = lifetimeCoins,
                pendingCoins = pendingCoins,
                withdrawnCoins = withdrawnCoins,
                updatedAt = updatedAt,
                lastServerCoins = coins
            )).copy(
                totalCoins = coins,
                lifetimeCoins = lifetimeCoins,
                pendingCoins = pendingCoins,
                withdrawnCoins = withdrawnCoins,
                updatedAt = updatedAt,
                lastServerCoins = coins
            )
            walletDao.upsert(updated)
            updated
        }
        saveWalletBackup(newTotal.totalCoins)
    }

    suspend fun applyConfirmedBalance(coins: Long, updatedAt: String = "") {
        val newTotal = walletMutationMutex.withLock {
            val current = walletDao.get() ?: WalletEntity(
                id = "user_wallet",
                totalCoins = coins,
                lifetimeCoins = coins,
                pendingCoins = 0L,
                withdrawnCoins = 0L,
                updatedAt = updatedAt,
                lastServerCoins = coins
            )
            val updated = current.copy(
                totalCoins = coins,
                lifetimeCoins = maxOf(current.lifetimeCoins, coins),
                updatedAt = updatedAt,
                lastServerCoins = coins
            )
            walletDao.upsert(updated)
            updated
        }
        saveWalletBackup(newTotal.totalCoins)
    }

    suspend fun clearPendingEarningForOrder(orderId: String) {
        pendingEarningDao.deleteByOrderId(orderId)
    }
    val transactions: Flow<List<WalletTransactionEntity>> = walletDao.observeTransactions()
    val withdrawals: Flow<List<WithdrawalEntity>> = withdrawalDao.observeAll()

    /**
     * Pulls the wallet from the backend.
     *
     * Wallet belongs to the backend user/device account, not to any one linked Instagram account.
     * Refresh whenever backend auth exists so coins survive after every Instagram account is
     * removed; with no backend token, keep the local/backup value instead of replacing it with 0.
     */
    suspend fun refresh(forceServer: Boolean = false): Resource<Unit> {
        if (!authRepository.isLoggedIn.value) {
            if (walletDao.get() == null) restoreWalletFromBackup()
            return Resource.Success(Unit)
        }
        return refreshFromServer(forceServer)
    }

    private suspend fun refreshFromServer(forceServer: Boolean = false): Resource<Unit> = try {
        if (walletDao.get() == null) restoreWalletFromBackup()
        // Deliberately outside the mutex below — this network round-trip is the slow part (can be
        // tens of seconds against a cold-starting host), and holding the lock across it would just
        // move the contention elsewhere by blocking every addCoins/reconcileCoins call for that
        // whole time instead of racing them.
        val serverWallet = api.getWallet().toEntity()
        val toSave = walletMutationMutex.withLock {
            // Read AFTER the network call, not before: a credit that landed on the row while
            // api.getWallet() was in flight must be the one this decision is based on, not a
            // snapshot from before it existed — see walletMutationMutex's doc comment.
            val current = walletDao.get()
            // current.lastServerCoins is the highest server total we've confirmed so far — a
            // monotonic high-water mark, NOT last-write-wins. Comparing the FRESH server total
            // against that (not against current.totalCoins, which may already be propped up by
            // an unconfirmed optimistic credit) is what lets a genuine server-side decrease — an
            // admin dashboard deduction, a processed withdrawal — be told apart from that credit,
            // instead of being permanently shadowed by it. See WalletEntity.lastServerCoins.
            //
            // But every account's runner calls refresh() after each completed action, so a burst
            // of overlapping requests — worst right when several accounts finish tasks around the
            // same moment — can resolve out of order. A slower request that started *before* a
            // credit landed (locally, or on another device's request that reached the server
            // first) can still return *after* it, carrying a server snapshot that simply predates
            // that credit. That looks identical to a genuine decrease by magnitude alone — the
            // only way to tell them apart is that a genuine decrease should still be visible on
            // the NEXT refresh, once the request storm has settled. So instead of trusting a
            // lower-than-confirmed reading immediately (which is what used to let a stale
            // response revert an already-confirmed higher total from a different, faster
            // in-flight refresh), keep the current total and let it catch up on a later refresh.
            // Real decreases still reach the wallet promptly via the many
            // refresh(forceServer = true) call sites (wallet/orders/withdraw/transfer/etc. screen
            // opens), which bypass this comparison entirely — forceServer is the one deliberate
            // exception where the server total is the number to show even if it reads lower than
            // whatever was locally propped up a moment ago.
            val resolved = if (!forceServer && current != null && isStaleSnapshot(current.updatedAt, serverWallet.updatedAt)) {
                current
            } else {
                serverWallet.copy(lastServerCoins = serverWallet.totalCoins)
            }
            walletDao.upsert(resolved)
            resolved
        }
        saveWalletBackup(toSave.totalCoins)
        val history = api.getWalletHistory().map { it.toEntity() }
        transferNotifyMutex.withLock {
            // A device syncing its transaction history for the very first time (fresh install,
            // fresh login, app data cleared) has nothing "already seen" to diff against — every
            // row in the first page would otherwise look "new" and flood the user with
            // notifications for transfers that may be weeks old. That first sync only establishes
            // the seen-baseline; notifications start from the next refresh onward.
            val hadAnyLocalHistory = walletDao.hasAnyTransaction()
            val existingIds = walletDao.transactionIdsIn(history.map { it.id }).toSet()
            val newlyReceived = if (hadAnyLocalHistory) {
                history.filter { it.type == TRANSFER_IN_TYPE && it.id !in existingIds }
            } else {
                emptyList()
            }
            walletDao.upsertTransactions(history)
            if (newlyReceived.isNotEmpty()) notifyCoinsReceived(newlyReceived)
        }
        withdrawalDao.upsertAll(api.getWithdrawHistory().map { it.toEntity() })
        Resource.Success(Unit)
    } catch (t: Throwable) {
        val local = walletDao.get()
        if (local == null) {
            restoreWalletFromBackup()
        }
        Resource.Error(t.message ?: "Failed to load wallet", t)
    }

    /** Increments wallet coins locally and backs up to disk immediately. */
    suspend fun addCoins(amount: Long) {
        if (amount <= 0) return
        val newTotal = walletMutationMutex.withLock {
            val current = walletDao.get() ?: WalletEntity(
                id = "user_wallet",
                totalCoins = 0L,
                lifetimeCoins = 0L,
                pendingCoins = 0L,
                withdrawnCoins = 0L,
                updatedAt = System.currentTimeMillis().toString()
            )
            val total = current.totalCoins + amount
            walletDao.upsert(
                current.copy(
                    totalCoins = total,
                    lifetimeCoins = maxOf(current.lifetimeCoins, total),
                    updatedAt = System.currentTimeMillis().toString()
                )
            )
            total
        }
        saveWalletBackup(newTotal)
    }

    /**
     * Adjusts the wallet by [delta] (positive or negative) to reconcile an optimistic local
     * credit against the backend's authoritative awarded amount for the same completion — unlike
     * [addCoins], this can move the total down, since it's correcting a specific over/under-credit
     * rather than adding a fresh reward.
     */
    suspend fun reconcileCoins(delta: Long) {
        if (delta == 0L) return
        val newTotal = walletMutationMutex.withLock {
            // A confirmed task reward can land before the very first refresh() has created this
            // row (fresh install/login, or a cold-starting backend that takes tens of seconds to
            // answer that first request) — dropping the delta here silently ate the first few
            // actions' rewards from the visible balance until the next refresh() caught it back up
            // via the server's authoritative total. Create the row instead, same as addCoins/
            // applyLiveUpdate already do, so nothing is lost while waiting on that first refresh.
            val current = walletDao.get() ?: WalletEntity(
                id = "user_wallet",
                totalCoins = 0L,
                lifetimeCoins = 0L,
                pendingCoins = 0L,
                withdrawnCoins = 0L,
                updatedAt = System.currentTimeMillis().toString()
            )
            val total = (current.totalCoins + delta).coerceAtLeast(0L)
            walletDao.upsert(
                current.copy(
                    totalCoins = total,
                    lifetimeCoins = maxOf(current.lifetimeCoins, total),
                    updatedAt = System.currentTimeMillis().toString()
                )
            )
            total
        }
        saveWalletBackup(newTotal)
    }

    suspend fun withdraw(
        coins: Long,
        paymentMethod: String,
        upiId: String?,
        bankDetails: String?,
        usdtAddress: String? = null
    ): Resource<Unit> = try {
        val dto = api.withdraw(WithdrawRequest(coins, paymentMethod, upiId, bankDetails, usdtAddress))
        withdrawalDao.upsertAll(listOf(dto.toEntity()))
        refresh(forceServer = true)
        Resource.Success(Unit)
    } catch (t: Throwable) {
        // The balance shown may be stale local data; pull the server's view so the user
        // sees the number the refusal was actually based on.
        runCatching { refresh(forceServer = true) }
        Resource.Error(t.apiErrorMessage("Withdrawal failed"), t)
    }

    private fun saveWalletBackup(coins: Long) {
        try {
            val backupFileName = "wallet_backup_${context.packageName.replace('.', '_')}.json"
            val file = File(context.filesDir, backupFileName)
            val json = JSONObject().apply {
                put("totalCoins", coins)
                put("timestamp", System.currentTimeMillis())
            }
            file.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private suspend fun restoreWalletFromBackup() {
        try {
            val backupFileName = "wallet_backup_${context.packageName.replace('.', '_')}.json"
            val file = File(context.filesDir, backupFileName)
            if (file.exists()) {
                val str = file.readText()
                if (str.isNotBlank()) {
                    val json = JSONObject(str)
                    val savedCoins = json.optLong("totalCoins", 0L)
                    if (savedCoins > 0) {
                        val current = walletDao.get() ?: WalletEntity(
                            id = "user_wallet",
                            totalCoins = 0L,
                            lifetimeCoins = 0L,
                            pendingCoins = 0L,
                            withdrawnCoins = 0L,
                            updatedAt = System.currentTimeMillis().toString()
                        )
                        if (current.totalCoins < savedCoins) {
                            walletDao.upsert(
                                current.copy(
                                    totalCoins = savedCoins,
                                    lifetimeCoins = maxOf(current.lifetimeCoins, savedCoins),
                                    updatedAt = System.currentTimeMillis().toString()
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Fires one system notification per newly-synced TransferIn row. Best-effort enriches each
     * with the sender's username via one `/wallet/transfer/history` call shared across the whole
     * batch — a receiver rarely gets more than one transfer per poll, so this is one extra call,
     * not one per transaction. Falls back to a senderless message if that lookup fails; the coins
     * already landed either way.
     */
    private suspend fun notifyCoinsReceived(transactions: List<WalletTransactionEntity>) {
        val senderByReference = runCatching { api.getTransferHistory() }.getOrNull()
            ?.associateBy { "transfer:${it.id}" }
            .orEmpty()
        createWalletChannel()
        transactions.forEach { txn ->
            val sender = txn.reference?.let { senderByReference[it]?.senderUsername }
            val text = if (sender != null) {
                "You received ${txn.coins} coins from @$sender"
            } else {
                "You received ${txn.coins} coins"
            }
            val notification = NotificationCompat.Builder(context, Constants.WALLET_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Coins received")
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            // Keyed off the transaction id (not a shared constant) so two transfers landing close
            // together each get their own notification instead of the second overwriting the
            // first — but re-notifying the very same transaction, should this ever run twice, just
            // replaces its own prior notification rather than duplicating it. Also folded through
            // DeviceIdentity.cloneScopedNotificationId so two same-package clones' transfer
            // notifications land in disjoint ranges too, not just each other's within one clone.
            val notificationId = deviceIdentity.cloneScopedNotificationId(NOTIFICATION_ID_BASE) +
                ((txn.id.hashCode() and 0x7fffffff) % 1_000)
            runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
        }
    }

    private fun createWalletChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.WALLET_CHANNEL_ID,
                "Coin transfers",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val TRANSFER_IN_TYPE = "TransferIn"
        const val NOTIFICATION_ID_BASE = 4400
    }

    private fun isStaleSnapshot(currentUpdatedAt: String, incomingUpdatedAt: String): Boolean {
        val current = parseSnapshotTime(currentUpdatedAt) ?: return false
        val incoming = parseSnapshotTime(incomingUpdatedAt) ?: return false
        return incoming < current
    }

    private fun parseSnapshotTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { return it }
        return runCatching {
            val trimmed = value.trim().trimEnd('Z')
            val normalized = if (trimmed.contains('.')) {
                val (datePart, fraction) = trimmed.split('.', limit = 2)
                "$datePart.${fraction.take(3).padEnd(3, '0')}"
            } else {
                "$trimmed.000"
            }
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(normalized)?.time
        }.getOrNull()
    }
}
