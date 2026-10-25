package com.feedpilot.client.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeConnectionState {
    Starting, Disabled, Online, Offline, RunningJob, Finished, SendingResult, ResultReturned, ResultFailed, JobFailed, Error
}

data class BridgeStatus(
    val state: BridgeConnectionState = BridgeConnectionState.Starting,
    val isOnline: Boolean = false,
    val message: String = "Bridge starting",
    val currentUsername: String? = null,
    val jobId: String? = null,
    val accountUsername: String? = null
) {
    val pillLabel: String get() = when (state) {
        BridgeConnectionState.Disabled -> "Bridge mode off"
        BridgeConnectionState.Starting -> "Bridge starting"
        BridgeConnectionState.Online -> "Bridge mode on"
        BridgeConnectionState.Offline -> "Bridge offline"
        BridgeConnectionState.RunningJob -> {
            val target = currentUsername?.removePrefix("@")?.takeIf { it.isNotBlank() }
            val account = accountUsername?.removePrefix("@")?.takeIf { it.isNotBlank() }
            if (target != null && account != null) {
                "Fetching: @$target with @$account"
            } else if (target != null) {
                "Fetching: @$target"
            } else if (account != null) {
                "Fetching with @$account"
            } else {
                "Bridge job running"
            }
        }
        BridgeConnectionState.Finished -> "Bridge job finished"
        BridgeConnectionState.SendingResult -> "Finished · returning result"
        BridgeConnectionState.ResultReturned -> "Bridge result returned"
        BridgeConnectionState.ResultFailed -> "Bridge result failed"
        BridgeConnectionState.JobFailed -> "Bridge job failed"
        BridgeConnectionState.Error -> "Bridge needs attention"
    } + if (accountUsername != null && state in setOf(BridgeConnectionState.Finished, BridgeConnectionState.SendingResult,
            BridgeConnectionState.ResultReturned, BridgeConnectionState.ResultFailed, BridgeConnectionState.JobFailed))
        " · @$accountUsername" else ""
}

/** Retain the last outcome briefly; heartbeats must not erase an in-progress job. */
open class BridgeStatusTracker(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    private var showOutcomeUntil = 0L
    private var enabled = true

    private fun publish(status: BridgeStatus) {
        _status.value = status
        BridgeLogStore.record(status)
    }

    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        showOutcomeUntil = 0L
        if (!value) publish(BridgeStatus(BridgeConnectionState.Disabled, false,
            "Bridge mode is disabled. This device will not accept bridge feed requests."))
        else publish(BridgeStatus())
    }

    @Synchronized fun starting() { if (enabled) publish(BridgeStatus()) }

    @Synchronized fun offline(message: String = "No internet connection") {
        if (!enabled) return
        publish(BridgeStatus(BridgeConnectionState.Offline, false, message))
    }

    @Synchronized fun online(message: String = "Waiting for bridge requests") {
        if (!enabled) return
        if (_status.value.state in setOf(BridgeConnectionState.RunningJob,
                BridgeConnectionState.Finished, BridgeConnectionState.SendingResult) ||
            now() < showOutcomeUntil) return
        publish(BridgeStatus(BridgeConnectionState.Online, true, message))
    }

    @Synchronized fun resolving(username: String?, jobId: String? = null, accountUsername: String? = null) {
        if (!enabled) return
        showOutcomeUntil = 0L
        val label = username?.takeIf { it.isNotBlank() }
        publish(BridgeStatus(BridgeConnectionState.RunningJob, true,
            label?.let { "Collecting feed for @$it" } ?: "Collecting bridge feed", label, jobId, accountUsername))
    }

    @Synchronized fun finished(postCount: Int) {
        if (!enabled) return
        publish(_status.value.copy(state = BridgeConnectionState.Finished,
            message = "Feed collection finished: $postCount posts."))
    }

    @Synchronized fun sendingResult() {
        if (!enabled) return
        publish(_status.value.copy(state = BridgeConnectionState.SendingResult,
            message = "Feed collection finished. Returning the result to FeedPilot."))
    }

    @Synchronized fun resultReturned(
        postCount: Int? = null,
        followerCount: Long? = null,
        followingCount: Long? = null,
        message: String? = null
    ) {
        if (!enabled) return
        showOutcomeUntil = now() + 4_000
        val target = _status.value.currentUsername?.removePrefix("@")?.takeIf { it.isNotBlank() }
        val targetPrefix = if (target != null) "Fetched @$target successfully" else "Fetched successfully"
        val counts = buildList {
            if (postCount != null) add("$postCount " + if (postCount == 1) "post" else "posts")
            if (followerCount != null) add("$followerCount " + if (followerCount == 1L) "follower" else "followers")
            if (followingCount != null) add("$followingCount following")
        }
        val detailMsg = if (message != null) {
            message
        } else if (counts.isNotEmpty()) {
            "$targetPrefix with ${counts.joinToString(", ")}. FeedPilot accepted the returned feed result."
        } else {
            "FeedPilot accepted the returned feed result."
        }
        publish(_status.value.copy(
            state = BridgeConnectionState.ResultReturned,
            isOnline = true,
            message = detailMsg
        ))
    }

    @Synchronized fun jobFailed(message: String) {
        if (!enabled) return
        showOutcomeUntil = now() + 4_000
        publish(_status.value.copy(state = BridgeConnectionState.JobFailed,
            isOnline = true, message = message))
    }

    @Synchronized fun resultFailed(message: String) {
        if (!enabled) return
        showOutcomeUntil = now() + 4_000
        publish(_status.value.copy(state = BridgeConnectionState.ResultFailed,
            isOnline = true, message = message))
    }

    @Synchronized fun error(message: String) {
        if (!enabled) return
        showOutcomeUntil = 0L
        publish(BridgeStatus(BridgeConnectionState.Error, false, message))
    }
}

object BridgeStatusStore : BridgeStatusTracker()
