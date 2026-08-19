package com.feedpilot.client.feature.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.local.AccountLogDao
import com.feedpilot.client.data.local.AccountLogEntity
import com.feedpilot.client.data.local.TaskDao
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.data.repository.TaskRepository
import com.feedpilot.client.data.repository.WalletRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.AppSettings
import com.feedpilot.client.service.RunnerStats
import com.feedpilot.client.service.TaskRunnerService
import com.feedpilot.client.service.TaskRunnerState
import com.feedpilot.client.task.EngagementEngine
import com.feedpilot.client.task.EngagementResult
import com.feedpilot.client.task.EngagementTaskType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Task type the user runs. Each follow, like, comment, repost, and save awards +1 coin. */
enum class TaskMode(val label: String, val reward: String) {
    LIKE("Like", "+1 Coin"),
    FOLLOW("Follow", "+1 Coin"),
    COMMENT("Comment (Random)", "+1 Coin"),
    COMMENT_CUSTOM("Comment (Custom)", "+1 Coin"),
    REPOST("Repost", "+1 Coin"),
    SAVE_POST("Save", "+1 Coin"),
    STORY_VIEW("Story View", "+1 Coin"),
    RANDOM("Random", "+1 Coin");

    /** Order types the runner may execute in this mode. */
    val allowedTypes: Set<EngagementTaskType>
        get() = when (this) {
            LIKE -> setOf(EngagementTaskType.LIKE)
            FOLLOW -> setOf(EngagementTaskType.FOLLOW)
            COMMENT -> setOf(EngagementTaskType.COMMENT)
            COMMENT_CUSTOM -> setOf(EngagementTaskType.COMMENT)
            REPOST -> setOf(EngagementTaskType.REPOST)
            SAVE_POST -> setOf(EngagementTaskType.SAVE_POST)
            STORY_VIEW -> setOf(EngagementTaskType.STORY_VIEW)
            RANDOM -> EngagementTaskType.entries.toSet()
        }
}

data class TasksUiState(
    val coins: Long = 0,
    val diamonds: Long = 0,
    val singleTasking: Boolean = true,
    val globalTaskMode: TaskMode = TaskMode.RANDOM,
    val accountTaskModes: Map<String, TaskMode> = emptyMap(),
    val accounts: List<AccountEntity> = emptyList(),
    val enabledAccountIds: Set<String> = emptySet(),
    val runner: RunnerStats = RunnerStats(),
    /** Accounts currently mid session-check — shows the progress spinner on that card. */
    val verifyingAccountIds: Set<String> = emptySet(),
    val validatingAccountIds: Set<String> = emptySet(),
    val settings: AppSettings = AppSettings()
) {
    val running: Boolean get() = runner.running || validatingAccountIds.isNotEmpty()

    fun getTaskModeForAccount(accountId: String): TaskMode =
        accountTaskModes[accountId] ?: globalTaskMode
}

data class ProfileUpdateState(
    val running: Boolean = false,
    val accountId: String? = null,
    val text: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val error: String? = null
)

