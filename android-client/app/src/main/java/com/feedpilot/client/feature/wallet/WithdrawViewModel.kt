package com.feedpilot.client.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.WithdrawalEntity
import com.feedpilot.client.data.repository.WalletRepository
import com.feedpilot.client.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WithdrawUiState(
    val totalCoins: Long = 0,
    val inrEquivalent: Double = 0.0,
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val history: List<WithdrawalEntity> = emptyList(),
    val minWithdrawalCoins: Long = 500,
    val minWithdrawalInr: Int = 100,
    val coinsPerInr: Int = 5,
    /** Dashboard-controlled: which payment methods the user is currently allowed to pick. */
    val upiEnabled: Boolean = true,
    val bankEnabled: Boolean = true,
    val usdtBep20Enabled: Boolean = false,
    val coinsPerUsdt: Int = 400,
    val minWithdrawalUsdt: Double = 5.0,
    val minWithdrawalUsdtCoins: Long = 2000
)

@HiltViewModel
class WithdrawViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val submitting = MutableStateFlow(false)
    private val uiMessage = MutableStateFlow<Pair<String, Boolean>?>(null)

    val state: StateFlow<WithdrawUiState> = combine(
        walletRepository.wallet,
        walletRepository.withdrawals,
        submitting,
        uiMessage,
        settingsRepository.settings
    ) { wallet, withdrawals, isSubmitting, msgPair, appSettings ->
        val coins = wallet?.totalCoins ?: 0L
        val coinsPerInr = appSettings.coinsPerInr.toDouble().coerceAtLeast(1.0)
        val inr = coins / coinsPerInr
        WithdrawUiState(
            totalCoins = coins,
            inrEquivalent = inr,
            isSubmitting = isSubmitting,
            message = msgPair?.first,
            isError = msgPair?.second ?: false,
            history = withdrawals,
            minWithdrawalCoins = appSettings.minWithdrawalInr.toLong() * appSettings.coinsPerInr,
            minWithdrawalInr = appSettings.minWithdrawalInr,
            coinsPerInr = appSettings.coinsPerInr,
            upiEnabled = appSettings.upiEnabled,
            bankEnabled = appSettings.bankEnabled,
            usdtBep20Enabled = appSettings.usdtBep20Enabled,
            coinsPerUsdt = appSettings.coinsPerUsdt,
            minWithdrawalUsdt = appSettings.minWithdrawalUsdt,
            minWithdrawalUsdtCoins = kotlin.math.ceil(appSettings.minWithdrawalUsdt * appSettings.coinsPerUsdt).toLong()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WithdrawUiState())

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        walletRepository.refresh()
    }

    fun submitWithdrawal(
        coinsInput: String,
        paymentMethod: String,
        upiId: String,
        bankDetails: String,
        usdtAddress: String = "",
        onSuccess: () -> Unit
    ) {
        val coins = coinsInput.toLongOrNull()
        if (coins == null) {
            uiMessage.value = Pair("Please enter a valid amount", true)
            return
        }

        viewModelScope.launch {
            submitting.value = true
            uiMessage.value = null

            val appSettings = settingsRepository.current()
            val isUsdt = paymentMethod == "UsdtBep20"

            val methodEnabled = when (paymentMethod) {
                "UPI" -> appSettings.upiEnabled
                "Bank" -> appSettings.bankEnabled
                "UsdtBep20" -> appSettings.usdtBep20Enabled
                else -> false
            }
            if (!methodEnabled) {
                uiMessage.value = Pair("This payment method is currently unavailable", true)
                submitting.value = false
                return@launch
            }

            val minWithdrawalCoins: Long
            if (isUsdt) {
                minWithdrawalCoins = kotlin.math.ceil(appSettings.minWithdrawalUsdt * appSettings.coinsPerUsdt).toLong()
                if (coins < minWithdrawalCoins) {
                    uiMessage.value = Pair(
                        "Minimum withdrawal is ${appSettings.minWithdrawalUsdt} USDT ($minWithdrawalCoins Coins)", true)
                    submitting.value = false
                    return@launch
                }
            } else {
                val minWithdrawalInr = appSettings.minWithdrawalInr
                minWithdrawalCoins = minWithdrawalInr.toLong() * appSettings.coinsPerInr
                if (coins < minWithdrawalCoins) {
                    uiMessage.value = Pair(
                        "Minimum withdrawal is ₹$minWithdrawalInr ($minWithdrawalCoins Coins)", true)
                    submitting.value = false
                    return@launch
                }
            }
            if (state.value.totalCoins < coins) {
                uiMessage.value = Pair("Insufficient coin balance in wallet", true)
                submitting.value = false
                return@launch
            }
            if (paymentMethod == "UPI" && upiId.isBlank()) {
                uiMessage.value = Pair("Please enter a valid UPI ID", true)
                submitting.value = false
                return@launch
            }
            if (paymentMethod == "Bank" && bankDetails.isBlank()) {
                uiMessage.value = Pair("Please enter your bank details", true)
                submitting.value = false
                return@launch
            }
            if (isUsdt && usdtAddress.isBlank()) {
                uiMessage.value = Pair("Please enter a valid USDT BEP20 wallet address", true)
                submitting.value = false
                return@launch
            }

            val result = walletRepository.withdraw(
                coins = coins,
                paymentMethod = paymentMethod,
                upiId = upiId.ifBlank { null },
                bankDetails = bankDetails.ifBlank { null },
                usdtAddress = usdtAddress.ifBlank { null }
            )

            submitting.value = false
            when (result) {
                is Resource.Success -> {
                    val amountLabel = if (isUsdt) {
                        "${"%.2f".format(coins / appSettings.coinsPerUsdt.toDouble())} USDT"
                    } else {
                        "₹${"%.2f".format(coins / appSettings.coinsPerInr.toDouble())}"
                    }
                    uiMessage.value = Pair("Withdrawal request of $amountLabel submitted!", false)
                    onSuccess()
                }
                is Resource.Error -> {
                    uiMessage.value = Pair(result.message ?: "Withdrawal request failed", true)
                }
                is Resource.Loading -> { }
            }
        }
    }

    fun clearMessage() {
        uiMessage.value = null
    }

    companion object {
        const val COINS_PER_INR = 5
        const val MIN_WITHDRAWAL_INR = 100
        const val MIN_WITHDRAWAL_COINS = MIN_WITHDRAWAL_INR * COINS_PER_INR // 500
    }
}
