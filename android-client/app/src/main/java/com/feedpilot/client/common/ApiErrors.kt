package com.feedpilot.client.common

import org.json.JSONObject
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pulls the backend's own explanation out of a failed call.
 *
 * Retrofit's [HttpException.message] is only the status line — "HTTP 400 Bad Request" — which
 * tells a user nothing. The API returns `{"message": "...", "code": "..."}`, so surface that
 * instead: "Insufficient balance." or "Minimum withdrawal is 5 coins." are actionable.
 */
fun Throwable.apiErrorMessage(fallback: String = "Something went wrong"): String {
    if (this is UnknownHostException || this is ConnectException || this is SocketTimeoutException || this is SocketException) {
        return "No internet connection. The app will retry automatically when the network is available."
    }
    if (this is HttpException) {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        if (!body.isNullOrBlank()) {
            val parsed = runCatching { JSONObject(body).optString("message") }.getOrNull()
            if (!parsed.isNullOrBlank()) return parsed
        }
        return "$fallback (HTTP ${code()})"
    }
    return localizedMessage ?: fallback
}
