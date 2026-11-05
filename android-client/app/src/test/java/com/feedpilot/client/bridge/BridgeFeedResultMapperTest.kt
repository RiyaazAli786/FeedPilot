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
        assertEquals(setOf("data", "resolvedAccountUsername"), payload.keys)
        val data = payload.getValue("data").jsonObject
        assertEquals(setOf("profile", "items", "maxId", "hasMore", "stories", "highlights"), data.keys)
        assertEquals("example", data.getValue("profile").jsonObject.getValue("username").jsonPrimitive.content)
        assertEquals("cursor", data.getValue("maxId").jsonPrimitive.content)
        assertTrue(data.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(1, data.getValue("items").jsonArray.size)
        val post = data.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "code", "caption", "mediaType", "displayUrl", "videoUrl", "likeCount", "commentCount", "repostCount", "isPinned", "takenAt", "mediaId"), post.keys)
        assertEquals("456", post.getValue("mediaId").jsonPrimitive.content)
        assertEquals(1791140402L, post.getValue("takenAt").jsonPrimitive.long)
        assertEquals(99L, post.getValue("likeCount").jsonPrimitive.long)
        assertEquals("video", post.getValue("videoUrl").jsonPrimitive.content)
        assertTrue(data.getValue("stories").jsonArray.isEmpty())
        assertTrue(data.getValue("highlights").jsonArray.isEmpty())
    }

    @Test fun `pinned post with older timestamp is ordered after newer chronological post`() {
        val pinnedOldItem = InstagramUserFeedItem(
            id = "pinned_1", code = "DeuAt8TKU6C", caption = "Pinned post", mediaType = 2,
            displayUrl = "img1", videoUrl = null, likeCount = 10, commentCount = 2,
            repostCount = 0, takenAt = 1792505679, mediaId = "4012147475156127362", isPinned = true
        )
        val latestUnpinnedItem = InstagramUserFeedItem(
            id = "latest_1", code = "DfNdhjQK08C", caption = "Latest post", mediaType = 2,
            displayUrl = "img2", videoUrl = null, likeCount = 5, commentCount = 1,
            repostCount = 0, takenAt = 1793560882, mediaId = "4020999891255512834", isPinned = false
        )
        val feed = InstagramUserFeedResult(listOf(pinnedOldItem, latestUnpinnedItem), null, false)
        val data = feed.toBridgeResultData(2)
        assertEquals("DfNdhjQK08C", data.items[0].code)
        assertFalse(data.items[0].isPinned)
        assertEquals("DeuAt8TKU6C", data.items[1].code)
        assertTrue(data.items[1].isPinned)
    }
}
