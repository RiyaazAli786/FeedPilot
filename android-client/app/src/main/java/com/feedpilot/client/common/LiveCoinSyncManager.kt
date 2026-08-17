package com.feedpilot.client.common

import android.util.Log
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

import com.feedpilot.client.data.remote.dto.RunnerSettingsDto
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.di.LiveSyncClient
import kotlinx.serialization.json.Json

@Singleton
class LiveCoinSyncManager @Inject constructor(
    private val authRepository: AuthRepository,
    private val walletRepository: WalletRepository,
    private val settingsRepository: SettingsRepository,
    @LiveSyncClient private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private var isConnectingOrConnected = false
    private var reconnectJob: Job? = null
    private val jsonParser = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        scope.launch {
            authRepository.isLoggedIn.collectLatest { loggedIn ->
                if (loggedIn) {
                    val token = authRepository.currentAccessToken()
                    if (!token.isNullOrBlank()) {
                        startSync(token)
                    }
                } else {
                    stopSync()
                }
            }
        }
    }

    @Synchronized
    fun startSync(token: String? = authRepository.currentAccessToken()) {
        val jwt = token ?: authRepository.currentAccessToken()
        if (jwt.isNullOrBlank() || isConnectingOrConnected) return
        isConnectingOrConnected = true
        connectWebSocket(jwt)
    }

    @Synchronized
    fun stopSync() {
        isConnectingOrConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Logout / Client stop")
        webSocket = null
        _isConnected.value = false
    }

    private fun connectWebSocket(token: String) {
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        val wsUrl = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "hubs/coin-sync?access_token=$token"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "SignalR WebSocket connected. Sending handshake...")
                // Send SignalR protocol handshake
                val handshake = "{\"protocol\":\"json\",\"version\":1}\u001E"
                webSocket.send(handshake)
                _isConnected.value = true
                scope.launch {
                    runCatching { settingsRepository.syncRunnerSettingsFromBackend(force = true) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalRMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                _isConnected.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                _isConnected.value = false
                isConnectingOrConnected = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                _isConnected.value = false
                isConnectingOrConnected = false
                scheduleReconnect()
            }
        })
    }

    private fun handleSignalRMessage(rawText: String) {
        // SignalR messages are delimited by 0x1E (Record Separator)
        val frames = rawText.split("\u001E").filter { it.isNotBlank() }
        for (frame in frames) {
            try {
                val json = JSONObject(frame)
                val type = json.optInt("type", 0)

                // Ping message (type 6) - respond with ping to keep connection alive
                if (type == 6) {
                    webSocket?.send("{\"type\":6}\u001E")
                    continue
                }

                // Invocation message (type 1)
                if (type == 1) {
                    val target = json.optString("target")
                    if (target == "WalletUpdated") {
                        val args = json.optJSONArray("arguments")
                        if (args != null && args.length() > 0) {
                            val walletObj = args.getJSONObject(0)
                            val coins = walletObj.optLong("coins", 0L)
                            val lifetimeCoins = walletObj.optLong("lifetimeCoins", 0L)
                            val pendingCoins = walletObj.optLong("pendingCoins", 0L)
                            val withdrawnCoins = walletObj.optLong("withdrawnCoins", 0L)
                            val updatedAt = walletObj.optString("updatedAt", System.currentTimeMillis().toString())

                            scope.launch {
                                // Routed through WalletRepository (not WalletDao directly) so
                                // this serializes against every other wallet mutation under the
                                // same lock — see WalletRepository.applyLiveUpdate's doc comment.
                                walletRepository.applyLiveUpdate(coins, lifetimeCoins, pendingCoins, withdrawnCoins, updatedAt)
                                Log.i(TAG, "Live Coin Sync updated balance to $coins coins")
                            }
                        }
                    } else if (target == "RunnerSettingsUpdated") {
                        val args = json.optJSONArray("arguments")
                        if (args != null && args.length() > 0) {
                            val settingsObjStr = args.getJSONObject(0).toString()
                            val dto = jsonParser.decodeFromString<RunnerSettingsDto>(settingsObjStr)
                            scope.launch {
                                settingsRepository.saveRunnerSettingsDto(dto)
                                Log.i(TAG, "Live Settings Sync updated runner settings in real-time")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing SignalR message: ${e.message}", e)
            }
        }
    }

    private fun scheduleReconnect() {
        if (!authRepository.isLoggedIn.value) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000L)
            val token = authRepository.currentAccessToken()
            if (!token.isNullOrBlank()) {
                Log.d(TAG, "Attempting WebSocket reconnect...")
                startSync(token)
            }
        }
    }

    private companion object {
        const val TAG = "LiveCoinSyncManager"
    }
}
