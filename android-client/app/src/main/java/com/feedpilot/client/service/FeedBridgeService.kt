package com.feedpilot.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.feedpilot.client.R
import com.feedpilot.client.bridge.FeedBridgeRunner
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.DeviceIdentity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FeedBridgeService : Service() {

    @Inject lateinit var runner: FeedBridgeRunner
    @Inject lateinit var deviceIdentity: DeviceIdentity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationId by lazy { deviceIdentity.cloneScopedNotificationId(NOTIFICATION_ID_BASE) }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat(buildNotification("Feed bridge active"))
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        var idleTicks = 0
        while (scope.isActive) {
            try {
                val ranJob = runner.runOneJob()
                idleTicks = if (ranJob) 0 else idleTicks + 1
                val text = if (ranJob) "Completed bridge feed request" else "Waiting for bridge requests"
                updateNotification(text)
                delay(if (ranJob) ACTIVE_DELAY_MS else IDLE_DELAY_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Feed bridge loop failed", e)
                updateNotification("Bridge retrying after network error")
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
            )
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
            .setContentTitle("FeedPilot bridge")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(notificationId, buildNotification(text))
        }
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
