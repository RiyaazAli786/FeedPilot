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
}
