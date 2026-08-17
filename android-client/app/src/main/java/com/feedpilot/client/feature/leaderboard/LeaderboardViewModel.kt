package com.feedpilot.client.feature.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortOrder {
    DESCENDING, ASCENDING
}

data class LeaderItem(
    val account: AccountEntity,
    val absoluteRank: Int
)

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {

    val sortOrder = MutableStateFlow(SortOrder.DESCENDING)

    private val _leaders = MutableStateFlow<List<LeaderItem>>(emptyList())
    val leaders: StateFlow<List<LeaderItem>> = _leaders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLastPage = MutableStateFlow(false)
    val isLastPage: StateFlow<Boolean> = _isLastPage.asStateFlow()

    private var currentPage = 1
    private val pageSize = 5
    private var totalItems = 0

    init {
        // Collect sort order changes to reload
        viewModelScope.launch {
            sortOrder.collect {
                resetAndLoadFirstPage()
            }
        }
    }

    fun updateSortOrder(order: SortOrder) {
        sortOrder.value = order
    }

    private fun resetAndLoadFirstPage() {
        currentPage = 1
        _isLastPage.value = false
        _leaders.value = emptyList()
        loadNextPage()
    }

    fun loadNextPage() {
        if (_isLoading.value || _isLastPage.value) return

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val orderStr = if (sortOrder.value == SortOrder.DESCENDING) "DESCENDING" else "ASCENDING"
                val response = accountRepository.getLeaderboard(1, pageSize, orderStr)
                
                val newItems = response.items.map { dto ->
                    LeaderItem(
                        account = AccountEntity(
                            id = dto.id,
                            username = dto.username,
                            profilePictureUrl = dto.profilePictureUrl,
                            status = "Active",
                            lastLogin = null,
                            lastActive = null,
                            coinsEarned = dto.coinsEarned,
                            sessionCookies = "",
                            isLoggedIn = false,
                            upgradedAt = null,
                            profileTaskCompletedAtMs = null,
                            bioTaskCompletedAtMs = null,
                            gender = "male"
                        ),
                        absoluteRank = dto.rank
                    )
                }

                _leaders.value = newItems
                totalItems = newItems.size
                _isLastPage.value = true
            } catch (e: Exception) {
                e.printStackTrace()
                _isLastPage.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }
}
