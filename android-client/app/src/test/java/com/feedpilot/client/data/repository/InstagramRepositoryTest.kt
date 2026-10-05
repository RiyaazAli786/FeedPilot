package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.InstagramWebClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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
        val http = OkHttpClient.Builder().apply {
            if (System.getenv("FEEDPILOT_TEST_MOBILE_HEADERS") == "true") {
                addInterceptor { chain ->
                    val crypto = com.feedpilot.client.common.InstagramCrypto
                    chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", crypto.DEFAULT_USER_AGENT)
                        .header("sec-ch-ua", crypto.SEC_CH_UA)
                        .header("sec-ch-ua-mobile", crypto.SEC_CH_UA_MOBILE)
                        .header("sec-ch-ua-platform", crypto.SEC_CH_UA_PLATFORM)
                        .header("sec-ch-ua-platform-version", crypto.SEC_CH_UA_PLATFORM_VERSION)
                        .header("sec-ch-ua-model", crypto.SEC_CH_UA_MODEL)
                        .header("Accept-Language", crypto.ACCEPT_LANGUAGE)
                        .build())
                }
            }
        }.build()
        val instagramWebClient = InstagramWebClient(http, null)

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
            override suspend fun setCoinsEarned(id: String, coinsEarned: Long) {}
            override suspend fun updateStatus(id: String, status: String) {}
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
    fun `parses xdt graphql timeline response edges`() {
        val client = InstagramWebClient(OkHttpClient.Builder().build(), null)
        val json = JSONObject(
            """
            {
              "data": {
                "xdt_api__v1__feed__user_timeline_graphql_connection": {
                  "edges": [
                    {
                      "node": {
                        "code": "DdSrXHitmbz",
                        "pk": "3986439324822824691",
                        "id": "3986439324822824691_45145019",
                        "caption": { "text": "After his successful title defense" },
                        "taken_at": 1789440691,
                        "video_versions": [{ "url": "https://cdn.example/video.mp4" }],
                        "image_versions2": { "candidates": [{ "url": "https://cdn.example/image.jpg" }] },
                        "comment_count": 1966,
                        "like_count": 111559,
                        "media_type": 2,
                        "carousel_media": [
                          {
                            "code": "NestedShouldNotBecomeAFeedItem",
                            "pk": "nested_pk",
                            "id": "nested_id",
                            "media_type": 1
                          }
                        ]
                      }
                    },
                    {
                      "node": {
                        "code": "DdOtherCode",
                        "pk": "3987742705182008029",
                        "id": "3987742705182008029_45145019",
                        "caption": { "text": "Second item" },
                        "taken_at": 1789440799,
                        "image_versions2": { "candidates": [{ "url": "https://cdn.example/second.jpg" }] },
                        "comment_count": 10,
                        "like_count": 20,
                        "media_type": 1
                      }
                    }
                  ],
                  "page_info": {
                    "end_cursor": "3987742705182008029_45145019",
                    "has_next_page": true
                  }
                }
              },
              "status": "ok"
            }
            """.trimIndent()
        )

        val method = InstagramWebClient::class.java.getDeclaredMethod(
            "parseGraphqlTimelineItems",
            JSONObject::class.java
        )
        method.isAccessible = true
        val items = method.invoke(client, json) as List<*>

        assertTrue("Expected exactly top-level edge nodes, got ${items.size}", items.size == 2)
        val first = items[0] as com.feedpilot.client.data.remote.InstagramUserFeedItem
        assertTrue("First code should parse", first.code == "DdSrXHitmbz")
        assertTrue("Video URL should parse", first.videoUrl == "https://cdn.example/video.mp4")
        assertTrue("Display URL should parse", first.displayUrl == "https://cdn.example/image.jpg")
        assertTrue("Like count should parse", first.likeCount == 111559L)
        assertTrue("Comment count should parse", first.commentCount == 1966L)
    }
    @Test
    fun `bridge path fetches target feed by username`() = runBlocking {
        requireSession()

        val targetUsername = System.getenv("FEEDPILOT_BRIDGE_TEST_USERNAME")
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
            ?: "instagram"
        val targetRepository = TargetRepository(instagramRepository)
        val result = targetRepository.fetchUserFeed(targetUsername, customCookies = sessionCookies)

        assertTrue(
            "Bridge target feed should succeed for @$targetUsername, got $result",
            result is Resource.Success
        )
        val feed = (result as Resource.Success).data
        println("BRIDGE_FEED username=@$targetUsername items=${feed.items.size} hasMore=${feed.hasMore} maxId=${feed.maxId}")
        feed.items.take(5).forEachIndexed { index, item ->
            println(
                "BRIDGE_FEED_ITEM[$index] code=${item.code} mediaId=${item.mediaId} " +
                    "type=${item.mediaType} likes=${item.likeCount} comments=${item.commentCount} " +
                    "takenAt=${item.takenAt} hasImage=${!item.displayUrl.isNullOrBlank()} hasVideo=${!item.videoUrl.isNullOrBlank()}"
            )
        }
        feed.profile?.username?.takeIf { it.isNotBlank() }?.let { resolvedUsername ->
            assertTrue(
                "Expected bridge feed profile username to be '$targetUsername' but was '$resolvedUsername'",
                resolvedUsername.equals(targetUsername, ignoreCase = true)
            )
        }
        assertTrue("Bridge target feed should return at least one post", feed.items.isNotEmpty())
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