package com.feedpilot.client.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY username")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY username")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("UPDATE accounts SET coinsEarned = coinsEarned + :increment WHERE id = :id")
    suspend fun incrementCoinsEarned(id: String, increment: Long)

    /**
     * Targeted single-column update for a cookie rotation Instagram issued mid-action (see
     * IgActionResult.updatedCookies). A full upsert of a stale in-memory copy risked clobbering
     * some other field a concurrent write had just changed; this only ever touches sessionCookies.
     */
    @Query("UPDATE accounts SET sessionCookies = :sessionCookies WHERE id = :id")
    suspend fun updateSessionCookies(id: String, sessionCookies: String)

    /** Updates account status string (e.g. Active, CHALLENGE_REQUIRED). */
    @Query("UPDATE accounts SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Flips the green-dot verified-login flag after an on-demand session check. */
    @Query("UPDATE accounts SET isLoggedIn = :isLoggedIn WHERE id = :id")
    suspend fun updateLoginStatus(id: String, isLoggedIn: Boolean)

    /**
     * Clears the green-dot flag off every other account so at most one is ever active at a time.
     * See [com.feedpilot.client.data.repository.AccountRepository.verifySession].
     */
    @Query("UPDATE accounts SET isLoggedIn = 0 WHERE id != :exceptId")
    suspend fun clearLoginStatusExcept(exceptId: String)

    /** Mirrors the backend's Account.UpgradedAt after a successful upgrade call. */
    @Query("UPDATE accounts SET upgradedAt = :upgradedAt WHERE id = :id")
    suspend fun updateUpgradedAt(id: String, upgradedAt: String?)

    /** Stamped by UpgradeRunnerService when the "profile" checklist task is verified today. */
    @Query("UPDATE accounts SET profileTaskCompletedAtMs = :atMillis WHERE id = :id")
    suspend fun updateProfileTaskCompletedAt(id: String, atMillis: Long?)

    /** Stamped by UpgradeRunnerService when the "bio" checklist task is verified today. */
    @Query("UPDATE accounts SET bioTaskCompletedAtMs = :atMillis WHERE id = :id")
    suspend fun updateBioTaskCompletedAt(id: String, atMillis: Long?)

    /** Set from the gender radio buttons on the Upgrade panel — see AccountEntity.gender. */
    @Query("UPDATE accounts SET gender = :gender WHERE id = :id")
    suspend fun updateGender(id: String, gender: String)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM accounts")
    suspend fun clearAll()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE status IN ('Completed', 'Reported')")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE status IN ('Assigned','Running')")
    fun observeRunningCount(): Flow<Int>

    /** Orders downloaded but not yet claimed by a local account runner, oldest first. */
    @Query("SELECT * FROM tasks WHERE status = 'Pending' ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'Pending' AND taskType IN (:taskTypes) ORDER BY createdAt LIMIT :limit")
    suspend fun pendingForTypes(taskTypes: List<String>, limit: Int): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'Pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM tasks WHERE status = 'Assigned' AND accountId = :accountId ORDER BY createdAt LIMIT :limit")
    suspend fun assignedForAccount(accountId: String, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'Assigned' AND accountId = :accountId AND taskType IN (:taskTypes) ORDER BY createdAt LIMIT :limit")
    suspend fun assignedForAccountAndTypes(accountId: String, taskTypes: List<String>, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id IN (:ids) ORDER BY createdAt")
    suspend fun byIds(ids: List<String>): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    /**
     * How many of an order's fanned-out tasks this device has finished *and not yet reported*
     * to the backend. Rows move to 'Reported' once [markOrderTasksReported] runs after a
     * successful progress report, so this never recounts work the backend already knows about.
     */
    @Query("SELECT COUNT(*) FROM tasks WHERE orderId = :orderId AND status = 'Completed'")
    suspend fun completedCountForOrder(orderId: String): Int

    /**
     * Marks this order's currently-'Completed' rows as 'Reported' so a later
     * [completedCountForOrder] call doesn't count them again. Must only be called right after a
     * progress report for [orderId] has actually landed on the backend — calling it eagerly would
     * let the next report under-count, calling it late (or not at all) would let the next report
     * re-add the same completions on top of the backend's now-updated total.
     */
    @Query("UPDATE tasks SET status = 'Reported' WHERE orderId = :orderId AND status = 'Completed'")
    suspend fun markOrderTasksReported(orderId: String)

    /** Whether this device still holds queued work for an order, i.e. keep the claim. */
    @Query("SELECT COUNT(*) FROM tasks WHERE orderId = :orderId AND status IN ('Pending','Assigned','Running')")
    suspend fun pendingCountForOrder(orderId: String): Int

    @Query(
        "SELECT DISTINCT orderId FROM tasks " +
            "WHERE id LIKE 'backend_order_%' " +
            "AND status IN ('Pending','Assigned','Running')"
    )
    suspend fun activeBackendOrderIdsHeldLocally(): List<String>

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE tasks SET status = 'Assigned', accountId = :accountId WHERE id IN (:ids) AND status = 'Pending'")
    suspend fun assignPendingToAccount(ids: List<String>, accountId: String)

    @Query("SELECT commentText FROM tasks WHERE (targetId = :target OR orderId = :target OR targetId LIKE '%' || :target || '%') AND commentText IS NOT NULL AND commentText != '' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getCommentTextForTarget(target: String): String?

    @Transaction
    suspend fun claimForAccount(accountId: String, limit: Int, taskTypes: List<String>? = null): List<TaskEntity> {
        val cleanTypes = taskTypes?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val existing = if (cleanTypes.isEmpty()) {
            assignedForAccount(accountId, limit)
        } else {
            assignedForAccountAndTypes(accountId, cleanTypes, limit)
        }
        if (existing.size >= limit) return existing

        val available = if (cleanTypes.isEmpty()) {
            pending(limit - existing.size)
        } else {
            pendingForTypes(cleanTypes, limit - existing.size)
        }
        if (available.isNotEmpty()) {
            assignPendingToAccount(available.map { it.id }, accountId)
        }

        return if (available.isEmpty()) {
            existing
        } else if (cleanTypes.isEmpty()) {
            assignedForAccount(accountId, limit)
        } else {
            assignedForAccountAndTypes(accountId, cleanTypes, limit)
        }
    }

    /**
     * Marks every still-pending task fanned out from [orderId] as Skipped in one go.
     *
     * A large order (say quantity 100000, one row server-side) fans out up to
     * [com.feedpilot.client.data.repository.TaskRepository.MAX_TASKS_PER_ORDER] local task rows
     * per download. If this account already completed the order's one target, every one of those
     * rows is doomed the same way — but the runner only ever pulls a small batch (20) at a time,
     * so without this it ground through the doomed rows 20 at a time, ~15-19s apart, before the
     * local pending count ever dropped low enough to fetch a *different* order. That is what
     * turned "no more work for this account on this order" into a multi-minute stall.
     */
    @Query("UPDATE tasks SET status = 'Skipped' WHERE orderId = :orderId AND status IN ('Pending','Assigned','Running')")
    suspend fun skipAllPendingForOrder(orderId: String)

    /**
     * Removes local, unreported copies of an order after the backend claim has been released.
     * This keeps another linked account in the same app from being blocked by stale Skipped ids
     * when it fetches the order's remaining quantity.
     */
    @Query("DELETE FROM tasks WHERE orderId = :orderId AND status IN ('Pending','Assigned','Running','Skipped','Failed')")
    suspend fun deleteUnprocessedForOrder(orderId: String)

    @Query("DELETE FROM tasks")
    suspend fun clearAll()
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet LIMIT 1")
    fun observe(): Flow<WalletEntity?>

    @Query("SELECT * FROM wallet LIMIT 1")
    suspend fun get(): WalletEntity?

    @Upsert
    suspend fun upsert(wallet: WalletEntity)

    @Query("DELETE FROM wallet")
    suspend fun clearWallet()

    @Query("SELECT * FROM wallet_transactions ORDER BY createdDate DESC")
    fun observeTransactions(): Flow<List<WalletTransactionEntity>>

    /** Which of [ids] are already stored locally — used to tell a freshly-synced transaction (e.g. an incoming transfer) apart from one already seen. */
    @Query("SELECT id FROM wallet_transactions WHERE id IN (:ids)")
    suspend fun transactionIdsIn(ids: List<String>): List<String>

    /** Whether this device has ever synced any wallet transaction — see [WalletRepository]'s transfer-notify baseline. */
    @Query("SELECT EXISTS(SELECT 1 FROM wallet_transactions LIMIT 1)")
    suspend fun hasAnyTransaction(): Boolean

    @Upsert
    suspend fun upsertTransactions(items: List<WalletTransactionEntity>)

    @Query("DELETE FROM wallet_transactions")
    suspend fun clearTransactions()
}

@Dao
interface WithdrawalDao {
    @Query("SELECT * FROM withdrawals ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WithdrawalEntity>>

    @Upsert
    suspend fun upsertAll(items: List<WithdrawalEntity>)

    @Query("DELETE FROM withdrawals")
    suspend fun clearAll()
}

@Dao
interface AccountLogDao {
    @Query("SELECT * FROM account_logs WHERE accountId = :accountId ORDER BY timestampMs DESC LIMIT :limit")
    fun observeForAccount(accountId: String, limit: Int = 50): Flow<List<AccountLogEntity>>

    @Query("SELECT COUNT(*) > 0 FROM account_logs WHERE accountId = :accountId AND target = :target")
    suspend fun hasAccountCompletedTarget(accountId: String, target: String): Boolean

    @Query("SELECT COUNT(*) > 0 FROM account_logs WHERE accountId = :accountId AND action = :action AND target = :target")
    suspend fun existsForAccountActionTarget(accountId: String, action: String, target: String): Boolean

    @Query("SELECT DISTINCT target FROM account_logs WHERE accountId = :accountId")
    suspend fun getInteractedTargetsForAccount(accountId: String): List<String>

    @Query("SELECT COUNT(*) FROM account_logs WHERE accountId = :accountId AND action = :action AND success = 1 AND timestampMs >= :sinceMs")
    suspend fun getDailyCountForAccountAction(accountId: String, action: String, sinceMs: Long): Int

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(log: AccountLogEntity)

    @Query("DELETE FROM account_logs WHERE accountId = :accountId")
    suspend fun clearForAccount(accountId: String)

    @Query("DELETE FROM account_logs")
    suspend fun clearAll()
}

@Dao
interface PendingEarningDao {
    @Query("SELECT * FROM pending_earnings ORDER BY createdAtMs ASC")
    fun observeAll(): Flow<List<PendingEarningEntity>>

    @Query("SELECT COALESCE(SUM(rewardCoins), 0) FROM pending_earnings")
    fun observePendingTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(rewardCoins), 0) FROM pending_earnings")
    suspend fun getPendingTotal(): Long

    @Query("SELECT * FROM pending_earnings ORDER BY createdAtMs ASC")
    suspend fun getAll(): List<PendingEarningEntity>

    @Upsert
    suspend fun upsert(earning: PendingEarningEntity)

    @Query("DELETE FROM pending_earnings WHERE id = :id OR taskId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_earnings WHERE orderId = :orderId")
    suspend fun deleteByOrderId(orderId: String)

    @Query("DELETE FROM pending_earnings")
    suspend fun clearAll()
}

@Dao
interface WatchedHandleDao {
    @Query("SELECT * FROM watched_handles ORDER BY username")
    fun observeHandles(): Flow<List<WatchedHandleEntity>>

    @Query("SELECT * FROM watched_handles ORDER BY username")
    suspend fun getHandles(): List<WatchedHandleEntity>

    @Query("SELECT * FROM watched_handles WHERE id = :id LIMIT 1")
    suspend fun getHandle(id: String): WatchedHandleEntity?

    @Upsert
    suspend fun upsertHandles(handles: List<WatchedHandleEntity>)

    @Upsert
    suspend fun upsertPosts(posts: List<WatchedPostEntity>)

    @Query("SELECT * FROM watched_posts WHERE watchedHandleId = :handleId ORDER BY COALESCE(takenAt, fetchedAt) DESC")
    fun observePosts(handleId: String): Flow<List<WatchedPostEntity>>

    @Query("DELETE FROM watched_handles WHERE id = :id")
    suspend fun deleteHandle(id: String)

    @Query("DELETE FROM watched_posts WHERE watchedHandleId = :handleId")
    suspend fun deletePostsForHandle(handleId: String)
}
