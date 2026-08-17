package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.OrderHistoryEntity
import com.feedpilot.client.data.local.OrderSource
import com.feedpilot.client.data.local.dao.OrderHistoryDao
import com.feedpilot.client.data.remote.HanumanSmmClient
import com.feedpilot.client.data.remote.dto.AppOrderDto
import com.feedpilot.client.data.toHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderHistoryRepository @Inject constructor(
    private val orderHistoryDao: OrderHistoryDao,
    private val sessionGate: AccountSessionGate
) {
    val allLogs: Flow<List<OrderHistoryEntity>> = orderHistoryDao.getAllLogs()

    fun getLogsByDateRange(startTimeMs: Long, endTimeMs: Long): Flow<List<OrderHistoryEntity>> =
        orderHistoryDao.getLogsByDateRange(startTimeMs, endTimeMs)

    suspend fun logOrder(log: OrderHistoryEntity) {
        orderHistoryDao.insertLog(log)
    }

    /** Records pulled admin orders, leaving any already-tracked row untouched. */
    suspend fun logExternalOrders(logs: List<OrderHistoryEntity>) {
        if (logs.isEmpty()) return
        orderHistoryDao.insertLogIfAbsent(logs)
    }

    suspend fun updateOrderStatus(smmOrderId: String, status: String, errorMessage: String? = null) {
        orderHistoryDao.updateOrderStatus(smmOrderId, status, errorMessage)
    }

    suspend fun incrementCompletedCount(id: String, increment: Int = 1) {
        orderHistoryDao.incrementCompletedCount(id, increment)
    }

    /**
     * Reconciles locally logged app orders with the backend's authoritative view, which is
     * what the dashboard updates as an order is fulfilled.
     */
    suspend fun syncFromBackend(orders: List<com.feedpilot.client.data.remote.dto.AppOrderDto>) {
        for (o in orders) {
            orderHistoryDao.updateOrderStatusWithCount(
                smmOrderId = o.id,
                status = o.status,
                completedCount = o.completedCount,
                errorMessage = o.errorMessage
            )
        }
    }

    /** Syncs live statuses for all pending SMM orders from server. */
    suspend fun syncPendingOrdersStatus(
        smmClient: HanumanSmmClient,
        apiUrl: String = "",
        apiKey: String = ""
    ) {
        // Reaches out to the SMM provider, so it waits for an account like every other fetch.
        // The local log stays readable meanwhile — it just is not refreshed.
        if (!sessionGate.isSignedIn()) return

        val pendingList = orderHistoryDao.getPendingLogs()
        if (pendingList.isEmpty()) return

        val pendingMap = pendingList.associateBy { it.smmOrderId }
        val orderIds = pendingList.mapNotNull { it.smmOrderId }.filter { it.isNotBlank() }
        if (orderIds.isEmpty()) return

        val statusMap = smmClient.checkMultipleOrdersStatus(apiUrl, apiKey, orderIds)
        statusMap.forEach { (orderId, info) ->
            val matchingLog = pendingMap[orderId]
            val totalQty = matchingLog?.quantity ?: 100
            val remains = info.remains.toIntOrNull() ?: 0
            val completed = maxOf(0, totalQty - remains)
            val isCompleted = completed >= totalQty || info.status.lowercase() == "completed"

            val mappedStatus = if (isCompleted) {
                "Completed"
            } else when (info.status.lowercase()) {
                "canceled", "cancelled" -> "Failed"
                "partial" -> "Partial"
                else -> "In Progress"
            }
            orderHistoryDao.updateOrderStatusWithCount(orderId, mappedStatus, if (isCompleted) totalQty else completed)
        }
    }

    /**
     * Writes one fetched page of orders (see AppOrderRepository.myOrders/externalOrders)
     * verbatim into local history — the page IS the source of truth for its own ids, so this
     * overwrites rather than merging, unlike [logExternalOrders]'s insert-if-absent.
     */
    suspend fun syncPage(orders: List<AppOrderDto>, source: OrderSource) {
        if (orders.isEmpty()) return
        orderHistoryDao.upsertLogs(orders.map { it.toHistoryEntity(source) })
    }

    suspend fun findBySmmOrderId(smmOrderId: String): OrderHistoryEntity? =
        orderHistoryDao.findBySmmOrderId(smmOrderId)

    suspend fun deleteLog(id: String) {
        orderHistoryDao.deleteLog(id)
    }
}
