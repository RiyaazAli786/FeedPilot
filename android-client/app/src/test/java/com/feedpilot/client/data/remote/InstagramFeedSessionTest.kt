package com.feedpilot.client.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class InstagramFeedSessionTest {
    @Test fun `partial graphql field errors preserve usable posts without fallback`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("<html></html>"))
            server.enqueue(MockResponse().setBody("""{"data":{"xdt_api__v1__feed__user_timeline_graphql_connection":{"edges":[{"node":{"id":"123_456","code":"partialPost","media_type":1,"taken_at":1791140402}}],"page_info":{"end_cursor":"next","has_next_page":true}}},"errors":[{"message":"A server error field_exception occured. Check server logs for details.","path":["xdt_api__v1__feed__user_timeline_graphql_connection","edges",0,"node","optional_field"]}]}"""))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val feed = InstagramWebClient(http, null).fetchUserFeed("example", sessionCookies = "sessionid=valid")!!
            assertEquals("partialPost", feed.items.single().code)
            assertEquals("next", feed.maxId)
            assertTrue(feed.hasMore)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun `profile cookie rotation is used in graphql and returned for persistence`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                .addHeader("Set-Cookie", "csrftoken=new-csrf; Path=/; Secure")
                .addHeader("Set-Cookie", "sessionid=new-session; Path=/; Secure")
                .setHeader("X-Ig-Set-WWW-Claim", "fresh-claim")
                .setBody("""["LSD",[],{"token":"fresh-lsd"}],["DTSGInitialData",[],{"token":"fresh-dtsg"}]"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "rur=new-region; Path=/; Secure")
                .setBody("""{"data":{"xdt_api__v1__feed__user_timeline_graphql_connection":{"edges":[{"node":{"id":"123_456","pk":"123","code":"post","media_type":1,"taken_at":1791140402}}],"page_info":{"end_cursor":"next","has_next_page":true}}},"status":"ok"}"""))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val feed = InstagramWebClient(http, null).fetchUserFeed("example", sessionCookies = "csrftoken=old-csrf; sessionid=old-session; mid=device")
            assertEquals(1, feed!!.items.size)
            server.takeRequest(1, TimeUnit.SECONDS)
            val query = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/graphql/query", query.path)
            assertEquals("new-csrf", query.getHeader("X-CSRFToken"))
            assertEquals("fresh-claim", query.getHeader("X-IG-WWW-Claim"))
            assertEquals("fresh-lsd", query.getHeader("X-FB-LSD"))
            assertTrue(query.getHeader("Cookie")!!.contains("sessionid=new-session"))
            assertTrue(query.getHeader("Cookie")!!.contains("mid=device"))
            assertTrue(feed.updatedCookies!!.contains("rur=new-region"))
            assertTrue(feed.updatedCookies!!.contains("csrftoken=new-csrf"))
        } finally { server.shutdown() }
    }

    @Test fun `two factor result preserves device cookies and rotated session`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"authenticated":true,"userId":"456"}""")
                .addHeader("Set-Cookie", "sessionid=verified-session; Path=/; Secure")
                .addHeader("Set-Cookie", "csrftoken=verified-csrf; Path=/; Secure")
                .addHeader("Set-Cookie", "ds_user_id=456; Path=/; Secure"))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val challenge = InstagramLoginResult.TwoFactorRequired("identifier", "example", "csrftoken=challenge-csrf; mid=device; ig_did=installation", "challenge-csrf")
            val result = InstagramWebClient(http, null).submitTwoFactorCode(challenge, "123456") as InstagramLoginResult.Success
            assertTrue(result.sessionData.contains("mid=device"))
            assertTrue(result.sessionData.contains("ig_did=installation"))
            assertTrue(result.sessionData.contains("sessionid=verified-session"))
            assertTrue(result.sessionData.contains("csrftoken=verified-csrf"))
            assertFalse(result.sessionData.contains("csrftoken=challenge-csrf"))
        } finally { server.shutdown() }
    }

    @Test fun `blended search uses Instagram app user agent without browser client hints`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"users":[{"user":{"username":"reelsflow","full_name":"Reels Flow","pk":"12345","is_private":false,"is_verified":true}}]}""")
            )
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath + "?" + (chain.request().url.encodedQuery ?: ""))).build())
            }.build()
            val results = InstagramWebClient(http, null).searchUsers("reels", "csrftoken=csrf; sessionid=sess")
            assertEquals(1, results.size)
            assertEquals("reelsflow", results.single().username)

            val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(recorded.path!!.startsWith("/api/v1/fbsearch/topsearch_flat/"))
            assertTrue(recorded.path!!.contains("context=blended"))
            val ua = recorded.getHeader("User-Agent").orEmpty()
            assertFalse("Blended search must not send desktop Windows User-Agent: $ua", ua.contains("Windows NT"))
            assertEquals(com.feedpilot.client.common.InstagramCrypto.INSTAGRAM_APP_USER_AGENT, ua)
            assertNull(recorded.getHeader("sec-ch-ua-platform"))
            assertNull(recorded.getHeader("sec-ch-ua-mobile"))
        } finally { server.shutdown() }
    }

    @Test fun `pk resolution blended search uses Instagram app user agent`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"users":[{"user":{"username":"targetuser","pk_id":"987654321"}}]}""")
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"items":[{"id":"1_987654321","pk":"1","code":"AbCd","media_type":1,"taken_at":1791140402}],"user":{"pk":"987654321","username":"targetuser"}}""")
            )
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val query = chain.request().url.encodedQuery
                val suffix = if (query != null) "?$query" else ""
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath + suffix)).build())
            }.build()
            val feed = InstagramWebClient(http, null).fetchUserFeed("targetuser", sessionCookies = null)
            assertNotNull(feed)
            assertEquals("AbCd", feed!!.items.single().code)

            val searchReq = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(searchReq.path!!.startsWith("/api/v1/fbsearch/topsearch_flat/"))
            assertTrue(searchReq.path!!.contains("context=blended"))
            val ua = searchReq.getHeader("User-Agent").orEmpty()
            assertFalse("PK resolution blended search must not send desktop Windows User-Agent: $ua", ua.contains("Windows NT"))
            assertEquals(com.feedpilot.client.common.InstagramCrypto.INSTAGRAM_APP_USER_AGENT, ua)
            assertNull(searchReq.getHeader("sec-ch-ua-platform"))
        } finally { server.shutdown() }
    }

    @Test fun `repository getUserFeed enriches zero counts via HoverCard when timeline omits totals`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // 1. Profile HTML for GraphQL timeline bootstrap (no og:description counts)
            server.enqueue(MockResponse().setBody("""<html>["LSD",[],{"token":"lsd-1"}],["DTSGInitialData",[],{"token":"dtsg-1"}],{"actorID":"111"},"profile_id":"222"</html>"""))
            // 2. GraphQL timeline response (user node has no follower_count/following_count)
            server.enqueue(MockResponse().setBody("""{"data":{"xdt_api__v1__feed__user_timeline_graphql_connection":{"edges":[{"node":{"id":"1_222","pk":"1","code":"post1","media_type":1,"taken_at":1791140402,"user":{"pk":"222","username":"example","profile_pic_url":"https://cdn.example/p.jpg","full_name":"Example"}}}],"page_info":{"end_cursor":null,"has_next_page":false}}},"status":"ok"}"""))
            // 3. Profile HTML for getUserProfileDetails
            server.enqueue(MockResponse().setBody("""<html>["LSD",[],{"token":"lsd-1"}],["DTSGInitialData",[],{"token":"dtsg-1"}],{"actorID":"111"},"profile_id":"222"</html>"""))
            // 4. PolarisUserHoverCardContentV2Query response with exact follower/following counts
            server.enqueue(MockResponse().setBody("""{"data":{"xig_user_by_igid_v2":{"user_dict":{"pk":"222","username":"example","full_name":"Example","profile_pic_url":"https://cdn.example/p.jpg","follower_count":15792598,"following_count":47,"media_count":3424,"is_private":false,"is_verified":true}}}}"""))

            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val webClient = InstagramWebClient(http, null)
            val dao = object : com.feedpilot.client.data.local.AccountDao {
                override fun observeAll() = throw UnsupportedOperationException()
                override suspend fun getAll() = listOf(
                    com.feedpilot.client.data.local.AccountEntity(
                        id = "a1", username = "viewer", profilePictureUrl = null, status = "Active",
                        lastLogin = null, lastActive = null, coinsEarned = 0L,
                        sessionCookies = "csrftoken=c1; sessionid=s1; ds_user_id=111"
                    )
                )
                override suspend fun getById(id: String) = getAll().firstOrNull()
                override suspend fun upsertAll(accounts: List<com.feedpilot.client.data.local.AccountEntity>) {}
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
            val repo = com.feedpilot.client.data.repository.InstagramRepository(
                webClient,
                com.feedpilot.client.data.repository.AccountSessionGate(dao)
            )

            val feed = repo.getUserFeed("example", customCookies = "csrftoken=c1; sessionid=s1; ds_user_id=111")
            assertNotNull(feed)
            assertEquals(15792598L, feed!!.profile?.followerCount)
            assertEquals(47L, feed.profile?.followingCount)
            assertEquals(3424L, feed.profile?.mediaCount)
        } finally { server.shutdown() }
    }

    @Test fun `secChUaForUserAgent aligns chrome major version`() {
        val ua138 = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.63 Mobile Safari/537.36"
        assertEquals(
            "\"Google Chrome\";v=\"138\", \"Chromium\";v=\"138\", \"Not_A Brand\";v=\"24\"",
            com.feedpilot.client.common.InstagramCrypto.secChUaForUserAgent(ua138)
        )
        assertEquals(
            com.feedpilot.client.common.InstagramCrypto.SEC_CH_UA,
            com.feedpilot.client.common.InstagramCrypto.secChUaForUserAgent(com.feedpilot.client.common.InstagramCrypto.DEFAULT_USER_AGENT)
        )
    }
}
