package com.feedpilot.client.feature.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.repository.AccountSessionGate
import com.feedpilot.client.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.feedpilot.client.common.LiveCoinSyncManager

data class BalanceUiState(
    val coins: Long = 0,
    /** Active paid tier name (e.g. "Gold"), or "Free". Shown as a badge in the header. */
    val plan: String = "Free",
    val latestVersionName: String? = null
)

/** VM providing the coin balance and current plan for AppHeader. */
@HiltViewModel
class BalanceViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val sessionGate: AccountSessionGate,
    private val api: ApiService,
    private val liveCoinSyncManager: LiveCoinSyncManager
) : ViewModel() {

    private val plan = MutableStateFlow("Free")
    private val latestVersionName = MutableStateFlow<String?>(null)

    val state: StateFlow<BalanceUiState> =
        combine(walletRepository.wallet, plan, latestVersionName) { wallet, planName, version ->
            BalanceUiState(coins = wallet?.totalCoins ?: 0, plan = planName, latestVersionName = version)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BalanceUiState())

    init {
        viewModelScope.launch {
            // Every main screen shares this VM through its header, so a short poll here is what
            // makes a coin transfer land in front of the receiver without them having to leave
            // and reopen the app — the balance simply ticks up on its own within a few seconds.
            while (true) {
                walletRepository.refresh()
                delay(BALANCE_POLL_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            // The plan is the only call this header makes directly rather than through a
            // repository, so it carries its own no-account guard. Free is the honest default.
            if (!sessionGate.isSignedIn()) return@launch
            // Only an active paid plan is worth badging; a lapsed one reads as Free.
            val sub = runCatching { api.getSubscription() }.getOrNull()
            plan.value = if (sub?.active == true) sub.name else "Free"
        }
        viewModelScope.launch {
            val version = runCatching { api.getLatestVersion() }.getOrNull()
            latestVersionName.value = version?.versionName
        }
    }

    private companion object {
        const val BALANCE_POLL_INTERVAL_MS = 10_000L
    }
}
