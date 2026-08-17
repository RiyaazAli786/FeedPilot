package com.feedpilot.client.service

import android.util.Log
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.data.remote.InstagramPostDetails
import com.feedpilot.client.data.remote.InstagramWebClient
import com.feedpilot.client.data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

data class PostFetcherTestResult(
    val input: String,
    val extractedShortcode: String,
    val calculatedMediaId: String,
    val success: Boolean,
    val postDetails: InstagramPostDetails?,
    val logs: List<String>
)

@Singleton
class PostFetcherTestService @Inject constructor(
    private val instagramWebClient: InstagramWebClient,
    private val instagramRepository: com.feedpilot.client.data.repository.InstagramRepository
) {

    suspend fun testFetchPost(input: String): PostFetcherTestResult {
        val logs = mutableListOf<String>()
        logs.add("Testing post fetch for input: '$input'")

        val shortcode = InstagramCrypto.getCodeFromUrl(input)
        logs.add("Extracted shortcode: '$shortcode'")

        val mediaId = if (shortcode.all { it.isDigit() }) shortcode else InstagramCrypto.getIdFromCode(shortcode)
        logs.add("Calculated numeric mediaId: '$mediaId'")

        val details = instagramRepository.getPostDetails(shortcode)
        if (details != null) {
            logs.add("SUCCESS: Post fetched successfully!")
            logs.add("ID: ${details.id}")
            logs.add("Code: ${details.code}")
            logs.add("Owner: @${details.ownerUsername ?: "unknown"}")
            logs.add("Caption: ${details.caption?.take(60) ?: "None"}")
            logs.add("Display URL: ${details.displayUrl?.take(60) ?: "None"}")
            logs.add("Video URL: ${details.videoUrl?.take(60) ?: "None"}")
            logs.add("Likes: ${details.likeCount}, Comments: ${details.commentCount}")
        } else {
            logs.add("FAILURE: Could not fetch post details for '$shortcode'")
        }

        val result = PostFetcherTestResult(
            input = input,
            extractedShortcode = shortcode,
            calculatedMediaId = mediaId,
            success = details != null,
            postDetails = details,
            logs = logs
        )

        logs.forEach { Log.d("PostFetcherTestService", it) }
        return result
    }
}
