package com.feedpilot.client.bridge

import com.feedpilot.client.data.remote.InstagramUserFeedItem
import com.feedpilot.client.data.remote.InstagramUserFeedResult
import com.feedpilot.client.data.remote.InstagramUserProfileDetails
import com.feedpilot.client.data.remote.dto.CompleteBridgeJobRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BridgeFeedResultMapperTest {
    @Test fun `new feed retains existing bridge wire contract`() {
        val profile = InstagramUserProfileDetails("123", "example", "Example", "Bio", "avatar", 100, 20, 30, false, true)
        val item = InstagramUserFeedItem(
            id = "456_123", code = "post-code", caption = "caption", mediaType = 2,
            displayUrl = "image", videoUrl = "video", likeCount = 99, commentCount = 7,
            repostCount = 3, takenAt = 1791140402, mediaId = "456"
        )
        val feed = InstagramUserFeedResult(listOf(item, item.copy(id = "789_123")), "cursor", true, profile)
        val json = Json { encodeDefaults = true }
        val payload = json.parseToJsonElement(json.encodeToString(CompleteBridgeJobRequest(feed.toBridgeResultData(1)))).jsonObject
        assertEquals(setOf("data"), payload.keys)
        val data = payload.getValue("data").jsonObject
        assertEquals(setOf("profile", "items", "maxId", "hasMore", "stories", "highlights"), data.keys)
        assertEquals("example", data.getValue("profile").jsonObject.getValue("username").jsonPrimitive.content)
        assertEquals("cursor", data.getValue("maxId").jsonPrimitive.content)
        assertTrue(data.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(1, data.getValue("items").jsonArray.size)
        val post = data.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "code", "caption", "mediaType", "displayUrl", "videoUrl", "likeCount", "commentCount", "repostCount", "takenAt", "mediaId"), post.keys)
        assertEquals("456", post.getValue("mediaId").jsonPrimitive.content)
        assertEquals(1791140402L, post.getValue("takenAt").jsonPrimitive.long)
        assertEquals(99L, post.getValue("likeCount").jsonPrimitive.long)
        assertEquals("video", post.getValue("videoUrl").jsonPrimitive.content)
        assertTrue(data.getValue("stories").jsonArray.isEmpty())
        assertTrue(data.getValue("highlights").jsonArray.isEmpty())
    }
}
