package com.feedpilot.client.data.repository

import android.content.Context
import android.util.Log
import com.feedpilot.client.security.AppGuardConfig
import com.feedpilot.client.security.AppGuardState
import com.feedpilot.client.service.TaskRunnerService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppGuardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _guardState = MutableStateFlow(AppGuardState())
    val guardState: StateFlow<AppGuardState> = _guardState.asStateFlow()

    init {
        startGuardMonitoring()
    }

    private fun startGuardMonitoring() {
        scope.launch {
            while (isActive) {
                checkConfig()
                delay(THIRTY_MINUTES_MS)
            }
        }
    }

    suspend fun checkConfig() {
        _guardState.update { it.copy(isChecking = true) }
        try {
            val request = Request.Builder()
                .url(CONFIG_URL)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string()
                if (!bodyString.isNullOrBlank()) {
                    val config = json.decodeFromString<AppGuardConfig>(bodyString)
                    val isDestructive = config.destructive
                    val tgUrl = config.tgurl?.takeIf { it.isNotBlank() } ?: AppGuardState.DEFAULT_TG_URL

                    _guardState.update {
                        it.copy(
                            isDestructive = isDestructive,
                            tgUrl = tgUrl,
                            isChecking = false
                        )
                    }

                    if (isDestructive) {
                        Log.w(TAG, "AppGuard: Destructive flag is true! Stopping all running activity.")
                        TaskRunnerService.stop(context)
                    }
                }
            } else {
                Log.e(TAG, "AppGuard config check failed with HTTP code ${response.code}")
                _guardState.update { it.copy(isChecking = false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AppGuard config fetch failed", e)
            _guardState.update { it.copy(isChecking = false) }
        }
    }

    companion object {
        private const val TAG = "AppGuardRepository"
        const val CONFIG_URL = "https://www.dropbox.com/scl/fi/x1q2lquw0f17ssjlxfbyt/tcfg.json?rlkey=ggoyfasnvcoafnal141wbzydi&st=l2mfxxwx&dl=1"
        private const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
    }
}
