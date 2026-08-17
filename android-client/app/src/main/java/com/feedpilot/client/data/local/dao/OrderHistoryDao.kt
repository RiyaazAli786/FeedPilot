package com.feedpilot.client.data.local.dao

import androidx.room.*
import com.feedpilot.client.data.local.OrderHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderHistoryDao {
    @Query("SELECT * FROM order_history ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<OrderHistoryEntity>>

    /**
     * Pending orders this app placed. Scoped to APP because external order IDs belong to
     * the admin queue and cannot be resolved through the user API's `action=status`.
     */
    @Query("SELECT * FROM order_history WHERE source = 'APP' AND status IN ('Pending', 'PLACED', 'IN_PROGRESS', 'In progress') ORDER BY timestamp DESC")
    suspend fun getPendingLogs(): List<OrderHistoryEntity>

    @Query("SELECT * FROM order_history WHERE timestamp BETWEEN :startTimeMs AND :endTimeMs ORDER BY timestamp DESC")
    fun getLogsByDateRange(startTimeMs: Long, endTimeMs: Long): Flow<List<OrderHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: OrderHistoryEntity)

    /**
     * Upserts a fetched page of backend orders. Unlike [insertLogIfAbsent] this deliberately
     * overwrites an existing row — a paginated fetch is meant to reflect exactly what the
     * backend just returned for that id, not defer to whatever was locally cached before.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLogs(logs: List<OrderHistoryEntity>)

    /**
     * Inserts only if the row is new. Pulled admin orders reuse a deterministic id, so a
     * repeated sync must not overwrite locally tracked progress with a fresh "Pending" row.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLogIfAbsent(logs: List<OrderHistoryEntity>)

    @Query("UPDATE order_history SET status = :status, errorMessage = :errorMessage WHERE smmOrderId = :smmOrderId")
    suspend fun updateOrderStatus(smmOrderId: String, status: String, errorMessage: String? = null)

    @Query("UPDATE order_history SET status = :status, completedCount = :completedCount, errorMessage = :errorMessage WHERE smmOrderId = :smmOrderId")
    suspend fun updateOrderStatusWithCount(smmOrderId: String, status: String, completedCount: Int, errorMessage: String? = null)

    @Query("""
        UPDATE order_history 
        SET completedCount = completedCount + :increment,
            status = CASE WHEN (completedCount + :increment) >= quantity THEN 'Completed' ELSE 'In Progress' END
        WHERE id = :id OR smmOrderId = :id
    """)
    suspend fun incrementCompletedCount(id: String, increment: Int = 1)

    @Query("SELECT * FROM order_history WHERE smmOrderId = :smmOrderId LIMIT 1")
    suspend fun findBySmmOrderId(smmOrderId: String): OrderHistoryEntity?

    @Query("DELETE FROM order_history WHERE id = :id")
    suspend fun deleteLog(id: String)

    @Query("DELETE FROM order_history")
    suspend fun clearAll()
}
