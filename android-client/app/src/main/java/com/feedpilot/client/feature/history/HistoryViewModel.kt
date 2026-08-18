package com.feedpilot.client.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.local.OrderHistoryEntity
import com.feedpilot.client.data.local.OrderSource
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.dto.PagedOrdersDto
import com.feedpilot.client.data.repository.AppOrderRepository
import com.feedpilot.client.data.repository.OrderHistoryRepository
import com.feedpilot.client.data.toHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class DateRangeFilter(val label: String) {
    ALL_TIME("All Time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days")
}

/** Page sizes offered in the pager control — the backend clamps to [1, 200] regardless. */
val PAGE_SIZE_OPTIONS = listOf(20, 50, 100, 200)

data class HistoryUiState(
    /**
     * The current page's rows. Display-only filter: this screen shows orders this app's user
     * placed themselves, never orders pulled in from the external SMM admin queue — those still
     * exist and still get processed/fulfilled exactly as before (see TaskRepository), they just
     * never show up in this list.
     */
    val logs: List<OrderHistoryEntity> = emptyList(),
    /** [logs] after the local search/date filter — pagination is server-side, search is not. */
    val filteredLogs: List<OrderHistoryEntity> = emptyList(),
    val searchQuery: String = "",
    val dateRange: DateRangeFilter = DateRangeFilter.ALL_TIME,
    val page: Int = 1,
    val pageSize: Int = 50,
    /** At least 1 even when there is nothing to show, so the pager never reads "page 1 of 0". */
    val totalPages: Int = 1,
    val pageLoading: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val orderHistoryRepository: OrderHistoryRepository,
    private val appOrderRepository: AppOrderRepository,
    private val accountDao: com.feedpilot.client.data.local.AccountDao
) : ViewModel() {

    suspend fun getActiveAccountSession(): Pair<String?, String?> {
        val accounts = accountDao.getAll()
        val active = accounts.firstOrNull { it.isLoggedIn } ?: accounts.firstOrNull()
        return Pair(active?.username, active?.sessionCookies)
    }

    private val searchQuery = MutableStateFlow("")
    private val dateRange = MutableStateFlow(DateRangeFilter.ALL_TIME)
    private val page = MutableStateFlow(1)
    private val pageSize = MutableStateFlow(50)

    private val pageLogs = MutableStateFlow<List<OrderHistoryEntity>>(emptyList())
    private val totalPages = MutableStateFlow(1)
    private val pageLoading = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private data class Filters(val query: String, val range: DateRangeFilter)
    private data class Pager(val page: Int, val pageSize: Int, val totalPages: Int, val loading: Boolean)

    private val filters: Flow<Filters> = combine(searchQuery, dateRange, ::Filters)
    private val pager: Flow<Pager> = combine(page, pageSize, totalPages, pageLoading, ::Pager)

    val state: StateFlow<HistoryUiState> = combine(
        pageLogs,
        filters,
        pager,
        message
    ) { logs, f, pg, msg ->
        val term = f.query.trim().removePrefix("@")
        val now = System.currentTimeMillis()
        val startTimeMs = when (f.range) {
            DateRangeFilter.ALL_TIME -> 0L
            DateRangeFilter.TODAY -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            DateRangeFilter.LAST_7_DAYS -> now - (7L * 24 * 60 * 60 * 1000)
            DateRangeFilter.LAST_30_DAYS -> now - (30L * 24 * 60 * 60 * 1000)
        }

        val filtered = logs.filter { log ->
            val matchesSearch = term.isBlank() ||
                log.targetUsername.contains(term, ignoreCase = true) ||
                log.providerNickname.contains(term, ignoreCase = true)
            val matchesDate = log.timestamp >= startTimeMs
            matchesSearch && matchesDate
        }

        HistoryUiState(
            logs = logs,
            filteredLogs = filtered,
            searchQuery = f.query,
            dateRange = f.range,
            page = pg.page,
            pageSize = pg.pageSize,
            totalPages = pg.totalPages,
            pageLoading = pg.loading,
            message = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    init {
        loadPage()
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setDateRangeFilter(range: DateRangeFilter) {
        dateRange.value = range
    }

    fun setPageSize(size: Int) {
        val clamped = size.coerceIn(1, 200)
        if (pageSize.value == clamped) return
        pageSize.value = clamped
        page.value = 1
        loadPage()
    }

    fun nextPage() {
        if (page.value >= totalPages.value) return
        page.value += 1
        loadPage()
    }

    fun prevPage() {
        if (page.value <= 1) return
        page.value -= 1
        loadPage()
    }

    /** Re-fetches the current page — the pull-to-refresh / toolbar refresh action. */
    fun refreshStatuses() = loadPage()

    /**
     * Fetches [page]/[pageSize] from the backend. Display-only: orders pulled in from the
     * external SMM admin queue are deliberately never fetched here, so they never appear in this
     * screen — they're still claimed, processed and settled exactly as before (TaskRepository),
     * this just isn't where that work is shown.
     */
    private fun loadPage() {
        val requestedPage = page.value
        val size = pageSize.value

        pageLoading.value = true
        viewModelScope.launch {
            try {
                val result = fetchPage(requestedPage, size)
                pageLogs.value = result?.items.orEmpty()
                totalPages.value = (result?.totalPages ?: 1).coerceAtLeast(1)
            } finally {
                pageLoading.value = false
            }
        }
    }

    /** Fetches one page of the caller's own app orders, mapping and caching it locally. */
    private suspend fun fetchPage(pageNum: Int, size: Int): PagedResultLocal? {
        return when (val result = appOrderRepository.myOrders(pageNum, size)) {
            is Resource.Success -> {
                val dto: PagedOrdersDto = result.data
                orderHistoryRepository.syncPage(dto.items, OrderSource.APP)
                PagedResultLocal(
                    items = dto.items.map { it.toHistoryEntity(OrderSource.APP) },
                    totalPages = dto.totalPages
                )
            }
            is Resource.Error -> {
                message.value = result.message ?: "Could not load orders"
                null
            }
            Resource.Loading -> null
        }
    }

    private data class PagedResultLocal(
        val items: List<OrderHistoryEntity>,
        val totalPages: Int
    )

    /**
     * Cancels through the backend, which owns app orders and refunds the coins. The old
     * path called the SMM panel directly, but an app order's id is now a backend id and
     * means nothing to a panel.
     */
    fun cancelOrder(log: OrderHistoryEntity) = viewModelScope.launch {
        if (log.smmOrderId.isNullOrBlank()) {
            orderHistoryRepository.updateOrderStatus(log.id, "Canceled")
            pageLogs.value = pageLogs.value.map { if (it.id == log.id) it.copy(status = "Canceled") else it }
            message.value = "Order marked as Canceled"
            return@launch
        }

        message.value = "Canceling order..."
        when (val res = appOrderRepository.cancelOrder(log.smmOrderId)) {
            is Resource.Success -> {
                orderHistoryRepository.updateOrderStatus(log.smmOrderId, res.data.status)
                pageLogs.value = pageLogs.value.map {
                    if (it.smmOrderId == log.smmOrderId) it.copy(status = res.data.status) else it
                }
                message.value = "Order canceled — ${res.data.coinsSpent} coins refunded"
            }
            is Resource.Error -> {
                message.value = res.message ?: "Could not cancel the order"
            }
            Resource.Loading -> Unit
        }
    }

    /** Deletes an app order from the backend first, then removes its cached local history row. */
    fun deleteLog(log: OrderHistoryEntity) = viewModelScope.launch {
        val backendOrderId = log.smmOrderId
        if (log.orderSource == OrderSource.APP && !backendOrderId.isNullOrBlank()) {
            message.value = "Deleting order..."
            when (val res = appOrderRepository.deleteOrder(backendOrderId)) {
                is Resource.Success -> Unit
                is Resource.Error -> {
                    message.value = res.message ?: "Could not delete the order"
                    return@launch
                }
                Resource.Loading -> Unit
            }
        }

        orderHistoryRepository.deleteLog(log.id)
        pageLogs.value = pageLogs.value.filterNot { it.id == log.id }
        message.value = "Order deleted"
    }

    fun consumeMessage() {
        message.value = null
    }
}
