package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.InstagramWebClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Integration tests for [InstagramRepository] against the live Instagram web API.
 *
 * These need real session cookies, so they **skip** (rather than fail) when none are
 * available — see [loadSessionCookies]. Supply them either by placing `testsession.json`
 * in the repo's `scripts/` folder or by setting the `FEEDPILOT_SESSION_FILE` environment variable.
 *
 * The file is a cookie export: `[{"name":"sessionid","value":"..."}, ...]`.
 *
 * Because these hit the network and depend on a real account, they are integration tests,
 * not unit tests — expect them to be slower and occasionally flaky on Instagram's side.
 */
class InstagramRepositoryTest {

    private lateinit var instagramRepository: InstagramRepository
    private var sessionCookies: String = ""
    private var testUserId: String = ""

    @Before
    fun setUp() {
        sessionCookies = loadSessionCookies()
        testUserId = extractUserId(sessionCookies)

        // No Android Context in a JVM test; the client only uses it for the device fingerprint,
        // which falls back to a default UA when null.
        val instagramWebClient = InstagramWebClient(OkHttpClient.Builder().build(), null)

        val stubAccountDao = object : AccountDao {
            override fun observeAll() = throw UnsupportedOperationException("not used in these tests")
            override suspend fun getAll(): List<AccountEntity> = listOf(
                AccountEntity(
                    id = "test_acc",
                    username = "test_user",
                    profilePictureUrl = null,
                    status = "Active",
                    lastLogin = null,
                    lastActive = null,
                    coinsEarned = 0L,
                    sessionCookies = sessionCookies
                )
            )
            override suspend fun getById(id: String): AccountEntity? = getAll().firstOrNull()
            override suspend fun upsertAll(accounts: List<AccountEntity>) {}
            override suspend fun incrementCoinsEarned(id: String, increment: Long) {}
            override suspend fun updateSessionCookies(id: String, sessionCookies: String) {}
            override suspend fun updateLoginStatus(id: String, isLoggedIn: Boolean) {}
            override suspend fun clearLoginStatusExcept(exceptId: String) {}
            override suspend fun updateUpgradedAt(id: String, upgradedAt: String?) {}
            override suspend fun updateProfileTaskCompletedAt(id: String, atMillis: Long?) {}
            override suspend fun updateBioTaskCompletedAt(id: String, atMillis: Long?) {}
            override suspend fun updateGender(id: String, gender: String) {}
            override suspend fun deleteById(id: String) {}
            override suspend fun clearAll() {}
        }

        // The stub account carries the loaded cookies, so the gate reports signed in and the
        // repository is willing to fetch — mirroring the app with an account linked.
        instagramRepository = InstagramRepository(instagramWebClient, AccountSessionGate(stubAccountDao))
    }

    /**
     * Skips the test — green, not red — when no usable session is configured. A missing
     * credential is an environment gap, not a defect in the code under test.
     */
    private fun requireSession() {
        assumeTrue(
            "No Instagram session cookies found. Place testsession.json in the repo's scripts/ " +
                "folder or set FEEDPILOT_SESSION_FILE.",
            sessionCookies.contains("sessionid=")
        )
    }

    // ---------------------------------------------------------------- read-only

    @Test
    fun `fetches profile details for a known public account`() = runBlocking {
        requireSession()

        val result = instagramRepository.getUserProfileDetails("instagram", sessionCookies)

        assertNotNull("Profile details should not be null for @instagram", result)
        assertTrue(
            "Expected the resolved username to be 'instagram' but was '${result?.username}'",
            result?.username.equals("instagram", ignoreCase = true)
        )
        assertTrue(
            "@instagram should report a positive follower count, got ${result?.followerCount}",
            (result?.followerCount ?: 0) > 0
        )
    }

    @Test
    fun `resolves a username to a numeric user id`() = runBlocking {
        requireSession()

        val resolvedId = instagramRepository.resolveUserId("instagram", sessionCookies)

        assertNotNull("Resolved user ID should not be null", resolvedId)
        assertTrue(
            "A user id should be all digits, got '$resolvedId'",
            resolvedId!!.isNotBlank() && resolvedId.all(Char::isDigit)
        )
    }

