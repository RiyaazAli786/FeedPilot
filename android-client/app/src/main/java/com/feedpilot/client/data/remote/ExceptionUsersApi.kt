package com.feedpilot.client.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.*
import java.util.concurrent.TimeUnit

@Serializable
data class ExceptionUsersPage(
    val success: Boolean,
    val users: List<String>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pages: Int
)

@Serializable
data class ExceptionUsername(val username: String)

@Serializable
data class ExceptionUserResult(
    val success: Boolean,
    val username: String,
    val added: Boolean? = null,
    val deleted: Boolean? = null
)

interface ExceptionUsersApi {
    // Never send `username` on this GET: GetIGFeed treats that as registration.
    @GET("api/exception-users")
    suspend fun list(
        @Query("search") search: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int = 25
    ): ExceptionUsersPage

    @POST("api/exception-users")
    suspend fun add(@Body user: ExceptionUsername): ExceptionUserResult

    @PUT("api/exception-users/{username}")
    suspend fun update(@Path("username") username: String, @Body user: ExceptionUsername): ExceptionUserResult

    @DELETE("api/exception-users/{username}")
    suspend fun remove(@Path("username") username: String): ExceptionUserResult
}

internal fun createExceptionUsersApi(
    json: Json,
    baseUrl: String = "https://feed.reelsflow.io/"
): ExceptionUsersApi = Retrofit.Builder()
    .baseUrl(baseUrl)
    // This public service must not receive FeedPilot JWTs, device signatures or IG cookies.
    .client(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build())
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()
    .create(ExceptionUsersApi::class.java)