private data class TaskFlags(
    val singleTasking: Boolean,
    val globalMode: TaskMode,
    val accountModes: Map<String, TaskMode>,
    val enabled: Set<String>?,
    val verifying: Set<String>,
    val validating: Set<String>
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository,
    private val walletRepository: WalletRepository,
    private val taskRepository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val runnerState: TaskRunnerState,
    private val accountLogDao: AccountLogDao,
    private val accountDao: AccountDao,
    private val taskDao: TaskDao,
    private val instagramRepository: InstagramRepository,
    private val engagementEngine: EngagementEngine
) : AndroidViewModel(application) {

    private val singleTasking = MutableStateFlow(true)
    private val globalTaskMode = MutableStateFlow(TaskMode.RANDOM)
    private val accountTaskModes = MutableStateFlow<Map<String, TaskMode>>(emptyMap())
    private val enabled = MutableStateFlow<Set<String>?>(emptySet())
    private val verifyingAccountIds = MutableStateFlow<Set<String>>(emptySet())
    private val validatingAccountIds = MutableStateFlow<Set<String>>(emptySet())
    private val _loginMessage = MutableStateFlow<String?>(null)
    /** One-shot toast text for the account-card login/verify flow — see [consumeLoginMessage]. */
    val loginMessage: StateFlow<String?> = _loginMessage.asStateFlow()
    private val _profileUpdateState = MutableStateFlow(ProfileUpdateState())
    val profileUpdateState: StateFlow<ProfileUpdateState> = _profileUpdateState.asStateFlow()

    private val flagsFlow = combine(
        singleTasking,
        globalTaskMode,
        accountTaskModes,
        enabled,
        combine(verifyingAccountIds, validatingAccountIds, ::Pair)
    ) { s, gm, am, e, (v, valIds) ->
        TaskFlags(s, gm, am, e, v, valIds)
    }

    val state: StateFlow<TasksUiState> =
        combine(
            accountRepository.accounts,
            walletRepository.wallet,
            runnerState.stats,
            settingsRepository.settings,
            flagsFlow
        ) { accounts, wallet, runner, settings, flags ->
            val activeEnabled = flags.enabled ?: emptySet()
            TasksUiState(
                coins = wallet?.totalCoins ?: 0,
                diamonds = 0,
                singleTasking = flags.singleTasking,
                globalTaskMode = flags.globalMode,
                accountTaskModes = flags.accountModes,
                accounts = accounts,
                enabledAccountIds = activeEnabled,
                runner = runner,
                verifyingAccountIds = flags.verifying,
                validatingAccountIds = flags.validating,
                settings = settings
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksUiState())

    init {
        viewModelScope.launch { accountRepository.refresh() }
        viewModelScope.launch { walletRepository.refresh() }
        viewModelScope.launch { taskRepository.downloadPending() }
    }

    fun toggleSingleTasking() { singleTasking.value = !singleTasking.value }
    fun setTaskMode(mode: TaskMode) { globalTaskMode.value = mode }

    fun setAccountTaskMode(accountId: String, mode: TaskMode) {
        accountTaskModes.value = accountTaskModes.value + (accountId to mode)
    }

    fun toggleSelectAll() {
        val allIds = state.value.accounts.map { it.id }.toSet()
        val currentEnabled = state.value.enabledAccountIds
        enabled.value = if (currentEnabled.size == allIds.size) emptySet() else allIds
    }

    /**
     * Starts or stops just [accountId]'s own runner loop — never any other account's.
     */
    fun startSingleAccount(accountId: String) {
        if (runnerState.isAccountRunning(accountId) || accountId in validatingAccountIds.value) {
            validatingAccountIds.value = validatingAccountIds.value - accountId
            TaskRunnerService.stop(getApplication(), listOf(accountId))
            return
        }
        viewModelScope.launch {
            validatingAccountIds.value = validatingAccountIds.value + accountId
            try {
                val account = accountDao.getById(accountId)
                if (account != null && account.status.contains("CHALLENGE", ignoreCase = true)) {
                    _loginMessage.value = "Account @${account.username} has an Instagram Challenge Required. Tap the browser (🌐) icon on the account card to resolve it."
                    return@launch
                }
                // Set by TaskRunnerService the moment a task fails with "Session logged out" —
                // without this check a manually-restarted run would just immediately hit the
                // same failure and stop again instead of being blocked up front with a clear
                // "re-add this account" message.
                if (account != null && account.status.contains("LOGGED_OUT", ignoreCase = true)) {
                    _loginMessage.value = "Account @${account.username}'s Instagram session is logged out. Tap the browser (🌐) icon on the account card to log in."
                    return@launch
                }
                if (account != null && !accountRepository.isUsernamePicked(account.username)) {
                    _loginMessage.value = "Selected account (@${account.username}) was not created using a picked suggested username. You cannot execute tasks with un-picked usernames."
                    return@launch
                }

                val check = accountRepository.meetsOrderStartRequirements(accountId)
                if (check is Resource.Error) {
                    _loginMessage.value = check.message
                    return@launch
                }

                val currentEnabled = enabled.value ?: emptySet()
                enabled.value = currentEnabled + accountId
                val current = state.value
                val perAccountModes = mapOf(accountId to current.getTaskModeForAccount(accountId).allowedTypes)
                TaskRunnerService.start(
                    context = getApplication(),
                    accountIds = listOf(accountId),
                    accountModes = perAccountModes,
                    singleTasking = true
                )
            } finally {
                validatingAccountIds.value = validatingAccountIds.value - accountId
            }
        }
    }

    fun toggleAccount(id: String) {
        val current = state.value.enabledAccountIds
        enabled.value = if (id in current) current - id else current + id
    }

    /**
     * Removes the account, stopping its own runner loop first if it's currently active.
     */
    fun removeAccount(id: String) = viewModelScope.launch {
        validatingAccountIds.value = validatingAccountIds.value - id
        if (runnerState.isAccountRunning(id)) {
            TaskRunnerService.stop(getApplication(), listOf(id))
        }
        accountRepository.removeAccount(id)
    }

    /**
     * Starts the background runner, checking requirements first with visual validation feedback.
     */
    fun start() = viewModelScope.launch {
        val current = state.value
        val targetIds = current.enabledAccountIds.toList()
        if (targetIds.isEmpty()) return@launch
        validatingAccountIds.value = validatingAccountIds.value + targetIds
        try {
            val (eligible, blocked) = checkStartEligibility(targetIds)
            if (blocked.isNotEmpty()) _loginMessage.value = blocked.joinToString("\n")
            if (eligible.isEmpty()) return@launch

            val perAccountModes = eligible.associateWith { id ->
                current.getTaskModeForAccount(id).allowedTypes
            }
            TaskRunnerService.start(
                context = getApplication(),
                accountIds = eligible,
                accountModes = perAccountModes,
                singleTasking = current.singleTasking
            )
        } finally {
            validatingAccountIds.value = validatingAccountIds.value - targetIds
        }
    }

    /** Runs [AccountRepository.meetsOrderStartRequirements] over [accountIds]; splits into (eligible ids, blocked messages). */
    private suspend fun checkStartEligibility(accountIds: List<String>): Pair<List<String>, List<String>> {
        val eligible = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        for (id in accountIds) {
            val account = accountDao.getById(id)
            if (account != null && account.status.contains("CHALLENGE", ignoreCase = true)) {
                blocked += "Account @${account.username} has an Instagram Challenge Required. Open browser (🌐) to resolve."
                continue
            }
            if (account != null && account.status.contains("LOGGED_OUT", ignoreCase = true)) {
                blocked += "Account @${account.username}'s Instagram session is logged out. Open browser (🌐) to log in."
                continue
            }
            if (account != null && !accountRepository.isUsernamePicked(account.username)) {
                blocked += "Selected account (@${account.username}) was not created using a picked suggested username. You cannot execute tasks with un-picked usernames."
                continue
            }
            when (val check = accountRepository.meetsOrderStartRequirements(id)) {
                is Resource.Success -> eligible += id
                is Resource.Error -> blocked += check.message
                is Resource.Loading -> Unit
            }
        }
        return eligible to blocked
    }

    fun stop() {
        validatingAccountIds.value = emptySet()
        TaskRunnerService.stop(getApplication())
    }

    /** Returns a live page of action log entries for the given account, newest-first. */
    fun logsForAccount(accountId: String, limit: Int = 50): Flow<List<AccountLogEntity>> =
        accountLogDao.observeForAccount(accountId, limit)

    /**
     * Wipes the action log for one account.
     *
     * Note this also clears the runner's duplicate protection: `hasAccountCompletedTarget`
     * reads these same rows, so targets this account already engaged with become eligible
     * again. The confirmation prompt says so.
     */
    fun clearLogsForAccount(accountId: String) = viewModelScope.launch {
        accountLogDao.clearForAccount(accountId)
    }

    /**
     * Confirms an account's saved session is still actually signed in on instagram.com and,
     * on success, lights up its green dot. Tapped from the account card — see
     * [AccountRepository.verifySession].
     */
    fun verifyAccountLogin(accountId: String) {
        if (accountId in verifyingAccountIds.value) return
        viewModelScope.launch {
            verifyingAccountIds.value = verifyingAccountIds.value + accountId
            val result = accountRepository.verifySession(accountId)
            verifyingAccountIds.value = verifyingAccountIds.value - accountId
            _loginMessage.value = when (result) {
                is com.feedpilot.client.common.Resource.Success -> "Logged in — account activated"
                is com.feedpilot.client.common.Resource.Error -> result.message
                is com.feedpilot.client.common.Resource.Loading -> null
            }
        }
    }

    /**
     * Re-fetches the account profile picture from Instagram session and updates local/backend DB ONLY on success.
     */
    fun refreshAccountProfile(accountId: String) {
        if (accountId in verifyingAccountIds.value) return
        viewModelScope.launch {
            verifyingAccountIds.value = verifyingAccountIds.value + accountId
            val result = accountRepository.refreshProfilePicture(accountId)
            verifyingAccountIds.value = verifyingAccountIds.value - accountId
            _loginMessage.value = when (result) {
                is com.feedpilot.client.common.Resource.Success -> "Profile picture updated successfully!"
                is com.feedpilot.client.common.Resource.Error -> result.message
                is com.feedpilot.client.common.Resource.Loading -> null
            }
        }
    }

    fun clearProfileUpdateMessage() {
        _profileUpdateState.value = _profileUpdateState.value.copy(message = null, error = null)
    }

    fun runProfileUpdate(
        accountId: String,
        bio: String,
        profilePicBytes: ByteArray?,
        storyImages: List<ByteArray>,
        feedImages: List<ByteArray>,
        feedCaption: String
    ) {
        if (_profileUpdateState.value.running) return
        viewModelScope.launch {
            val account = accountDao.getById(accountId)
            if (account == null) {
                _profileUpdateState.value = ProfileUpdateState(error = "Account not found.")
                return@launch
            }
            if (account.sessionCookies.isBlank()) {
                _profileUpdateState.value = ProfileUpdateState(error = "Instagram session missing. Log in again.")
                return@launch
            }

            val trimmedBio = bio.trim()
            val total = (if (profilePicBytes != null) 1 else 0) +
                (if (trimmedBio.isNotBlank()) 1 else 0) +
                storyImages.size +
                feedImages.size
            if (total == 0) {
                _profileUpdateState.value = ProfileUpdateState(error = "Select an image or enter a bio first.")
                return@launch
            }

            var done = 0
            var failed = 0
            fun progress(text: String) {
                _profileUpdateState.value = ProfileUpdateState(
                    running = true,
                    accountId = accountId,
                    text = text,
                    done = done,
                    total = total
                )
            }

            _profileUpdateState.value = ProfileUpdateState(running = true, accountId = accountId, done = 0, total = total)

            profilePicBytes?.let { bytes ->
                progress("Updating profile picture...")
                instagramRepository.getProfilePicProps(account.username, account.sessionCookies)
                if (!instagramRepository.updateProfilePicture(bytes, account.sessionCookies)) failed++
                done++
            }

            if (trimmedBio.isNotBlank()) {
                progress("Updating bio...")
                val profile = instagramRepository.getLoggedInUserProfile(account.sessionCookies)
                val firstName = profile?.fullName ?: account.username
                if (!instagramRepository.updateBiography(trimmedBio, firstName, account.username, account.sessionCookies)) failed++
                done++
            }

            storyImages.forEachIndexed { index, bytes ->
                val uploadId = "${System.currentTimeMillis()}_${index}_story"
                progress("Uploading story ${index + 1}/${storyImages.size}...")
                val uploaded = instagramRepository.uploadPhoto(bytes, uploadId, isStory = true, account.sessionCookies)
                val configured = uploaded && instagramRepository.configureStory(uploadId, account.sessionCookies)
                if (!configured) failed++
                done++
            }

            feedImages.forEachIndexed { index, bytes ->
                val uploadId = "${System.currentTimeMillis()}_${index}_feed"
                progress("Uploading feed post ${index + 1}/${feedImages.size}...")
                val uploaded = instagramRepository.uploadPhoto(bytes, uploadId, isStory = false, account.sessionCookies)
                val configured = uploaded && instagramRepository.configurePhoto(uploadId, feedCaption.trim(), account.sessionCookies)
                if (!configured) failed++
                done++
            }

            val finalMessage = if (failed == 0) {
                "Profile update completed."
            } else {
                "Profile update finished with $failed failed item${if (failed == 1) "" else "s"}."
            }
            _profileUpdateState.value = ProfileUpdateState(
                running = false,
                accountId = accountId,
                text = finalMessage,
                done = done,
                total = total,
                message = finalMessage,
                error = if (failed > 0) finalMessage else null
            )
            refreshAccountProfile(accountId)
        }
    }

    /**
     * Retries a failed action log entry for an Instagram account.
     *
     * This drives Instagram directly and is never reported to the backend (there is no live
     * order/task behind an old log entry to report against) — its "success" is only this device's
     * own on-device check, not a backend-confirmed completion the way a normal claimed task's is.
     * On success it still awards the dashboard-configured coin reward for that action type (per
     * product decision: manual retries should pay out like any other completed action), priced
     * via the same rewardCoinsForTaskType RunnerSettingsStore mirror TaskRepository uses. Because
     * there is no backend round-trip to reconcile against, this credit is permanent and cannot
     * self-correct the way a normal claimed task's optimistic credit does — keep that in mind if
     * account/wallet totals are ever audited against the backend's own numbers.
     */
    fun retryFailedActionLog(log: AccountLogEntity, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val account = accountDao.getById(log.accountId) ?: run {
                    _loginMessage.value = "Account not found"
                    return@launch
                }

                val taskType = parseTaskTypeFromAction(log.action)
                val target = log.target

                val selectedCommentText = if (taskType == EngagementTaskType.COMMENT) {
                    getCommentForTarget(target)
                } else ""

                val outcome = when (taskType) {
                    EngagementTaskType.FOLLOW -> engagementEngine.follow(target, account.id)
                    EngagementTaskType.LIKE -> engagementEngine.like(target, account.id)
                    EngagementTaskType.COMMENT -> engagementEngine.comment(target, selectedCommentText, account.id)
                    EngagementTaskType.REPOST -> engagementEngine.repost(target, account.id)
                    EngagementTaskType.SAVE_POST -> engagementEngine.savePost(target, account.id)
                    EngagementTaskType.STORY_VIEW -> engagementEngine.storyView(target, account.id)
                }

                when (outcome) {
                    is EngagementResult.Success -> {
                        val credit = taskRepository.submitManualActionResult(
                            accountId = account.id,
                            taskType = taskType.wireName,
                            target = target,
                            message = "Manual retry succeeded for $target"
                        )
                        val rewardCoins = when (credit) {
                            is Resource.Success -> credit.data.toLong()
                            is Resource.Error -> {
                                _loginMessage.value = credit.message
                                return@launch
                            }
                            Resource.Loading -> 0L
                        }
                        val successMsg = "Retry successful (+${rewardCoins} coins)"

                        // Update log in DB to success
                        accountLogDao.insert(
                            log.copy(
                                success = true,
                                message = successMsg,
                                timestampMs = System.currentTimeMillis()
                            )
                        )

                        // Update live runner stats for card completed count
                        runnerState.recordSuccess(account.id, rewardCoins.toInt(), successMsg)

                        _loginMessage.value = "Retry successful! +${rewardCoins} coin(s) awarded."
                    }
                    is EngagementResult.Failure -> {
                        val failMsg = outcome.reason ?: "Retry failed"
                        accountLogDao.insert(
                            log.copy(
                                message = failMsg,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                        _loginMessage.value = "Retry failed: $failMsg"
                    }
                }
            } catch (t: Throwable) {
                _loginMessage.value = "Retry failed: ${t.message ?: "Unknown error"}"
            } finally {
                onDone?.invoke()
            }
        }
    }

    private suspend fun getCommentForTarget(target: String): String {
        val dbComment = taskDao.getCommentTextForTarget(target)?.trim()
        if (!dbComment.isNullOrBlank()) {
            return dbComment
        }
        val defaultComments = listOf(
            "Awesome post! 🔥",
            "Great content, keep it up! 👍",
            "Love this! ❤️",
            "Super cool! ✨",
            "Amazing view! 💯"
        )
        val index = kotlin.math.abs(target.hashCode()) % defaultComments.size
        return defaultComments[index]
    }

    private fun parseTaskTypeFromAction(action: String): EngagementTaskType {
        return when {
            action.contains("Follow", ignoreCase = true) -> EngagementTaskType.FOLLOW
            action.contains("Like", ignoreCase = true) -> EngagementTaskType.LIKE
            action.contains("Repost", ignoreCase = true) || action.contains("Reshare", ignoreCase = true) -> EngagementTaskType.REPOST
            action.contains("Save", ignoreCase = true) -> EngagementTaskType.SAVE_POST
            action.contains("Comment", ignoreCase = true) -> EngagementTaskType.COMMENT
            action.contains("Story", ignoreCase = true) -> EngagementTaskType.STORY_VIEW
            else -> EngagementTaskType.LIKE
        }
    }

    fun consumeLoginMessage() { _loginMessage.value = null }
}


