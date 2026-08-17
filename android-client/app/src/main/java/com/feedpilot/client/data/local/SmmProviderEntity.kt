package com.feedpilot.client.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.feedpilot.client.data.remote.HanumanSmmClient
import java.util.UUID

@Entity(tableName = "smm_providers")
data class SmmProviderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val nickname: String,
    val apiUrl: String,
    val apiKey: String,
    val followServiceId: String = HanumanSmmClient.DEFAULT_FOLLOW_SERVICE_ID,
    val likeServiceId: String = HanumanSmmClient.DEFAULT_LIKE_SERVICE_ID,
    val isDefault: Boolean = false
) {
    companion object {
        const val DEFAULT_PROVIDER_ID = "hanuman_smm_default"

        /**
         * The provider orders are placed through when none is configured.
         *
         * Points at the user API (`/api/v2`), which is the only API that accepts
         * `action=add`; the smmorigin admin key is not valid there.
         */
        fun default(): SmmProviderEntity = SmmProviderEntity(
            id = DEFAULT_PROVIDER_ID,
            nickname = "Hanuman SMM Panel",
            apiUrl = HanumanSmmClient.DEFAULT_ORDER_API_URL,
            apiKey = HanumanSmmClient.DEFAULT_ORDER_API_KEY,
            followServiceId = HanumanSmmClient.DEFAULT_FOLLOW_SERVICE_ID,
            likeServiceId = HanumanSmmClient.DEFAULT_LIKE_SERVICE_ID,
            isDefault = true
        )
    }
}
