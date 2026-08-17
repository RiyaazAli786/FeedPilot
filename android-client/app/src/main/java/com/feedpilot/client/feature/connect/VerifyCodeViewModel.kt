package com.feedpilot.client.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VerifyUiState(
    val code: String = "",
    val verifying: Boolean = false,
    val resendSeconds: Int = 0,
    val error: String? = null,
    val verified: Boolean = false
)

/**
 * Branded one-time-code screen for the app's own account security (e.g. confirming a login or a
 * sensitive action). Entirely FeedPilot's own flow — not a copy of any third-party screen.
 *
 * The verify/resend calls are stubbed to talk to your backend; wire them to real endpoints when
 * the OTP API exists.
 */
const val CODE_LENGTH = 6

@HiltViewModel
class VerifyCodeViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state

    init { startResendCountdown() }

    fun onCodeChange(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(CODE_LENGTH)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == CODE_LENGTH) verify()
    }

    fun verify() {
        val code = _state.value.code
        if (code.length < CODE_LENGTH) {
            _state.update { it.copy(error = "Enter the $CODE_LENGTH-digit code") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, error = null) }
            // TODO: replace with backend call, e.g. authRepository.verifyCode(code)
            delay(600)
            _state.update { it.copy(verifying = false, verified = true) }
        }
    }

    fun resend() {
        if (_state.value.resendSeconds > 0) return
        viewModelScope.launch {
            // TODO: replace with backend call to re-send the code
            _state.update { it.copy(error = null) }
            startResendCountdown()
        }
    }

    private fun startResendCountdown() {
        viewModelScope.launch {
            _state.update { it.copy(resendSeconds = 30) }
            while (_state.value.resendSeconds > 0) {
                delay(1000)
                _state.update { it.copy(resendSeconds = it.resendSeconds - 1) }
            }
        }
    }
}
