package com.feedpilot.client.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.service.FeedBridgeService
import com.feedpilot.client.worker.FeedBridgeWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Application-owned control survives leaving Settings and reapplies the saved choice on launch. */
@Singleton
class BridgeModeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val runner: FeedBridgeRunner,
    private val notifications: BridgeJobNotifier
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            settings.settings.map { it.bridgeEnabled }.distinctUntilChanged().collectLatest { enabled ->
                BridgeStatusStore.setEnabled(enabled)
                val work = WorkManager.getInstance(context)
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<FeedBridgeWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                    FeedBridgeService.start(context)
                } else {
                    notifications.cancel()
                    context.stopService(Intent(context, FeedBridgeService::class.java))
                    work.cancelUniqueWork(WORK_NAME)
                    // Notify the backend even if the foreground runner was never started.
                    // Retry while offline; a later enable cancels this disabled-mode loop.
                    withContext(Dispatchers.IO) {
                        while (!runner.heartbeat()) {
                            Log.w("BridgeModeController", "Retrying disabled bridge heartbeat")
                            delay(10_000)
                        }
                    }
                }
            }
        }
    }

    companion object { const val WORK_NAME = "FeedPilotFeedBridge" }
}
