package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AccountSessionGateTest {
    private fun account(id: String, active: Boolean, cookies: String) = AccountEntity(
        id = id, username = id, profilePictureUrl = null, status = "Active",
        lastLogin = null, lastActive = null, coinsEarned = 0, sessionCookies = cookies, isLoggedIn = active
    )
    private fun gate(accounts: List<AccountEntity>, onUpdate: (String, String) -> Unit = { _, _ -> }): AccountSessionGate {
        val dao = Proxy.newProxyInstance(AccountDao::class.java.classLoader, arrayOf(AccountDao::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> accounts
                "updateSessionCookies" -> onUpdate(args[0] as String, args[1] as String)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as AccountDao
        return AccountSessionGate(dao)
    }
    @Test fun `newly logged in session wins over older stored cookies`() = runBlocking {
        assertEquals("sessionid=fresh", gate(listOf(account("old", false, "sessionid=old"), account("new", true, "sessionid=fresh"))).activeSessionCookies())
    }
    @Test fun `empty active session cannot replace usable stored session`() = runBlocking {
        assertEquals("sessionid=old", gate(listOf(account("new", true, ""), account("old", false, "sessionid=old"))).activeSessionCookies())
    }
    @Test fun `rotated bridge session updates its own account rather than active account`() = runBlocking {
        val updates = mutableListOf<Pair<String, String>>()
        val gate = gate(listOf(account("active", true, "sessionid=active"), account("bridge", false, "sessionid=old"))) { id, cookies -> updates.add(id to cookies) }
        gate.persistSessionCookies("sessionid=old", "sessionid=rotated")
        assertEquals(listOf("bridge" to "sessionid=rotated"), updates)
    }
    @Test fun `rotation from replaced session cannot overwrite new login`() = runBlocking {
        val updates = mutableListOf<Pair<String, String>>()
        val gate = gate(listOf(account("active", true, "sessionid=new-login"))) { id, cookies -> updates.add(id to cookies) }
        gate.persistSessionCookies("sessionid=old", "sessionid=rotated")
        assertEquals(emptyList<Pair<String, String>>(), updates)
    }
}
