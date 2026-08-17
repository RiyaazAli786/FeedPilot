package com.feedpilot.client.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.dto.CoinTransferDto
import com.feedpilot.client.data.remote.dto.TransferSuggestionDto
import com.feedpilot.client.data.repository.CoinTransferRepository
import com.feedpilot.client.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransferUiState(
    val totalCoins: Long = 0,
    val receiverUsername: String = "",
    val coinsInput: String = "",
    val note: String = "",
    /** Set once the receiver username resolves to a real account; cleared on every edit. */
    val resolvedReceiver: String? = null,
    val searching: Boolean = false,
    val searchError: String? = null,
    /** Matching usernames for the dropdown, most recently active first; cleared once resolved. */
    val suggestions: List<TransferSuggestionDto> = emptyList(),
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val history: List<CoinTransferDto> = emptyList()
) {
    val coins: Long get() = coinsInput.toLongOrNull() ?: 0
    val canSubmit: Boolean get() = !isSubmitting && resolvedReceiver != null &&
        coins in 1..totalCoins
}

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val transferRepository: CoinTransferRepository
) : ViewModel() {

    private val form = MutableStateFlow(TransferUiState())
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    val state: StateFlow<TransferUiState> = combine(walletRepository.wallet, form) { wallet, f ->
        f.copy(totalCoins = wallet?.totalCoins ?: 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransferUiState())

    init {
        viewModelScope.launch { walletRepository.refresh(forceServer = true) }
        loadHistory()
    }

    private fun loadHistory() = viewModelScope.launch {
        val result = transferRepository.history()
        if (result is Resource.Success) form.update { it.copy(history = result.data) }
    }

    fun setReceiverUsername(value: String) {
        form.update { it.copy(receiverUsername = value, resolvedReceiver = null, searchError = null) }
        searchJob?.cancel()
        suggestJob?.cancel()
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            form.update { it.copy(searching = false, suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            form.update { it.copy(searching = true) }
            when (val result = transferRepository.search(trimmed)) {
                is Resource.Success -> form.update {
                    it.copy(searching = false, resolvedReceiver = result.data.username, searchError = null)
                }
                is Resource.Error -> form.update {
                    it.copy(searching = false, resolvedReceiver = null, searchError = result.message)
                }
                Resource.Loading -> Unit
            }
        }
        // Suggests matches as the user types instead of requiring the full handle up front —
        // runs alongside the exact-match lookup above rather than replacing it, since picking a
        // suggestion (or typing the complete handle correctly) still needs that to confirm it.
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val hits = transferRepository.suggest(trimmed)
            // Typed (or just picked) the exact handle already — nothing left to suggest.
            val shown = if (hits.size == 1 && hits[0].username.equals(trimmed, ignoreCase = true)) {
                emptyList()
            } else hits
            form.update { it.copy(suggestions = shown) }
        }
    }

    /** Picks a suggestion from the dropdown — fills the field and resolves it exactly, same as typing it in full. */
    fun selectSuggestion(username: String) {
        form.update { it.copy(suggestions = emptyList()) }
        setReceiverUsername(username)
    }

    fun setCoinsInput(value: String) {
        form.update { it.copy(coinsInput = value.filter { c -> c.isDigit() }) }
    }

    fun setNote(value: String) {
        form.update { it.copy(note = value) }
    }

    fun submitTransfer() {
        val snapshot = state.value
        val receiver = snapshot.resolvedReceiver ?: return
        if (snapshot.coins !in 1..snapshot.totalCoins) {
            form.update { it.copy(message = "Enter an amount you actually have.", isError = true) }
            return
        }

        viewModelScope.launch {
            form.update { it.copy(isSubmitting = true, message = null) }
            when (val result = transferRepository.transfer(receiver, snapshot.coins, snapshot.note)) {
                is Resource.Success -> {
                    walletRepository.refresh(forceServer = true)
                    loadHistory()
                    form.update {
                        it.copy(
                            isSubmitting = false,
                            message = "Sent ${result.data.coins} coins to ${result.data.receiverUsername}.",
                            isError = false,
                            receiverUsername = "",
                            coinsInput = "",
                            note = "",
                            resolvedReceiver = null,
                            suggestions = emptyList()
                        )
                    }
                }
                is Resource.Error -> {
                    viewModelScope.launch { walletRepository.refresh(forceServer = true) }
                    form.update {
                        it.copy(isSubmitting = false, message = result.message ?: "Transfer failed", isError = true)
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun clearMessage() = form.update { it.copy(message = null) }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
        const val SUGGEST_DEBOUNCE_MS = 300L
    }
}
