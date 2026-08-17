package com.feedpilot.client.service

import com.feedpilot.client.feature.upgrade.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live progress of one upgrade-checklist task (post/profile/story/bio upload+verify).
 */
data class UpgradeTaskProgress(
    val status: TaskStatus = TaskStatus.PENDING,
    val progressText: String? = null
)

/**
 * Process-wide state for [UpgradeRunnerService], shared between the service and the UI —
 * the same role [TaskRunnerState] plays for [TaskRunnerService].
 *
 * Held as a singleton so a task started from [com.feedpilot.client.feature.upgrade.UpgradeScreen]
 * keeps reporting progress here regardless of whether that screen is still on-screen, backgrounded,
 * or gone entirely: the service is the only writer, and it runs independently of any ViewModel.
 */
@Singleton
class UpgradeRunnerState @Inject constructor() {

    data class Snapshot(
        /** The account whose tasks [tasks] describes — a second account's card must not show it. */
        val accountId: String? = null,
        val tasks: Map<String, UpgradeTaskProgress> = emptyMap(),
        val message: String? = null
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun isRunning(accountId: String, taskId: String): Boolean {
        val s = _snapshot.value
        return s.accountId == accountId && s.tasks[taskId]?.status == TaskStatus.PROCESSING
    }

    fun anyRunning(accountId: String): Boolean {
        val s = _snapshot.value
        return s.accountId == accountId && s.tasks.values.any { it.status == TaskStatus.PROCESSING }
    }

    /** Starts tracking [taskId] for [accountId]. Switching accounts drops the previous account's rows. */
    fun start(accountId: String, taskId: String) {
        _snapshot.update {
            val base = if (it.accountId == accountId) it.tasks else emptyMap()
            it.copy(accountId = accountId, tasks = base + (taskId to UpgradeTaskProgress(TaskStatus.PROCESSING, null)))
        }
    }

    fun progress(accountId: String, taskId: String, text: String?) {
        _snapshot.update {
            if (it.accountId != accountId) return@update it
            it.copy(tasks = it.tasks + (taskId to (it.tasks[taskId] ?: UpgradeTaskProgress()).copy(progressText = text)))
        }
    }

    fun finish(accountId: String, taskId: String, success: Boolean, progressText: String?, message: String?) {
        _snapshot.update {
            if (it.accountId != accountId) return@update it
            it.copy(
                tasks = it.tasks + (taskId to UpgradeTaskProgress(
                    status = if (success) TaskStatus.COMPLETED else TaskStatus.PENDING,
                    progressText = progressText
                )),
                message = message
            )
        }
    }

    fun consumeMessage() {
        _snapshot.update { it.copy(message = null) }
    }
}
