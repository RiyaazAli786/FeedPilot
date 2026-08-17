package com.feedpilot.client.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.AuthInterceptor
import com.feedpilot.client.data.remote.DeviceSigningInterceptor
import com.feedpilot.client.data.remote.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the OkHttpClient used for direct Instagram calls, which must NOT carry the backend's
 * auth/device-signing interceptors or token authenticator — those would stamp our backend JWT
 * and request signature onto instagram.com requests (leaking the backend session to a third
 * party) and would fire a backend token refresh on any 401 from Instagram.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InstagramClient

/**
 * Marks the OkHttpClient used for the CoinSyncHub SignalR WebSocket — the general client's
 * `callTimeout(45s)` applies to a WebSocket's whole open-to-close lifetime, not just one REST
 * call, so sharing it force-closed the live-sync connection every 45 seconds regardless of
 * activity and with no ping frames to tell a genuinely dead connection apart from an idle one.
 * That churn — not a code bug in the message handlers themselves — was why live wallet/runner-
 * settings pushes looked unreliable: the socket was constantly reconnecting instead of staying up.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LiveSyncClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceSigningInterceptor: DeviceSigningInterceptor,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(deviceSigningInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            // Recovers expired sessions on 401 by refreshing the token and retrying once.
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // A hard ceiling on the whole call (connect+write+read combined), binding tighter
            // than connectTimeout+readTimeout could otherwise stack (up to 90s) — without this a
            // single hung claim call could silently make a "15s" poll wait look hung for 90s+
            // with no visible difference in the UI.
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A bare client for direct Instagram web-API calls: no backend interceptors, no
     * authenticator. Instagram sees only the request the caller builds — kept off
     * [HttpLoggingInterceptor] entirely since these requests carry enc_password and session
     * cookies and that interceptor logs both sides verbatim to logcat.
     *
     * [telemetry] is a narrower, deliberately safe alternative: it only ever reads the response
     * (never the request, so the Cookie header and enc_password never pass through it) and
     * redacts+truncates before relaying a snippet to the backend for Telegram monitoring. See
     * [com.feedpilot.client.data.remote.InstagramCallTelemetry] for exactly what it does and does
     * not forward.
     */
    @Provides
    @Singleton
    @InstagramClient
    fun provideInstagramOkHttpClient(
        telemetry: com.feedpilot.client.data.remote.InstagramCallTelemetry
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(telemetry)
            .build()

    @Provides
    @Singleton
    @LiveSyncClient
    fun provideLiveSyncOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Deliberately no callTimeout/readTimeout — see LiveSyncClient's doc comment.
            // pingInterval is what keeps this long-lived connection alive through idle
            // stretches and lets OkHttp detect a genuinely dead one (missed pong) instead.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)
}
