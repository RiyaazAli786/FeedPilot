package com.feedpilot.client.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class InstagramFeedSessionTest {
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
}
