package com.feedpilot.client.data.remote

import android.util.Log
import com.feedpilot.client.data.remote.dto.InstagramCallLogRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Relays every direct-to-Instagram HTTP call's outcome to the backend (which forwards it to
 * Telegram — see ClientLogController/TelegramRequestLogger.LogClientApiCallAsync server-side).
 *
 * These calls go straight from the device to instagram.com and never touch this app's own
 * backend otherwise, so without this they are invisible anywhere for debugging what a live
 * device actually saw from Instagram.
 *
 * Deliberately logs the RESPONSE only, never the request: the request carries the session
 * `Cookie` header and, for login, `enc_password` — forwarding either would hand out a live
 * Instagram credential to everyone with access to the Telegram chat. The response body is also
 * redacted and size-capped here, before it ever leaves the device, as a defense-in-depth layer on
 * top of the backend's own redaction (see TelegramRequestLogger.RedactAndTruncate).
 */
@Singleton
class InstagramCallTelemetry @Inject constructor(
    private val apiServiceProvider: Provider<ApiService>
) : Interceptor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // peekBody returns an independent buffered copy — the real response body stream, which
        // every caller in InstagramWebClient still needs to read afterwards, is left untouched.
        val snippet = runCatching { response.peekBody(MAX_PEEK_BYTES).string() }
            .getOrNull()
            ?.let(::redactAndTruncate)

        val method = request.method
        val friendlyName = request.header("X-FB-Friendly-Name")
        val actionName = request.header("X-Action-Name")
        val tag = when {
            !actionName.isNullOrBlank() && !friendlyName.isNullOrBlank() -> " [$actionName | $friendlyName]"
            !friendlyName.isNullOrBlank() -> " [$friendlyName]"
            !actionName.isNullOrBlank() -> " [$actionName]"
            else -> ""
        }
        val url = sanitizeUrl(request.url) + tag
        val statusCode = response.code

        // Fire-and-forget: a slow or failed relay must never add latency to, or break, the
        // actual Instagram call this interceptor wraps.
        scope.launch {
            runCatching {
                apiServiceProvider.get().logInstagramCall(
                    InstagramCallLogRequest(method, url, statusCode, snippet)
                )
            }.onFailure { Log.w(TAG, "Failed to relay Instagram call log for $url", it) }
        }

        return response
    }

    private fun redactAndTruncate(body: String): String {
        val redacted = SENSITIVE_FIELD.replace(body) { m -> "\"${m.groupValues[1]}\":\"[redacted]\"" }
        return if (redacted.length > MAX_SNIPPET_LENGTH) redacted.take(MAX_SNIPPET_LENGTH) + "…" else redacted
    }

    private companion object {
        const val TAG = "InstagramCallTelemetry"
        const val MAX_PEEK_BYTES = 4096L
        const val MAX_SNIPPET_LENGTH = 700

        private fun sanitizeUrl(url: okhttp3.HttpUrl): String {
            val builder = url.newBuilder()
            for (i in 0 until url.querySize) {
                val name = url.queryParameterName(i)
                if (SENSITIVE_QUERY_PARAM.matches(name)) {
                    builder.setQueryParameter(name, "[redacted]")
                }
            }
            return builder.build().toString()
        }

        val SENSITIVE_QUERY_PARAM = Regex(
            "(access|refresh)?_?(token|password|secret|api_?key|session_?cookies|sessionid|csrftoken|authorization)",
            RegexOption.IGNORE_CASE
        )

        /** Same field-name pattern as the backend's redactor — keep the two in sync. */
        val SENSITIVE_FIELD = Regex(
            "\"([a-zA-Z_]*(access|refresh)?_?(token|password|secret|api_?key|session_?cookies|sessionid|csrftoken|authorization)[a-zA-Z_]*)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
            RegexOption.IGNORE_CASE
        )
    }
}
