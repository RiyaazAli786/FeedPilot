package com.feedpilot.client.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.SecureStorage
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountLogDao
import com.feedpilot.client.data.local.TaskDao
import com.feedpilot.client.data.local.WalletDao
import com.feedpilot.client.data.local.WithdrawalDao
import com.feedpilot.client.data.local.dao.OrderHistoryDao
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.AppSettings
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.ThemeMode
import com.feedpilot.client.data.repository.WalletRepository
import com.feedpilot.client.service.TaskRunnerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.feedpilot.client.data.repository.ReferralRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val walletRepository: WalletRepository,
    private val accountRepository: AccountRepository,
    private val accountDao: AccountDao,
    private val taskDao: TaskDao,
    private val walletDao: WalletDao,
    private val withdrawalDao: WithdrawalDao,
    private val accountLogDao: AccountLogDao,
    private val orderHistoryDao: OrderHistoryDao,
    private val referralRepository: ReferralRepository,
    private val secureStorage: SecureStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _referralCode = MutableStateFlow("")
    val referralCode: StateFlow<String> = _referralCode.asStateFlow()

    private val _referralBonusCoins = MutableStateFlow(100)
    val referralBonusCoins: StateFlow<Int> = _referralBonusCoins.asStateFlow()

    init {
        viewModelScope.launch {
            when (val res = referralRepository.getReferralStats()) {
                is Resource.Success -> {
                    _referralCode.value = res.data.referralCode
                    _referralBonusCoins.value = res.data.referralBonusCoins
                }
                else -> Unit
            }
        }
        // Support/Telegram links (and the rest of RunnerSettings) are otherwise only refreshed
        // by TaskRunnerService's own loop, which may not be running while the user is just
        // browsing Settings — force a fetch here so dashboard link edits show up on screen open.
        viewModelScope.launch { settingsRepository.syncRunnerSettingsFromBackend(force = true) }
    }

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings()
        )

    val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn
    val userEmail: StateFlow<String?> = authRepository.userEmail

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setTheme(mode) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { settingsRepository.setNotifications(v) }
    fun setAutoUpdate(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoUpdate(enabled) }
    fun setBackgroundSync(enabled: Boolean) = viewModelScope.launch { settingsRepository.setBackgroundSync(enabled) }
    fun setAutoPartialCancelledTasks(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoPartialCancelledTasks(enabled) }
    fun setActionDelayRange(minMs: Long, maxMs: Long) = viewModelScope.launch { settingsRepository.setActionDelayRange(minMs, maxMs) }
    fun setFetchDelay(ms: Long) = viewModelScope.launch { settingsRepository.setFetchDelay(ms) }
    fun setCooldownSeconds(seconds: Int) = viewModelScope.launch { settingsRepository.setCooldownSeconds(seconds) }
    fun setRandomStreakCounts(
        follow: Int,
        like: Int,
        comment: Int,
        repost: Int,
        savePost: Int,
        storyView: Int
    ) = viewModelScope.launch {
        settingsRepository.setRandomStreakCounts(follow, like, comment, repost, savePost, storyView)
    }

    fun clearCache() = viewModelScope.launch { context.cacheDir.deleteRecursively() }

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        authRepository.logout()
        onDone()
    }

    private val _backupCode = MutableStateFlow(secureStorage.get(SecureStorage.KEY_BACKUP_CODE))
    /** Non-null once this install has a Backup Code set up — see [generateBackupCode]. */
    val backupCode: StateFlow<String?> = _backupCode.asStateFlow()

    /**
     * Single-tap data-recovery setup — see [AuthRepository.generateBackupCode]. Caches the code
     * locally (so it can be shown again without re-generating) and refreshes it into this
     * screen's state.
     */
    fun generateBackupCode(onResult: (success: Boolean, codeOrMessage: String) -> Unit) {
        viewModelScope.launch {
            when (val res = authRepository.generateBackupCode()) {
                is Resource.Success -> {
                    secureStorage.put(SecureStorage.KEY_BACKUP_CODE, res.data)
                    _backupCode.value = res.data
                    onResult(true, res.data)
                }
                is Resource.Error -> onResult(false, res.message ?: "Could not secure this account.")
                Resource.Loading -> Unit
            }
        }
    }

    /**
     * Restores the account tied to a previously generated Backup Code — see
     * [AuthRepository.restoreWithBackupCode]. Replaces whatever account is currently active
     * (including a fresh one a reinstall may have just created); the wallet and linked-account
     * lists are refreshed immediately afterward so the restored coin balance shows without
     * needing to restart the app.
     */
    fun restoreWithBackupCode(code: String, onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            when (val res = authRepository.restoreWithBackupCode(code)) {
                is Resource.Success -> {
                    secureStorage.put(SecureStorage.KEY_BACKUP_CODE, code.trim())
                    _backupCode.value = code.trim()
                    walletRepository.refresh()
                    // Retried, not fire-and-forget: this is the account_logs completed-task
                    // backfill's first and highest-stakes chance to land before the operator taps
                    // Start — see AccountRepository.refreshWithRetry's doc for why that matters.
                    accountRepository.refreshWithRetry()
                    onResult(true, "Account restored — your coins are back.")
                }
                is Resource.Error -> onResult(false, res.message ?: "Could not restore with that code.")
                Resource.Loading -> Unit
            }
        }
    }

    fun deleteAccount(onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            TaskRunnerService.stop(context)
            when (val res = authRepository.deleteCurrentAccount()) {
                is Resource.Success -> {
                    clearLocalAccountData()
                    onResult(true, "Account deleted.")
                }
                is Resource.Error -> onResult(false, res.message ?: "Could not delete account.")
                Resource.Loading -> Unit
            }
        }
    }

    private suspend fun clearLocalAccountData() {
        taskDao.clearAll()
        accountLogDao.clearAll()
        orderHistoryDao.clearAll()
        walletDao.clearTransactions()
        walletDao.clearWallet()
        withdrawalDao.clearAll()
        accountDao.clearAll()

        val suffix = context.packageName.replace('.', '_')
        listOf(
            "accounts_backup_$suffix.json",
            "wallet_backup_$suffix.json"
        ).forEach { name ->
            runCatching { java.io.File(context.filesDir, name).delete() }
        }
    }
}
