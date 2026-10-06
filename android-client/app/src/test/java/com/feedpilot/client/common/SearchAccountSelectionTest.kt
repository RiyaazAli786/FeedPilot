package com.feedpilot.client.common

import com.feedpilot.client.data.local.AccountEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class SearchAccountSelectionTest {
    @Test
    fun selectedSessionStaysPinnedAndNeverFallsBackAfterRemoval() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val first = account("first", "first-cookie")
            val active = account("active", "active-cookie", true)
            val accounts = MutableStateFlow(listOf(first, active))
            val selection = SearchAccountSelection(accounts, scope)
            assertEquals("active", selection.state.value.selectedAccountId)
            assertEquals("active-cookie", selection.cookies())

            selection.select("first")
            assertEquals("first-cookie", selection.cookies())
            accounts.value = listOf(first.copy(isLoggedIn = true), active.copy(isLoggedIn = false))
            assertEquals("first-cookie", selection.cookies())

            accounts.value = listOf(active)
            assertNull(selection.state.value.selectedAccountId)
            try {
                selection.cookies()
                fail("A removed selected account must not use another account's cookies")
            } catch (_: IllegalStateException) { }
        } finally { scope.cancel() }
    }

    @Test
    fun accountsWithoutSessionsAreUnavailable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val selection = SearchAccountSelection(MutableStateFlow(listOf(account("empty", ""))), scope)
            assertTrue(selection.state.value.accounts.isEmpty())
            assertNull(selection.state.value.selectedAccountId)
        } finally { scope.cancel() }
    }

    private fun account(id: String, cookies: String, active: Boolean = false) = AccountEntity(
        id, id, null, "Active", null, null, 0L, cookies, active
    )
}
