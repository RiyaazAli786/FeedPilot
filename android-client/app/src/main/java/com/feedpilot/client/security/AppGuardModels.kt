package com.feedpilot.client.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppGuardConfig(
    @SerialName("destructive") val destructive: Boolean = false,
    @SerialName("tgurl") val tgurl: String? = null
)

data class AppGuardState(
    val isDestructive: Boolean = false,
    val tgUrl: String = DEFAULT_TG_URL,
    val isChecking: Boolean = false
) {
    companion object {
        const val DEFAULT_TG_URL = "https://t.me/+0RumB_V8jRMxODc5"
    }
}
