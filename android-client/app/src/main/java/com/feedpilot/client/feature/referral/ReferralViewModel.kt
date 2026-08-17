package com.feedpilot.client.feature.referral

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.dto.ReferralStatsDto
import com.feedpilot.client.data.repository.ReferralRepository
import com.feedpilot.client.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReferralViewModel @Inject constructor(
    private val referralRepository: ReferralRepository,
    private val walletRepository: WalletRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReferralUiState>(ReferralUiState.Loading)
    val uiState: StateFlow<ReferralUiState> = _uiState.asStateFlow()

    private val _applySuccessMessage = MutableStateFlow<String?>(null)
    val applySuccessMessage: StateFlow<String?> = _applySuccessMessage.asStateFlow()

    private val _applyErrorMessage = MutableStateFlow<String?>(null)
    val applyErrorMessage: StateFlow<String?> = _applyErrorMessage.asStateFlow()

    private val _isApplying = MutableStateFlow(false)
    val isApplying: StateFlow<Boolean> = _isApplying.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = ReferralUiState.Loading
            when (val result = referralRepository.getReferralStats()) {
                is Resource.Success -> _uiState.value = ReferralUiState.Success(result.data)
                is Resource.Error -> _uiState.value = ReferralUiState.Error(result.message)
                Resource.Loading -> {}
            }
        }
    }

    fun applyCode(code: String) {
        _applyErrorMessage.value = null
        _applySuccessMessage.value = null
        if (code.isBlank()) {
            _applyErrorMessage.value = "Please enter a referral code"
            return
        }
        viewModelScope.launch {
            _isApplying.value = true
            when (val result = referralRepository.applyReferralCode(code.trim())) {
                is Resource.Success -> {
                    _applySuccessMessage.value = result.data.message
                    walletRepository.refresh()
                    loadStats()
                }
                is Resource.Error -> {
                    _applyErrorMessage.value = result.message
                }
                Resource.Loading -> {}
            }
            _isApplying.value = false
        }
    }

    fun clearMessages() {
        _applySuccessMessage.value = null
        _applyErrorMessage.value = null
    }
}

sealed interface ReferralUiState {
    object Loading : ReferralUiState
    data class Success(val stats: ReferralStatsDto) : ReferralUiState
    data class Error(val message: String) : ReferralUiState
}
