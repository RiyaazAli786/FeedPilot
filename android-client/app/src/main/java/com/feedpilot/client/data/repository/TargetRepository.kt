package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.InstagramUserFeedResult
import com.feedpilot.client.data.remote.dto.TargetMediaDto
import com.feedpilot.client.data.remote.dto.TargetProfileDto
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches target account details and posts on demand from real Instagram data on-device. */
@Singleton
class TargetRepository @Inject constructor(
    private val instagramRepository: InstagramRepository
) {
    suspend fun fetchProfile(username: String): Resource<TargetProfileDto> = try {
        // Real Instagram data, fetched on-device. There is no backend fallback: the server has
        // no session and used to answer with fabricated numbers, which we never want to show.
        val webProfile = instagramRepository.getUserProfileDetails(username)
            ?: return Resource.Error("Couldn't load @$username")
        Resource.Success(
            TargetProfileDto(
                username = webProfile.username,
                fullName = webProfile.fullName,
                avatarUrl = webProfile.profilePicUrl,
                followers = webProfile.followerCount,
                following = webProfile.followingCount,
                posts = webProfile.mediaCount,
                isPrivate = webProfile.isPrivate,
                media = emptyList()
            )
        )
    } catch (t: Throwable) {
        Resource.Error(mapError(t, "Couldn't load @$username"), t)
    }

    suspend fun fetchPost(link: String): Resource<TargetMediaDto> = try {
        val postDetails = instagramRepository.getPostDetails(link)
            ?: return Resource.Error("Couldn't load that post")
        Resource.Success(
            TargetMediaDto(
                id = postDetails.id,
                imageUrl = postDetails.displayUrl ?: "",
                likes = postDetails.likeCount,
                comments = postDetails.commentCount,
                reposts = postDetails.repostCount,
                link = "https://www.instagram.com/p/${postDetails.code}/",
                videoUrl = postDetails.videoUrl,
                isVideo = postDetails.mediaType == 2 || !postDetails.videoUrl.isNullOrBlank(),
                ownerUsername = postDetails.ownerUsername,
                caption = postDetails.caption
            )
        )
    } catch (t: Throwable) {
        Resource.Error(mapError(t, "Couldn't load that post"), t)
    }

    suspend fun fetchUserFeed(userIdOrUsername: String, maxId: String? = null): Resource<InstagramUserFeedResult> = try {
        // The feed endpoint is /feed/user/<username>/username/ — it expects the USERNAME, and
        // returns 0 items when given a numeric id. So pass the handle straight through; do NOT
        // resolve it to a numeric id first (that was silently emptying the posts + like counts).
        val feedResult = instagramRepository.getUserFeed(userIdOrUsername.trim().removePrefix("@"), maxId)
        if (feedResult != null) {
            Resource.Success(feedResult)
        } else {
            Resource.Error("Failed to fetch user feed for $userIdOrUsername")
        }
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Failed to fetch user feed", t)
    }

    private fun mapError(t: Throwable, fallback: String): String {
        val msg = t.message ?: ""
        return when {
            msg.contains("401") -> "Please log in to load details"
            msg.contains("404") -> "Not found"
            else -> fallback
        }
    }
}
