package com.feedpilot.client.bridge

import com.feedpilot.client.data.remote.InstagramUserFeedResult
import com.feedpilot.client.data.remote.dto.*

internal fun InstagramUserFeedResult.toBridgeResultData(
    limit: Int,
    stories: List<BridgeStoryItemDto> = emptyList(),
    highlights: List<BridgeHighlightDto> = emptyList()
): BridgeFeedResultDataDto = BridgeFeedResultDataDto(
    profile = profile?.let {
        BridgeFeedProfileDto(
            id = it.id, username = it.username, fullName = it.fullName,
            biography = it.biography, profilePicUrl = it.profilePicUrl,
            followerCount = it.followerCount, followingCount = it.followingCount,
            mediaCount = it.mediaCount, isPrivate = it.isPrivate, isVerified = it.isVerified
        )
    },
    items = items.take(limit).map {
        BridgeFeedItemDto(
            id = it.id, code = it.code, caption = it.caption, mediaType = it.mediaType,
            displayUrl = it.displayUrl, videoUrl = it.videoUrl,
            likeCount = it.likeCount, commentCount = it.commentCount, repostCount = it.repostCount,
            takenAt = it.takenAt, mediaId = it.mediaId
        )
    },
    maxId = maxId,
    hasMore = hasMore,
    stories = stories,
    highlights = highlights
)
