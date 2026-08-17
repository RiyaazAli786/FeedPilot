package com.feedpilot.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What the app should show before the tabs: authenticating, ready, or unreachable. */
enum class AuthGate { Checking, Ready, Offline }

@HiltViewModel
class RootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    // Local phase of the automatic sign-in; combined with the real login flag below.
    private val phase = MutableStateFlow(
        if (authRepository.isLoggedIn.value) AuthGate.Ready else AuthGate.Checking
    )
    private var attemptedEmptyAccountRecovery = false

    /**
     * A signed-in session always wins (covers both device auth and a manual email login), so
     * the tabs show the moment a token exists; otherwise the phase decides between the
     * loading and the offline-retry screens.
     */
    val gate: StateFlow<AuthGate> =
        combine(authRepository.isLoggedIn, phase) { loggedIn, ph ->
            if (loggedIn) AuthGate.Ready else ph
        }.stateIn(viewModelScope, SharingStarted.Eagerly, phase.value)

    val themeMode: StateFlow<ThemeMode> =
        settingsRepository.settings
            .map { it.theme }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.LIGHT)

    val accounts: StateFlow<List<AccountEntity>> =
        accountRepository.accounts
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * `null` until the account list has actually been read, then whether one is linked.
     *
     * Navigation has to tell "not known yet" apart from "none linked": treating the former as the
     * latter is what showed the login screen to a user who was already signed in.
     */
    val hasAccounts: StateFlow<Boolean?> =
        accountRepository.accounts
            .map<List<AccountEntity>, Boolean?> { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            authRepository.isLoggedIn.collect { loggedIn ->
                if (!loggedIn &&
                    phase.value == AuthGate.Ready &&
                    authRepository.consumeDeviceSessionRecoveryRequest()
                ) {
                    authenticate()
                }
            }
        }
        viewModelScope.launch {
            hasAccounts.filterNotNull().collect { hasAnyAccounts ->
                if (!hasAnyAccounts &&
                    authRepository.isLoggedIn.value &&
                    authRepository.isDeviceAccountSession() &&
                    !attemptedEmptyAccountRecovery
                ) {
                    attemptedEmptyAccountRecovery = true
                    val ok = authRepository.reloadDeviceSession(BuildConfig.VERSION_NAME)
                    if (ok) {
                        accountRepository.refresh()
                    }
                }
            }
        }
        authenticate()
    }

    /**
     * Signs the app in automatically for this install/clone. No-op if already signed in.
     * Exposed so the offline-retry screen can call it again.
     */
    fun authenticate() {
        if (authRepository.isLoggedIn.value) {
            phase.value = AuthGate.Ready
            return
        }
        phase.value = AuthGate.Checking
        viewModelScope.launch {
            val ok = withTimeoutOrNull(8_000) {
                authRepository.ensureDeviceSession(BuildConfig.VERSION_NAME)
            } == true
            phase.value = AuthGate.Ready
            if (ok) {
                accountRepository.refresh()
            } else {
                retryDeviceAuthInBackground()
            }
        }
    }

    private fun retryDeviceAuthInBackground() {
        viewModelScope.launch {
            repeat(8) {
                kotlinx.coroutines.delay(15_000)
                if (authRepository.isLoggedIn.value) return@launch
                val ok = authRepository.ensureDeviceSession(BuildConfig.VERSION_NAME)
                if (ok) {
                    accountRepository.refresh()
                    return@launch
                }
            }
        }
    }
}

