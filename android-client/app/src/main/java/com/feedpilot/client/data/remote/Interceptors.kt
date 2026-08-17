package com.feedpilot.client.data.remote

import com.feedpilot.client.BuildConfig
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/** Attaches the bearer token (when present) to every request. */
class AuthInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = secureStorage.get(SecureStorage.KEY_ACCESS_TOKEN)
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            // header() replaces rather than appends: when the TokenAuthenticator retries a
            // request after refreshing, this must overwrite the stale token, not add a second
            // Authorization header (which the backend would reject).
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}

/**
 * Adds device identity headers and a request signature.
 *
 * The signature is HMAC-SHA256(secret, "method:path:timestamp") using the secret shared with
 * the backend's RequestSigning:Secret (see RequestSigningMiddleware there) — proof the call came
 * from this app, not a replayed token hitting the API directly with curl/Postman. The timestamp
 * rides alongside as its own header so the backend can bound how long a captured request stays
 * replayable; it is deliberately part of the signed payload too; so it can't be swapped for a
 * fresher one without also breaking the signature.
 *
 * The previous version of this signed with the installation id instead of a secret — a value
 * that rides in its own plain header on the very same request, so anyone could recompute a
 * "valid" signature themselves. It was never actually checked server-side, so this was always
 * decorative; it now does the job the header name always implied.
 */
class DeviceSigningInterceptor @Inject constructor(
    private val deviceIdentity: DeviceIdentity
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = sign(original.method, original.url.encodedPath, timestamp)
        val request = original.newBuilder()
            // Official FeedPilot app id compiled into the APK.
            .addHeader("X-App-Id", deviceIdentity.appId)
            // Stable official app/device id. This must match the order-claim id.
            .addHeader("X-Device-Id", deviceIdentity.stableAppInstallationId)
            // Hardware-bound Widevine anchor for backend physical device tracking across reinstalls
            .addHeader("X-Hardware-Id", deviceIdentity.hardwareDeviceId)
            // Human-readable manufacturer + model, for logs/dashboards — the id above is an
            // opaque hash and meaningless to read at a glance.
            .addHeader("X-Device-Model", deviceIdentity.deviceLabel)
            // Random per install: still handy to spot reinstalls of the same device apart.
            .addHeader("X-Installation-Id", deviceIdentity.installationId)
            .addHeader("X-App-Instance-Id", deviceIdentity.appInstanceId)
            .addHeader("X-Request-Timestamp", timestamp)
            .addHeader("X-Request-Signature", signature)
            .build()
        return chain.proceed(request)
    }

    private fun sign(method: String, path: String, timestamp: String): String {
        val payload = "$method:$path:$timestamp"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.REQUEST_SIGNING_SECRET.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
