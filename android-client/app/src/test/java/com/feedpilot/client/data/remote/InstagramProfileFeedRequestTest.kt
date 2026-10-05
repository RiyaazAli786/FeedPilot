package com.feedpilot.client.data.remote

import okhttp3.FormBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstagramProfileFeedRequestTest {
    private fun fields(cursor: String? = null): Map<String, String> {
        val body = InstagramProfileFeedRequest.build("example", cursor, "sessionid=test", "csrf", emptyMap()).body as FormBody
        return (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    @Test fun `uses captured query and forward pagination`() {
        val fields = fields("next-page")
        val variables = JSONObject(fields.getValue("variables"))
        assertEquals("29240983615539641", fields["doc_id"])
        assertEquals("next-page", variables.getString("after"))
        assertTrue(variables.isNull("before"))
        assertTrue(variables.getBoolean("include_multi_captions"))
        assertTrue(variables.getBoolean("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider"))
        assertFalse(variables.has("__relay_internal__pv__PolarisIsLoggedInrelayprovider"))
    }

    @Test fun `first page does not reuse captured cursor`() {
        val variables = JSONObject(fields().getValue("variables"))
        assertTrue(variables.isNull("after"))
        assertTrue(variables.isNull("before"))
    }

    @Test fun `extracts tokens from current session page and binds header to form`() {
        val html = """["LSD",[],{"token":"live-lsd"}],["DTSGInitialData",[],{"token":"live-dtsg"}],{"actorID":"123"}"""
        val tokens = InstagramProfileFeedRequest.tokens(html)
        val request = InstagramProfileFeedRequest.build("example", null, "sessionid=test", "csrf", tokens)
        val body = request.body as FormBody
        val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals("live-lsd", request.header("X-FB-LSD"))
        assertEquals("live-lsd", fields["lsd"])
        assertEquals("live-dtsg", fields["fb_dtsg"])
        assertEquals("123", fields["av"])
        assertEquals("https://www.instagram.com/example/", request.header("Referer"))
    }
}
