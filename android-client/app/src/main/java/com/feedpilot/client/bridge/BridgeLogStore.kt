package com.feedpilot.client.bridge

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeLogEntry(val timestamp: Long, val status: BridgeStatus) {
    fun text(): String = "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))} | ${status.state} | account=${status.accountUsername.orEmpty()} | target=${status.currentUsername.orEmpty()} | job=${status.jobId.orEmpty()} | ${status.message}"
}

/** Device-local history, bounded to the latest 1,000 state changes. Never stores sessions or feeds. */
class BridgeLogHistory(private val now: () -> Long = System::currentTimeMillis) {
    private val mutableEntries = MutableStateFlow<List<BridgeLogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private var file: File? = null
    private val writes = Channel<Pair<File, List<BridgeLogEntry>>>(Channel.CONFLATED)
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for ((destination, snapshot) in writes) runCatching {
                val temporary = File(destination.path + ".tmp")
                temporary.writeText(snapshot.joinToString("\n") { encode(it) })
                if (!temporary.renameTo(destination)) {
                    destination.writeText(temporary.readText())
                    temporary.delete()
                }
            }
        }
    }
    @Synchronized fun initialize(destination: File) {
        if (file != null) return
        val restored = runCatching { destination.readLines().mapNotNull(::decode) }.getOrDefault(emptyList())
        mutableEntries.value = (mutableEntries.value + restored).take(1000)
        file = destination
    }
    @Synchronized fun record(status: BridgeStatus) {
        if (mutableEntries.value.firstOrNull()?.status == status) return
        mutableEntries.value = (listOf(BridgeLogEntry(now(), status)) + mutableEntries.value).take(1000)
        file?.let { writes.trySend(it to mutableEntries.value) }
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c == '\\' && i < value.length) append(when (val next = value[i++]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> next }) else append(c)
        }
    }
    private fun encode(entry: BridgeLogEntry) = with(entry.status) {
        listOf(entry.timestamp.toString(), state.name, isOnline.toString(), message, currentUsername.orEmpty(), jobId.orEmpty(), accountUsername.orEmpty()).joinToString("\t", transform = ::escape)
    }
    private fun decode(line: String): BridgeLogEntry? = runCatching {
        val parts = line.split('\t').map(::unescape)
        BridgeLogEntry(parts[0].toLong(), BridgeStatus(BridgeConnectionState.valueOf(parts[1]), parts[2].toBoolean(), parts[3], parts[4].ifEmpty { null }, parts[5].ifEmpty { null }, parts[6].ifEmpty { null }))
    }.getOrNull()
}

object BridgeLogStore {
    val history = BridgeLogHistory()
    val entries = history.entries
    fun initialize(file: File) = history.initialize(file)
    fun record(status: BridgeStatus) = history.record(status)
}
