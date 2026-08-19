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
import kotlinx.coroutines.flow.combine
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
        emitAll(
            combine(walletDao.observe(), pendingEarningDao.observePendingTotal()) { baseWallet, pendingTotal ->
                if (baseWallet == null) null
                else {
                    val effectiveTotal = baseWallet.totalCoins + pendingTotal
                    baseWallet.copy(
                        totalCoins = effectiveTotal,
                        lifetimeCoins = maxOf(baseWallet.lifetimeCoins, effectiveTotal)
                    )
                }
            }
        )
    }

    val spendableWallet: Flow<WalletEntity?> = walletDao.observe()

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

    /**
     * Confirms one task's optimistic pending earning: credits [confirmedCoins] to the base
     * total *before* removing the matching pending row, so `base + pendingTotal` never dips
     * through the transition. The other order — clear first, let the base catch up later via
     * [reconcileCoins] or the next [refresh] — left a real window where a task's reward was in
     * neither bucket: normally too brief to notice, but wide enough to see under load (several
     * accounts settling tasks concurrently, each contending for [walletMutationMutex] and for
     * `refresh()`'s own network round trip) — which is what showed up as a reward appearing and
     * then visibly dropping a moment later while running multiple accounts at once.
     */
    suspend fun creditConfirmedEarning(taskId: String, confirmedCoins: Long) {
        if (confirmedCoins > 0) reconcileCoins(confirmedCoins)
        clearPendingEarning(taskId)
    }

    /**
     * Applies a WalletUpdated push from CoinSyncHub under the same [walletMutationMutex] every
     * other wallet mutation uses. This used to be done by LiveCoinSyncManager writing straight
     * to WalletDao, unsynchronized against addCoins/reconcileCoins/refreshFromServer — with
     * several accounts earning concurrently (many pushes in quick succession, each racing local
     * mutations) that let a push and a local mutation interleave into a total lower than either
     * one alone intended, which is what showed up as a visible coin drop right after a credit.
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
            val mergedTotal = when {
                current == null -> coins
                coins < current.lastServerCoins -> coins
                current.totalCoins > coins -> current.totalCoins
                else -> coins
            }
            val updated = (current ?: WalletEntity(
                id = "user_wallet",
                totalCoins = mergedTotal,
                lifetimeCoins = lifetimeCoins,
                pendingCoins = pendingCoins,
                withdrawnCoins = withdrawnCoins,
                updatedAt = updatedAt,
                lastServerCoins = coins
            )).copy(
                totalCoins = mergedTotal,
                lifetimeCoins = maxOf(current?.lifetimeCoins ?: 0L, lifetimeCoins, mergedTotal),
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
            // current.lastServerCoins is the server total as of our last refresh — comparing the
            // FRESH server total against that (not against current.totalCoins, which may already
            // be propped up by an unconfirmed optimistic credit) is what lets a genuine
            // server-side decrease — an admin dashboard deduction, a processed withdrawal — be
            // told apart from that credit and always win, instead of being permanently shadowed
            // by it. See WalletEntity.lastServerCoins.
            val mergedTotal = when {
                forceServer || current == null -> serverWallet.totalCoins
                serverWallet.totalCoins < current.lastServerCoins -> serverWallet.totalCoins
                current.totalCoins > serverWallet.totalCoins -> current.totalCoins
                else -> serverWallet.totalCoins
            }
            val resolved = serverWallet.copy(
                totalCoins = mergedTotal,
                lifetimeCoins = maxOf(current?.lifetimeCoins ?: 0L, serverWallet.lifetimeCoins, mergedTotal),
                lastServerCoins = serverWallet.totalCoins
            )
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
            val current = walletDao.get() ?: return
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
}
