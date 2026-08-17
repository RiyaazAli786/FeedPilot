package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.AccountDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single answer to "is an Instagram account signed in on this device?", used to keep the app
 * from fetching anything until one is.
 *
 * An account row without session cookies does not count. It cannot act on Instagram, and the
 * backend work — tasks, orders, wallet — only exists in relation to an account that can, so the
 * rule matches the one [AccountRepository.getActiveAccount] and [InstagramRepository] use when they
 * pick which session to act as.
 *
 * Deliberately *not* a gate on the device session or the app-update check: those have to work
 * before any account exists, or there would be no way to reach the login screen or ship a fix.
 */
@Singleton
class AccountSessionGate @Inject constructor(
    private val accountDao: AccountDao
) {
    /** Cookies of the account to act as, or null when nothing is signed in. */
    suspend fun activeSessionCookies(): String? =
        accountDao.getAll().firstOrNull { it.sessionCookies.isNotBlank() }?.sessionCookies

    suspend fun isSignedIn(): Boolean = activeSessionCookies() != null
}
