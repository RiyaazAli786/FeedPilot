package com.feedpilot.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSessionMergeTest {
    @Test fun `server refresh preserves newly saved device session`() {
        assertEquals("sessionid=new; csrftoken=rotated", mergeAccountSessionCookies(
            "sessionid=new; csrftoken=rotated", "sessionid=old; csrftoken=old"))
    }
    @Test fun `restore uses server session when device has none`() {
        assertEquals("sessionid=restored", mergeAccountSessionCookies(null, "sessionid=restored"))
        assertEquals("sessionid=restored", mergeAccountSessionCookies("", "sessionid=restored"))
    }
    @Test fun `empty server snapshot preserves device session`() {
        assertEquals("sessionid=device", mergeAccountSessionCookies("sessionid=device", ""))
    }
}
