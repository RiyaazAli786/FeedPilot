package com.feedpilot.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.feedpilot.client.R
import com.feedpilot.client.bridge.BridgeStatusStore
import com.feedpilot.client.bridge.FeedBridgeRunner
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.hasInternetConnection
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@AndroidEntryPoint
class FeedBridgeService : Service() {

    @Inject lateinit var runner: FeedBridgeRunner
    @Inject lateinit var deviceIdentity: DeviceIdentity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationId by lazy { deviceIdentity.cloneScopedNotificationId(NOTIFICATION_ID_BASE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        BridgeStatusStore.starting()
        startForegroundCompat(buildNotification("Feed bridge active"))
        acquireLocks()
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        BridgeStatusStore.error("Bridge stopped. Reopen FeedPilot to reconnect.")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep the bridge alive when the user only clears Recents. Android still stops this
        // after a real force-stop, which is the intended hard off switch.
        scope.launch { if (runner.isBridgeEnabled()) start(this@FeedBridgeService) }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                if (!runner.isBridgeEnabled()) {
                    stopSelf()
                    return
                }
                if (!hasInternetConnection()) {
                    BridgeStatusStore.offline()
                    delay(ERROR_DELAY_MS)
                    continue
                }

                val ranJob = runner.runOneJob()
                delay(if (ranJob) ACTIVE_DELAY_MS else IDLE_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Feed bridge loop failed", e)
                BridgeStatusStore.error("Bridge retrying after network error")
                delay(ERROR_DELAY_MS)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.FEED_BRIDGE_CHANNEL_ID,
                "Feed bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setBlockable(true)
            }
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, Constants.FEED_BRIDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bridge mode on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:FeedBridge")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.w(TAG, "Could not acquire feed bridge wake lock", it) }
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:FeedBridgeWifi")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.w(TAG, "Could not acquire feed bridge wifi lock", it) }
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }.onFailure { Log.w(TAG, "Could not release feed bridge locks", it) }
    }

    companion object {
        private const val TAG = "FeedBridgeService"
        private const val NOTIFICATION_ID_BASE = 7300
        private const val ACTIVE_DELAY_MS = 500L
        private const val IDLE_DELAY_MS = 2_000L
        private const val ERROR_DELAY_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, FeedBridgeService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Could not start feed bridge foreground service", it) }
        }
    }
}
