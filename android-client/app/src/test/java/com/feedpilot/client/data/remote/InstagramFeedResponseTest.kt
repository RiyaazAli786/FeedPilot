package com.feedpilot.client.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class InstagramFeedResponseTest {
    private fun htmlFailure(path: String): String {
        val client = InstagramWebClient(OkHttpClient.Builder().build(), null)
        val method = InstagramWebClient::class.java.getDeclaredMethod(
            "throwInstagramFeedHtmlFailure", String::class.java, String::class.java
        ).apply { isAccessible = true }
        try {
            method.invoke(client, path, "graphql/query")
            error("Expected HTML response to fail")
        } catch (e: InvocationTargetException) {
            return e.targetException.message.orEmpty()
        }
    }

    @Test fun `homepage HTML is not evidence of expired session`() {
        val message = htmlFailure("/")
        assertTrue(message.contains("request may be restricted"))
        assertTrue(!message.contains("login_required"))
    }

    @Test fun `login redirect retains authentication failure`() {
        assertTrue(htmlFailure("/accounts/login/").startsWith("login_required:"))
    }

    @Test fun `challenge redirect retains verification failure`() {
        assertTrue(htmlFailure("/challenge/").startsWith("checkpoint_required:"))
    }

    @Test fun `extractProfileStatsFromHtml parses followers and following from og description`() {
        val client = InstagramWebClient(OkHttpClient.Builder().build(), null)
        val method = InstagramWebClient::class.java.getDeclaredMethod(
            "extractProfileStatsFromHtml", String::class.java
        ).apply { isAccessible = true }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta property="og:description" content="1,234 Followers, 567 Following, 89 Posts - See Instagram photos and videos from Ervin Demo (@ervindemo)">
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val stats = method.invoke(client, html)
        org.junit.Assert.assertNotNull(stats)
        val followersField = stats.javaClass.getDeclaredField("followers").apply { isAccessible = true }
        val followingField = stats.javaClass.getDeclaredField("following").apply { isAccessible = true }
        val postsField = stats.javaClass.getDeclaredField("posts").apply { isAccessible = true }

        org.junit.Assert.assertEquals(1234L, followersField.get(stats))
        org.junit.Assert.assertEquals(567L, followingField.get(stats))
        org.junit.Assert.assertEquals(89L, postsField.get(stats))
    }

    @Test fun `extractProfileStatsFromHtml parses formatted counts with K and M`() {
        val client = InstagramWebClient(OkHttpClient.Builder().build(), null)
        val method = InstagramWebClient::class.java.getDeclaredMethod(
            "extractProfileStatsFromHtml", String::class.java
        ).apply { isAccessible = true }

        val html = """
            <meta name="description" content="2.5M Followers, 1.2K Following, 350 Posts - Photos">
        """.trimIndent()

        val stats = method.invoke(client, html)
        org.junit.Assert.assertNotNull(stats)
        val followersField = stats.javaClass.getDeclaredField("followers").apply { isAccessible = true }
        val followingField = stats.javaClass.getDeclaredField("following").apply { isAccessible = true }

        org.junit.Assert.assertEquals(2500000L, followersField.get(stats))
        org.junit.Assert.assertEquals(1200L, followingField.get(stats))
    }
}
