package com.feedpilot.client.common

object AppFeatureFlags {
    /**
     * Central switch for the task login/start preconditions.
     *
     * false: runner start ignores challenge/logged-out/picked-username/profile checks and the
     * service does not stop solely because local session cookies are blank.
     * true: production-style preconditions are enforced before and during task execution.
     */
    const val ENFORCE_LOGIN_AND_START_PRECONDITIONS = true
}
