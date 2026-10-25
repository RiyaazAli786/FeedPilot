package com.feedpilot.client.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgeStatusTrackerTest {
    @Test fun fetchingAccountIsVisibleAndRetainedThroughDelivery() {
        val tracker = BridgeStatusTracker { 0L }
        tracker.resolving("target", "job", "fetcher")
        assertEquals("Fetching: @target with @fetcher", tracker.status.value.pillLabel)
        assertEquals("target", tracker.status.value.currentUsername)
        tracker.finished(12)
        tracker.sendingResult()
        tracker.resultReturned(postCount = 12, followerCount = 323000L, followingCount = 0L)
        assertEquals("Fetched @target successfully with 12 posts, 323000 followers, 0 following. FeedPilot accepted the returned feed result.", tracker.status.value.message)
        assertEquals("fetcher", tracker.status.value.accountUsername)
        assertTrue(tracker.status.value.pillLabel.contains("@fetcher"))
    }

    @Test fun disabledModeRejectsLateJobAndHeartbeatStatusUpdates() {
        val tracker = BridgeStatusTracker { 0L }
        tracker.resolving("target", "job", "fetcher")
        tracker.setEnabled(false)
        tracker.online()
        tracker.resolving("another", "new-job", "another-account")
        tracker.finished(12)
        tracker.sendingResult()
        tracker.resultReturned()
        tracker.jobFailed("late failure")
        tracker.error("service stopped")
        assertEquals("Bridge mode off", tracker.status.value.pillLabel)
        assertFalse(tracker.status.value.isOnline)
        assertNull(tracker.status.value.jobId)
        tracker.setEnabled(true)
        tracker.online()
        assertEquals("Bridge mode on", tracker.status.value.pillLabel)
    }
    @Test fun showsJobAndAcknowledgedResultBeforeReturningToIdle() {
        var now = 0L
        val tracker = BridgeStatusTracker { now }
        tracker.online()
        assertEquals("Bridge mode on", tracker.status.value.pillLabel)
        tracker.resolving("target", "job-123")
        tracker.online()
        assertEquals("Fetching: @target", tracker.status.value.pillLabel)
        assertEquals("job-123", tracker.status.value.jobId)
        tracker.finished(12)
        assertEquals("Bridge job finished", tracker.status.value.pillLabel)
        tracker.sendingResult()
        tracker.online()
        assertEquals(BridgeConnectionState.SendingResult, tracker.status.value.state)
        tracker.resultReturned()
        tracker.online()
        assertEquals("Bridge result returned", tracker.status.value.pillLabel)
        now = 4_001
        tracker.online()
        assertEquals("Bridge mode on", tracker.status.value.pillLabel)
        assertNull(tracker.status.value.jobId)
    }

    @Test fun failedDeliveryNeverShowsResultReturnedAndNextJobCanStart() {
        val tracker = BridgeStatusTracker { 0L }
        tracker.resolving("first", "one")
        tracker.finished(12)
        tracker.sendingResult()
        tracker.resultFailed("Backend rejected the result")
        tracker.online()
        assertEquals("Bridge result failed", tracker.status.value.pillLabel)
        tracker.resolving("second", "two")
        assertEquals("Fetching: @second", tracker.status.value.pillLabel)
        assertEquals("two", tracker.status.value.jobId)
    }
}
