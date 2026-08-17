package com.feedpilot.client.data.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.di.InstagramClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

sealed class InstagramLoginResult {
    data class Success(
        val userId: String,
        val username: String,
        val sessionData: String
    ) : InstagramLoginResult()

    /**
     * Instagram accepted the password but wants a one-time code. Everything needed to finish the
     * login is carried here: the code has to be submitted on the very session that was challenged,
     * so the cookies and CSRF token from that response travel with it.
     */
    data class TwoFactorRequired(
        val identifier: String,
        val username: String,
        val sessionCookies: String,
        val csrfToken: String,
        /** Where Instagram says it sent the code, ready to show the user. Null when it did not say. */
        val hint: String? = null
    ) : InstagramLoginResult()

    /**
     * Instagram accepted credentials but requires security/email verification code.
     */
    data class EmailCodeRequired(
        val username: String,
        val challengeUrl: String,
        val sessionCookies: String,
        val csrfToken: String,
        /** Where Instagram sent the verification code. Null when not specified. */
        val hint: String? = null
    ) : InstagramLoginResult()

    data class Failure(val message: String) : InstagramLoginResult()
}

/**
 * Outcome of a web engagement action (like/follow). On ,failure it carries Instagram's own
 * reason so the action log can show what actually happened instead of a generic guess.
 */
data class IgActionResult(
    val ok: Boolean,
    val reason: String? = null,
    /**
     * A refreshed `Cookie` header, when this action's response rotated one or more cookies
     * (csrftoken most often). Null when nothing changed. Instagram rotates cookies on ordinary
     * write responses the same way a browser's cookie jar picks them up automatically; a caller
     * that keeps reusing the cookie string it started the session with, forever, eventually gets
     * rejected as logged-out even though the account never actually signed out — it just never
     * saw the rotation. The caller is expected to persist this back onto the account so the next
     * request (and the next app launch) uses it.
     */
    val updatedCookies: String? = null
) {
    companion object {
        val Ok = IgActionResult(true)
        fun fail(reason: String) = IgActionResult(false, reason)

        /**
         * Turns an Instagram response into a specific, user-facing reason. The distinctions
         * matter: a rate-limit, a logged-out session, a checkpoint and an action-block each
         * need a different response from the operator, and the old code reported them all the
         * same. Order matters — a logged-out session is a 404 "not-logged-in" page, so that is
         * matched before the generic 404-means-missing-post case.
         */
        fun classify(action: String, httpCode: Int, body: String): IgActionResult {
            val b = body.lowercase()
            val reason = when {
                httpCode == 429 || b.contains("please wait a few minutes") ->
                    "Rate limited by Instagram (429) — too many actions; wait a while and retry"
                b.contains("login_required") || b.contains("not-logged-in") ||
                    b.contains("\"logout_reason\"") || httpCode == 401 ->
                    "Session logged out — cookies are expired or region-locked; re-add the account with a fresh session"
                b.contains("checkpoint_required") || b.contains("challenge_required") ->
                    "Account checkpoint — verify this account in the Instagram app, then re-add it"
                b.contains("feedback_required") || b.contains("spam") ->
                    "Instagram action-block (feedback_required) — this account is temporarily blocked from ${action}s"
                // Distinct from the generic 404 below: this is Instagram saying the *account* id
                // is gone (deleted/renamed since it was last resolved), not that a post is
                // missing. Matched first so a caller (e.g. a follow whose target id came from the
                // backend's resolve-cache) can tell a stale cached id apart from a dead post and
                // re-resolve instead of just failing.
                b.contains("user_not_found") ->
                    "Target Instagram account not found (deleted or renamed) — HTTP 404"
                b.contains("media_not_found") || (httpCode == 404) ->
                    "Post not found or unavailable (404)"
                else -> {
                    val msg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                    "Instagram rejected the $action (HTTP $httpCode)" + (msg?.let { ": $it" } ?: "")
                }
            }
            return IgActionResult(false, reason)
        }
    }
}

data class PreLoginData(
    val csrfToken: String,
    val webDeviceId: String,
    val keyId: Int,
    val publicKey: String,
    val version: String,
    val lsdToken: String = "",
    /**
     * Every cookie this fetch was issued (datr, mid, ig_did, csrftoken…), as a `Cookie` header
     * value. A real browser always carries these into the very next request; sending the login
     * POST without them — a CSRF header with no matching cookie jar at all — is one of the
     * clearest automation signals Instagram's login endpoint checks for.
     */
    val cookies: String = ""
)

data class InstagramUserProfileDetails(
    val id: String,
    val username: String,
    val fullName: String,
    val biography: String,
    val profilePicUrl: String,
    val followerCount: Long,
    val followingCount: Long,
    val mediaCount: Long,
    val isPrivate: Boolean,
    val isVerified: Boolean,
    val externalUrl: String? = null,
    /**
     * Instagram always serves *some* profile_pic_url, even unset — a generated default avatar —
     * so a non-blank URL alone cannot tell "has a real photo" from "never uploaded one". This is
     * IG's own flag for that distinction, returned by the authenticated user/info endpoints.
     */
    val hasAnonymousProfilePic: Boolean = false
)

data class InstagramUserFeedItem(
    val id: String,
    val code: String,
    val caption: String?,
    val mediaType: Int,
    val displayUrl: String?,
    /** Direct CDN url for a video/reel, straight from the feed — no second lookup needed. */
    val videoUrl: String? = null,
    val likeCount: Long,
    val commentCount: Long,
    val repostCount: Long = 0L,
    val takenAt: Long,
    /** Numeric media id (`pk`), required by the like and repost mutations. */
    val mediaId: String = "",
    /** Whether the signed-in account has already liked this post. */
    val hasLiked: Boolean = false,
    /** Whether Instagram allows the viewer to repost/reshare this media. */
    val canReshare: Boolean = true,
    /** Whether commenting is turned off for this post. */
    val commentsDisabled: Boolean = false
)

/** One comment on a post. */
data class InstagramComment(
    val id: String,
    val username: String,
    val avatarUrl: String?,
    val text: String,
    val likeCount: Long,
    val createdAt: Long
)

/** A page of comments plus the cursor needed to load the next one. */
data class InstagramCommentPage(
    val comments: List<InstagramComment>,
    val endCursor: String?,
    val hasMore: Boolean,
    val total: Long,
    /** Instagram refused the read because no signed-in session was supplied. */
    val requiresLogin: Boolean = false
)

data class InstagramUserFeedResult(
    val items: List<InstagramUserFeedItem>,
    val maxId: String?,
    val hasMore: Boolean
)

data class InstagramPostDetails(
    val id: String,
    val code: String,
    val caption: String?,
    val mediaType: Int,
    val displayUrl: String?,
    val videoUrl: String?,
    val likeCount: Long,
    val commentCount: Long,
    val repostCount: Long = 0L,
    val ownerUserId: String?,
    val ownerUsername: String?,
    val takenAt: Long
)

data class InstagramSearchUser(
    val username: String,
    val fullName: String?,
    val profilePicUrl: String?,
    val isPrivate: Boolean,
    val isVerified: Boolean
)

