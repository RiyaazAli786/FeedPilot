package com.feedpilot.client.data.remote

import com.feedpilot.client.common.SecureStorage
import com.feedpilot.client.data.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recovers from an expired access token. OkHttp calls this whenever the backend answers 401;
 * we swap the stale token for a fresh one via the refresh token and retry the original request
 * once. If the refresh token is also dead — expired, or the backend was reset — the session is
 * ended so the UI returns to login rather than looping on 401s.
 *
 * Depends on [AuthRepository] through [dagger.Lazy] to break the DI cycle
 * (OkHttpClient → Authenticator → AuthRepository → ApiService → Retrofit → OkHttpClient).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: dagger.Lazy<AuthRepository>
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Never try to refresh the refresh call itself, or an unauthenticated endpoint.
        if (response.request.url.encodedPath.endsWith("/api/auth/refresh")) return null
        // No refresh token means there is nothing to recover with — let the 401 stand.
        if (secureStorage.get(SecureStorage.KEY_REFRESH_TOKEN) == null) return null
        // Give up after one retry so a persistently-401 endpoint cannot loop forever.
        if (responseCount(response) >= 2) return null

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")?.trim()

        synchronized(lock) {
            // A concurrent request may already have refreshed the token; if the stored token
            // has changed since this request went out, just retry with the current one.
            val current = secureStorage.get(SecureStorage.KEY_ACCESS_TOKEN)
            val token = if (!current.isNullOrBlank() && current != failedToken) {
                current
            } else {
                when (val result = runBlocking { authRepository.get().refreshTokensResult() }) {
                    is AuthRepository.RefreshResult.Success -> result.token
                    is AuthRepository.RefreshResult.InvalidSession -> {
                        authRepository.get().onSessionExpired()
                        null
                    }
                    is AuthRepository.RefreshResult.NetworkError -> null
                }
            }

            if (token.isNullOrBlank()) {
                return null
            }

            return response.request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
    }

    /** How many times this request has been attempted, following the prior-response chain. */
    private fun responseCount(response: Response): Int {
        var prior = response.priorResponse
        var count = 1
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        val lock = Any()
    }
}
