package com.feedpilot.client.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress for a single account. The card for each account shows its own row of this, so one
 * account's activity is never reported under another's name.
 *
 * [running] and [active] are deliberately separate: [running] is "this account has its own
 * independent claim/process loop alive" (set once for the whole run, cleared once for the whole
 * run), while [active] flips on and off around every single task inside that loop. Collapsing
 * them into one flag is what made starting a second account's loop stop the first — with a
 * single flag there was nowhere to record "these particular accounts are the ones running" other
 * than a global boolean, so starting anyone reset it for everyone.
 */
data class AccountRunnerStats(
    val running: Boolean = false,
    val active: Boolean = false,
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val coinsEarned: Int = 0,
    val message: String? = null,
    /**
     * Epoch millis this account's current idle wait is expected to end, or null when not idling
     * on a computed backoff (a real task running, a fixed-wording state like "Network
     * unavailable"/"Session logged out"). Lets the UI show a live countdown instead of a static
     * string, so a long backoff reads as "counting down on schedule" rather than "hung" — see
     * [noteAccountWaiting] and TasksScreen's status banner.
     */
    val waitUntilMs: Long? = null
)

/**
 * Live progress of the order runner, shared between [TaskRunnerService] and the UI.
 *
 * Holds no single "the run" concept — [TaskRunnerService] runs one independent coroutine per
 * account (see its runJobs map), so the only running/not-running state that means anything is
 * per account, in [accounts]. [completed]/[failed]/[skipped]/[coinsEarned] are totals across
 * every account for the small "N completed" summary line; they are never reset to zero just
 * because one particular account started or stopped.
 */
data class RunnerStats(
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val coinsEarned: Int = 0,
    val accounts: Map<String, AccountRunnerStats> = emptyMap()
) {
    val processed: Int get() = completed + failed + skipped

    /** True while at least one account has an independent loop running. */
    val running: Boolean get() = accounts.values.any { it.running }

    fun forAccount(accountId: String): AccountRunnerStats =
        accounts[accountId] ?: AccountRunnerStats()

    /** True while this specific account's own loop is running — independent of every other account. */
    fun isParticipating(accountId: String): Boolean = forAccount(accountId).running
}

/**
 * Process-wide runner state. [TaskRunnerService] is the only writer; view models observe [stats].
 * Held as a singleton so the UI keeps showing progress across screen and process-lifecycle changes.
 *
 * Every mutator here takes an accountId and only ever touches that one account's row in
 * [RunnerStats.accounts] — never the whole map — so accounts genuinely run independently: one
 * starting, finishing, or crashing never touches another's recorded state.
 */
@Singleton
class TaskRunnerState @Inject constructor() {

    private val _stats = MutableStateFlow(RunnerStats())
    val stats: StateFlow<RunnerStats> = _stats.asStateFlow()

    fun isAccountRunning(accountId: String): Boolean = _stats.value.forAccount(accountId).running

    /** Marks one account's independent loop as started, leaving every other account untouched. */
    fun startingAccount(accountId: String) {
        _stats.update {
            it.copy(
                accounts = it.mutateAccount(accountId) { a ->
                    a.copy(
                        running = true,
                        active = false,
                        completed = 0,
                        failed = 0,
                        skipped = 0,
                        coinsEarned = 0,
                        message = "Starting…",
                        waitUntilMs = null
                    )
                }
            )
        }
    }

    fun working(accountId: String, label: String) {
        _stats.update {
            it.copy(
                accounts = it.mutateAccount(accountId) { a -> a.copy(active = true, message = label, waitUntilMs = null) }
            )
        }
    }

    fun recordSuccess(accountId: String, coins: Int, message: String) {
        _stats.update {
            it.copy(
                completed = it.completed + 1,
                coinsEarned = it.coinsEarned + coins,
                accounts = it.mutateAccount(accountId) { a ->
                    a.copy(
                        active = false,
                        completed = a.completed + 1,
                        coinsEarned = a.coinsEarned + coins,
                        message = message,
                        waitUntilMs = null
                    )
                }
            )
        }
    }

    fun recordFailure(accountId: String, message: String) {
        _stats.update {
            it.copy(
                failed = it.failed + 1,
                accounts = it.mutateAccount(accountId) { a ->
                    a.copy(active = false, failed = a.failed + 1, message = message, waitUntilMs = null)
                }
            )
        }
    }

    /**
     * A skip is counted in the shared total but deliberately not written to an account row: a
     * task that account was never eligible for is not that account's status, and surfacing it
     * there is what made every card read "Like post not selected".
     */
    fun recordSkip(message: String) {
        _stats.update { it.copy(skipped = it.skipped + 1) }
    }

    /**
     * Per-account note with fixed wording and no countdown, e.g. "Network unavailable" or
     * "Session logged out" — clears any stale [AccountRunnerStats.waitUntilMs] left over from a
     * prior [noteAccountWaiting] call so the UI doesn't keep counting down toward a retry this
     * message no longer describes.
     */
    fun noteAccount(accountId: String, message: String) {
        _stats.update {
            it.copy(accounts = it.mutateAccount(accountId) { a -> a.copy(message = message, waitUntilMs = null) })
        }
    }

    /**
     * Per-account idle-wait note: [message] plus a computed backoff of [waitMs] — see
     * [TaskRunnerService.idleWait]. Stamps [AccountRunnerStats.waitUntilMs] so the UI can render
     * a live countdown instead of a static string, making a long-but-on-schedule backoff visibly
     * different from a genuine hang.
     */
    fun noteAccountWaiting(accountId: String, message: String, waitMs: Long) {
        _stats.update {
            it.copy(
                accounts = it.mutateAccount(accountId) { a ->
                    a.copy(message = message, waitUntilMs = System.currentTimeMillis() + waitMs)
                }
            )
        }
    }

    /** Marks one account's loop as stopped, leaving every other account's state untouched. */
    fun stoppedAccount(accountId: String, message: String?) {
        _stats.update {
            it.copy(
                accounts = it.mutateAccount(accountId) { a ->
                    a.copy(running = false, active = false, message = message ?: a.message)
                }
            )
        }
    }

    private inline fun RunnerStats.mutateAccount(
        accountId: String,
        change: (AccountRunnerStats) -> AccountRunnerStats
    ): Map<String, AccountRunnerStats> =
        accounts + (accountId to change(accounts[accountId] ?: AccountRunnerStats()))
}
