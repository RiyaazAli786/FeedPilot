package com.feedpilot.client.feature.upgrade

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.isUpgradedWithin24h
import com.feedpilot.client.common.isWithinLast24h
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.InstagramUserProfileDetails
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.feature.upgrade.provider.UpgradeDataProvider
import com.feedpilot.client.service.UpgradeRunnerService
import com.feedpilot.client.service.UpgradeRunnerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpgradeUiState(
    val isLoading: Boolean = true,
    /** The account (green-dot verified) the checklist and upgrade apply to; null if none active. */
    val activeAccount: AccountEntity? = null,
    /** True once every checklist task above has verified COMPLETED against Instagram. */
    val checklistComplete: Boolean = false,
    /** True when the active account already has a live 24h upgrade window on the backend. */
    val isUpgraded: Boolean = false,
    val tasks: List<UpgradeTaskItem> = emptyList(),
    val message: String? = null
) {
    val hasActiveAccount: Boolean get() = activeAccount != null
}

@HiltViewModel
class UpgradeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val instagramRepository: InstagramRepository,
    private val upgradeDataProvider: UpgradeDataProvider,
    private val upgradeRunnerState: UpgradeRunnerState,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private companion object {
        const val TAG = "UpgradeViewModel"
    }

    private val _state = MutableStateFlow(UpgradeUiState())

    /**
     * [_state] holds whatever the last full checklist reload found; [UpgradeRunnerState.snapshot]
     * holds whatever [UpgradeRunnerService] is reporting live for the task it's currently running
     * (or just finished) — a task started here keeps running in that service specifically so it
     * survives this ViewModel being cleared, so its progress has to be picked up from there
     * rather than from anything only this ViewModel would remember. Combining them on every
     * emission (rather than a one-shot merge) means that even a ViewModel created *after* the
     * service already started a task — e.g. the user left the Upgrade screen mid-upload and came
     * back — immediately reflects it instead of showing a stale Pending row until the service's
     * next progress tick.
     */
    val state: StateFlow<UpgradeUiState> = combine(_state, upgradeRunnerState.snapshot) { local, snap ->
        if (local.activeAccount == null || snap.accountId != local.activeAccount.id) {
            return@combine local
        }
        val mergedTasks = local.tasks.map { t ->
            snap.tasks[t.id]?.let { p -> t.copy(status = p.status, progressText = p.progressText) } ?: t
        }
        local.copy(
            tasks = mergedTasks,
            checklistComplete = mergedTasks.all { it.status == TaskStatus.COMPLETED },
            message = snap.message ?: local.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpgradeUiState())

    init {
        loadUpgradeState()
        viewModelScope.launch {
            upgradeRunnerState.snapshot.collect { snap ->
                val allTask = snap.tasks["all"]
                if (allTask != null && allTask.status == TaskStatus.COMPLETED) {
                    loadUpgradeState()
                }
            }
        }
        viewModelScope.launch {
            state.collect { s ->
                if (s.hasActiveAccount && s.checklistComplete && !s.isUpgraded && !s.isLoading && !upgradeRunnerState.anyRunning(s.activeAccount?.id ?: "")) {
                    Log.i(TAG, "Checklist completed! Automatically upgrading account...")
                    performUpgrade()
                }
            }
        }
    }

    fun loadUpgradeState() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }

        // Pulls the latest UpgradedAt from the backend first — it's the source of truth for the
        // 24h window, and a stale local copy (e.g. upgraded from another session) must not read
        // as still-upgraded, or as upgradeable, past its actual expiry.
        runCatching { accountRepository.refresh() }

        val activeAcc = accountRepository.getActiveAccount()
        if (activeAcc == null) {
            _state.update {
                UpgradeUiState(
                    isLoading = false,
                    activeAccount = null,
                    tasks = baseTasks(),
                    message = "No active account. Tap an account's avatar on the Tasks screen and log in first."
                )
            }
            return@launch
        }

        // A task the runner service is still working on for this account must not be clobbered
        // by a fresh-from-Instagram Pending row here — the merge above only overlays [_state]'s
        // *current* task, and a reload replaces the whole list, so without this a checklist
        // reload mid-upload would flash the task back to its pre-upload state for a moment.
        val runningTaskId = upgradeRunnerState.snapshot.value
            .takeIf { it.accountId == activeAcc.id }
            ?.tasks?.entries?.firstOrNull { it.value.status == TaskStatus.PROCESSING }?.key

        val profile = runCatching {
            instagramRepository.getLoggedInUserProfile(activeAcc.sessionCookies)
        }.getOrNull()
        val hasStory = profile?.let {
            runCatching { instagramRepository.hasActiveStory(it.id, activeAcc.sessionCookies) }.getOrDefault(false)
        } ?: false

        val postsToday = profile?.let {
            val feed = runCatching { instagramRepository.getUserFeed(it.id, customCookies = activeAcc.sessionCookies) }.getOrNull()
            val nowSec = System.currentTimeMillis() / 1000
            val oneDayAgoSec = nowSec - 24 * 60 * 60
            feed?.items?.count { item -> item.takenAt >= oneDayAgoSec } ?: 0
        } ?: 0

        var taskList = buildTaskList(activeAcc, profile, hasStory, postsToday)
        if (runningTaskId != null) {
            taskList = taskList.map { t -> if (t.id == runningTaskId) t.copy(status = TaskStatus.PROCESSING) else t }
        }
        val allDone = taskList.all { it.status == TaskStatus.COMPLETED }

        _state.update {
            it.copy(
                isLoading = false,
                activeAccount = activeAcc,
                tasks = taskList,
                checklistComplete = allDone,
                isUpgraded = isUpgradedWithin24h(activeAcc.upgradedAt)
            )
        }
    }

    private fun baseTasks(status: TaskStatus = TaskStatus.PENDING) = listOf(
        UpgradeTaskItem("posts", "6\nPosts", "Send 6 Posts", "Upload at least 6 posts to your Instagram account", status),
        UpgradeTaskItem("profile", "Upload\nProfile", "Upload Your Profile", "Add a photo to your Instagram profile", status),
        UpgradeTaskItem("story", "Upload\nStory", "Upload Story", "Upload one story on Instagram", status),
        UpgradeTaskItem("bio", "Add\nBio", "Add bio to instagram", "Write a bio on your Instagram account", status)
    )

    private fun buildTaskList(
        account: AccountEntity,
        profile: InstagramUserProfileDetails?,
        hasStory: Boolean,
        postsToday: Int
    ): List<UpgradeTaskItem> {
        fun statusOf(done: Boolean) = if (done) TaskStatus.COMPLETED else TaskStatus.PENDING
        return listOf(
            UpgradeTaskItem("posts", "6\nPosts", "Send 6 Posts", "Upload at least 6 posts to your Instagram account",
                statusOf(postsToday >= 6)),
            // Instagram has no "when was this set" signal for a profile photo or bio the way it
            // does for posts (upload timestamp) and stories (expire after 24h) — a photo/bio from
            // months ago would otherwise read as "done" forever. Also requiring today's local
            // verification timestamp (set by UpgradeRunnerService once this app confirms it) is
            // what makes these two behave like the other two: complete only for today, and reset
            // the next day even if Instagram still shows the same photo/bio.
            UpgradeTaskItem("profile", "Upload\nProfile", "Upload Your Profile", "Add a photo to your Instagram profile",
                statusOf(profile != null && !profile.hasAnonymousProfilePic &&
                    isWithinLast24h(account.profileTaskCompletedAtMs))),
            UpgradeTaskItem("story", "Upload\nStory", "Upload Story", "Upload one story on Instagram",
                statusOf(hasStory)),
            UpgradeTaskItem("bio", "Add\nBio", "Add bio to instagram", "Write a bio on your Instagram account",
                statusOf(!profile?.biography.isNullOrBlank() && isWithinLast24h(account.bioTaskCompletedAtMs)))
        )
    }

    /**
     * Starts (or, if already running, leaves alone) [taskId] on [UpgradeRunnerService] — its
     * upload/verify work happens there, independent of this screen, so navigating away or
     * closing the app no longer cancels it. See [UpgradeRunnerService] for why.
     */
    fun verifyTask(taskId: String) {
        Log.i(TAG, "verifyTask tapped: taskId=$taskId")
        val activeAcc = _state.value.activeAccount
        if (activeAcc == null) {
            Log.w(TAG, "verifyTask ignored: no active account")
            _state.update { it.copy(message = "No active account. Tap an account's avatar on the Tasks screen and log in first.") }
            return
        }
        if (upgradeRunnerState.anyRunning(activeAcc.id)) {
            Log.w(TAG, "verifyTask ignored: a task is already running for @${activeAcc.username}")
            return
        }
        if (activeAcc.sessionCookies.isBlank()) {
            _state.update { it.copy(message = "Instagram session missing. Log in to this account again.") }
            return
        }

        upgradeDataProvider.clearCache()
        UpgradeRunnerService.start(context, activeAcc.id, taskId)
    }

    fun toggleTaskStatus(taskId: String) {
        val currentTask = state.value.tasks.find { it.id == taskId } ?: return
        if (currentTask.status == TaskStatus.PROCESSING) return
        verifyTask(taskId)
    }

    fun autoCompleteAndUpgrade() {
        val s = state.value
        val activeAcc = s.activeAccount
        if (activeAcc == null) {
            _state.update { it.copy(message = "No active account. Tap an account's avatar on the Tasks screen and log in first.") }
            return
        }
        if (s.isUpgraded) {
            _state.update { it.copy(message = "This account is already upgraded for today.") }
            return
        }
        if (upgradeRunnerState.anyRunning(activeAcc.id)) {
            _state.update { it.copy(message = "A verification task is already running.") }
            return
        }
        if (activeAcc.sessionCookies.isBlank()) {
            _state.update { it.copy(message = "Instagram session missing. Log in to this account again.") }
            return
        }

        upgradeDataProvider.clearCache()
        UpgradeRunnerService.start(context, activeAcc.id, "all")
    }

    fun performUpgrade() {
        // Reads the merged flow, not [_state] directly: the checklist UI (UpgradeScreen) binds to
        // [state], which overlays UpgradeRunnerService's live per-task progress on top of the last
        // loadUpgradeState() snapshot. [_state] alone is only refreshed by that one-shot reload, so
        // once the last task finished via the runner service, [_state].checklistComplete was still
        // stuck at false — the button read that stale flag and rejected an already-complete
        // checklist even though every row on screen showed COMPLETED.
        val s = state.value
        val activeAcc = s.activeAccount
        if (activeAcc == null) {
            _state.update { it.copy(message = "No active account. Tap an account's avatar on the Tasks screen and log in first.") }
            return
        }
        if (s.isUpgraded) {
            _state.update { it.copy(message = "This account is already upgraded for today.") }
            return
        }
        if (!s.checklistComplete) {
            _state.update { it.copy(message = "Please complete all Instagram tasks above to upgrade your account.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            when (val result = accountRepository.performUpgrade(activeAcc.id)) {
                is Resource.Success -> {
                    upgradeDataProvider.resetAccountFolderAssignment(activeAcc.username, activeAcc.gender)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            activeAccount = result.data,
                            isUpgraded = true,
                            message = "Account successfully upgraded! You now earn +2 extra coins per action for the next 24 hours."
                        )
                    }
                }
                is Resource.Error -> _state.update {
                    // A 409 from the backend means another device/tab already upgraded this
                    // account today — reflect that instead of just showing a raw error.
                    val alreadyUpgraded = result.message.contains("already upgraded", ignoreCase = true)
                    it.copy(isLoading = false, isUpgraded = it.isUpgraded || alreadyUpgraded, message = result.message)
                }
                Resource.Loading -> _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Persists the gender picked on the Upgrade panel's radio buttons for the active account. */
    fun setGender(gender: String) {
        val activeAcc = _state.value.activeAccount ?: return
        if (activeAcc.gender == gender) return
        viewModelScope.launch {
            accountRepository.updateGender(activeAcc.id, gender)
            upgradeDataProvider.clearCache()
            _state.update { it.copy(activeAccount = it.activeAccount?.copy(gender = gender)) }
            loadUpgradeState()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
        upgradeRunnerState.consumeMessage()
    }
}
