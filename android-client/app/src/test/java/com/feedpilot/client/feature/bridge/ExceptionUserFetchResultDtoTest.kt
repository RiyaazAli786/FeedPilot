package com.feedpilot.client.feature.bridge

import com.feedpilot.client.bridge.toBridgeResultData
import com.feedpilot.client.data.remote.InstagramUserFeedItem
import com.feedpilot.client.data.remote.InstagramUserFeedResult
import com.feedpilot.client.data.remote.InstagramUserProfileDetails
import com.feedpilot.client.data.remote.dto.ExceptionUserFetchResultDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class ExceptionUserFetchResultDtoTest {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun formatsUserFetchResponseCorrectly() {
        val profile = InstagramUserProfileDetails(
            id = "1001",
            username = "target_user",
            fullName = "Target User",
            biography = "Bio text",
            profilePicUrl = "https://example.com/avatar.jpg",
            followerCount = 1500,
            followingCount = 300,
            mediaCount = 45,
            isPrivate = false,
            isVerified = true
        )
        val item = InstagramUserFeedItem(
            id = "item_1",
            code = "CODE1",
            caption = "Test caption",
            mediaType = 1,
            displayUrl = "https://example.com/image.jpg",
            videoUrl = null,
            likeCount = 250,
            commentCount = 15,
            repostCount = 2,
            takenAt = 1791140000,
            mediaId = "media_1"
        )
        val feed = InstagramUserFeedResult(listOf(item), "next_max_id", true, profile)
        val bridgeData = feed.toBridgeResultData(1)

        val dto = ExceptionUserFetchResultDto(
            target = "target_user",
            fetchedByAccount = "account_worker",
            status = "SUCCESS",
            postsCount = bridgeData.items.size,
            profile = bridgeData.profile,
            items = bridgeData.items,
            maxId = bridgeData.maxId,
            hasMore = bridgeData.hasMore
        )

        val serialized = json.encodeToString(dto)
        val parsed = json.parseToJsonElement(serialized).jsonObject

        assertEquals("target_user", parsed.getValue("target").jsonPrimitive.content)
        assertEquals("account_worker", parsed.getValue("fetchedByAccount").jsonPrimitive.content)
        assertEquals("SUCCESS", parsed.getValue("status").jsonPrimitive.content)
        assertEquals(1, parsed.getValue("postsCount").jsonPrimitive.content.toInt())

        val parsedProfile = parsed.getValue("profile").jsonObject
        assertEquals("target_user", parsedProfile.getValue("username").jsonPrimitive.content)
        assertEquals(1500L, parsedProfile.getValue("followerCount").jsonPrimitive.long)
        assertEquals(300L, parsedProfile.getValue("followingCount").jsonPrimitive.long)

        val itemsArray = parsed.getValue("items")
        assertNotNull(itemsArray)
        assertTrue(serialized.contains("target_user"))
        assertTrue(serialized.contains("account_worker"))
    }
}
