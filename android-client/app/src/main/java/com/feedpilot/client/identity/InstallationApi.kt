package com.feedpilot.client.identity

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Foundational-phase installation identity endpoints (see IdentityIsolation.md). Distinct from
 * [com.feedpilot.client.data.remote.ApiService]'s existing device/auth endpoints — this is a new,
 * additive identity vertical, not a replacement for them.
 */
interface InstallationApi {
    @POST("api/installations/register")
    suspend fun register(@Body body: InstallationRegisterRequest): InstallationDto

    @POST("api/installations/challenge")
    suspend fun challenge(@Body body: InstallationChallengeRequest): InstallationChallengeDto

    @POST("api/installations/verify")
    suspend fun verify(@Body body: InstallationVerifyRequest): InstallationVerifyResultDto
}

@Serializable
data class InstallationRegisterRequest(val installationId: String, val publicKey: String)

@Serializable
data class InstallationDto(val installationId: String, val status: String, val createdAt: String)

@Serializable
data class InstallationChallengeRequest(val installationId: String)

@Serializable
data class InstallationChallengeDto(val challenge: String, val expiresAt: String)

@Serializable
data class InstallationVerifyRequest(val installationId: String, val challenge: String, val signature: String)

@Serializable
data class InstallationVerifyResultDto(val valid: Boolean)
