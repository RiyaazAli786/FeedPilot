package com.feedpilot.client.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.dto.DeviceListItemDto
import com.feedpilot.client.data.remote.dto.PagedDevicesDto
import com.feedpilot.client.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicesUiState(
    val items: List<DeviceListItemDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val totalPages: Int = 1,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val onlineCount: Int = 0,
    val bridgeOnCount: Int = 0
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null

    init {
        loadDevices(page = 1)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(400)
            loadDevices(page = 1)
        }
    }

    fun clearSearch() {
        _uiState.value = _uiState.value.copy(searchQuery = "")
        loadDevices(page = 1)
    }

    fun loadPage(page: Int) {
        loadDevices(page = page)
    }

    fun nextPage() {
        val current = _uiState.value
        if (current.page < current.totalPages) {
            loadDevices(page = current.page + 1)
        }
    }

    fun prevPage() {
        val current = _uiState.value
        if (current.page > 1) {
            loadDevices(page = current.page - 1)
        }
    }

    fun refresh() {
        loadDevices(page = _uiState.value.page)
    }

    private fun loadDevices(page: Int) {
        viewModelScope.launch {
            val query = _uiState.value.searchQuery.trim()
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            when (val res = deviceRepository.getDevices(search = query, page = page, pageSize = 20)) {
                is Resource.Success -> {
                    val paged = res.data
                    val totalPages = maxOf(1, (paged.total + paged.pageSize - 1) / paged.pageSize)
                    val onlineCount = paged.items.count { it.isOnline }
                    val bridgeOnCount = paged.items.count { it.bridgeEnabled }

                    _uiState.value = _uiState.value.copy(
                        items = paged.items,
                        total = paged.total,
                        page = paged.page,
                        pageSize = paged.pageSize,
                        totalPages = totalPages,
                        isLoading = false,
                        errorMessage = null,
                        onlineCount = onlineCount,
                        bridgeOnCount = bridgeOnCount
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = res.message
                    )
                }
                is Resource.Loading -> {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }
            }
        }
    }
}
