package com.feedpilot.client.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeConnectionState {
    Starting, Online, Offline, RunningJob, Finished, SendingResult, ResultReturned, ResultFailed, JobFailed, Error
}

data class BridgeStatus(
    val state: BridgeConnectionState = BridgeConnectionState.Starting,
    val isOnline: Boolean = false,
    val message: String = "Bridge starting",
    val currentUsername: String? = null,
    val jobId: String? = null
) {
    val pillLabel: String get() = when (state) {
        BridgeConnectionState.Starting -> "Bridge starting"
        BridgeConnectionState.Online -> "Bridge mode on"
        BridgeConnectionState.Offline -> "Bridge offline"
        BridgeConnectionState.RunningJob -> currentUsername?.let { "Running: @$it" } ?: "Bridge job running"
        BridgeConnectionState.Finished -> "Bridge job finished"
        BridgeConnectionState.SendingResult -> "Finished · returning result"
        BridgeConnectionState.ResultReturned -> "Bridge result returned"
        BridgeConnectionState.ResultFailed -> "Bridge result failed"
        BridgeConnectionState.JobFailed -> "Bridge job failed"
        BridgeConnectionState.Error -> "Bridge needs attention"
    }
}

/** Retain the last outcome briefly; heartbeats must not erase an in-progress job. */
open class BridgeStatusTracker(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    private var showOutcomeUntil = 0L

    @Synchronized fun starting() { _status.value = BridgeStatus() }

    @Synchronized fun offline(message: String = "No internet connection") {
        _status.value = BridgeStatus(BridgeConnectionState.Offline, false, message)
    }

    @Synchronized fun online(message: String = "Waiting for bridge requests") {
        if (_status.value.state in setOf(BridgeConnectionState.RunningJob,
                BridgeConnectionState.Finished, BridgeConnectionState.SendingResult) ||
            now() < showOutcomeUntil) return
        _status.value = BridgeStatus(BridgeConnectionState.Online, true, message)
    }

    @Synchronized fun resolving(username: String?, jobId: String? = null) {
        showOutcomeUntil = 0L
        val label = username?.takeIf { it.isNotBlank() }
        _status.value = BridgeStatus(BridgeConnectionState.RunningJob, true,
            label?.let { "Collecting feed for @$it" } ?: "Collecting bridge feed", label, jobId)
    }

    @Synchronized fun finished(postCount: Int) {
        _status.value = _status.value.copy(state = BridgeConnectionState.Finished,
            message = "Feed collection finished: $postCount posts.")
    }

    @Synchronized fun sendingResult() {
        _status.value = _status.value.copy(state = BridgeConnectionState.SendingResult,
            message = "Feed collection finished. Returning the result to FeedPilot.")
    }

    @Synchronized fun resultReturned() {
        showOutcomeUntil = now() + 4_000
        _status.value = _status.value.copy(state = BridgeConnectionState.ResultReturned,
            isOnline = true, message = "FeedPilot accepted the returned feed result.")
    }

    @Synchronized fun jobFailed(message: String) {
        showOutcomeUntil = now() + 4_000
        _status.value = _status.value.copy(state = BridgeConnectionState.JobFailed,
            isOnline = true, message = message)
    }

    @Synchronized fun resultFailed(message: String) {
        showOutcomeUntil = now() + 4_000
        _status.value = _status.value.copy(state = BridgeConnectionState.ResultFailed,
            isOnline = true, message = message)
    }

    @Synchronized fun error(message: String) {
        showOutcomeUntil = 0L
        _status.value = BridgeStatus(BridgeConnectionState.Error, false, message)
    }
}

object BridgeStatusStore : BridgeStatusTracker()
