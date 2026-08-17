package com.feedpilot.client.task

/** Result of performing a single order. Drives what gets reported back to the backend. */
sealed interface TaskOutcome {

    /** The action was performed. The reward is claimed from the backend. */
    data class Success(val message: String) : TaskOutcome

    /**
     * The action could not be performed. Reported as a failure so the backend can requeue it
     * (it retries up to 3 times before marking the task failed).
     */
    data class Failure(val reason: String) : TaskOutcome

    /**
     * The order was left untouched — unsupported type, or filtered out by the user's task mode.
     * Nothing is reported to the backend; the order stays claimable by another run.
     */
    data class Skipped(val reason: String) : TaskOutcome

    val describe: String
        get() = when (this) {
            is Success -> message
            is Failure -> reason
            is Skipped -> reason
        }
}
