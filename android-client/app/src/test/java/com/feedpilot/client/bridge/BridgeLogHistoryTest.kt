package com.feedpilot.client.bridge

import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BridgeLogHistoryTest {
    @Test fun clearRemovesSavedHistoryAndAllowsNewEntries() = runBlocking {
        val directory = Files.createTempDirectory("bridge-clear").toFile()
        val file = java.io.File(directory, "history.tsv")
        val history = BridgeLogHistory()
        history.initialize(file)
        val status = BridgeStatus(BridgeConnectionState.Online, true, "Ready")
        history.record(status)
        withTimeout(5000) { while (!file.exists() || file.readText().isEmpty()) delay(10) }
        history.clear()
        assertTrue(history.entries.value.isEmpty())
        withTimeout(5000) { while (file.readText().isNotEmpty()) delay(10) }
        val restored = BridgeLogHistory()
        restored.initialize(file)
        assertTrue(restored.entries.value.isEmpty())
        history.record(status)
        assertEquals(status, history.entries.value.single().status)
        withTimeout(5000) { while (file.readText().isEmpty()) delay(10) }
        directory.deleteRecursively()
        Unit
    }

    @Test fun clearingHistoryPersistsAndAllowsNewEntries() = runBlocking {
        val directory = Files.createTempDirectory("bridge-clear").toFile()
        val file = java.io.File(directory, "history.tsv")
        val history = BridgeLogHistory()
        history.initialize(file)
        val online = BridgeStatus(BridgeConnectionState.Online, true)
        history.record(online)
        history.clear()
        assertTrue(history.entries.value.isEmpty())
        withTimeout(5000) { while (!file.exists() || file.readText().isNotEmpty()) delay(10) }
        val restored = BridgeLogHistory()
        restored.initialize(file)
        assertTrue(restored.entries.value.isEmpty())
        history.record(online)
        assertEquals(1, history.entries.value.size)
        withTimeout(5000) { while (file.readText().isEmpty()) delay(10) }
        directory.deleteRecursively()
        Unit
    }

    @Test fun historyKeepsNewestEntriesAndSuppressesRepeatedHeartbeats() {
        var time = 0L
        val history = BridgeLogHistory { ++time }
        val online = BridgeStatus(BridgeConnectionState.Online, true)
        history.record(online)
        history.record(online)
        assertEquals(1, history.entries.value.size)
        repeat(1005) { history.record(online.copy(message = "Job $it")) }
        assertEquals(1000, history.entries.value.size)
        assertEquals("Job 1004", history.entries.value.first().status.message)
        assertEquals("Job 5", history.entries.value.last().status.message)
    }

    @Test fun historySurvivesRestartWithAccountTargetAndEscapedMessage() = runBlocking {
        val directory = Files.createTempDirectory("bridge-logs").toFile()
        val file = java.io.File(directory, "history.tsv")
        val history = BridgeLogHistory { 123L }
        history.initialize(file)
        val status = BridgeStatus(BridgeConnectionState.JobFailed, true,
            "Failure\nwith\ttab and \\ path", "target", "job-1", "fetcher")
        history.record(status)
        withTimeout(5000) { while (!file.exists()) delay(10) }
        val restored = BridgeLogHistory()
        restored.initialize(file)
        assertEquals(history.entries.value, restored.entries.value)
        assertTrue(restored.entries.value.single().text().contains("account=fetcher"))
        directory.deleteRecursively()
        Unit
    }
}
