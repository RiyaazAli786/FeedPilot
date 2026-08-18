package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.remote.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized Repository for all Instagram service calls and operations.
 * Acts as the single point of entry for Instagram API calls across the entire app.
 *
 * Every call here runs as a signed-in account. Without one they return "nothing" instead of asking
 * Instagram anyway: a logged-out request is not just useless, it is an anonymous hit on Instagram's
 * endpoints from this device. The exceptions are [login], [submitTwoFactorCode] and
 * [getPreLoginData] — those are how an account gets signed in — and any caller that supplies
 * `customCookies`, which is the session it just captured and not yet stored.
 */
@Singleton
class InstagramRepository @Inject constructor(
    private val instagramWebClient: InstagramWebClient,
    private val sessionGate: AccountSessionGate
) {

    /** Authenticates Instagram credentials via Web API. */
    suspend fun login(username: String, password: String): InstagramLoginResult {
        return instagramWebClient.login(username, password)
    }

    /** Answers a two-factor challenge raised by [login] with the code the user entered. */
    suspend fun submitTwoFactorCode(
        challenge: InstagramLoginResult.TwoFactorRequired,
        code: String
    ): InstagramLoginResult {
        return instagramWebClient.submitTwoFactorCode(challenge, code)
    }

    /** Submits the email verification code for an Instagram email challenge raised by [login]. */
    suspend fun submitEmailCode(
        challenge: InstagramLoginResult.EmailCodeRequired,
        code: String
    ): InstagramLoginResult {
        return instagramWebClient.submitEmailCode(challenge, code)
    }

    /** Resends the email verification code for an Instagram email challenge raised by [login]. */
    suspend fun resendEmailCode(
        challenge: InstagramLoginResult.EmailCodeRequired
    ): InstagramLoginResult.EmailCodeRequired {
        return instagramWebClient.resendEmailCode(challenge)
    }

    /** Fetches pre-login Instagram metadata (CSRF token, device ID, public key). */
    suspend fun getPreLoginData(customCookieHeader: String? = null): PreLoginData {
        return instagramWebClient.fetchPreLoginData(customCookieHeader)
    }

    /** Resolves profile details for a given username or URL, as the signed-in account. */
    suspend fun getUserProfileDetails(username: String, customCookies: String? = null): InstagramUserProfileDetails? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.fetchUserProfileDetails(username, cookies)
    }

    /** Resolves profile details for the currently logged-in account using its session cookies. */
    suspend fun getLoggedInUserProfile(customCookies: String? = null): InstagramUserProfileDetails? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.fetchCurrentLoggedInUser(cookies)
    }

    /** Direct HTML fallback to extract username handle when API endpoints fail. */
    suspend fun getLoggedInUsernameFromWebHtml(customCookies: String? = null): String? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.fetchUsernameFromWebHtml(cookies)
    }

    /** Whether [userId] currently has a live (not expired) Instagram story. */
    suspend fun hasActiveStory(userId: String, customCookies: String? = null): Boolean {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return false
        return instagramWebClient.fetchHasActiveStory(userId, cookies)
    }

    /** Fetches media feed for a user ID or username. */
    suspend fun getUserFeed(userId: String, maxId: String? = null, customCookies: String? = null): InstagramUserFeedResult? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.fetchUserFeed(userId, maxId, cookies)
    }

    /** Fetches metadata for a specific media item by ID or shortcode. */
    suspend fun getPostDetails(mediaCodeOrId: String, customCookies: String? = null): InstagramPostDetails? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.fetchPostDetails(mediaCodeOrId, cookies)
    }

    /** Performs a follow action on a target user ID or handle. */
    suspend fun follow(targetUserId: String, targetUsername: String = "", customCookies: String? = null): IgActionResult {
        val cookies = customCookies ?: getActiveAccountCookies()
            ?: return IgActionResult.fail("No Instagram session for this account")
        return instagramWebClient.follow(targetUserId, targetUsername, cookies)
    }

    /** Performs a like action on a media item. */
    suspend fun like(mediaCodeOrId: String, customCookies: String? = null): IgActionResult {
        val cookies = customCookies ?: getActiveAccountCookies()
            ?: return IgActionResult.fail("No Instagram session for this account")
        return instagramWebClient.like(mediaCodeOrId, cookies)
    }

    /** Reposts (reshares) a media item to the signed-in account. */
    suspend fun repost(mediaCodeOrId: String, customCookies: String? = null): IgActionResult {
        val cookies = customCookies ?: getActiveAccountCookies()
            ?: return IgActionResult.fail("No Instagram session for this account")
        return instagramWebClient.repost(mediaCodeOrId, cookies)
    }

    /** Saves a media item to the signed-in account's saved collection. */
    suspend fun savePost(mediaCodeOrId: String, customCookies: String? = null): IgActionResult {
        val cookies = customCookies ?: getActiveAccountCookies()
            ?: return IgActionResult.fail("No Instagram session for this account")
        return instagramWebClient.savePost(mediaCodeOrId, cookies)
    }

    /** Views an Instagram story using PolarisStoriesV3SeenMutation. */
    suspend fun storyView(targetIdOrUrl: String, customCookies: String? = null): IgActionResult {
        val cookies = customCookies ?: getActiveAccountCookies()
            ?: return IgActionResult.fail("No Instagram session for this account")
        return instagramWebClient.storyView(targetIdOrUrl, cookies)
    }

    /** Posts a comment on a media item as the signed-in account. */
    suspend fun postComment(mediaCodeOrId: String, text: String, customCookies: String? = null): IgActionResult {
        val cookies = customCookies ?: getActiveAccountCookies()
            ?: return IgActionResult.fail("No Instagram session for this account")
        return instagramWebClient.postComment(mediaCodeOrId, text, cookies)
    }

    /**
     * Loads a page of comments for a post.
     *
     * Instagram does serve these to a signed-out visitor for public posts, but this app has no
     * reason to ask before an account is linked, so it does not.
     */
    suspend fun getComments(
        mediaCodeOrLink: String,
        maxId: String? = null,
        customCookies: String? = null
    ): InstagramCommentPage? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.fetchComments(mediaCodeOrLink, cookies, maxId)
    }

    /** Resolves a username/URL to numeric Instagram User ID. */
    suspend fun resolveUserId(usernameOrUrl: String, customCookies: String? = null): String? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.resolveUserId(usernameOrUrl, cookies)
    }

    suspend fun searchUsers(query: String, customCookies: String? = null): List<InstagramSearchUser> {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return emptyList()
        return instagramWebClient.searchUsers(query, cookies)
    }

    suspend fun fetchSuggestedUsers(customCookies: String? = null, maxNumberToDisplay: Int = 50): List<InstagramSuggestedUser> {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return emptyList()
        return instagramWebClient.fetchSuggestedUsers(cookies, maxNumberToDisplay)
    }

    suspend fun uploadPhoto(photoBytes: ByteArray, uploadId: String, isStory: Boolean, customCookies: String? = null): Boolean {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return false
        return instagramWebClient.uploadPhoto(photoBytes, uploadId, isStory, cookies)
    }

    suspend fun configurePhoto(uploadId: String, caption: String, customCookies: String? = null): Boolean {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return false
        return instagramWebClient.configurePhoto(uploadId, caption, cookies)
    }

    suspend fun configureStory(uploadId: String, customCookies: String? = null): Boolean {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return false
        return instagramWebClient.configureStory(uploadId, cookies)
    }

    suspend fun updateProfilePicture(photoBytes: ByteArray, customCookies: String? = null): Boolean {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return false
        return instagramWebClient.updateProfilePicture(photoBytes, cookies)
    }

    suspend fun getProfilePicProps(username: String, customCookies: String? = null): String? {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return null
        return instagramWebClient.getProfilePicProps(username, cookies)
    }

    suspend fun updateBiography(biography: String, firstName: String, username: String, customCookies: String? = null): Boolean {
        val cookies = customCookies ?: getActiveAccountCookies() ?: return false
        return instagramWebClient.updateBiography(biography, firstName, username, cookies)
    }

    private suspend fun getActiveAccountCookies(): String? = sessionGate.activeSessionCookies()
}
