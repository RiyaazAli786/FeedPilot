package com.feedpilot.client.data.remote

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

import com.feedpilot.client.common.InstagramCrypto

internal object InstagramProfileFeedRequest {
    const val DOC_ID = "29240983615539641"
    const val FRIENDLY_NAME = "PolarisProfilePostsTabContentQuery_connection"
    const val USER_AGENT = InstagramCrypto.DEFAULT_USER_AGENT

    fun tokens(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((field, module) in listOf("lsd" to "LSD", "fb_dtsg" to "DTSGInitialData")) {
            Regex(""""$module"\s*,\s*\[.*?\]\s*,\s*\{\s*"token"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1)?.let { result[field] = it }
        }
        Regex(""""actorID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)?.let { result["av"] = it }
        return result
    }

    fun build(
        username: String,
        cursor: String?,
        cookies: String,
        csrf: String?,
        tokens: Map<String, String>,
        fingerprint: InstagramCrypto.DeviceFingerprint? = null
    ): Request {
        val fp = fingerprint ?: InstagramCrypto.DeviceFingerprint(
            userAgent = InstagramCrypto.DEFAULT_USER_AGENT,
            platform = InstagramCrypto.SEC_CH_UA_PLATFORM,
            platformVersion = InstagramCrypto.SEC_CH_UA_PLATFORM_VERSION,
            model = InstagramCrypto.SEC_CH_UA_MODEL,
            isMobile = InstagramCrypto.SEC_CH_UA_MOBILE
        )
        val variables = JSONObject()
            .put("after", cursor?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("before", JSONObject.NULL)
            .put("data", JSONObject()
                .put("count", 12)
                .put("include_reel_media_seen_timestamp", true)
                .put("include_relationship_info", true)
                .put("latest_besties_reel_media", true)
                .put("latest_reel_media", true))
            .put("first", 12)
            .put("include_multi_captions", true)
            .put("last", JSONObject.NULL)
            .put("username", username)
            .put("__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider", true)
            .put("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider", true)
            .put("__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider", false)
        val form = FormBody.Builder()
            .add("__d", "www").add("__user", "0").add("__a", "1")
            .add("__comet_req", "7")
            .add("__crn", "comet.igweb.PolarisProfilePostsTabRoute")
            .add("fb_api_caller_class", "RelayModern")
            .add("fb_api_req_friendly_name", FRIENDLY_NAME)
            .add("server_timestamps", "true")
            .add("doc_id", DOC_ID)
            .add("variables", variables.toString())
        tokens.forEach { (key, value) -> form.add(key, value) }
        tokens["fb_dtsg"]?.let { token -> form.add("jazoest", "2" + token.sumOf { it.code }) }
        val request = Request.Builder()
            .url("https://www.instagram.com/graphql/query")
            .post(form.build())
            .header("User-Agent", fp.userAgent)
            .header("sec-ch-ua", fp.secChUa)
            .header("sec-ch-ua-platform", fp.platform)
            .header("sec-ch-ua-platform-version", fp.platformVersion)
            .header("sec-ch-ua-model", fp.model)
            .header("sec-ch-ua-mobile", fp.isMobile)
            .header("X-IG-App-ID", InstagramCrypto.INSTAGRAM_APP_ID)
            .header("X-Root-Field-Name", "xdt_api__v1__feed__user_timeline_graphql_connection")
            .header("X-FB-Friendly-Name", FRIENDLY_NAME)
            .header("X-ASBD-ID", InstagramCrypto.ASBD_ID)
            .header("Accept", "*/*")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/$username/")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Accept-Language", InstagramCrypto.ACCEPT_LANGUAGE)
            .header("Cookie", cookies)
        csrf?.takeIf { it.isNotBlank() }?.let { request.header("X-CSRFToken", it) }
        tokens["lsd"]?.let { request.header("X-FB-LSD", it) }
        return request.build()
    }
}
