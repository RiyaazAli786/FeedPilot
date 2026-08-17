package com.feedpilot.client

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.CrashReporter
import com.feedpilot.client.common.isCrashReportProcess
import com.feedpilot.client.worker.FeedWatcherWorker
import com.feedpilot.client.worker.SyncWorker
import com.feedpilot.client.worker.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FeedPilotApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Before content providers, Hilt injection and onCreate — a startup crash has to be
        // caught here or it goes unreported, which is exactly the case that is hardest to
        // diagnose on a device that is not the build machine.
        CrashReporter.install(base)
    }

    override fun onCreate() {
        super.onCreate()

        // CrashActivity runs in its own process, which loads this Application too. It must not
        // repeat the startup work that may have just brought the main process down.
        if (isCrashReportProcess()) return

        warmUpDeviceFingerprint()
        verifyAppIntegrity()
        scheduleBackgroundWork()
    }

    /**
     * `WebSettings.getDefaultUserAgent()` — behind [InstagramCrypto.getDeviceFingerprint] — only
     * works on the main thread. Priming its cache here, before WorkManager/background sync ever
     * gets a chance to call it first from an IO thread, guarantees every Instagram API call in
     * this process (whichever thread it runs on) uses the same UA the login WebView used, instead
     * of drifting onto a different hardcoded fallback and tripping Instagram's "useragent
     * mismatch" check.
     */
    private fun warmUpDeviceFingerprint() {
        runCatching { com.feedpilot.client.common.InstagramCrypto.getDeviceFingerprint(this) }
    }

    private fun verifyAppIntegrity() {
        // A tamper check is not worth a launch failure. It reports; it does not decide whether
        // the app runs.
        val result = runCatching {
            com.feedpilot.client.security.AntiPatchGuard.verifyAppIntegrity(this)
        }.getOrNull() ?: return

        if (result.isTampered) {
            Log.e("AntiPatchGuard", "Security check failed: ${result.reason}")
            if (result.reason == "Unofficial cloned package detected") {
                com.feedpilot.client.security.AntiPatchGuard.terminateProcess()
            }
        }
    }

    private fun scheduleBackgroundWork() {
        // WorkManager touches its own database here. A failure to enqueue periodic work costs
        // background sync, not the app, so it must not propagate out of onCreate.
        runCatching {
            val networkConstraint = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()

            val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .build()

            val feedWatcherRequest = PeriodicWorkRequestBuilder<FeedWatcherWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()

            WorkManager.getInstance(this).apply {
                enqueueUniquePeriodicWork(
                    Constants.SYNC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
                enqueueUniquePeriodicWork(
                    Constants.UPDATE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    updateRequest
                )
                enqueueUniquePeriodicWork(
                    FEED_WATCHER_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    feedWatcherRequest
                )
            }
        }.onFailure { Log.e(TAG, "Could not schedule background work", it) }
    }

    private companion object {
        const val TAG = "FeedPilotApp"
        const val FEED_WATCHER_WORK_NAME = "FeedPilotFeedWatcher"
    }
}
