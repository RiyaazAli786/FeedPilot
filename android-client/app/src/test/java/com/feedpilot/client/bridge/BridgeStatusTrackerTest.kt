package com.feedpilot.client.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgeStatusTrackerTest {
    @Test fun showsJobAndAcknowledgedResultBeforeReturningToIdle() {
        var now = 0L
        val tracker = BridgeStatusTracker { now }
        tracker.online()
        assertEquals("Bridge mode on", tracker.status.value.pillLabel)
        tracker.resolving("target", "job-123")
        tracker.online()
        assertEquals("Running: @target", tracker.status.value.pillLabel)
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
        assertEquals("Running: @second", tracker.status.value.pillLabel)
        assertEquals("two", tracker.status.value.jobId)
    }
}
