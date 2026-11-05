package com.feedpilot.client.bridge

import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.dto.BridgeJobDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class BridgeCandidateAccountsTest {

    private fun account(id: String, username: String, status: String = "Active", cookies: String = "sessionid=test_$id") =
        AccountEntity(
            id = id,
            username = username,
            profilePictureUrl = null,
            status = status,
            lastLogin = null,
            lastActive = null,
            coinsEarned = 0,
            sessionCookies = cookies,
            isLoggedIn = true
        )

    private fun mockDao(accountsList: List<AccountEntity>): AccountDao {
        return Proxy.newProxyInstance(
            AccountDao::class.java.classLoader,
            arrayOf(AccountDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> accountsList
                else -> throw UnsupportedOperationException(method.name)
            }
        } as AccountDao
    }

    private fun createRunner(accounts: List<AccountEntity>): FeedBridgeRunner {
        val dao = mockDao(accounts)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val runner = allocateMethod.invoke(unsafe, FeedBridgeRunner::class.java) as FeedBridgeRunner
        FeedBridgeRunner::class.java.getDeclaredField("accounts").apply { isAccessible = true }.set(runner, dao)
        val roundRobinField = FeedBridgeRunner::class.java.getDeclaredField("roundRobinCounter").apply { isAccessible = true }
        roundRobinField.set(runner, java.util.concurrent.atomic.AtomicInteger(0))
        return runner
    }

    @Test
    fun `assigned account is prioritized first followed by remaining local accounts`() = runBlocking {
        val acc1 = account("1", "user_alpha")
        val acc2 = account("2", "user_beta")
        val acc3 = account("3", "user_gamma")
        val runner = createRunner(listOf(acc1, acc2, acc3))

        val job = BridgeJobDto(
            id = "test-job-1",
            targetUsername = "target_person",
            targetUserId = null,
            limit = 12,
            maxId = null,
            includeStories = false,
            expiresAt = "2026-11-05T12:00:00Z",
            assignedAccountId = "2",
            accountUsername = "user_beta"
        )

        val candidates = runner.getCandidateAccounts(job)
        assertEquals(3, candidates.size)
        assertEquals("user_beta", candidates[0].username)
        assertEquals("user_gamma", candidates[1].username)
        assertEquals("user_alpha", candidates[2].username)
    }

    @Test
    fun `expired or suspended accounts are filtered out from candidates`() = runBlocking {
        val acc1 = account("1", "user_alpha", status = "SessionExpired")
        val acc2 = account("2", "user_beta", status = "Active")
        val acc3 = account("3", "user_gamma", status = "Suspended")
        val runner = createRunner(listOf(acc1, acc2, acc3))

        val job = BridgeJobDto(
            id = "test-job-2",
            targetUsername = "target_person",
            targetUserId = null,
            limit = 12,
            maxId = null,
            includeStories = false,
            expiresAt = "2026-11-05T12:00:00Z",
            assignedAccountId = null,
            accountUsername = null
        )

        val candidates = runner.getCandidateAccounts(job)
        assertEquals(1, candidates.size)
        assertEquals("user_beta", candidates[0].username)
    }

    @Test
    fun `all usable accounts are returned for local device failover`() = runBlocking {
        val acc1 = account("1", "user_alpha")
        val acc2 = account("2", "user_beta")
        val runner = createRunner(listOf(acc1, acc2))

        val job = BridgeJobDto(
            id = "test-job-3",
            targetUsername = "target_person",
            targetUserId = null,
            limit = 12,
            maxId = null,
            includeStories = false,
            expiresAt = "2026-11-05T12:00:00Z",
            assignedAccountId = null,
            accountUsername = null
        )

        val candidates = runner.getCandidateAccounts(job)
        assertEquals(2, candidates.size)
        val usernames = candidates.map { it.username }.toSet()
        assertEquals(setOf("user_alpha", "user_beta"), usernames)
    }
}