@Singleton
class InstagramWebClient @Inject constructor(
    // A clean client with no backend interceptors/authenticator (see @InstagramClient), so our
    // backend JWT and device signature never travel to instagram.com and a 401 from Instagram
    // never triggers a backend token refresh.
    @InstagramClient private val client: OkHttpClient,
    // Nullable only so JVM unit tests can construct this without an Android Context; Hilt still
    // injects the real @ApplicationContext at runtime. Used solely for the device fingerprint,
    // which already tolerates a null context.
    @ApplicationContext private val context: Context?
) {

    private val http: OkHttpClient = client

    private val activeFingerprint: InstagramCrypto.DeviceFingerprint
        get() = InstagramCrypto.getDeviceFingerprint(context)

    private fun Request.Builder.applyDeviceHeaders(fp: InstagramCrypto.DeviceFingerprint = activeFingerprint): Request.Builder {
        return this
            .header("User-Agent", fp.userAgent)
            .header("sec-ch-ua", InstagramCrypto.SEC_CH_UA)
            .header("sec-ch-ua-mobile", fp.isMobile)
            .header("sec-ch-ua-platform", fp.platform)
            .header("sec-ch-ua-platform-version", fp.platformVersion)
            .header("sec-ch-ua-model", fp.model)
            .header("Accept-Language", InstagramCrypto.ACCEPT_LANGUAGE)
    }

    /**
     * Dynamically fetches the LSD token by requesting Instagram HTML page.
     */
    suspend fun fetchLsdToken(customCookieHeader: String? = null, targetUrl: String = "https://www.instagram.com/accounts/login/"): String = withContext(Dispatchers.IO) {
        var extracted = ""
        try {
            val fullTargetUrl = if (targetUrl.startsWith("http")) targetUrl else "https://www.instagram.com${if (targetUrl.startsWith("/")) "" else "/"}$targetUrl"
            val fp = activeFingerprint
            val requestBuilder = Request.Builder()
                .url(fullTargetUrl)
                .get()
                .applyDeviceHeaders(fp)
                .header("Host", "www.instagram.com")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", InstagramCrypto.ACCEPT_LANGUAGE)
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")

            if (!customCookieHeader.isNullOrBlank()) {
                requestBuilder.header("Cookie", customCookieHeader)
            }

            http.newCall(requestBuilder.build()).execute().use { response ->
                val htmlText = response.body?.string() ?: ""
                extracted = extractLsdToken(htmlText)
                Log.e(TAG, "fetchLsdToken from $targetUrl: HTTP ${response.code}, lsdLen=${extracted.length}, lsd=${extracted.take(12)}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchLsdToken failed for $targetUrl", t)
        }
        extracted
    }

    private fun extractLsdToken(responseText: String?): String {
        if (responseText.isNullOrBlank()) return ""
        return runCatching {
            LSD_TOKEN_REGEX.find(responseText)?.groupValues?.get(1)
                ?: LSD_TOKEN_FALLBACK_REGEX.find(responseText)?.groupValues?.get(1)
                ?: Regex("""name="lsd"\s+value="([^"]+)"""").find(responseText)?.groupValues?.get(1)
                ?: Regex("""value="([^"]+)"\s+name="lsd"""").find(responseText)?.groupValues?.get(1)
                ?: Regex(""""(?:lsd|LSD|LSD_TOKEN|_js_lsd)"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.get(1)
                ?: Regex("""\blsd=([A-Za-z0-9_-]{10,})""").find(responseText)?.groupValues?.get(1)
                ?: run {
                    val lsdIdx = responseText.indexOf("LSD")
                    if (lsdIdx != -1) {
                        val sub = responseText.substring(lsdIdx, Math.min(lsdIdx + 300, responseText.length))
                        Regex(""""token"\s*:\s*"([^"]+)"""").find(sub)?.groupValues?.get(1)
                    } else null
                }
                ?: extractJsonValue(responseText, "lsd")
                ?: extractJsonValue(responseText, "LSD")
                ?: ""
        }.getOrDefault("")
    }

    /**
     * Fetches Instagram shared_data to extract CSRF token and encryption public key.
     */
    suspend fun fetchPreLoginData(customCookieHeader: String? = null): PreLoginData = withContext(Dispatchers.IO) {
        val fp = activeFingerprint
        val requestBuilder = Request.Builder()
            .url(InstagramCrypto.SHARED_DATA_URL)
            .header("User-Agent", fp.userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", InstagramCrypto.ACCEPT_LANGUAGE)
            .header("sec-ch-ua", InstagramCrypto.SEC_CH_UA)
            .header("sec-ch-ua-mobile", fp.isMobile)
            .header("sec-ch-ua-platform", fp.platform)
            .header("sec-ch-ua-platform-version", fp.platformVersion)
            .header("sec-ch-ua-model", fp.model)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")

        if (!customCookieHeader.isNullOrBlank()) {
            requestBuilder.header("Cookie", customCookieHeader)
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            val responseText = response.body?.string() ?: ""
            // OkHttp may return multiple Set-Cookie headers; check all of them for csrftoken
            val allSetCookies = response.headers("Set-Cookie")
            val csrfToken = allSetCookies
                .firstNotNullOfOrNull { extractCookie(it, "csrftoken") }
                ?: extractJsonValue(responseText, "csrf_token")
                ?: "missing_csrf"

            var lsdToken = extractLsdToken(responseText)
            val currentCookieStr = joinCookies(parseSetCookies(allSetCookies))
            if (lsdToken.isBlank()) {
                lsdToken = fetchLsdToken(currentCookieStr) ?: ""
            }
            if (lsdToken.isBlank()) {
                lsdToken = DEFAULT_LSD_TOKEN
            }
            Log.e(TAG, "PreLogin: HTTP ${response.code}, csrf=${csrfToken.take(12)}…, lsd=${lsdToken.take(12)}…, setCookieCount=${allSetCookies.size}")

            val deviceId = extractJsonValue(responseText, "device_id") ?: ""

            var keyId = 0
            var publicKey = ""
            var version = "10"

            try {
                if (responseText.contains("\"encryption\":")) {
                    val encIndex = responseText.indexOf("\"encryption\":")
                    val sub = responseText.substring(encIndex, Math.min(encIndex + 300, responseText.length))
                    keyId = extractJsonValue(sub, "key_id")?.toIntOrNull() ?: 0
                    publicKey = extractJsonValue(sub, "public_key") ?: ""
                    version = extractJsonValue(sub, "version") ?: "10"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing encryption block", e)
            }

            // The csrftoken extracted above is only ever right if it also shows up here — build
            // the header from the very same Set-Cookie values rather than re-deriving it.
            val preLoginCookies = parseSetCookies(allSetCookies) + ("csrftoken" to csrfToken)

            PreLoginData(
                csrfToken = csrfToken,
                webDeviceId = deviceId,
                keyId = keyId,
                publicKey = publicKey,
                version = version,
                lsdToken = lsdToken,
                cookies = joinCookies(preLoginCookies)
            )
        }
    }

    /**
     * Authenticates Instagram account via direct Web AJAX API endpoint.
     */
    suspend fun login(username: String, password: String): InstagramLoginResult = withContext(Dispatchers.IO) {
        try {
            val fp = activeFingerprint
            val preLogin = fetchPreLoginData()
            Log.e(TAG, "PreLoginKeys: keyId=${preLogin.keyId}, pubKeyLen=${preLogin.publicKey.length}, version=${preLogin.version}, csrf=${preLogin.csrfToken.take(12)}…")
            val encPassword = InstagramCrypto.encryptInstagramPassword(
                password = password,
                keyId = preLogin.keyId,
                publicKeyHex = preLogin.publicKey,
                version = preLogin.version
            )
            Log.e(TAG, "encPassword format check: starts=${encPassword.take(30)}")

            val formBody = FormBody.Builder()
                .add("username", username)
                .add("enc_password", encPassword)
                .add("caaF2DebugGroup", "0")
                .add("isPrivacyPortalReq", "false")
                .add("loginAttemptSubmissionCount", "0")
                .add("optIntoOneTap", "false")
                .add("queryParams", "{}")
                .add("trustedDeviceRecords", "{}")
                .add("jazoest", InstagramCrypto.createJazoest())
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/accounts/login/ajax/")
                .post(formBody)
                .header("Host", "www.instagram.com")
                .header("User-Agent", fp.userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", InstagramCrypto.ACCEPT_LANGUAGE)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", preLogin.csrfToken)
                .header("X-Web-Device-Id", preLogin.webDeviceId)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-IG-WWW-Claim", "0")
                .header("sec-ch-ua", InstagramCrypto.SEC_CH_UA)
                .header("sec-ch-ua-mobile", fp.isMobile)
                .header("sec-ch-ua-platform", fp.platform)
                .header("sec-ch-ua-platform-version", fp.platformVersion)
                .header("sec-ch-ua-model", fp.model)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/accounts/login/")
                .apply { if (preLogin.cookies.isNotBlank()) header("Cookie", preLogin.cookies) }
                .build()

            http.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                Log.e(TAG, "LoginResponse: HTTP ${response.code}, body(500)=${responseText.take(500)}")
                // The login response only re-issues what changed (typically a rotated
                // csrftoken); datr/mid/ig_did came from pre-login and never repeat here, so the
                // pre-login cookies are the base and the responses are layered on top — dropping
                // them would hand back a session missing the identifiers Instagram already
                // associated with this "browser".
                val loginClaim = response.header("X-Ig-Set-WWW-Claim")?.trim()
                val setCookies = parseSetCookies(preLogin.cookies.split(";")) +
                    parseSetCookies(response.headers("Set-Cookie")) +
                    (if (!loginClaim.isNullOrBlank()) mapOf(WWW_CLAIM_KEY to loginClaim) else emptyMap())
                val cookieHeader = joinCookies(setCookies)

                val authenticated = responseText.contains("\"authenticated\":true")
                val twoFactor = responseText.contains("\"two_factor_required\":true")
                val isCheckpoint = responseText.contains("checkpoint_required") ||
                    responseText.contains("challenge_required") ||
                    responseText.contains("\"challenge\":")

                if (authenticated) {
                    val userId = extractJsonValue(responseText, "userId")
                        ?: extractJsonValue(responseText, "ds_user_id")
                        ?: ""
                    InstagramLoginResult.Success(
                        userId = userId,
                        username = username,
                        sessionData = cookieHeader
                    )
                } else if (twoFactor) {
                    val info = runCatching {
                        JSONObject(responseText).optJSONObject("two_factor_info")
                    }.getOrNull()

                    // The challenged session is the one that must answer, so carry its cookies
                    // forward. csrftoken has to match the header sent with the code; the login
                    // response reissues it, and pre-login's copy is the fallback.
                    val csrfToken = setCookies["csrftoken"]?.ifBlank { null } ?: preLogin.csrfToken

                    InstagramLoginResult.TwoFactorRequired(
                        identifier = info?.optString("two_factor_identifier")?.ifBlank { null }
                            ?: extractJsonValue(responseText, "two_factor_identifier")
                            ?: "",
                        username = info?.optString("username")?.ifBlank { null } ?: username,
                        sessionCookies = joinCookies(setCookies + ("csrftoken" to csrfToken)),
                        csrfToken = csrfToken,
                        hint = describeTwoFactorDelivery(info)
                    )
                } else if (isCheckpoint) {
                    val rawCheckpointUrl = runCatching {
                        val json = JSONObject(responseText)
                        json.optString("checkpoint_url")?.ifBlank { null }
                            ?: json.optJSONObject("challenge")?.optString("url")?.ifBlank { null }
                            ?: json.optJSONObject("challenge")?.optString("api_path")?.ifBlank { null }
                    }.getOrNull() ?: extractJsonValue(responseText, "checkpoint_url")

                    val csrfToken = setCookies["csrftoken"]?.ifBlank { null } ?: preLogin.csrfToken
                    val baseCookies = joinCookies(setCookies + ("csrftoken" to csrfToken))
                    val targetUrl = rawCheckpointUrl ?: "https://www.instagram.com/challenge/"

                    // Trigger Instagram to dispatch the email verification code immediately
                    val (cookiesAfterTrigger, emailHint) = requestEmailCode(targetUrl, baseCookies, csrfToken)

                    val contactHint = emailHint ?: runCatching {
                        val json = JSONObject(responseText)
                        json.optJSONObject("challenge")?.optString("contact_point")?.ifBlank { null }
                            ?: json.optString("contact_point")?.ifBlank { null }
                    }.getOrNull() ?: "your email address"

                    InstagramLoginResult.EmailCodeRequired(
                        username = username,
                        challengeUrl = targetUrl,
                        sessionCookies = cookiesAfterTrigger,
                        csrfToken = csrfToken,
                        hint = contactHint
                    )
                } else {
                    val message = parseInstagramLoginError(responseText, response.code)
                    InstagramLoginResult.Failure(message)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Login failed for $username", t)
            InstagramLoginResult.Failure(t.message ?: "Network error during Instagram login")
        }
    }

    private fun formatChallengeApiUrl(challengeUrl: String): String {
        var cleanUrl = challengeUrl
        if (cleanUrl.contains("/challenge/action/")) {
            cleanUrl = cleanUrl.replace("/challenge/action/", "/challenge/")
        }
        return when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> {
                if (!cleanUrl.contains("/api/v1/")) {
                    cleanUrl.replace("instagram.com/", "instagram.com/api/v1/")
                } else {
                    cleanUrl
                }
            }
            cleanUrl.startsWith("/") -> {
                if (cleanUrl.startsWith("/api/v1/")) {
                    "https://www.instagram.com$cleanUrl"
                } else {
                    "https://www.instagram.com/api/v1$cleanUrl"
                }
            }
            else -> "https://www.instagram.com/api/v1/$cleanUrl"
        }
    }

    /**
     * Triggers Instagram to send an email verification code for a security challenge.
     * Uses Instagram AuthPlatform GraphQL query (AuthPlatformCodeEntryViewQuery) directly.
     */
    suspend fun requestEmailCode(
        challengeUrl: String,
        sessionCookies: String,
        csrfToken: String
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        try {
            val fullRefererUrl = if (challengeUrl.startsWith("http")) challengeUrl else "https://www.instagram.com${if (challengeUrl.startsWith("/")) "" else "/"}$challengeUrl"
            Log.e(TAG, "requestEmailCode (GraphQL AuthPlatform) starting for challengeUrl=$fullRefererUrl")

            var currentCookies = sessionCookies
            var currentCsrf = csrfToken

            var currentLsd = fetchLsdToken(currentCookies, fullRefererUrl)
            if (currentLsd.isBlank()) {
                currentLsd = DEFAULT_LSD_TOKEN
            }
            Log.e(TAG, "Executing AuthPlatformCodeEntryViewQuery GraphQL query with lsd=$currentLsd for email code trigger")

            val apcContext = runCatching {
                val uri = Uri.parse(fullRefererUrl)
                uri.getQueryParameter("apc")
            }.getOrNull() ?: ""

            val gqlForm = FormBody.Builder()
                .add("av", "0")
                .add("__d", "www")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "1")
                .add("lsd", currentLsd)
                .add("jazoest", InstagramCrypto.createJazoest(currentLsd))
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "AuthPlatformCodeEntryViewQuery")
                .add("server_timestamps", "true")
                .add("variables", "{\"apc\":\"$apcContext\"}")
                .add("doc_id", "34414353874878894")
                .build()

            val gqlRequest = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .post(gqlForm)
                .applyDeviceHeaders()
                .header("Host", "www.instagram.com")
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-FB-LSD", currentLsd)
                .header("X-FB-Friendly-Name", "AuthPlatformCodeEntryViewQuery")
                .header("X-CSRFToken", currentCsrf)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(currentCookies))
                .header("Cookie", currentCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", fullRefererUrl)
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            var updatedCookies = currentCookies
            var emailHint: String? = null

            http.newCall(gqlRequest).execute().use { gqlResponse ->
                val rawGqlText = gqlResponse.body?.string() ?: ""
                Log.e(TAG, "AuthPlatformCodeEntryViewQuery GraphQL response HTTP ${gqlResponse.code}: $rawGqlText")
                val getCookies = parseSetCookies(currentCookies.split(";")) + parseSetCookies(gqlResponse.headers("Set-Cookie"))
                updatedCookies = joinCookies(getCookies)
                getCookies["csrftoken"]?.ifBlank { null }?.let { currentCsrf = it }

                val cleanText = rawGqlText.replace(Regex("^for\\s*\\(\\s*;\\s*;\\s*\\);?"), "").trim()

                emailHint = runCatching {
                    val json = JSONObject(cleanText)
                    json.optJSONObject("data")?.optJSONObject("auth_platform")?.optJSONObject("code_entry_view")?.optString("contact_point")?.ifBlank { null }
                        ?: json.optJSONObject("step_data")?.optString("contact_point")?.ifBlank { null }
                }.getOrNull()
            }

            Pair(updatedCookies, emailHint)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to trigger email code send for $challengeUrl", t)
            Pair(sessionCookies, null)
        }
    }

    /**
     * Resends the email verification code for an existing EmailCodeRequired challenge.
     */
    suspend fun resendEmailCode(challenge: InstagramLoginResult.EmailCodeRequired): InstagramLoginResult.EmailCodeRequired {
        val (updatedCookies, newHint) = requestEmailCode(challenge.challengeUrl, challenge.sessionCookies, challenge.csrfToken)
        return challenge.copy(
            sessionCookies = updatedCookies,
            hint = newHint ?: challenge.hint
        )
    }

    /**
     * Finishes a login that Instagram challenged with an email code / security verification code.
     * Submits the code directly via Instagram GraphQL mutation (useAuthPlatformSubmitCodeMutation).
     */
    suspend fun submitEmailCode(
        challenge: InstagramLoginResult.EmailCodeRequired,
        code: String
    ): InstagramLoginResult = withContext(Dispatchers.IO) {
        try {
            val fullRefererUrl = if (challenge.challengeUrl.startsWith("http")) challenge.challengeUrl else "https://www.instagram.com${if (challenge.challengeUrl.startsWith("/")) "" else "/"}${challenge.challengeUrl}"
            val cleanCode = code.filter { it.isDigit() }
            Log.d(TAG, "submitEmailCode (GraphQL AuthPlatform) starting for challengeUrl=$fullRefererUrl")

            var currentLsd = fetchLsdToken(challenge.sessionCookies, fullRefererUrl)
            if (currentLsd.isBlank()) {
                currentLsd = DEFAULT_LSD_TOKEN
            }
            Log.d(TAG, "Executing GraphQL AuthPlatform submit code mutation with lsd=$currentLsd...")

            val apcContext = runCatching {
                val uri = Uri.parse(fullRefererUrl)
                uri.getQueryParameter("apc")
            }.getOrNull() ?: ""

            val gqlForm = FormBody.Builder()
                .add("av", "0")
                .add("__d", "www")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "m")
                .add("__crn", "comet.igweb.PolarisAuthPlatformCodeEntryRoute")
                .add("lsd", currentLsd)
                .add("jazoest", InstagramCrypto.createJazoest(currentLsd))
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "useAuthPlatformSubmitCodeMutation")
                .add("server_timestamps", "true")
                .add("variables", "{\"input\":{\"actor_id\":\"0\",\"client_mutation_id\":\"10\",\"code\":\"$cleanCode\",\"encrypted_ap_context\":\"$apcContext\"}}")
                .add("doc_id", "25017097917894476")
                .build()

            val gqlRequest = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .post(gqlForm)
                .applyDeviceHeaders()
                .header("Host", "www.instagram.com")
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-FB-LSD", currentLsd)
                .header("X-FB-Friendly-Name", "useAuthPlatformSubmitCodeMutation")
                .header("X-CSRFToken", challenge.csrfToken)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(challenge.sessionCookies))
                .header("Cookie", challenge.sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", fullRefererUrl)
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            var responseText = ""
            var responseCode = 0
            var setCookieHeaders = listOf<String>()

            http.newCall(gqlRequest).execute().use { response ->
                val rawText = response.body?.string() ?: ""
                responseText = rawText.replace(Regex("^for\\s*\\(\\s*;\\s*;\\s*\\);?"), "").trim()
                responseCode = response.code
                setCookieHeaders = response.headers("Set-Cookie")
                Log.d(TAG, "submitEmailCode GraphQL response HTTP $responseCode: $responseText")
            }

            val merged = parseSetCookies(challenge.sessionCookies.split(";")) + parseSetCookies(setCookieHeaders)
            val updatedCookies = joinCookies(merged)
            val authenticated = responseText.contains("\"authenticated\":true") ||
                responseText.contains("\"status\":\"ok\"") ||
                merged.containsKey("sessionid")

            if (authenticated) {
                val userId = merged["ds_user_id"]
                    ?: extractJsonValue(responseText, "userId")
                    ?: extractJsonValue(responseText, "ds_user_id")
                    ?: ""
                InstagramLoginResult.Success(
                    userId = userId,
                    username = challenge.username,
                    sessionData = updatedCookies
                )
            } else {
                InstagramLoginResult.Failure(
                    parseInstagramLoginError(responseText, responseCode)
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Email verification code submission failed for ${challenge.username}", t)
            InstagramLoginResult.Failure(t.message ?: "Network error while submitting email verification code")
        }
    }

    /**
     * Finishes a login that Instagram challenged with two-factor auth by submitting the code the
     * user typed.
     *
     * Runs on [TwoFactorRequired.sessionCookies] — the identifier is only valid for the session
     * that was challenged, so a fresh one would be rejected. A wrong or expired code comes back as
     * [InstagramLoginResult.Failure] carrying Instagram's own wording, and the challenge stays
     * usable for another try.
     */
    suspend fun submitTwoFactorCode(
        challenge: InstagramLoginResult.TwoFactorRequired,
        code: String
    ): InstagramLoginResult = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("username", challenge.username)
                .add("identifier", challenge.identifier)
                .add("verificationCode", code.filter { it.isDigit() })
                .add("queryParams", "{}")
                .add("trust_signal_requested", "1")
                .add("jazoest", InstagramCrypto.createJazoest())
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/accounts/login/ajax/two_factor/")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Host", "www.instagram.com")
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", challenge.csrfToken)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(challenge.sessionCookies))
                .header("Cookie", challenge.sessionCookies)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/accounts/login/two_factor/")
                .build()

            http.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                if (!responseText.contains("\"authenticated\":true")) {
                    return@withContext InstagramLoginResult.Failure(
                        parseInstagramLoginError(responseText, response.code)
                    )
                }

                // Instagram only reissues what changed, so the challenge cookies stay as the base:
                // dropping them would lose mid/ig_did and leave a session Instagram distrusts.
                val twoFactorClaim = response.header("X-Ig-Set-WWW-Claim")?.trim()
                val merged = parseSetCookies(challenge.sessionCookies.split(";")) +
                    parseSetCookies(response.headers("Set-Cookie")) +
                    (if (!twoFactorClaim.isNullOrBlank()) mapOf(WWW_CLAIM_KEY to twoFactorClaim) else emptyMap())

                InstagramLoginResult.Success(
                    userId = merged["ds_user_id"]?.ifBlank { null }
                        ?: extractJsonValue(responseText, "userId")
                        ?: extractJsonValue(responseText, "ds_user_id")
                        ?: "",
                    username = challenge.username,
                    sessionData = joinCookies(merged)
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Two-factor login failed for ${challenge.username}", t)
            InstagramLoginResult.Failure(t.message ?: "Network error while verifying the code")
        }
    }

    /** Turns Instagram's `two_factor_info` into something worth showing above a code field. */
    private fun describeTwoFactorDelivery(info: JSONObject?): String? {
        if (info == null) return null
        // Worded to complete "Enter the code from …", whichever branch wins.
        val phone = info.optString("obfuscated_phone_number").trim()
        return when {
            info.optBoolean("totp_two_factor_on") -> "your authenticator app"
            info.optBoolean("whatsapp_two_factor_on") -> "WhatsApp"
            phone.isNotBlank() -> "the SMS sent to the number ending $phone"
            info.optBoolean("sms_two_factor_on") -> "the SMS Instagram sent"
            else -> null
        }
    }

    private fun parseInstagramLoginError(responseText: String, httpStatusCode: Int): String {
        if (responseText.isBlank()) {
            return "Instagram server returned HTTP status code $httpStatusCode"
        }

        try {
            val json = JSONObject(responseText)

            // 1. Direct feedback title & message (e.g. Account Suspended / Action Blocked)
            val feedbackTitle = json.optString("feedback_title")?.ifBlank { null }
            val feedbackMsg = json.optString("feedback_message")?.ifBlank { null }
            if (!feedbackTitle.isNullOrBlank() || !feedbackMsg.isNullOrBlank()) {
                val title = feedbackTitle ?: "Instagram Alert"
                val body = feedbackMsg ?: ""
                return if (body.isNotBlank()) "$title: $body" else title
            }

            // 2. Localized error message or error title
            val localizedError = json.optString("localized_error_message")?.ifBlank { null }
            val errorTitle = json.optString("error_title")?.ifBlank { null }
            if (!localizedError.isNullOrBlank() || !errorTitle.isNullOrBlank()) {
                return listOfNotNull(errorTitle, localizedError).joinToString(": ")
            }

            val errorType = json.optString("error_type")?.ifBlank { null }

            // 3. Checkpoint or Challenge Required with full URL resolution
            val rawCheckpointUrl = json.optString("checkpoint_url")?.ifBlank { null }
                ?: json.optJSONObject("challenge")?.optString("url")?.ifBlank { null }
                ?: json.optString("challenge")?.ifBlank { null }

            val fullCheckpointUrl = when {
                rawCheckpointUrl.isNullOrBlank() -> null
                rawCheckpointUrl.startsWith("http://") || rawCheckpointUrl.startsWith("https://") -> rawCheckpointUrl
                rawCheckpointUrl.startsWith("/") -> "https://www.instagram.com$rawCheckpointUrl"
                else -> "https://www.instagram.com/$rawCheckpointUrl"
            }

            if (errorType.equals("checkpoint_required", ignoreCase = true) ||
                errorType?.contains("AntiScripting", ignoreCase = true) == true ||
                responseText.contains("checkpoint_required") ||
                responseText.contains("challenge_required") ||
                !fullCheckpointUrl.isNullOrBlank()
            ) {
                val errTypeStr = if (!errorType.isNullOrBlank() && !errorType.equals("checkpoint_required", ignoreCase = true)) " ($errorType)" else ""
                return if (!fullCheckpointUrl.isNullOrBlank()) {
                    "Checkpoint Required$errTypeStr: Security verification needed at $fullCheckpointUrl"
                } else {
                    "Checkpoint Required$errTypeStr: Security verification required. Please open Instagram app/web to verify your account."
                }
            }

            // 4. Account Suspended / Disabled / Deactivated
            if (errorType.equals("account_suspended", ignoreCase = true) ||
                errorType.equals("user_deactivated", ignoreCase = true) ||
                json.optBoolean("is_user_inactivated_error", false) ||
                responseText.contains("account_suspended") ||
                responseText.contains("user_deactivated")
            ) {
                return "Account Suspended: Your Instagram account has been disabled or suspended."
            }

            // 5. Standard message field
            val message = json.optString("message")?.ifBlank { null }
            if (!message.isNullOrBlank()) {
                return when {
                    message.equals("checkpoint_required", ignoreCase = true) -> {
                        val errTypeStr = if (!errorType.isNullOrBlank()) " ($errorType)" else ""
                        "Checkpoint Required$errTypeStr: Security verification required. Please open Instagram app/web to verify your account."
                    }
                    message.equals("user_deactivated", ignoreCase = true) ->
                        "Account Suspended: Your Instagram account has been disabled or suspended."
                    message.equals("feedback_required", ignoreCase = true) ->
                        "Action Blocked: Instagram rate limit or security check required. Try again later."
                    else -> if (!errorType.isNullOrBlank() && !message.contains(errorType)) "$message ($errorType)" else message
                }
            }

            if (!errorType.isNullOrBlank()) {
                return "Instagram Login Error ($errorType)"
            }
        } catch (_: Exception) {
            // Fallback parsing if response is non-JSON
        }

        return when {
            responseText.contains("checkpoint_required") ->
                "Checkpoint Required: Security verification required. Please open Instagram app/web to verify."
            responseText.contains("user_deactivated") || responseText.contains("suspended") ->
                "Account Suspended: Your Instagram account has been disabled or suspended."
            // A 200 that says the account exists ("user":true) but was not authenticated is
            // Instagram rejecting the credentials themselves. The direct-login password path does
            // not perform Instagram's real sealed-box encryption, so it lands here even for a
            // correct password — the reliable path is the in-app web login.
            responseText.contains("\"user\":true") || responseText.contains("\"user\": true") ->
                "Could not sign in with username and password. Use \"Log in via Web\" instead — it signs in through Instagram directly."
            else ->
                "Could not sign in. Please use \"Log in via Web\" instead."
        }
    }

    /**
     * Follows an Instagram account given numeric user ID or target handle.
     * Uses complete, realistic Chrome/131 web headers. Falls back to GraphQL mutation if rejected.
     */
    suspend fun follow(
        targetUserId: String,
        targetUsername: String = "",
        sessionCookies: String
    ): IgActionResult = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            //val extractedFbDtsg = fetchFbDtsg(sessionCookies)

            val formBodyBuilder = FormBody.Builder()
                .add("container_module", "profile")
                .add("nav_chain", "PolarisProfilePostsTabRoot:profilePage:1:via_cold_start")
                .add("user_id", targetUserId)
                .add("include_follow_friction_check", "true")
                .add("jazoest", InstagramCrypto.createJazoest(targetUserId))

            // if (!extractedFbDtsg.isNullOrBlank()) {
            //     formBodyBuilder.add("fb_dtsg", extractedFbDtsg)
            // }

            val formBody = formBodyBuilder.build()

            val refererUrl = if (targetUsername.isNotBlank()) "https://www.instagram.com/$targetUsername/"
            else "https://www.instagram.com/"

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/friendships/create/$targetUserId/")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", refererUrl)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            val firstResult = http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val hasErrorSignatures = bodyText.contains("\"status\":\"fail\"") ||
                    bodyText.contains("feedback_required") ||
                    bodyText.contains("spam") ||
                    bodyText.contains("checkpoint_required") ||
                    bodyText.contains("login_required")

                // Explicitly check for following:true or outgoing_request:true (for private accounts)
                // A response containing only "status":"ok" without following/outgoing_request confirmation is NOT a successful follow!
                val isFollowingConfirmed = (bodyText.contains("\"following\":true") || bodyText.contains("\"following\": true")) ||
                    (bodyText.contains("\"outgoing_request\":true") || bodyText.contains("\"outgoing_request\": true"))

                val isSuccess = response.isSuccessful && !hasErrorSignatures && isFollowingConfirmed
                Log.i(TAG, "Follow REST HTTP ${response.code} for $targetUserId: $isSuccess (followingConfirmed=$isFollowingConfirmed)")
                val rotated = mergeSetCookiesAndClaim(sessionCookies, response)
                if (isSuccess) IgActionResult.Ok.copy(updatedCookies = rotated)
                else null // Trigger GraphQL fallback
            }

            if (firstResult != null) {
                return@withContext firstResult
            }

            Log.w(TAG, "Standard REST follow rejected/failed for $targetUserId. Executing GraphQL usePolarisFollowMutation fallback...")
            return@withContext followViaGraphQL(targetUserId, targetUsername, sessionCookies)

        } catch (e: Exception) {
            Log.e(TAG, "Standard REST follow failed for $targetUserId, trying GraphQL fallback...", e)
            return@withContext followViaGraphQL(targetUserId, targetUsername, sessionCookies)
        }
    }

    /**
     * Fallback follow action via Instagram GraphQL API (usePolarisFollowMutation doc_id: 26508036048874888).
     * Used when standard friendships/create fails or returns "Instagram rejected the follow".
     */
    suspend fun followViaGraphQL(
        targetUserId: String,
        targetUsername: String = "",
        sessionCookies: String
    ): IgActionResult = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val dsUserId = extractCookie(sessionCookies, "ds_user_id") ?: "0"
            val lsdToken = extractCookie(sessionCookies, "lsd") ?: "9TjJvcwkR5rOoXDuAO_1-5"
            val targetHandle = if (targetUsername.isNotBlank()) targetUsername else "accounts/edit"
            val extractedFbDtsg = fetchFbDtsg(sessionCookies, targetHandle)

            val variablesJson = org.json.JSONObject().apply {
                put("target_user_id", targetUserId)
                put("container_module", "profile")
                put("nav_chain", "PolarisProfilePostsTabRoot:profilePage:1:via_cold_start")
            }.toString()

            val formBodyBuilder = FormBody.Builder()
                .add("__comet_req", "7")
                .add("jazoest", InstagramCrypto.createJazoest(targetUserId))
                .add("lsd", lsdToken)
                .add("__crn", "comet.igweb.PolarisProfilePostsTabRoute")
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "usePolarisFollowMutation")
                .add("variables", variablesJson)
                .add("doc_id", "26508036048874888")

            if (!extractedFbDtsg.isNullOrBlank()) {
                formBodyBuilder.add("fb_dtsg", extractedFbDtsg)
            }

            val formBody = formBodyBuilder.build()

            val refererUrl = if (targetUsername.isNotBlank()) "https://www.instagram.com/$targetUsername/"
            else "https://www.instagram.com/"

            val request = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-FB-LSD", lsdToken)
                .header("X-FB-Friendly-Name", "usePolarisFollowMutation")
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", refererUrl)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val hasErrorSignatures = bodyText.contains("\"errors\":[") ||
                    bodyText.contains("\"error\":") ||
                    bodyText.contains("feedback_required") ||
                    bodyText.contains("spam") ||
                    bodyText.contains("login_required")

                val isFollowingConfirmed = (bodyText.contains("\"following\":true") || bodyText.contains("\"following\": true")) ||
                    (bodyText.contains("\"outgoing_request\":true") || bodyText.contains("\"outgoing_request\": true"))

                val isSuccess = response.isSuccessful && !hasErrorSignatures && isFollowingConfirmed
                Log.i(TAG, "GraphQL Follow HTTP ${response.code} for $targetUserId: $isSuccess (followingConfirmed=$isFollowingConfirmed)")
                val rotated = mergeSetCookiesAndClaim(sessionCookies, response)
                if (isSuccess) IgActionResult.Ok.copy(updatedCookies = rotated)
                else IgActionResult.classify("follow", response.code, bodyText).copy(updatedCookies = rotated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GraphQL Follow fallback failed for user $targetUserId", e)
            IgActionResult.fail("GraphQL follow error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Likes a post through the `PolarisAPILikePostMutation` GraphQL mutation — the same request
     * the Instagram web client sends. There is no REST fallback: Instagram retired
     * `/api/v1/web/likes/../like/` for the web client, so falling back only produced a
     * confusing second failure.
     */
    suspend fun like(
        mediaCodeOrId: String,
        sessionCookies: String
    ): IgActionResult = runGraphqlMediaMutation(
        action = "like",
        mediaCodeOrId = mediaCodeOrId,
        sessionCookies = sessionCookies,
        friendlyName = "PolarisAPILikePostMutation",
        docId = LIKE_MUTATION_DOC_ID,
        crn = "comet.igweb.PolarisPostRoute",
        rootFieldName = null
    ) { mediaId, actorId ->
        // variables={"input":{"media_id":..,"actor_id":..,"client_mutation_id":"1"}}
        JSONObject().apply {
            put("input", JSONObject().apply {
                put("media_id", mediaId)
                put("actor_id", actorId)
                put("client_mutation_id", "1")
            })
        }.toString()
    }

    /**
     * Reposts (reshares) a post via the `usePolarisCreateMediaRepostMutation` GraphQL mutation.
     * Shares the signed GraphQL path with [like] so both carry the plumbing Instagram requires
     * on writes — most importantly the per-session LSD token.
     */
    suspend fun repost(
        mediaCodeOrId: String,
        sessionCookies: String
    ): IgActionResult = runGraphqlMediaMutation(
        action = "repost",
        mediaCodeOrId = mediaCodeOrId,
        sessionCookies = sessionCookies,
        friendlyName = "usePolarisCreateMediaRepostMutation",
        docId = REPOST_MUTATION_DOC_ID,
        crn = "comet.igweb.PolarisPostRoute",
        rootFieldName = "xdt_create_media_note_v2"
    ) { mediaId, actorId ->
        JSONObject().apply {
            put("input", JSONObject().apply {
                put("actor_id", actorId)
                put("client_mutation_id", "1")
                put("audience", 7)
                put("media_id", mediaId)
                put("note_style", 13)
                put("text", "")
            })
        }.toString()
    }

    /**
     * Saves a post via the `usePolarisSaveMediaSaveMutation` GraphQL mutation.
     *
     * Success response shape:
     *   {"data":{"xig_media_save":{"media":{"id":"<media_id>_<user_id>"}}},...}
     *
     * Validated by the presence of `data.xig_media_save.media` with a non-blank id.
     */
    suspend fun savePost(
        mediaCodeOrId: String,
        sessionCookies: String
    ): IgActionResult = runGraphqlMediaMutation(
        action = "save",
        mediaCodeOrId = mediaCodeOrId,
        sessionCookies = sessionCookies,
        friendlyName = "PolarisAPISavePostMutation",
        docId = SAVE_MUTATION_DOC_ID,
        crn = "comet.igweb.PolarisPostRoute",
        rootFieldName = "xig_media_save",
        successCheck = { body ->
            // The save mutation is successful when data.xig_media_save.media exists and has an id
            try {
                val media = JSONObject(body)
                    .optJSONObject("data")
                    ?.optJSONObject("xig_media_save")
                    ?.optJSONObject("media")
                media != null && !media.optString("id").isNullOrBlank()
            } catch (_: Exception) {
                body.contains("\"xig_media_save\"") && body.contains("\"media\"")
            }
        }
    ) { mediaId, actorId ->
        JSONObject().apply {
            put("input", JSONObject().apply {
                put("media_id", mediaId)
                put("actor_id", actorId)
                put("client_mutation_id", "1")
            })
        }.toString()
    }

    /**
     * Views an Instagram story using `PolarisStoriesV3SeenMutation` (doc_id: 26234228992942885).
     *
     * Parses the story link/target, fetches active story media details via Instagram's reels API
     * if available, and sends the GraphQL seen mutation.
     */
    suspend fun storyView(
        targetIdOrUrl: String,
        sessionCookies: String
    ): IgActionResult = withContext(Dispatchers.IO) {
        if (targetIdOrUrl.isBlank()) return@withContext IgActionResult.fail("No target story specified")

        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val rurValue = extractCookie(sessionCookies, "rur") ?: ""
            val actorIdFromRur = Regex("""\b(\d{5,})\b""").find(rurValue)?.groupValues?.get(1)
            val actorId = actorIdFromRur
                ?: extractCookie(sessionCookies, "ds_user_id")
                ?: resolveActorId(sessionCookies)
                ?: "0"
            val lsd = fetchLsdToken(sessionCookies)
            val fbDtsg = fetchFbDtsg(sessionCookies)

            var username = ""
            var specifiedMediaId = ""

            val cleanTarget = targetIdOrUrl.trim()
            if (cleanTarget.contains("/stories/")) {
                val path = cleanTarget.substringAfter("/stories/").substringBefore("?").trim('/')
                val parts = path.split("/")
                if (parts.isNotEmpty()) username = parts[0].trimStart('@')
                if (parts.size > 1) specifiedMediaId = parts[1]
            } else if (cleanTarget.contains("/")) {
                val parts = cleanTarget.split("/")
                if (parts.isNotEmpty()) username = parts[0].trimStart('@')
                if (parts.size > 1) specifiedMediaId = parts[1]
            } else if (cleanTarget.all { it.isDigit() }) {
                specifiedMediaId = cleanTarget
            } else {
                username = cleanTarget.trimStart('@')
            }

            // Resolve the story owner's numeric user ID when a username was parsed.
            // reelId must be the target's own user ID, not the viewer's actorId.
            val targetUserId: String = if (username.isNotBlank()) {
                resolveUserId(username, sessionCookies)
                    ?.takeIf { it.isNotBlank() }
                    ?: actorId
            } else {
                actorId
            }

            var reelId = targetUserId
            var reelMediaId = specifiedMediaId
            var reelMediaOwnerId = targetUserId
            var reelMediaTakenAt = (System.currentTimeMillis() / 1000) - 3600
            val viewSeenAt = System.currentTimeMillis() / 1000

            // Resolve reelMediaTakenAt and reelMediaOwnerId via the media info API.
            // This is more precise than reels_media: directly fetches the specific media item's metadata.
            // Endpoint: GET /api/v1/media/{mediaId}/info/
            if (specifiedMediaId.isNotBlank()) {
                try {
                    val mediaInfoUrl = "https://www.instagram.com/api/v1/media/$reelMediaId/info/"
                    val mediaInfoReq = Request.Builder()
                        .url(mediaInfoUrl)
                        .applyDeviceHeaders()
                        .header("Accept", "*/*")
                        .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                        .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                        .header("X-CSRFToken", csrfToken)
                        .header("Cookie", sessionCookies)
                        .header("Referer", if (username.isNotBlank()) "https://www.instagram.com/stories/$username/" else "https://www.instagram.com/")
                        .build()

                    http.newCall(mediaInfoReq).execute().use { res ->
                        val resBody = res.body?.string() ?: ""
                        if (res.isSuccessful && resBody.contains("items")) {
                            val root = JSONObject(resBody)
                            val item = root.optJSONArray("items")?.optJSONObject(0)
                            if (item != null) {
                                val takenAt = item.optLong("taken_at", 0L)
                                val ownerPk = item.optJSONObject("user")?.optString("pk")
                                    ?: item.optString("owner_id").takeIf { it.isNotBlank() }

                                if (takenAt > 0L) reelMediaTakenAt = takenAt
                                if (!ownerPk.isNullOrBlank()) reelMediaOwnerId = ownerPk
                                // reelId = story owner's user ID (already resolved via resolveUserId above)
                                // Use owner's pk from media info as additional confirmation
                                if (reelId == actorId && !ownerPk.isNullOrBlank()) reelId = ownerPk
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch media info for $specifiedMediaId, using resolved/default values", e)
                }
            }


            if (reelMediaId.isBlank()) {
                reelMediaId = specifiedMediaId.ifBlank { "3960632038848820786" }
            }
            if (reelMediaOwnerId.isBlank() || reelMediaOwnerId == "0") {
                reelMediaOwnerId = reelId.ifBlank { actorId }
            }

            val variablesObj = JSONObject().apply {
                put("reelId", reelId)
                put("reelMediaId", reelMediaId)
                put("reelMediaOwnerId", reelMediaOwnerId)
                put("reelMediaTakenAt", reelMediaTakenAt)
                put("viewSeenAt", viewSeenAt)
            }

            val formBuilder = FormBody.Builder()
            if (!actorIdFromRur.isNullOrBlank()) {
                formBuilder.add("av", actorIdFromRur)
            }
            formBuilder
                .add("__d", "www")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "13")
                .add("__hs", "20676.HYP:instagram_web_pkg.2.1...0")
                .add("dpr", "1")
                .add("__ccg", "EXCELLENT")
                .add("__comet_req", "7")
                .add("__crn", "comet.igweb.PolarisStoriesV3Route")
                // -- Mutation --
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "PolarisStoriesV3SeenMutation")
                .add("server_timestamps", "true")
                .add("variables", variablesObj.toString())
                .add("doc_id", "26234228992942885")

            if (!lsd.isNullOrBlank()) {
                formBuilder.add("lsd", lsd)
            }
            if (!fbDtsg.isNullOrBlank()) {
                formBuilder.add("fb_dtsg", fbDtsg)
                formBuilder.add("jazoest", InstagramCrypto.createJazoest(fbDtsg))
            }

            val refererUrl = if (username.isNotBlank()) "https://www.instagram.com/stories/$username/" else "https://www.instagram.com/"

            val requestBuilder = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .post(formBuilder.build())
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-FB-Friendly-Name", "PolarisStoriesV3SeenMutation")
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-IG-Max-Touch-Points", "0")
                .header("X-CSRFToken", csrfToken)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", refererUrl)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Language", "en-US,en;q=0.9")

            if (!lsd.isNullOrBlank()) requestBuilder.header("X-FB-LSD", lsd)

            http.newCall(requestBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                Log.i(TAG, "GraphQL storyView HTTP ${response.code} for $targetIdOrUrl")
                val rotated = mergeSetCookiesAndClaim(sessionCookies, response)

                // Success only when Instagram returns the exact seen-mutation acknowledgement:
                // {"data":{"xdt_mark_story_reel_seen":{"__typename":"XDTMarkSeenResponse"}},...}
                val isSuccess = response.isSuccessful &&
                    bodyText.contains("xdt_mark_story_reel_seen") &&
                    bodyText.contains("XDTMarkSeenResponse")

                if (isSuccess) {
                    IgActionResult.Ok.copy(updatedCookies = rotated)
                } else {
                    Log.w(TAG, "GraphQL storyView rejected: ${bodyText.take(300)}")
                    IgActionResult.classify("storyView", response.code, bodyText).copy(updatedCookies = rotated)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GraphQL storyView failed for $targetIdOrUrl", e)
            IgActionResult.fail("Network error viewing story: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Shared driver for the media mutations (like, repost, save). Both are GraphQL writes that
     * Instagram signs with a per-session LSD token, so they must send identical plumbing —
     * keeping them on one path is what stops one silently drifting out of date.
     *
     * `media_id` comes from the post link (or the numeric id straight from the feed) and
     * `actor_id` from the `rur` session cookie.
     */
    private suspend fun runGraphqlMediaMutation(
        action: String,
        mediaCodeOrId: String,
        sessionCookies: String,
        friendlyName: String,
        docId: String,
        crn: String,
        rootFieldName: String?,
        successCheck: ((body: String) -> Boolean)? = null,
        buildVariables: (mediaId: String, actorId: String) -> String
    ): IgActionResult = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val sanitizedCode = InstagramCrypto.getCodeFromUrl(mediaCodeOrId)
            val mediaId = when (val resolution = resolveMediaId(sanitizedCode, sessionCookies)) {
                is MediaIdResolution.Found -> resolution.id
                is MediaIdResolution.Fallback -> resolution.id
                MediaIdResolution.NotFound -> {
                    // Instagram's media-info endpoint already gave a clean, unambiguous answer —
                    // classify it the same way a failed mutation attempt against a dead id would
                    // be, instead of spending a whole GraphQL round trip to rediscover the same
                    // thing through a less reliable error message.
                    Log.i(TAG, "GraphQL $action: media-info confirmed $mediaCodeOrId not found, skipping the mutation")
                    return@withContext IgActionResult.classify(action, 404, "media_not_found")
                }
            }
            val actorId = resolveActorId(sessionCookies)
                ?: return@withContext IgActionResult.fail("No Instagram session for this account")

            // Per-session token; a captured one is dead on arrival, so it is read live.
            val lsd = fetchLsdToken(sessionCookies)
            if (lsd.isNullOrBlank()) Log.w(TAG, "GraphQL $action: no LSD token; the write may be refused")

            val fbDtsg = fetchFbDtsg(sessionCookies)
            val jazoest = if (!fbDtsg.isNullOrBlank()) InstagramCrypto.createJazoest(fbDtsg) else InstagramCrypto.createJazoest(mediaId)

            val formBuilder = FormBody.Builder()
                .add("av", actorId)
                .add("__d", "www")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "1y")
                .add("dpr", "1")
                .add("__ccg", "MODERATE")
                .add("__comet_req", "7")
                .add("jazoest", jazoest)
                .add("__crn", crn)
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", friendlyName)
                .add("server_timestamps", "true")
                .add("variables", buildVariables(mediaId, actorId))
                .add("doc_id", docId)
            if (!lsd.isNullOrBlank()) formBuilder.add("lsd", lsd)
            if (!fbDtsg.isNullOrBlank()) formBuilder.add("fb_dtsg", fbDtsg)

            val cleanCode = sanitizedCode.substringAfter("/p/").substringAfter("/reel/").trimEnd('/')
            val referer = if (cleanCode.isNotBlank() && !cleanCode.all(Char::isDigit))
                "https://www.instagram.com/p/$cleanCode/" else "https://www.instagram.com/"

            val builder = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .post(formBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("sec-ch-ua-platform-version", "\"15.0.0\"")
                .header("sec-ch-ua-model", "\"\"")
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-FB-Friendly-Name", friendlyName)
                .header("X-Action-Name", action)
                .header("X-BLOKS-VERSION-ID", BLOKS_VERSION_ID)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-IG-Max-Touch-Points", "0")
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-CSRFToken", csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", referer)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Accept-Language", "en-US,en;q=0.9")
            // if (rootFieldName != null) builder.header("X-Root-Field-Name", rootFieldName)
            if (!lsd.isNullOrBlank()) builder.header("X-FB-LSD", lsd)

            http.newCall(builder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                Log.i(TAG, "GraphQL $action HTTP ${response.code} for $mediaCodeOrId")
                val rotated = mergeSetCookiesAndClaim(sessionCookies, response)
                val isSuccess = if (successCheck != null) {
                    response.code in 200..299 && successCheck(bodyText)
                } else {
                    isGraphqlLikeSuccess(response.code, bodyText, rootFieldName)
                }
                if (isSuccess) {
                    IgActionResult.Ok.copy(updatedCookies = rotated)
                } else {
                    Log.w(TAG, "GraphQL $action rejected: ${bodyText.take(300)}")
                    IgActionResult.classify(action, response.code, bodyText).copy(updatedCookies = rotated)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GraphQL $action failed for media $mediaCodeOrId", e)
            IgActionResult.fail("Network error contacting Instagram: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Posts a comment on a media item, mirroring the web client's
     * `POST /api/v1/web/comments/{mediaId}/add/` with a `comment_text` form field.
     */
    suspend fun postComment(
        mediaCodeOrId: String,
        text: String,
        sessionCookies: String
    ): IgActionResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext IgActionResult.fail("Write a comment first")
        try {
            val mediaId = when (val resolution = resolveMediaId(mediaCodeOrId, sessionCookies)) {
                is MediaIdResolution.Found -> resolution.id
                is MediaIdResolution.Fallback -> resolution.id
                MediaIdResolution.NotFound -> return@withContext IgActionResult.classify("comment", 404, "media_not_found")
            }
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""

            val formBody = FormBody.Builder()
                .add("comment_text", text.trim())
                .add("jazoest", InstagramCrypto.createJazoest(mediaId))
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/comments/$mediaId/add/")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-CSRFToken", csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val ok = response.isSuccessful && bodyText.contains("\"status\":\"ok\"")
                Log.i(TAG, "Comment HTTP ${response.code} for $mediaCodeOrId: $ok")
                val rotated = mergeSetCookiesAndClaim(sessionCookies, response)
                if (ok) IgActionResult.Ok.copy(updatedCookies = rotated)
                else IgActionResult.classify("comment", response.code, bodyText).copy(updatedCookies = rotated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Comment failed for media $mediaCodeOrId", e)
            IgActionResult.fail("Network error contacting Instagram: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Fetches a page of comments for a post, keyed by its shortcode. Uses the same GraphQL
     * `query_hash` the desktop web client uses for comment paging. [maxId] is the `end_cursor`
     * from a previous page; pass null for the first page.
     */
    suspend fun fetchComments(
        mediaCodeOrLink: String,
        sessionCookies: String? = null,
        maxId: String? = null
    ): InstagramCommentPage? = withContext(Dispatchers.IO) {
        val code = InstagramCrypto.getCodeFromUrl(mediaCodeOrLink).ifBlank { mediaCodeOrLink.trim() }
        if (code.isBlank()) return@withContext null
        try {
            val after = maxId.orEmpty()
            val variables = "{\"shortcode\":\"$code\",\"first\":50,\"after\":\"$after\"}"
            val url = "https://www.instagram.com/graphql/query/?query_hash=$COMMENTS_QUERY_HASH" +
                "&variables=" + java.net.URLEncoder.encode(variables, "UTF-8")

            val builder = Request.Builder()
                .url(url)
                .applyDeviceHeaders()
                .header("Accept", "application/json")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Instagram-AJAX", "1")

            if (!sessionCookies.isNullOrBlank()) {
                builder.header("Cookie", sessionCookies)
                extractCookie(sessionCookies, "csrftoken")?.let { builder.header("X-CSRFToken", it) }
            }

            http.newCall(builder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (response.code == 401 || bodyText.contains("require_login")) {
                    return@withContext InstagramCommentPage(
                        comments = emptyList(), endCursor = null, hasMore = false,
                        total = 0, requiresLogin = true
                    )
                }
                if (!response.isSuccessful || bodyText.isBlank()) return@withContext null

                val media = JSONObject(bodyText)
                    .optJSONObject("data")?.optJSONObject("shortcode_media")
                    ?: return@withContext null

                val connection = media.optJSONObject("edge_media_to_parent_comment")
                    ?: media.optJSONObject("edge_media_to_comment")
                    ?: return@withContext null

                val edges = connection.optJSONArray("edges") ?: return@withContext null
                val comments = mutableListOf<InstagramComment>()
                for (i in 0 until edges.length()) {
                    val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                    val owner = node.optJSONObject("owner")
                    comments.add(
                        InstagramComment(
                            id = node.optString("id"),
                            username = owner?.optString("username").orEmpty(),
                            avatarUrl = owner?.optString("profile_pic_url")?.ifBlank { null },
                            text = node.optString("text"),
                            likeCount = node.optJSONObject("edge_liked_by")?.optLong("count") ?: 0L,
                            createdAt = node.optLong("created_at")
                        )
                    )
                }

                val pageInfo = connection.optJSONObject("page_info")
                InstagramCommentPage(
                    comments = comments,
                    endCursor = pageInfo?.optString("end_cursor")?.ifBlank { null },
                    hasMore = pageInfo?.optBoolean("has_next_page", false) ?: false,
                    total = connection.optLong("count", comments.size.toLong())
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load comments for $mediaCodeOrLink", e)
            null
        }
    }

    /**
     * Reads the per-session LSD token the web client stamps on GraphQL writes. Instagram embeds
     * it in the page HTML; a token captured earlier is session-bound and rejected, so it must be
     * fetched fresh. Returns null when not found — the caller then sends without it.
     */
    private fun fetchLsdToken(sessionCookies: String): String? = try {
        val request = Request.Builder()
            .url("https://www.instagram.com/")
            .applyDeviceHeaders()
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Cookie", sessionCookies)
            .build()
        http.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            LSD_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: LSD_TOKEN_FALLBACK_REGEX.find(html)?.groupValues?.getOrNull(1)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read LSD token", e)
        null
    }

    /**
     * Outcome of [resolveMediaId]. A confirmed [NotFound] (Instagram's media-info endpoint
     * answering, e.g., `{"message":"Media not found or unavailable","status":"fail"}`) is
     * distinct from every other failure — a network blip, a non-2xx that isn't a not-found
     * response — which fall back to a locally-decoded id and let the mutation attempt run anyway,
     * same as before this distinction existed. Conflating the two used to mean a genuinely deleted
     * post never surfaced as "not found" from here at all: the caller quietly retried with the
     * local guess, and whatever the mutation itself returned for a bogus id was whatever
     * [IgActionResult.classify] happened to make of it — not necessarily recognized as not-found,
     * so the backend order never got marked NotFound and kept getting reclaimed and reattempted
     * instead.
     */
    private sealed class MediaIdResolution {
        data class Found(val id: String) : MediaIdResolution()
        data class Fallback(val id: String) : MediaIdResolution()
        data object NotFound : MediaIdResolution()
    }

    /**
     * Resolves a post shortcode/URL to its numeric media id, then confirms it against the
     * media-info API — that endpoint only ever accepts a numeric id, so both the "already
     * numeric" and "decoded from a shortcode" cases below must land on one before it's ever
     * called, and *neither* case is trusted without the API call: a numeric id that looks
     * well-formed can still point at a since-deleted post, so skipping the call for it would
     * have skipped the one place that can actually tell.
     */
    private suspend fun resolveMediaId(mediaCodeOrId: String, sessionCookies: String): MediaIdResolution {
        val trimmed = mediaCodeOrId.trim()

        val numeric = trimmed.substringBefore('_')
        val candidateId = if (numeric.isNotEmpty() && numeric.all(Char::isDigit)) {
            numeric
        } else {
            // getIdFromCode falls back to returning the input shortcode verbatim if it hits a
            // character outside its base64-style alphabet — non-numeric, and the media-info API
            // would just reject it outright, so that case is treated the same as not being able
            // to resolve an id at all rather than sent to the API.
            InstagramCrypto.getIdFromCode(trimmed).ifBlank { null }?.takeIf { it.all(Char::isDigit) }
                ?: return MediaIdResolution.NotFound
        }

        try {
            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/media/$candidateId/info/")
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")
                .header("Cookie", sessionCookies)
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.isNotBlank()) {
                    val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0)
                    val pk = item?.optString("pk")?.ifBlank { null }
                        ?: item?.optString("id")?.substringBefore("_")?.ifBlank { null }
                    if (!pk.isNullOrBlank()) return MediaIdResolution.Found(pk)
                } else if (isMediaNotFoundResponse(body)) {
                    return MediaIdResolution.NotFound
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "media-info lookup failed for $mediaCodeOrId, using local id", e)
        }
        return MediaIdResolution.Fallback(candidateId)
    }

    /** Matches Instagram's own `{"message":"Media not found or unavailable","status":"fail"}`. */
    private fun isMediaNotFoundResponse(body: String): Boolean {
        if (body.isBlank()) return false
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        return message.contains("not found", ignoreCase = true) ||
            message.contains("unavailable", ignoreCase = true)
    }

    /**
     * The liker's IG-scoped actor id, taken from the `rur` session cookie.
     *
     * `rur` looks like `<datacenter>,<actor_id>,<timestamp>:<signature>` — e.g.
     * `LDC,17841461448020249,1786521172:01ff…`. The actor id is the long numeric field, which
     * is NOT the same as `ds_user_id` (that is the legacy id). Browser exports URL-encode the
     * separators (`%2C`, `%3A`) and cookie jars sometimes octal-escape them (`\054`, `\072`);
     * both forms are normalised before splitting. Picking the longest all-digit field lands on
     * the ~17-digit actor id rather than the 10-digit timestamp. Falls back to `ds_user_id`.
     */
    private fun resolveActorId(sessionCookies: String): String? {
        val rur = extractCookie(sessionCookies, "rur")
        if (!rur.isNullOrBlank()) {
            val decoded = rur
                .replace("%2C", ",").replace("%3A", ":")
                .replace("\\054", ",").replace("\\072", ":")
                .trim('"')
            val actorId = decoded.split(",")
                .map { it.substringBefore(":").trim() }
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                .maxByOrNull { it.length }
            if (!actorId.isNullOrBlank()) return actorId
        }
        return extractCookie(sessionCookies, "ds_user_id")?.ifBlank { null }
    }

    /**
     * Resolves profile details for the currently logged-in Instagram session.
     */
    suspend fun fetchCurrentLoggedInUser(sessionCookies: String): InstagramUserProfileDetails? = withContext(Dispatchers.IO) {
        // Best-effort — a WebView-captured session doesn't always carry `rur`/`ds_user_id` in the
        // exact form this parses (cookie scoping/rotation can drop them from the merged jar even
        // though the session itself is perfectly valid). Deliberately NOT an early return: the
        // current_user/ fallback below needs no pre-known id at all, so a session that fails to
        // resolve an id here must still get a chance there instead of being reported "expired".
        val userId = resolveActorId(sessionCookies)
        val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
        Log.w(TAG, "fetchCurrentLoggedInUser: userId=$userId, csrfToken=$csrfToken, cookies=$sessionCookies")

        // Set once either attempt actually gets a response back from Instagram, regardless of its
        // status code. If neither attempt manages this — a timeout, no connectivity, a DNS/TLS
        // failure — every catch block below swallows its exception and this method would
        // otherwise fall through to the same `null` a genuinely logged-out session produces,
        // which is what made a plain network blip show up as "Instagram session expired" to the
        // user instead of "couldn't check right now, try again".
        var reachedInstagram = false

        // 1. Try /api/v1/users/{userId}/info/ — only possible once an id is known.
        //
        // Deliberately on www.instagram.com, not i.instagram.com: the latter is the
        // mobile-app API surface and expects the official app's request signing, so it silently
        // rejects a plain cookie session (a WebView-captured login never has that signature).
        // www.instagram.com serves the same paths to the browser and accepts cookies alone,
        // which is why follow()/like() (also cookie-only) already use it and succeed while this
        // lookup was quietly failing.
        if (userId != null) try {
            val infoUrl = "https://www.instagram.com/api/v1/users/$userId/info/"
            val reqBuilder = Request.Builder()
                .url(infoUrl)
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")
                .header("Cookie", sessionCookies)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
            if (csrfToken.isNotBlank()) reqBuilder.header("x-csrftoken", csrfToken)

            http.newCall(reqBuilder.build()).execute().use { response ->
                reachedInstagram = true
                val bodyText = response.body?.string() ?: ""
                Log.w(TAG, "fetchCurrentLoggedInUser /info/ response: code=${response.code}, body=$bodyText")
                if (response.isSuccessful && bodyText.isNotBlank()) {
                    val json = JSONObject(bodyText)
                    val user = json.optJSONObject("user")
                    if (user != null) {
                        val handle = user.optString("username").ifBlank { null }
                        val pic = user.optJSONObject("hd_profile_pic_url_info")?.optString("url")
                            ?: user.optString("profile_pic_url_hd")?.ifBlank { null }
                            ?: user.optString("profile_pic_url", "")
                        if (handle != null) {
                            val details = fetchUserProfileDetails(handle, sessionCookies)
                            if (details != null && details.followerCount > 0L) {
                                return@withContext details
                            }
                            return@withContext InstagramUserProfileDetails(
                                id = userId,
                                username = handle,
                                fullName = user.optString("full_name", ""),
                                biography = user.optString("biography", ""),
                                profilePicUrl = pic,
                                followerCount = user.optLong("follower_count", 0L),
                                followingCount = user.optLong("following_count", 0L),
                                mediaCount = user.optLong("media_count", 0L),
                                isPrivate = user.optBoolean("is_private", false),
                                isVerified = user.optBoolean("is_verified", false),
                                hasAnonymousProfilePic = user.optBoolean("has_anonymous_profile_picture", false)
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCurrentLoggedInUser via info/ failed for $userId", e)
        }

        // 2. Direct HTML web extraction fallback when /info/ is unavailable
        try {
            val htmlHandle = fetchUsernameFromWebHtml(sessionCookies)
            if (!htmlHandle.isNullOrBlank()) {
                reachedInstagram = true
                Log.i(TAG, "fetchCurrentLoggedInUser resolved handle @$htmlHandle via Web HTML fallback")
                val details = fetchUserProfileDetails(htmlHandle, sessionCookies)
                if (details != null) return@withContext details
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCurrentLoggedInUser via Web HTML fallback failed", e)
        }

        // Both attempts failed before ever getting a response — this is a connectivity problem,
        // not evidence the session is dead, so it must not be reported as one.
        if (!reachedInstagram) {
            throw java.io.IOException("Could not reach Instagram to verify the session")
        }

        return@withContext null
    }

    /**
     * Direct HTML page extraction fallback when API user info endpoints do not return a handle.
     */
    suspend fun fetchUsernameFromWebHtml(sessionCookies: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.instagram.com/")
                .applyDeviceHeaders()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Cookie", sessionCookies)
                .build()

            http.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: ""
                if (html.isNotBlank()) {
                    val match = Regex(""""username"\s*:\s*"([A-Za-z0-9._]+)"""").find(html)
                        ?: Regex("""viewer"[\s\S]*?"username"\s*:\s*"([A-Za-z0-9._]+)"""").find(html)
                        ?: Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["'][^"']*@([A-Za-z0-9._]+)""", RegexOption.IGNORE_CASE).find(html)
                    val handle = match?.groupValues?.get(1)?.trim()?.removePrefix("@")
                    if (!handle.isNullOrBlank() && !handle.all(Char::isDigit)) {
                        return@withContext handle
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchUsernameFromWebHtml failed", e)
        }
        null
    }

    /**
     * Whether the account has an active (not-yet-expired) story right now.
     *
     * `/api/v1/feed/user/{userId}/story/` answers `{"reel": {...}}` when a story is live and
     * `{"reel": null}` (still 200) when it isn't — same www.instagram.com cookie-auth surface as
     * [fetchCurrentLoggedInUser], for the same reason (i.instagram.com expects app-signed
     * requests a captured web session never has).
     */
    suspend fun fetchHasActiveStory(userId: String, sessionCookies: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val reqBuilder = Request.Builder()
                .url("https://www.instagram.com/api/v1/feed/user/$userId/story/")
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")
                .header("Cookie", sessionCookies)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
            if (csrfToken.isNotBlank()) reqBuilder.header("x-csrftoken", csrfToken)

            http.newCall(reqBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (!response.isSuccessful || bodyText.isBlank()) return@withContext false
                val json = JSONObject(bodyText)
                val reel = json.optJSONObject("reel")
                val items = reel?.optJSONArray("items")
                reel != null && (items == null || items.length() > 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchHasActiveStory failed for $userId", e)
            false
        }
    }

    /**
     * A GraphQL mutation answers 200 even for some failures, so "ok" means: a 2xx response
     * that carries a non-null `data` payload and no `errors` array.
     */
    private fun isGraphqlLikeSuccess(httpCode: Int, body: String, rootFieldName: String? = null): Boolean {
        if (httpCode !in 200..299 || body.isBlank()) return false
        return try {
            val json = JSONObject(body)
            val hasErrors = json.optJSONArray("errors")?.let { it.length() > 0 } == true
            val data = json.optJSONObject("data")
            if (data == null || hasErrors) return false
            if (!rootFieldName.isNullOrBlank()) {
                !data.isNull(rootFieldName)
            } else true
        } catch (_: Exception) {
            val notNullRoot = if (!rootFieldName.isNullOrBlank()) !body.contains("\"$rootFieldName\":null") else true
            body.contains("\"data\"") && !body.contains("\"errors\"") && notNullRoot
        }
    }

    /**
     * Resolves target profile to extract numeric Instagram User ID.
     */
    /**
     * Resolves a username or profile URL to the numeric Instagram user ID.
     *
     * Prefers the same `www.instagram.com/api/v1` path that [fetchUserProfileDetails] uses.
     * The legacy `?__a=1` endpoint below is kept only as a fallback — Instagram now answers
     * it with a login page for most callers, so on its own it returned null every time,
     * silently breaking every follow task that needed an ID.
     */
    suspend fun resolveUserId(usernameOrUrl: String, sessionCookies: String? = null): String? = withContext(Dispatchers.IO) {
        val username = InstagramCrypto.parseUsername(usernameOrUrl) ?: usernameOrUrl

        // Already an ID — nothing to resolve.
        if (username.isNotBlank() && username.all(Char::isDigit)) return@withContext username

        fetchUserProfileDetails(username, sessionCookies)
            ?.id
            ?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }

        try {
            val reqBuilder = Request.Builder()
                .url("https://www.instagram.com/$username/?__a=1&__d=dis")
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")

            if (!sessionCookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", sessionCookies)
            }

            http.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                extractJsonValue(body, "id") ?: extractJsonValue(body, "user_id")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving user ID for $username", e)
            null
        }
    }

    /**
     * Fetches Instagram user profile details given username/ID:
     * 1. Hits /api/v1/feed/user/{username}/username/?count=12 on www.instagram.com.
     * 2. Extracts user metadata (pk, username, full_name, profile_pic, counts).
     * 3. Fallbacks to /api/v1/users/{pk}/info/ if follower/following counts are missing.
     */
    suspend fun fetchUserProfileDetails(
        username: String,
        sessionCookies: String? = null
    ): InstagramUserProfileDetails? = withContext(Dispatchers.IO) {
        val cleanUsername = InstagramCrypto.parseUsername(username) ?: username.trim().removePrefix("@")
        if (cleanUsername.isBlank()) return@withContext null
        
        val csrfToken = if (!sessionCookies.isNullOrBlank()) extractCookie(sessionCookies, "csrftoken") else null
        var userObj: JSONObject? = null
        var mediaCount = 0L
        var numericPk: String? = if (cleanUsername.all { it.isDigit() }) cleanUsername else null
        var uName: String = cleanUsername
        var followers: Long? = null
        var following: Long? = null
        var picUrl: String = ""

        // 0. Initial Check: web_profile_info endpoint
        if (!cleanUsername.all { it.isDigit() }) {
            try {
                val webProfileUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$cleanUsername"
                val webProfileReqBuilder = Request.Builder()
                    .url(webProfileUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-ASBD-ID", "129477")
                    .header("Accept", "*/*")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")

                if (!sessionCookies.isNullOrBlank()) {
                    webProfileReqBuilder.header("Cookie", sessionCookies)
                }

                http.newCall(webProfileReqBuilder.build()).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    // Check if valid JSON and contains data.user (not HTML fallback or user agent mismatch redirect)
                    if (response.isSuccessful && bodyText.isNotBlank() && !bodyText.trimStart().startsWith("<")) {
                        val json = JSONObject(bodyText)
                        val user = json.optJSONObject("data")?.optJSONObject("user")
                        if (user != null) {
                            userObj = user
                            val timeline = user.optJSONObject("edge_owner_to_timeline_media")
                            if (timeline != null) {
                                mediaCount = timeline.optLong("count", 0L)
                            }
                            Log.i(TAG, "web_profile_info successfully fetched profile for $cleanUsername")
                        }
                    } else {
                        Log.w(TAG, "web_profile_info returned HTML/error (HTTP ${response.code}), falling back to next step")
                    }
                }

                if (numericPk == null && userObj != null) {
                    numericPk = userObj?.optString("id")?.ifBlank { null }
                        ?: userObj?.optString("pk")?.ifBlank { null }
                }
            } catch (e: Exception) {
                Log.w(TAG, "web_profile_info endpoint failed for $cleanUsername, falling back", e)
            }
        }

        // 1. Second Fallback: PolarisUserHoverCardContentV2Query GraphQL query
        if (userObj == null) {
            try {
                if (numericPk.isNullOrBlank() && !cleanUsername.all { it.isDigit() }) {
                    numericPk = resolveNumericPkFromProfilePage(cleanUsername, sessionCookies)
                }

                val targetUserId = numericPk ?: if (cleanUsername.all { it.isDigit() }) cleanUsername else null
                if (!targetUserId.isNullOrBlank()) {
                    val fbDtsgToken = fetchFbDtsgToken(sessionCookies, cleanUsername)
                    val lsdToken = extractCookie(sessionCookies ?: "", "lsd") ?: "toxLtqxo-5GooSYWUv2PJ1"
                    val actorId = extractAvFromCookies(sessionCookies)

                    val formBodyBuilder = FormBody.Builder()
                        .add("jazoest", InstagramCrypto.createJazoest(targetUserId))
                        .add("__crn", "comet.igweb.PolarisProfilePostsTabRoute")
                        .add("fb_api_caller_class", "RelayModern")
                        .add("fb_api_req_friendly_name", "PolarisUserHoverCardContentV2Query")
                        .add("server_timestamps", "true")
                        .add("variables", "{\"userID\":\"$targetUserId\"}")
                        .add("doc_id", "27756568060663620")

                    if (!fbDtsgToken.isNullOrBlank()) {
                        formBodyBuilder.add("fb_dtsg", fbDtsgToken)
                    }

                    val formBody = formBodyBuilder.build()

                    val reqBuilder = Request.Builder()
                        .url("https://www.instagram.com/api/graphql")
                        .post(formBody)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("sec-ch-ua-full-version-list", "\"Not=A?Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"151.0.7922.108\", \"Chromium\";v=\"151.0.7922.108\"")
                        .header("sec-ch-ua-platform", "\"Windows\"")
                        .header("viewport-width", "1517")
                        .header("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"")
                        .header("sec-ch-ua-model", "\"\"")
                        .header("sec-ch-ua-mobile", "?0")
                        .header("X-IG-App-ID", "936619743392459")
                        .header("X-FB-LSD", lsdToken)
                        .header("X-IG-Max-Touch-Points", "0")
                        .header("X-FB-Friendly-Name", "PolarisUserHoverCardContentV2Query")
                        .header("dpr", "0.9")
                        .header("sec-ch-prefers-color-scheme", "dark")
                        .header("DNT", "1")
                        .header("sec-ch-ua-platform-version", "\"15.0.0\"")
                        .header("Accept", "*/*")
                        .header("Origin", "https://www.instagram.com")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Referer", "https://www.instagram.com/$cleanUsername/")
                        .header("Accept-Language", "en-US,en;q=0.9")

                    if (!csrfToken.isNullOrBlank()) {
                        reqBuilder.header("X-CSRFToken", csrfToken)
                    }
                    if (!sessionCookies.isNullOrBlank()) {
                        reqBuilder.header("Cookie", sessionCookies)
                    }

                    http.newCall(reqBuilder.build()).execute().use { response ->
                        var bodyText = response.body?.string() ?: ""
                        if (bodyText.startsWith("for (;;);")) {
                            bodyText = bodyText.substring("for (;;);".length)
                        }
                        if (response.isSuccessful && bodyText.isNotBlank() && !bodyText.trimStart().startsWith("<")) {
                            val json = JSONObject(bodyText)
                            val hoverUserDict = json.optJSONObject("data")
                                ?.optJSONObject("xig_user_by_igid_v2")
                                ?.optJSONObject("user_dict")

                            if (hoverUserDict != null) {
                                userObj = hoverUserDict

                                // Explicit data extraction from data.xig_user_by_igid_v2.user_dict:
                                // 1. data.xig_user_by_igid_v2.user_dict.pk
                                val hoverPk = hoverUserDict.optString("pk").ifBlank { null }
                                if (!hoverPk.isNullOrBlank()) {
                                    numericPk = hoverPk
                                }

                                // 2. data.xig_user_by_igid_v2.user_dict.username
                                val hoverUsername = hoverUserDict.optString("username").ifBlank { null }
                                if (!hoverUsername.isNullOrBlank()) {
                                    uName = hoverUsername
                                }

                                // 3. data.xig_user_by_igid_v2.user_dict.profile_pic_url
                                val hoverPic = hoverUserDict.optString("profile_pic_url").ifBlank { null }
                                    ?: hoverUserDict.optJSONObject("hd_profile_pic_url_info")?.optString("url")?.ifBlank { null }
                                if (!hoverPic.isNullOrBlank()) {
                                    picUrl = hoverPic
                                }

                                // 4. data.xig_user_by_igid_v2.user_dict.follower_count
                                val hoverFollowers = hoverUserDict.optLong("follower_count", -1L)
                                if (hoverFollowers >= 0) {
                                    followers = hoverFollowers
                                }

                                // 5. data.xig_user_by_igid_v2.user_dict.following_count
                                val hoverFollowing = hoverUserDict.optLong("following_count", -1L)
                                if (hoverFollowing >= 0) {
                                    following = hoverFollowing
                                }

                                // 6. data.xig_user_by_igid_v2.user_dict.media_count
                                val hoverMediaCount = hoverUserDict.optLong("media_count", -1L)
                                if (hoverMediaCount >= 0) {
                                    mediaCount = hoverMediaCount
                                }

                                Log.i(
                                    TAG,
                                    "PolarisUserHoverCardContentV2Query successfully extracted user_dict: " +
                                            "pk=$numericPk, username=$uName, profile_pic_url=$picUrl, " +
                                            "follower_count=$followers, following_count=$following, media_count=$mediaCount"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PolarisUserHoverCardContentV2Query fallback failed for $cleanUsername", e)
            }
        }

        // 2. Secondary: feed/user endpoint (if web_profile_info did not resolve userObj)
        if (userObj == null && !cleanUsername.all { it.isDigit() }) {
            try {
                val feedUrl = "https://www.instagram.com/api/v1/feed/user/$cleanUsername/username/?count=12"
                val feedReqBuilder = Request.Builder()
                    .url(feedUrl)
                    .applyDeviceHeaders()
                    .header("x-asbd-id", InstagramCrypto.ASBD_ID)
                    .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                    .header("Accept", "*/*")

                if (!csrfToken.isNullOrBlank()) {
                    feedReqBuilder.header("x-csrftoken", csrfToken)
                }
                if (!sessionCookies.isNullOrBlank()) {
                    feedReqBuilder.header("Cookie", sessionCookies)
                    feedReqBuilder.header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                }

                http.newCall(feedReqBuilder.build()).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyText.isNotBlank()) {
                        val json = JSONObject(bodyText)
                        userObj = json.optJSONObject("user")
                        val itemsArr = json.optJSONArray("items")
                        if (userObj == null && itemsArr != null && itemsArr.length() > 0) {
                            userObj = itemsArr.optJSONObject(0)?.optJSONObject("user")
                        }
                        if (mediaCount <= 0L) {
                            mediaCount = itemsArr?.length()?.toLong() ?: 0L
                        }
                    }
                }

                if (numericPk == null) {
                    numericPk = userObj?.optString("pk_id")?.ifBlank { null }
                        ?: userObj?.optString("pk")?.ifBlank { null }
                        ?: userObj?.optString("id")?.ifBlank { null }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Feed user endpoint failed for $cleanUsername", e)
            }
        }

        if (followers == null) {
            followers = pickCount(userObj, "edge_followed_by", "follower_count", "followers_count")
        }
        if (following == null) {
            following = pickCount(userObj, "edge_follow", "following_count", "follows_count")
        }
        if (picUrl.isBlank()) {
            picUrl = userObj?.optJSONObject("hd_profile_pic_url_info")?.optString("url")
                ?: userObj?.optString("profile_pic_url_hd")?.ifBlank { null }
                ?: userObj?.optString("profile_pic_url", "") ?: ""
        }
        var bio = userObj?.optString("biography", "") ?: ""
        var fullName = userObj?.optString("full_name", "") ?: ""
        uName = userObj?.optString("username", cleanUsername) ?: cleanUsername
        val isPrivate = userObj?.optBoolean("is_private", false) ?: false
        val isVerified = userObj?.optBoolean("is_verified", false) ?: false

        // 2. Direct info lookup fallback via /api/v1/users/{pk}/info/
        if ((followers == null || following == null || followers == 0L || uName.all { it.isDigit() }) && !numericPk.isNullOrBlank()) {
            try {
                val infoUrl = "https://www.instagram.com/api/v1/users/$numericPk/info/"
                val infoReqBuilder = Request.Builder()
                    .url(infoUrl)
                    .applyDeviceHeaders()
                    .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                    .header("Accept", "*/*")

                if (!csrfToken.isNullOrBlank()) {
                    infoReqBuilder.header("x-csrftoken", csrfToken)
                }
                if (!sessionCookies.isNullOrBlank()) {
                    infoReqBuilder.header("Cookie", sessionCookies)
                    infoReqBuilder.header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                }

                http.newCall(infoReqBuilder.build()).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyText.isNotBlank()) {
                        val infoJson = JSONObject(bodyText)
                        val infoUser = infoJson.optJSONObject("user")
                        if (infoUser != null) {
                            val infoUsername = infoUser.optString("username").ifBlank { null }
                            if (infoUsername != null && (uName.all { it.isDigit() } || uName.isBlank())) {
                                uName = infoUsername
                            }
                            followers = followers ?: pickCount(infoUser, "follower_count", "edge_followed_by")
                            following = following ?: pickCount(infoUser, "following_count", "edge_follow")
                            if (picUrl.isBlank()) {
                                picUrl = infoUser.optJSONObject("hd_profile_pic_url_info")?.optString("url")
                                    ?: infoUser.optString("profile_pic_url_hd")?.ifBlank { null }
                                    ?: infoUser.optString("profile_pic_url", "")
                            }
                            if (bio.isBlank()) bio = infoUser.optString("biography", "")
                            if (fullName.isBlank()) fullName = infoUser.optString("full_name", "")
                            val infoMediaCount = infoUser.optLong("media_count", -1L)
                            if (infoMediaCount >= 0) mediaCount = infoMediaCount
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Secondary info fetch failed for $numericPk", e)
            }
        }

        // 3. Public OpenGraph HTML fallback using Facebook Bot User-Agent
        if (followers == null || following == null || followers == 0L) {
            try {
                val pageReq = Request.Builder()
                    .url("https://www.instagram.com/$cleanUsername/")
                    .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                http.newCall(pageReq).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful && body.isNotBlank()) {
                        val ogDesc = Regex("""<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:description["']""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)

                        if (!ogDesc.isNullOrBlank()) {
                            val followersStr = Regex("""([0-9.,KMBm]+)\s+Followers""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)
                            val followingStr = Regex("""([0-9.,KMBm]+)\s+Following""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)
                            val postsStr = Regex("""([0-9.,KMBm]+)\s+Posts""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)

                            if (!followersStr.isNullOrBlank()) followers = parseFormattedCount(followersStr)
                            if (!followingStr.isNullOrBlank()) following = parseFormattedCount(followingStr)
                            if (!postsStr.isNullOrBlank() && mediaCount <= 0L) mediaCount = parseFormattedCount(postsStr)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Facebook bot HTML fallback failed for $cleanUsername", e)
            }
        }

        if (userObj == null && followers == null && following == null && picUrl.isBlank() && uName == cleanUsername && uName.all { it.isDigit() }) {
            return@withContext null
        }

        InstagramUserProfileDetails(
            id = numericPk ?: cleanUsername,
            username = uName,
            fullName = fullName,
            biography = bio,
            profilePicUrl = picUrl,
            followerCount = followers ?: 0L,
            followingCount = following ?: 0L,
            mediaCount = mediaCount,
            isPrivate = isPrivate,
            isVerified = isVerified,
            externalUrl = userObj?.optString("external_url")?.ifBlank { null }
        )
    }

    private fun pickCount(obj: JSONObject?, vararg keys: String): Long? {
        if (obj == null) return null
        for (key in keys) {
            if (obj.has(key)) {
                val v = obj.optLong(key, -1L)
                if (v >= 0) return v
                val sub = obj.optJSONObject(key)
                if (sub != null && sub.has("count")) {
                    val subV = sub.optLong("count", -1L)
                    if (subV >= 0) return subV
                }
            }
        }
        return null
    }

    /**
     * Fetches user media feed from user ID via www.instagram.com feed/user/{userId}/username/ endpoint.
     * Ports logic from GramDominator GetUserFeedAsync.
     */
    suspend fun fetchUserFeed(
        userId: String,
        maxId: String? = null,
        sessionCookies: String? = null
    ): InstagramUserFeedResult? = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext null
        try {
            val maxParam = if (!maxId.isNullOrBlank()) "&max_id=$maxId" else ""
            // www.instagram.com, not i.instagram.com — see the note in fetchCurrentLoggedInUser.
            val url = "https://www.instagram.com/api/v1/feed/user/$userId/username/?count=12$maxParam"
            val csrfToken = if (!sessionCookies.isNullOrBlank()) extractCookie(sessionCookies, "csrftoken") else null

            val reqBuilder = Request.Builder()
                .url(url)
                .applyDeviceHeaders()
                .header("x-asbd-id", InstagramCrypto.ASBD_ID)
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")

            if (!csrfToken.isNullOrBlank()) {
                reqBuilder.header("x-csrftoken", csrfToken)
            }
            if (!sessionCookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", sessionCookies)
                reqBuilder.header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
            }

            http.newCall(reqBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (!response.isSuccessful || bodyText.isBlank()) return@withContext null

                val json = JSONObject(bodyText)
                val itemsArr = json.optJSONArray("items") ?: return@withContext null

                val feedItems = mutableListOf<InstagramUserFeedItem>()
                for (i in 0 until itemsArr.length()) {
                    val item = itemsArr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    val code = item.optString("code", "")
                    val mediaType = item.optInt("media_type", 1)
                    val captionObj = item.optJSONObject("caption")
                    val caption = captionObj?.optString("text")?.ifBlank { null }
                    val likeCount = item.optLong("like_count", 0L)
                    val commentCount = item.optLong("comment_count", 0L)
                    val repostCount = item.optLong("media_repost_count", item.optLong("reshare_count", item.optLong("repost_count", 0L)))
                    val takenAt = item.optLong("taken_at", 0L)

                    var displayUrl: String? = null
                    val imgVersions = item.optJSONObject("image_versions2")
                    val candidates = imgVersions?.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        displayUrl = candidates.optJSONObject(0)?.optString("url")?.ifBlank { null }
                    }

                    val videoUrl = item.optJSONArray("video_versions")
                        ?.optJSONObject(0)?.optString("url")?.ifBlank { null }

                    val mediaId = item.optString("pk").ifBlank { id.substringBefore('_') }

                    feedItems.add(
                        InstagramUserFeedItem(
                            id = id,
                            code = code,
                            caption = caption,
                            mediaType = mediaType,
                            displayUrl = displayUrl,
                            videoUrl = videoUrl,
                            likeCount = likeCount,
                            commentCount = commentCount,
                            repostCount = repostCount,
                            takenAt = takenAt,
                            mediaId = mediaId,
                            hasLiked = item.optBoolean("has_liked", false),
                            canReshare = item.optBoolean("can_viewer_reshare", true),
                            commentsDisabled = item.optBoolean("comments_disabled", false) ||
                                item.optBoolean("disable_caption_and_comment", false)
                        )
                    )
                }

                val hasMore = json.optBoolean("more_available", false) || json.optBoolean("has_more", false)
                val nextMaxId = json.optString("next_max_id").ifBlank { null }

                InstagramUserFeedResult(
                    items = feedItems,
                    maxId = nextMaxId,
                    hasMore = hasMore
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed fetching user feed for userId $userId", e)
            null
        }
    }

    /**
     * Fetches post media details given shortcode or numeric media ID via media/{mediaId}/info/ endpoint.
     * Ports logic from GramDominator MediaInfo.
     */
    suspend fun fetchPostDetails(
        mediaCodeOrId: String,
        sessionCookies: String? = null
    ): InstagramPostDetails? = withContext(Dispatchers.IO) {
        if (mediaCodeOrId.isBlank()) return@withContext null
        val code = InstagramCrypto.getCodeFromUrl(mediaCodeOrId)
        val mediaId = if (code.all { it.isDigit() }) code else InstagramCrypto.getIdFromCode(code)

        // 1. If authenticated session cookies exist, try the full Media Info API first (returns 200 OK with video, likes, comments)
        if (!sessionCookies.isNullOrBlank()) {
            try {
                val url = "https://www.instagram.com/api/v1/media/$mediaId/info/"
                val csrfToken = extractCookie(sessionCookies, "csrftoken")

                val reqBuilder = Request.Builder()
                    .url(url)
                    .applyDeviceHeaders()
                    .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                    .header("Referer", "https://www.instagram.com/p/$code/")
                    .header("Cookie", sessionCookies)
                    .header("Accept", "*/*")

                if (!csrfToken.isNullOrBlank()) reqBuilder.header("X-CSRFToken", csrfToken)

                http.newCall(reqBuilder.build()).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyText.isNotBlank()) {
                        val json = JSONObject(bodyText)
                        val itemsArr = json.optJSONArray("items")
                        if (itemsArr != null && itemsArr.length() > 0) {
                            val item = itemsArr.getJSONObject(0)
                            val id = item.optString("id", mediaId)
                            val itemCode = item.optString("code", code)
                            val mediaType = item.optInt("media_type", 1)
                            val captionObj = item.optJSONObject("caption")
                            val caption = captionObj?.optString("text")?.ifBlank { null }
                            val likeCount = item.optLong("like_count", 0L)
                            val commentCount = item.optLong("comment_count", 0L)
                            val repostCount = item.optLong("media_repost_count", item.optLong("reshare_count", item.optLong("repost_count", 0L)))
                            val takenAt = item.optLong("taken_at", 0L)

                            val userObj = item.optJSONObject("user")
                            val ownerUserId = userObj?.optString("pk")?.ifBlank { null }
                            val ownerUsername = userObj?.optString("username")?.ifBlank { null }

                            var displayUrl: String? = null
                            val imgVersions = item.optJSONObject("image_versions2")
                            val candidates = imgVersions?.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                displayUrl = candidates.optJSONObject(0)?.optString("url")?.ifBlank { null }
                            }

                            var videoUrl: String? = null
                            val vidVersions = item.optJSONArray("video_versions")
                            if (vidVersions != null && vidVersions.length() > 0) {
                                videoUrl = vidVersions.optJSONObject(0)?.optString("url")?.ifBlank { null }
                            }

                            return@withContext InstagramPostDetails(
                                id = id,
                                code = itemCode,
                                caption = caption,
                                mediaType = mediaType,
                                displayUrl = displayUrl,
                                videoUrl = videoUrl,
                                likeCount = likeCount,
                                commentCount = commentCount,
                                repostCount = repostCount,
                                ownerUserId = ownerUserId,
                                ownerUsername = ownerUsername,
                                takenAt = takenAt
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Authenticated Media Info API failed for post $code, falling back to public OEmbed", e)
            }
        }

        // 2. Public Fallback: Instagram OEmbed API (100% public, works without login cookies, returns 200 OK)
        try {
            val oembedUrl = "https://www.instagram.com/api/v1/oembed/?url=https://www.instagram.com/p/$code/"
            val request = Request.Builder()
                .url(oembedUrl)
                .applyDeviceHeaders()
                .header("Accept", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (response.isSuccessful && bodyText.isNotBlank()) {
                    val json = JSONObject(bodyText)
                    val authorName = json.optString("author_name")?.ifBlank { null }
                    val title = json.optString("title")?.ifBlank { null }
                    val thumbnailUrl = json.optString("thumbnail_url")?.ifBlank { null }
                    val rawMediaId = json.optString("media_id")?.ifBlank { null }
                    val resolvedId = rawMediaId?.substringBefore("_") ?: mediaId

                    if (!thumbnailUrl.isNullOrBlank() || !title.isNullOrBlank()) {
                        return@withContext InstagramPostDetails(
                            id = resolvedId,
                            code = code,
                            caption = title,
                            mediaType = 1,
                            displayUrl = thumbnailUrl,
                            videoUrl = null,
                            likeCount = 0L,
                            commentCount = 0L,
                            ownerUserId = json.optString("author_id")?.ifBlank { null },
                            ownerUsername = authorName,
                            takenAt = System.currentTimeMillis() / 1000
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OEmbed API failed for post $code", e)
        }

        // 2. Fallback: Parse HTML OpenGraph meta tags directly from post page
        try {
            val htmlUrl = "https://www.instagram.com/p/$code/"
            val reqBuilder = Request.Builder()
                .url(htmlUrl)
                .applyDeviceHeaders()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            if (!sessionCookies.isNullOrBlank()) reqBuilder.header("Cookie", sessionCookies)

            http.newCall(reqBuilder.build()).execute().use { response ->
                val html = response.body?.string() ?: ""
                if (html.isNotBlank()) {
                    val ogImage = extractMetaProperty(html, "og:image")
                    val ogVideo = extractMetaProperty(html, "og:video") ?: extractMetaProperty(html, "og:video:secure_url")
                    val ogTitle = extractMetaProperty(html, "og:title")
                    val ogDesc = extractMetaProperty(html, "og:description")

                    val ownerUsername = extractOwnerFromTitle(ogTitle ?: ogDesc)
                    val caption = ogDesc ?: ogTitle

                    if (!ogImage.isNullOrBlank() || !ogTitle.isNullOrBlank()) {
                        return@withContext InstagramPostDetails(
                            id = mediaId,
                            code = code,
                            caption = caption,
                            mediaType = if (!ogVideo.isNullOrBlank()) 2 else 1,
                            displayUrl = ogImage,
                            videoUrl = ogVideo,
                            likeCount = 0L,
                            commentCount = 0L,
                            ownerUserId = null,
                            ownerUsername = ownerUsername,
                            takenAt = System.currentTimeMillis() / 1000
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTML fallback failed for post $code", e)
        }

        null
    }

    suspend fun searchUsers(
        query: String,
        sessionCookies: String? = null
    ): List<InstagramSearchUser> = withContext(Dispatchers.IO) {
        val clean = query.trim().trimStart('@')
        if (clean.length < 2) return@withContext emptyList()
        try {
            val searchUrl = "https://www.instagram.com/web/search/topsearch/?context=blended&query=${java.net.URLEncoder.encode(clean, "UTF-8")}"
            val reqBuilder = Request.Builder()
                .url(searchUrl)
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")

            if (!sessionCookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", sessionCookies)
                reqBuilder.header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
            }

            http.newCall(reqBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (!response.isSuccessful || bodyText.isBlank()) return@withContext emptyList()
                val usersArr = JSONObject(bodyText).optJSONArray("users") ?: return@withContext emptyList()
                val results = mutableListOf<InstagramSearchUser>()
                for (i in 0 until usersArr.length()) {
                    val user = usersArr.optJSONObject(i)?.optJSONObject("user") ?: continue
                    val username = user.optString("username").trim()
                    if (username.isBlank()) continue
                    results += InstagramSearchUser(
                        username = username,
                        fullName = user.optString("full_name").ifBlank { null },
                        profilePicUrl = user.optString("profile_pic_url").ifBlank { null },
                        isPrivate = user.optBoolean("is_private", false),
                        isVerified = user.optBoolean("is_verified", false)
                    )
                }
                results.distinctBy { it.username.lowercase(Locale.US) }.take(25)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instagram user search failed for $clean", e)
            emptyList()
        }
    }

    suspend fun uploadPhoto(
        photoBytes: ByteArray,
        uploadId: String,
        isStory: Boolean,
        sessionCookies: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val entityName = if (isStory) "story_$uploadId" else "fb_uploader_$uploadId"
            val url = if (isStory) "https://i.instagram.com/rupload_igphoto/story_$uploadId"
                      else "https://i.instagram.com/rupload_igphoto/fb_uploader_$uploadId"

            val ruploadParams = if (isStory) {
                "{\"upload_id\":\"$uploadId\",\"media_type\":1,\"upload_media_width\":720,\"upload_media_height\":1280}"
            } else {
                "{\"media_type\":1,\"upload_id\":\"$uploadId\",\"upload_media_height\":1280,\"upload_media_width\":720}"
            }

            val requestBody = photoBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("X-Instagram-Rupload-Params", ruploadParams)
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("Offset", "0")
                .header("X-Entity-Length", photoBytes.size.toString())
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Entity-Type", "image/jpeg")
                .header("X-Entity-Name", entityName)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val success = response.isSuccessful && bodyText.contains("\"status\":\"ok\"")
                Log.i(TAG, "UploadPhoto isStory=$isStory success=$success code=${response.code}: $bodyText")
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "UploadPhoto failed isStory=$isStory", e)
            false
        }
    }

    suspend fun configurePhoto(
        uploadId: String,
        caption: String,
        sessionCookies: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val userId = extractCookie(sessionCookies, "ds_user_id") ?: ""
            val formBody = FormBody.Builder()
                .add("archive_only", "false")
                .add("caption", caption)
                .add("clips_share_preview_to_feed", "1")
                .add("disable_comments", "0")
                .add("disable_oa_reuse", "false")
                .add("igtv_share_preview_to_feed", "1")
                .add("is_meta_only_post", "0")
                .add("is_unified_video", "1")
                .add("like_and_view_counts_disabled", "0")
                .add("media_share_flow", "creation_flow")
                .add("share_to_facebook", "")
                .add("share_to_fb_destination_type", "USER")
                .add("source_type", "library")
                .add("upload_id", uploadId)
                .add("geotag_enabled", "false")
                .add("jazoest", InstagramCrypto.createJazoest(userId))
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/media/configure/")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val success = response.isSuccessful && bodyText.contains("\"status\":\"ok\"")
                Log.i(TAG, "ConfigurePhoto success=$success code=${response.code}: $bodyText")
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "ConfigurePhoto failed", e)
            false
        }
    }

    suspend fun configureStory(
        uploadId: String,
        sessionCookies: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val formBody = FormBody.Builder()
                .add("upload_id", uploadId)
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/create/configure_to_story/")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val success = response.isSuccessful && bodyText.contains("\"status\":\"ok\"")
                Log.i(TAG, "ConfigureStory success=$success code=${response.code}: $bodyText")
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "ConfigureStory failed", e)
            false
        }
    }

    /**
     * Dynamically fetches target page HTML (e.g. accounts/edit/ for profile updates, or target user handle for follow actions)
     * and extracts the active `fb_dtsg` token.
     * Returns null if unreachable or not found.
     */
    suspend fun fetchFbDtsg(
        sessionCookies: String,
        targetPathOrHandle: String = "accounts/edit"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val cleanPath = targetPathOrHandle.trim('/')
            val targetUrl = if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
                cleanPath
            } else if (cleanPath.equals("accounts/edit", ignoreCase = true) || cleanPath.isBlank()) {
                "https://www.instagram.com/accounts/edit/"
            } else {
                "https://www.instagram.com/$cleanPath/"
            }

            val request = Request.Builder()
                .url(targetUrl)
                .applyDeviceHeaders()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-CSRFToken", csrfToken)
                .header("Cookie", sessionCookies)
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: ""

                val dtsgRegexes = listOf(
                    Regex(""""DTSGInitialData"\s*,\s*\[\]\s*,\s*\{\s*"token"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
                    Regex(""""token"\s*:\s*"([A-Za-z0-9_-]+:[0-9]+:[0-9]+)"""", RegexOption.IGNORE_CASE),
                    Regex("""name=["']fb_dtsg["']\s+value=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex(""""dtsg"\s*:\s*\{\s*"token"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
                    Regex(""""fb_dtsg"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                )

                for (regex in dtsgRegexes) {
                    val match = regex.find(html)
                    if (match != null && match.groupValues.size > 1) {
                        val token = match.groupValues[1].trim()
                        if (token.isNotBlank()) {
                            Log.i(TAG, "Extracted dynamic fb_dtsg token from $targetUrl: ${token.take(15)}...")
                            return@withContext token
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract fb_dtsg token from $targetPathOrHandle page", e)
            null
        }
    }

    suspend fun updateProfilePicture(
        photoBytes: ByteArray,
        sessionCookies: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val extractedFbDtsg = fetchFbDtsg(sessionCookies)

            val mediaType = "image/jpeg".toMediaTypeOrNull()
            val filePart = photoBytes.toRequestBody(mediaType)

            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("profile_pic", "profilepic.jpg", filePart)

            if (!extractedFbDtsg.isNullOrBlank()) {
                requestBodyBuilder.addFormDataPart("fb_dtsg", extractedFbDtsg)
            }
            requestBodyBuilder.addFormDataPart("jazoest", InstagramCrypto.createJazoest(java.util.UUID.randomUUID().toString()))

            val requestBody = requestBodyBuilder.build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/accounts/web_change_profile_picture/")
                .post(requestBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/accounts/edit/")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val success = response.isSuccessful && bodyText.contains("\"status\":\"ok\"")
                Log.i(TAG, "UpdateProfilePicture success=$success code=${response.code}: $bodyText")
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "UpdateProfilePicture failed", e)
            false
        }
    }

    suspend fun getProfilePicProps(
        username: String,
        sessionCookies: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/get_profile_pic_props/$username/")
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/accounts/edit/")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                Log.i(TAG, "GetProfilePicProps success=${response.isSuccessful} code=${response.code}: $bodyText")
                if (response.isSuccessful) bodyText else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetProfilePicProps failed for $username", e)
            null
        }
    }

    suspend fun updateBiography(
        biography: String,
        firstName: String,
        username: String,
        sessionCookies: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
            val extractedFbDtsg = fetchFbDtsg(sessionCookies)
            val jazoest = InstagramCrypto.createJazoest(java.util.UUID.randomUUID().toString())

            val formBodyBuilder = FormBody.Builder()
                .add("biography", biography)
                .add("chaining_enabled", "on")
                .add("external_url", "")
                .add("first_name", firstName)
                .add("username", username)
                .add("jazoest", jazoest)

            if (!extractedFbDtsg.isNullOrBlank()) {
                formBodyBuilder.add("fb_dtsg", extractedFbDtsg)
            }

            val formBody = formBodyBuilder.build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/accounts/edit/")
                .post(formBody)
                .applyDeviceHeaders()
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("X-IG-WWW-Claim", extractWwwClaim(sessionCookies))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrfToken)
                .header("X-Instagram-AJAX", InstagramCrypto.generateInstagramAjax())
                .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
                .header("Cookie", sessionCookies)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/accounts/edit/")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                val success = response.isSuccessful && bodyText.contains("\"status\":\"ok\"")
                Log.i(TAG, "UpdateBiography success=$success code=${response.code}: $bodyText")
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "UpdateBiography failed", e)
            false
        }
    }

    private fun extractMetaProperty(html: String, property: String): String? {
        val regex = Regex("<meta[^>]+property=[\"']$property[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val match = regex.find(html) ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']$property[\"']", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1)
    }

    private fun extractOwnerFromTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val match = Regex("@([A-Za-z0-9._]+)").find(title)
        return match?.groupValues?.get(1)
    }

    /**
     * Anchored to a cookie boundary (string start or after `;`) so a name that happens to be a
     * suffix of another cookie's name can't match it by accident, and strips a surrounding quote
     * pair some WebView `CookieManager` captures leave on the value.
     */
    private fun extractCookie(cookieHeader: String, name: String): String? {
        val regex = Regex("(?:^|;)\\s*${Regex.escape(name)}=([^;]+)")
        return regex.find(cookieHeader)?.groupValues?.get(1)?.trim()?.trim('"')?.ifBlank { null }
    }

    private suspend fun fetchFbDtsgToken(sessionCookies: String?, targetHandle: String = "accounts/edit"): String? {
        if (sessionCookies.isNullOrBlank()) return null
        return try {
            val path = if (targetHandle.equals("accounts/edit", ignoreCase = true)) "/accounts/edit/" else "/${targetHandle.trim().removePrefix("/")}/"
            val req = Request.Builder()
                .url("https://www.instagram.com$path")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Cookie", sessionCookies)
                .build()

            http.newCall(req).execute().use { response ->
                val html = response.body?.string() ?: ""
                val regexes = listOf(
                    Regex("\"DTSGInitialData\"\\s*,\\s*\\[]\\s*,\\s*\\{\\s*\"token\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                    Regex("name=[\"']fb_dtsg[\"']\\s+value=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                    Regex("\"token\"\\s*:\\s*\"([A-Za-z0-9_-]+:[0-9]+:[0-9]+)\"", RegexOption.IGNORE_CASE)
                )
                for (reg in regexes) {
                    val match = reg.find(html)
                    if (match != null && match.groupValues.size > 1) {
                        return match.groupValues[1]
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveNumericPkFromProfilePage(username: String, sessionCookies: String?): String? {
        // 1. Try feed/user endpoint
        try {
            val feedUrl = "https://www.instagram.com/api/v1/feed/user/$username/username/?count=1"
            val feedReqBuilder = Request.Builder()
                .url(feedUrl)
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")
            if (!sessionCookies.isNullOrBlank()) feedReqBuilder.header("Cookie", sessionCookies)

            http.newCall(feedReqBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (response.isSuccessful && bodyText.isNotBlank()) {
                    val json = JSONObject(bodyText)
                    val uObj = json.optJSONObject("user")
                        ?: json.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("user")
                    val pk = uObj?.optString("pk_id")?.ifBlank { null }
                        ?: uObj?.optString("pk")?.ifBlank { null }
                        ?: uObj?.optString("id")?.ifBlank { null }
                    if (!pk.isNullOrBlank()) return pk
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "feed/user PK resolution failed for $username", e)
        }

        // 2. Try topsearch fallback
        try {
            val searchUrl = "https://www.instagram.com/web/search/topsearch/?context=blended&query=${java.net.URLEncoder.encode(username, "UTF-8")}"
            val searchReqBuilder = Request.Builder()
                .url(searchUrl)
                .applyDeviceHeaders()
                .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
                .header("Accept", "*/*")
            if (!sessionCookies.isNullOrBlank()) searchReqBuilder.header("Cookie", sessionCookies)

            http.newCall(searchReqBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (response.isSuccessful && bodyText.isNotBlank()) {
                    val json = JSONObject(bodyText)
                    val usersArr = json.optJSONArray("users")
                    if (usersArr != null) {
                        for (i in 0 until usersArr.length()) {
                            val uObj = usersArr.optJSONObject(i)?.optJSONObject("user")
                            if (uObj != null && uObj.optString("username").equals(username, ignoreCase = true)) {
                                val pk = uObj.optString("pk_id").ifBlank { null }
                                    ?: uObj.optString("pk").ifBlank { null }
                                    ?: uObj.optString("id").ifBlank { null }
                                if (!pk.isNullOrBlank()) return pk
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "topsearch PK resolution failed for $username", e)
        }

        // 3. Fallback to profile page HTML scanning
        return try {
            val reqBuilder = Request.Builder()
                .url("https://www.instagram.com/$username/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

            if (!sessionCookies.isNullOrBlank()) reqBuilder.header("Cookie", sessionCookies)

            http.newCall(reqBuilder.build()).execute().use { response ->
                val html = response.body?.string() ?: ""
                val regexes = listOf(
                    Regex("\"profile_owner\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE),
                    Regex("\"target_user_id\"\\s*:\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE),
                    Regex("\"profile_id\"\\s*:\\s*\"([1-9]\\d+)\"", RegexOption.IGNORE_CASE),
                    Regex("\"user_id\"\\s*:\\s*\"([1-9]\\d{5,})\"", RegexOption.IGNORE_CASE),
                    Regex("\"pk\"\\s*:\\s*\"([1-9]\\d{5,})\"", RegexOption.IGNORE_CASE),
                    Regex("\"id\"\\s*:\\s*\"([1-9]\\d{5,})\"", RegexOption.IGNORE_CASE)
                )
                for (reg in regexes) {
                    val match = reg.find(html)
                    if (match != null && match.groupValues.size > 1) {
                        return match.groupValues[1]
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractAvFromCookies(sessionCookies: String?): String {
        if (sessionCookies.isNullOrBlank()) return "0"
        val rur = extractCookie(sessionCookies, "rur")
        if (!rur.isNullOrBlank()) {
            val decoded = try { java.net.URLDecoder.decode(rur, "UTF-8") } catch (e: Exception) { rur }
            val parts = decoded.split(",", "\\054")
            if (parts.size >= 2 && parts[1].all { it.isDigit() } && parts[1].isNotBlank()) {
                return parts[1]
            }
        }
        return extractCookie(sessionCookies, "ds_user_id") ?: "0"
    }

    private fun buildCookieHeader(setCookies: List<String>): String =
        joinCookies(parseSetCookies(setCookies))

    /**
     * Reduces `Set-Cookie` headers (or the pairs of an existing `Cookie` header) to name → value,
     * so two responses can be merged instead of one replacing the other wholesale.
     */
    private fun parseSetCookies(setCookies: List<String>): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        for (header in setCookies) {
            val parts = header.split(";")[0].split("=", limit = 2)
            if (parts.size >= 2 && parts[0].isNotBlank()) {
                cookies[parts[0].trim()] = parts[1].trim()
            }
        }
        return cookies
    }

    private fun joinCookies(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * The value to send as `X-IG-WWW-Claim` on an authenticated request. Instagram issues a
     * rotating claim token via `X-Ig-Set-WWW-Claim` once a session is established and expects it
     * echoed back on every subsequent write; a client that always sends the pre-claim default
     * "0" looks anomalous to Instagram's abuse detection, and the session gets quietly treated as
     * logged-out server-side even though `sessionid` itself never expired. A real browser (and
     * therefore the in-app WebView) handles this automatically, which is why a session can look
     * signed-in there while this client's direct calls start getting rejected.
     */
    private fun extractWwwClaim(sessionCookies: String): String =
        extractCookie(sessionCookies, WWW_CLAIM_KEY)?.ifBlank { null } ?: "0"

    /**
     * Folds any `Set-Cookie` headers — and a rotated WWW-Claim token, if this response issued one
     * — from an action response into [original]'s jar. Returns null when nothing actually changed
     * from what [original] already had, so callers are not tempted to write back an "updated"
     * value that is byte-for-byte the same as before on every single request.
     */
    private fun mergeSetCookiesAndClaim(original: String, response: Response): String? {
        val claim = response.header("X-Ig-Set-WWW-Claim")?.trim()
        val originalMap = parseSetCookies(original.split(";"))
        var merged = originalMap + parseSetCookies(response.headers("Set-Cookie"))
        if (!claim.isNullOrBlank()) merged = merged + (WWW_CLAIM_KEY to claim)
        return if (merged != originalMap) joinCookies(merged) else null
    }

    private fun extractJsonValue(text: String, key: String): String? {
        val pattern = Pattern.compile("\"$key\"\\s*:\\s*\"?([^\",\\}]+)\"?", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun parseFormattedCount(str: String): Long {
        val clean = str.trim().uppercase()
        return try {
            when {
                clean.endsWith("M") -> (clean.removeSuffix("M").replace(",", "").toDouble() * 1_000_000).toLong()
                clean.endsWith("K") -> (clean.removeSuffix("K").replace(",", "").toDouble() * 1_000).toLong()
                clean.endsWith("B") -> (clean.removeSuffix("B").replace(",", "").toDouble() * 1_000_000_000).toLong()
                else -> clean.replace(",", "").replace(".", "").toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    data class EmailSignupSessionTokens(
        val csrfToken: String,
        val lsdToken: String,
        val cookieHeader: String
    )

    suspend fun fetchCsrfTokensFromEmailSignup(): EmailSignupSessionTokens = withContext(Dispatchers.IO) {
        try {
            val pageRequest = Request.Builder()
                .url("https://www.instagram.com/accounts/emailsignup/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Upgrade-Insecure-Requests", "1")
                .get()
                .build()

            http.newCall(pageRequest).execute().use { response ->
                val setCookies = response.headers.values("Set-Cookie")
                var extractedCsrf: String? = null
                val cookiePairs = mutableListOf<String>()

                for (cookie in setCookies) {
                    val firstPart = cookie.split(";").firstOrNull()?.trim() ?: continue
                    if (firstPart.isNotEmpty()) {
                        cookiePairs.add(firstPart)
                    }
                    if (firstPart.startsWith("csrftoken=")) {
                        extractedCsrf = firstPart.removePrefix("csrftoken=")
                    }
                }

                val bodyText = response.body?.string() ?: ""

                if (extractedCsrf.isNullOrBlank()) {
                    val csrfRegex = Regex("\"csrf_token\":\"([^\"]+)\"|\"csrftoken\",\"([^\"]+)\"")
                    extractedCsrf = csrfRegex.find(bodyText)?.groupValues?.filter { it.isNotBlank() }?.getOrNull(1)
                }

                val lsdRegex = Regex("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}|name=\"lsd\" value=\"([^\"]+)\"|\"lsd\":\"([^\"]+)\"")
                val extractedLsd = lsdRegex.find(bodyText)?.groupValues?.filter { it.isNotBlank() }?.getOrNull(1) ?: "AdSnZGvwlV6GToRX7hoFiECzBUA"

                val finalCsrf = extractedCsrf ?: "P9AvEbtlSg8UE-D56g7jIa"
                if (!cookiePairs.any { it.startsWith("csrftoken=") }) {
                    cookiePairs.add("csrftoken=$finalCsrf")
                }
                if (!cookiePairs.any { it.startsWith("wd=") }) {
                    cookiePairs.add("wd=1517x665")
                }

                EmailSignupSessionTokens(
                    csrfToken = finalCsrf,
                    lsdToken = extractedLsd,
                    cookieHeader = cookiePairs.joinToString("; ")
                )
            }
        } catch (e: Exception) {
            Log.e("InstagramWebClient", "Failed to fetch email signup CSRF tokens: ${e.message}")
            EmailSignupSessionTokens(
                csrfToken = "P9AvEbtlSg8UE-D56g7jIa",
                lsdToken = "AdSnZGvwlV6GToRX7hoFiECzBUA",
                cookieHeader = "csrftoken=P9AvEbtlSg8UE-D56g7jIa; datr=rU94ajjSyPr-75ur20iOvzVd; ig_did=26B533E5-AD3C-4652-B3DB-D52E57E52B53; mid=anhPrQALAAEUja1zX_Bdok3vRQ0v; wd=1517x665; dpr=0.8999999761581421"
            )
        }
    }

    suspend fun fetchUsernameSuggestions(username: String): UsernameValidationResult = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().removePrefix("@")
        if (cleanUsername.isBlank()) return@withContext UsernameValidationResult(available = false, suggestions = emptyList(), error = "Username cannot be empty")

        val suggestionsList = mutableListOf<String>()
        var isAvailable = false
        var apiError: String? = null

        try {
            // Dynamically fetch fresh CSRF token, LSD token, and cookies from https://www.instagram.com/accounts/emailsignup/
            val sessionTokens = fetchCsrfTokensFromEmailSignup()

            // Slice input username by 2-3 characters for prefix suggestion seed matching
            val slicedSeed = when {
                cleanUsername.length >= 3 -> cleanUsername.take(3)
                cleanUsername.length == 2 -> cleanUsername.take(2)
                else -> cleanUsername
            }

            val variablesJson = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("fetch_username_suggestions", true)
                    put("field_name", "USERNAME")
                    put("username", JSONObject().put("sensitive_string_value", slicedSeed))
                })
                put("scale", 1)
            }.toString()

            val formBody = FormBody.Builder()
                .add("av", "0")
                .add("__d", "www")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "1a")
                .add("__hs", "20674.HYP:instagram_web_pkg.2.1...0")
                .add("dpr", "1")
                .add("__ccg", "MODERATE")
                .add("__rev", "1044809023")
                .add("__comet_req", "7")
                .add("lsd", sessionTokens.lsdToken)
                .add("jazoest", "22359")
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "useCAARegistrationFieldValidationQuery")
                .add("server_timestamps", "true")
                .add("variables", variablesJson)
                .add("doc_id", "26387190147557007")
                .add("qpl_active_flow_ids", "516759801")
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-FB-LSD", sessionTokens.lsdToken)
                .header("X-CSRFToken", sessionTokens.csrfToken)
                .header("X-ASBD-ID", "359341")
                .header("X-FB-Friendly-Name", "useCAARegistrationFieldValidationQuery")
                .header("Accept", "*/*")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/accounts/emailsignup/?next=")
                .header("Cookie", sessionTokens.cookieHeader)
                .post(formBody)
                .build()

            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (response.isSuccessful && bodyText.isNotBlank()) {
                    runCatching {
                        val json = JSONObject(bodyText)
                        val data = json.optJSONObject("data")
                        val validation = data?.optJSONObject("caa_registration_field_validation")
                        if (validation != null) {
                            isAvailable = validation.optBoolean("is_valid", false)
                            val suggestionsArray = validation.optJSONArray("suggestions")
                            if (suggestionsArray != null) {
                                for (i in 0 until suggestionsArray.length()) {
                                    val s = suggestionsArray.optString(i)
                                    if (!s.isNullOrBlank()) suggestionsList.add(s)
                                }
                            }
                        }
                    }
                } else {
                    apiError = "HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            apiError = e.message
        }

        // If live Instagram API returned no suggestions or failed, generate formula-based suggestions for the username
        if (suggestionsList.isEmpty()) {
            val fallbackSuggestions = com.feedpilot.client.common.BrazilUsernameGenerator.generateSuggestionsForSeed(cleanUsername)
            suggestionsList.addAll(fallbackSuggestions)
        }

        val cleanSeed = cleanUsername.trim().lowercase().removePrefix("@")
        val uniqueSuggestions = suggestionsList
            .map { it.trim().lowercase().removePrefix("@") }
            .filter { it.isNotBlank() && it != cleanSeed }
            .distinct()

        UsernameValidationResult(
            available = isAvailable || uniqueSuggestions.isNotEmpty(),
            suggestions = uniqueSuggestions,
            error = if (uniqueSuggestions.isNotEmpty()) null else apiError
        )
    }

    suspend fun pickUsername(username: String, deviceId: String, appId: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().removePrefix("@")
        if (cleanUsername.isBlank()) return@withContext false
        try {
            val json = JSONObject().apply {
                put("username", cleanUsername)
                put("deviceId", deviceId)
                put("appId", appId)
            }
            val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val baseUrl = com.feedpilot.client.BuildConfig.API_BASE_URL.trimEnd('/') + "/"
            val path = "/api/picked-usernames/pick"
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val request = Request.Builder()
                .url("$baseUrl${path.removePrefix("/")}")
                .header("X-Request-Timestamp", timestamp)
                .header("X-Request-Signature", signBackendRequest("POST", path, timestamp))
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "pickUsername failed: HTTP ${response.code} ${responseBody.take(300)}")
                }
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.e(TAG, "pickUsername failed", t)
            false
        }
    }

    suspend fun getPickedUsernames(deviceId: String, appId: String, page: Int, pageSize: Int): com.feedpilot.client.data.remote.dto.PagedPickedUsernamesDto? = withContext(Dispatchers.IO) {
        try {
            val encodedDevId = java.net.URLEncoder.encode(deviceId, "UTF-8")
            val encodedAppId = java.net.URLEncoder.encode(appId, "UTF-8")
            val baseUrl = com.feedpilot.client.BuildConfig.API_BASE_URL.trimEnd('/') + "/"
            val path = "/api/picked-usernames"
            val queryPath = "$path?deviceId=$encodedDevId&appId=$encodedAppId&page=$page&pageSize=$pageSize"
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val request = Request.Builder()
                .url("$baseUrl${queryPath.removePrefix("/")}")
                .header("X-Request-Timestamp", timestamp)
                .header("X-Request-Signature", signBackendRequest("GET", path, timestamp))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: return@withContext null
                    val json = org.json.JSONObject(bodyStr)
                    val itemsArray = json.optJSONArray("items") ?: json.optJSONArray("Items") ?: org.json.JSONArray()
                    val items = mutableListOf<com.feedpilot.client.data.remote.dto.PickedUsernameDto>()
                    for (i in 0 until itemsArray.length()) {
                        val item = itemsArray.getJSONObject(i)
                        val uName = item.optString("username").takeIf { it.isNotBlank() } ?: item.optString("Username")
                        val pAt = item.optString("pickedAt").takeIf { it.isNotBlank() } ?: item.optString("PickedAt")
                        items.add(
                            com.feedpilot.client.data.remote.dto.PickedUsernameDto(
                                username = uName,
                                pickedAt = pAt
                            )
                        )
                    }
                    val total = json.optInt("totalCount", json.optInt("TotalCount", 0))
                    val pg = json.optInt("page", json.optInt("Page", 1))
                    val pgSize = json.optInt("pageSize", json.optInt("PageSize", 5))
                    com.feedpilot.client.data.remote.dto.PagedPickedUsernamesDto(
                        items = items,
                        totalCount = total,
                        page = pg,
                        pageSize = pgSize
                    )
                } else {
                    Log.w("InstagramWebClient", "getPickedUsernames failed: HTTP ${response.code} ${response.body?.string()?.take(300).orEmpty()}")
                    null
                }
            }
        } catch (t: Throwable) {
            Log.e("InstagramWebClient", "getPickedUsernames failed", t)
            null
        }
    }

    suspend fun deletePickedUsername(username: String, deviceId: String, appId: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().removePrefix("@")
        if (cleanUsername.isBlank()) return@withContext false
        try {
            val encodedDevId = java.net.URLEncoder.encode(deviceId, "UTF-8")
            val encodedAppId = java.net.URLEncoder.encode(appId, "UTF-8")
            val baseUrl = com.feedpilot.client.BuildConfig.API_BASE_URL.trimEnd('/') + "/"
            val path = "/api/picked-usernames/$cleanUsername"
            val queryPath = "$path?deviceId=$encodedDevId&appId=$encodedAppId"
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val request = Request.Builder()
                .url("$baseUrl${queryPath.removePrefix("/")}")
                .header("X-Request-Timestamp", timestamp)
                .header("X-Request-Signature", signBackendRequest("DELETE", path, timestamp))
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.e("InstagramWebClient", "deletePickedUsername failed", t)
            false
        }
    }



    /**
     * Signs a request the same way [DeviceSigningInterceptor] does for the app's main API client.
     * [client] here is the `@InstagramClient` instance, which deliberately carries no signing
     * interceptor since most of its traffic goes straight to instagram.com — calls that instead
     * target our own backend (like [pickUsername]) have to sign themselves or the backend's
     * RequestSigningMiddleware rejects them with "Invalid request signature."
     */
    private fun signBackendRequest(method: String, path: String, timestamp: String): String {
        val payload = "$method:$path:$timestamp"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(com.feedpilot.client.BuildConfig.REQUEST_SIGNING_SECRET.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private companion object {
        const val TAG = "InstagramWebClient"

        /** doc_id of the web client's PolarisAPILikePostMutation (captured from the live site). */
        const val LIKE_MUTATION_DOC_ID = "27358573637160660"

        /** doc_id of usePolarisCreateMediaRepostMutation (captured from the live site). */
        const val REPOST_MUTATION_DOC_ID = "27683770241233071"

        /** doc_id of usePolarisSaveMediaSaveMutation (captured from the live site). */
        const val SAVE_MUTATION_DOC_ID = "27326721586982367"

        /** query_hash the web client uses to page a post's comments. */
        const val COMMENTS_QUERY_HASH = "33ba35852cb50da46f5b5e889df7d159"

        /** Bloks bundle id the web client reports on GraphQL writes (from the live site). */
        const val BLOKS_VERSION_ID =
            "bfecd6361a52173ff4ec1f7f511e6f707e413054b01fc6e675391eb9d9a8f461"

        /**
         * Synthetic key the WWW-Claim token rides under inside the opaque cookie/session blob
         * (see [extractWwwClaim]/[mergeSetCookiesAndClaim]). Not a real Instagram cookie name —
         * Instagram's cookie parser silently ignores unrecognized names, so folding it into the
         * same string that's already persisted and synced as `SessionData` is safe and needs no
         * extra storage.
         */
        const val WWW_CLAIM_KEY = "__igwwwclaim"

        /** Matches the LSD token Instagram embeds in its page HTML script tags. */
        val LSD_TOKEN_REGEX = Regex(""""LSD"\s*,\s*\[.*?\]\s*,\s*\{\s*"token"\s*:\s*"([^"]+)"""")
        val LSD_TOKEN_FALLBACK_REGEX = Regex(""""LSD"\s*:?\s*,\s*\{\s*"token"\s*:\s*"([^"]+)"""")
        const val DEFAULT_LSD_TOKEN = "AdRDeaOf3ijEZn0sa3wXaPAZQ_Y"

    }
}

data class UsernameValidationResult(
    val available: Boolean,
    val suggestions: List<String>,
    val error: String? = null
)

