package com.feedpilot.client.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CsvAccountImportStats(
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val added: Int = 0,
    val failed: Int = 0,
    val currentUsername: String? = null,
    val message: String? = null
)

@Singleton
class CsvAccountImportState @Inject constructor() {
    private val _stats = MutableStateFlow(CsvAccountImportStats())
    val stats: StateFlow<CsvAccountImportStats> = _stats

    fun update(stats: CsvAccountImportStats) {
        _stats.value = stats
    }

    fun clear() {
        _stats.value = CsvAccountImportStats()
    }
}
