package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.ApplyReferralRequest
import com.feedpilot.client.data.remote.dto.ApplyReferralResponse
import com.feedpilot.client.data.remote.dto.ReferralStatsDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReferralRepository @Inject constructor(
    private val api: ApiService
) {
    suspend fun getReferralStats(): Resource<ReferralStatsDto> = try {
        Resource.Success(api.getReferralStats())
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    suspend fun applyReferralCode(code: String): Resource<ApplyReferralResponse> = try {
        Resource.Success(api.applyReferralCode(ApplyReferralRequest(code)))
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    private fun readApiError(t: Throwable): String {
        return t.apiErrorMessage()
    }
}