    @Test
    fun `fetches the signed-in user's own feed`() = runBlocking {
        requireSession()
        assumeTrue("No ds_user_id in the session cookies", testUserId.isNotBlank())

        val result = instagramRepository.getUserFeed(testUserId, customCookies = sessionCookies)

        assertNotNull("Feed result should not be null", result)
        // An account may legitimately have no posts, so assert on shape rather than count.
        // Note: this must not end in an expression — JUnit 4 requires void test methods.
        val firstItem = result!!.items.firstOrNull()
        if (firstItem != null) {
            assertTrue("Feed items should carry a shortcode", firstItem.code.isNotBlank())
        }
    }

    /**
     * Uses a shortcode taken from a real feed rather than a made-up one. The previous
     * version passed a placeholder that could never resolve, so it asserted nothing and
     * passed no matter how badly [InstagramRepository.getPostDetails] was broken.
     */
    @Test
    fun `fetches details for a post taken from a real feed`() = runBlocking {
        requireSession()
        assumeTrue("No ds_user_id in the session cookies", testUserId.isNotBlank())

        val feed = instagramRepository.getUserFeed(testUserId, customCookies = sessionCookies)
        val shortcode = feed?.items?.firstOrNull()?.code
        assumeTrue("The test account has no posts to inspect", !shortcode.isNullOrBlank())

        val details = instagramRepository.getPostDetails(shortcode!!, sessionCookies)

        assertNotNull("Post details should not be null for shortcode $shortcode", details)
    }

    // ---------------------------------------------------------------- mutating

    /**
     * Disabled by default: this really follows the target from the signed-in account and
     * there is no matching unfollow, so every run leaves the account changed. Enable it
     * deliberately, against a throwaway account.
     */
    @Ignore("Mutates the live account — follows @instagram for real. Run manually.")
    @Test
    fun `follows a target account`() = runBlocking {
        requireSession()

        val result = instagramRepository.follow("25025320", "instagram", sessionCookies)

        assertTrue("Follow request should succeed, got: ${result.reason}", result.ok)
    }

    /** Disabled for the same reason: a like is a real, visible action on someone's post. */
    @Ignore("Mutates the live account — likes a real post. Run manually.")
    @Test
    fun `likes a post from a real feed`() = runBlocking {
        requireSession()
        assumeTrue("No ds_user_id in the session cookies", testUserId.isNotBlank())

        val feed = instagramRepository.getUserFeed(testUserId, customCookies = sessionCookies)
        val shortcode = feed?.items?.firstOrNull()?.code
        assumeTrue("The test account has no posts to like", !shortcode.isNullOrBlank())

        val result = instagramRepository.like(shortcode!!, sessionCookies)

        assertTrue("Like request should succeed, got: ${result.reason}", result.ok)
    }

    private companion object {
        const val SESSION_FILE_ENV = "FEEDPILOT_SESSION_FILE"
        const val SESSION_FILE_NAME = "testsession.json"

        /**
         * Finds the cookie export without hardcoding an absolute path — the previous
         * version pointed at one developer's `D:/` drive and could not work anywhere else.
         * Checks the env var first, then walks up from the working directory, checking both an
         * ancestor directory itself and its `scripts/` subfolder — the file lives at
         * `scripts/testsession.json` (see the test_*.js scripts there, which read/write the same
         * file), not the repo root.
         */
        fun sessionFile(): File? {
            System.getenv(SESSION_FILE_ENV)?.takeIf { it.isNotBlank() }?.let { configured ->
                return File(configured).takeIf(File::exists)
            }

            var dir: File? = File("").absoluteFile
            repeat(5) {
                val current = dir
                val inScripts = current?.resolve("scripts")?.resolve(SESSION_FILE_NAME)
                if (inScripts != null && inScripts.exists()) return inScripts
                val here = current?.resolve(SESSION_FILE_NAME)
                if (here != null && here.exists()) return here
                dir = current?.parentFile
            }
            return null
        }

        fun loadSessionCookies(): String {
            val file = sessionFile() ?: return ""
            return try {
                val array = JSONArray(file.readText())
                (0 until array.length())
                    .map { array.getJSONObject(it) }
                    .joinToString("; ") { "${it.getString("name")}=${it.getString("value")}" }
            } catch (e: Exception) {
                // A malformed export should skip the tests, not blow up during setUp.
                ""
            }
        }

        fun extractUserId(cookieHeader: String): String =
            cookieHeader.split("; ")
                .firstOrNull { it.startsWith("ds_user_id=") }
                ?.substringAfter("=")
                .orEmpty()
    }
}
