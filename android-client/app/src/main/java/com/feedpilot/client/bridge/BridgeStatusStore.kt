package com.feedpilot.client.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeConnectionState {
    Starting,
    Online,
    Offline,
    RunningJob,
    Error
}

data class BridgeStatus(
    val state: BridgeConnectionState = BridgeConnectionState.Starting,
    val isOnline: Boolean = false,
    val message: String = "Bridge starting",
    val currentUsername: String? = null
)

object BridgeStatusStore {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    fun starting() {
        _status.value = BridgeStatus(
            state = BridgeConnectionState.Starting,
            isOnline = false,
            message = "Bridge starting"
        )
    }

    fun offline(message: String = "No internet connection") {
        _status.value = BridgeStatus(
            state = BridgeConnectionState.Offline,
            isOnline = false,
            message = message
        )
    }

    fun online(message: String = "Waiting for bridge requests") {
        _status.value = BridgeStatus(
            state = BridgeConnectionState.Online,
            isOnline = true,
            message = message
        )
    }

    fun resolving(username: String?) {
        val label = username?.takeIf { it.isNotBlank() }
        _status.value = BridgeStatus(
            state = BridgeConnectionState.RunningJob,
            isOnline = true,
            message = label?.let { "Resolving @$it" } ?: "Resolving bridge job",
            currentUsername = label
        )
    }

    fun error(message: String) {
        _status.value = BridgeStatus(
            state = BridgeConnectionState.Error,
            isOnline = false,
            message = message
        )
    }
}
